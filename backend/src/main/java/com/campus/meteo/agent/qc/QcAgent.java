package com.campus.meteo.agent.qc;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.campus.meteo.common.constant.MqTopics;
import com.campus.meteo.common.constant.QcFlag;
import com.campus.meteo.entity.QcReviewTask;
import com.campus.meteo.entity.Station;
import com.campus.meteo.influx.ObsData;
import com.campus.meteo.influx.ObsReader;
import com.campus.meteo.influx.ObsWriter;
import com.campus.meteo.mapper.QcReviewTaskMapper;
import com.campus.meteo.mapper.StationMapper;
import com.campus.meteo.mq.RabbitConfig;
import com.rabbitmq.client.Channel;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.amqp.support.AmqpHeaders;
import org.springframework.messaging.handler.annotation.Header;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 质控Agent：
 * 1. 消费 topic.meteo.raw
 * 2. 极值检查 + 时间一致性检查（阈值来自 sys_config 的 qc.* 参数）
 * 3. 全部通过 → 写入 qc_flag=passed；存在可疑要素 → 写入 qc_flag=suspect 并生成人工审核任务
 * 4. 质控结果发布到 topic.meteo.qc 供告警/报表Agent消费
 * 消费端幂等：msgId + Redis 去重
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class QcAgent {

    private final ObsWriter obsWriter;
    private final ObsReader obsReader;
    private final QcThresholdService thresholdService;
    private final QcReviewTaskMapper qcReviewTaskMapper;
    private final StationMapper stationMapper;
    private final org.springframework.data.redis.core.StringRedisTemplate redisTemplate;
    private final com.campus.meteo.mq.MqProducer mqProducer;

    /** 幂等去重键前缀 */
    private static final String KEY_DEDUP = "meteo:mq:dedup:";

    @RabbitListener(queues = RabbitConfig.QUEUE_METEO_RAW)
    public void onRawData(ObsData obs, Channel channel,
                          @Header(AmqpHeaders.DELIVERY_TAG) long deliveryTag) throws IOException {
        try {
            if (isDuplicated(obs.getMsgId())) {
                log.info("重复消息，跳过: msgId={}", obs.getMsgId());
                channel.basicAck(deliveryTag, false);
                return;
            }

            List<String> suspectReasons = check(obs);

            String qcFlag = suspectReasons.isEmpty() ? QcFlag.PASSED.getValue() : QcFlag.SUSPECT.getValue();
            obs.setQcFlag(qcFlag);
            obsWriter.writeObs(obs);

            if (!suspectReasons.isEmpty()) {
                createReviewTasks(obs, suspectReasons);
            }

            // 质控后数据分发（仅通过的数据进入告警链路，可疑数据由人工审核闭环）
            if (QcFlag.PASSED.getValue().equals(qcFlag)) {
                mqProducer.send(MqTopics.METEO_QC, obs);
            }
            log.info("质控完成: station={}, qc={}, msgId={}", obs.getStationCode(), qcFlag, obs.getMsgId());
            channel.basicAck(deliveryTag, false);
        } catch (Exception e) {
            log.error("质控处理失败: station={}, msgId={}, err={}",
                    obs.getStationCode(), obs.getMsgId(), e.getMessage(), e);
            // 消费失败不重回队列（避免毒消息循环），进入死信需人工介入
            channel.basicNack(deliveryTag, false, false);
        }
    }

    /** 极值检查 + 时间一致性检查 */
    private List<String> check(ObsData obs) {
        List<String> reasons = new ArrayList<>();
        Map<String, Double> elements = obs.getElements();

        // 极值检查：要素级 max/min 参数（如 qc.temp.max / qc.temp.min）
        elements.forEach((element, value) -> {
            double max = thresholdService.getThreshold("qc." + element + ".max", Double.MAX_VALUE);
            double min = thresholdService.getThreshold("qc." + element + ".min", -Double.MAX_VALUE);
            if (value > max || value < min) {
                reasons.add("极值检查[" + element + "=" + value + ", 有效区间 " + min + "~" + max + "]");
            }
        });

        // 时间一致性检查：与最近 2 小时上一时点值的变化率
        Instant from = obs.getTs().minusSeconds(2 * 3600);
        elements.forEach((element, value) -> {
            if ("temp".equals(element)) {
                Double last = obsReader.queryLastElementValue(obs.getStationCode(), element, from);
                double changeMax = thresholdService.getThreshold("qc." + element + ".change.max", Double.MAX_VALUE);
                if (last != null && Math.abs(value - last) > changeMax) {
                    reasons.add("时间一致性[" + element + ": " + last + " → " + value
                            + ", 变化率超限 " + changeMax + "]");
                }
            }
        });
        return reasons;
    }

    /** 可疑要素生成人工审核任务 */
    private void createReviewTasks(ObsData obs, List<String> reasons) {
        Station station = stationMapper.selectOne(new LambdaQueryWrapper<Station>()
                .eq(Station::getStationCode, obs.getStationCode()));
        if (station == null) {
            log.warn("可疑数据但站点不存在，跳过审核任务: station={}", obs.getStationCode());
            return;
        }
        LocalDateTime obsTime = LocalDateTime.ofInstant(obs.getTs(), ZoneId.of("Asia/Shanghai"));
        for (String reason : reasons) {
            String element = extractElement(reason);
            Double value = obs.getElements().get(element);
            if (value == null) {
                continue;
            }
            QcReviewTask task = new QcReviewTask();
            task.setStationId(station.getId());
            task.setElement(element);
            task.setObsTime(obsTime);
            task.setObsValue(BigDecimal.valueOf(value));
            task.setQcType(reason.startsWith("极值") ? 1 : 2);
            task.setQcDetail(reason);
            task.setStatus(0);
            qcReviewTaskMapper.insert(task);
        }
    }

    /**
     * 从检验详情中提取要素名：要素名紧跟 '['，直到 '=' / ':' / ',' 中最早出现的分隔符为止。
     * 极值检查写作 "极值检查[temp=55.0, ...]"，时间一致性写作 "时间一致性[temp: 10.0 → ...]"，
     * 两种格式都要能解析——早先只按 '=' 切分，时间一致性的可疑数据取不到要素名，
     * 审核任务被静默丢弃（reason 生成后 createReviewTasks 里 continue 掉了）。
     */
    private String extractElement(String reason) {
        int start = reason.indexOf('[') + 1;
        if (start <= 0 || start >= reason.length()) {
            return "unknown";
        }
        int end = reason.length();
        for (char separator : new char[]{'=', ':', ','}) {
            int index = reason.indexOf(separator, start);
            if (index > start && index < end) {
                end = index;
            }
        }
        return reason.substring(start, end).trim();
    }

    /** msgId 去重（Redis SETNX，10 分钟过期） */
    private boolean isDuplicated(String msgId) {
        if (msgId == null) {
            return false;
        }
        Boolean first = redisTemplate.opsForValue().setIfAbsent(KEY_DEDUP + msgId, "1", java.time.Duration.ofMinutes(10));
        return !Boolean.TRUE.equals(first);
    }
}
