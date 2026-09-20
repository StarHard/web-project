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
import com.campus.meteo.mq.MqProducer;
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
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 质控Agent：
 * 1. 消费 topic.meteo.raw
 * 2. 极值检查 + 时间一致性检查 + 空间一致性检查（阈值来自 sys_config 的 qc.* 参数）
 * 3. 全部通过 → 写入 qc_flag=passed；存在可疑要素 → 写入 qc_flag=suspect 并生成人工审核任务
 * 4. 质控结果发布到 topic.meteo.qc 供告警/报表Agent消费；通过的数据同时走 WebSocket 实时推送并写实时缓存
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
    private final InterpolationService interpolationService;
    private final com.campus.meteo.agent.realtime.RealtimeWebSocketHandler realtimeWebSocketHandler;
    private final com.campus.meteo.agent.realtime.RealtimeCache realtimeCache;

    /** 幂等去重键前缀 */
    private static final String KEY_DEDUP = "meteo:mq:dedup:";

    /** 时间一致性比较基准的回溯窗口（秒） */
    private static final long CONSISTENCY_LOOKBACK_SECONDS = 2 * 3600;

    /** 空间一致性：邻站同时刻匹配容差缺省值（秒） */
    private static final double DEFAULT_SPATIAL_TOLERANCE_SECONDS = 300;

    /** 空间一致性：邻站最大距离缺省值（公里） */
    private static final double DEFAULT_SPATIAL_MAX_DISTANCE_KM = 50;

    /**
     * 风向是圆周量，直接做差值没有意义（359° 与 1° 实际只差 2°，差值却是 358°），
     * 不参与变化量与空间偏差判定，避免满屏误判。
     */
    private static final Set<String> CIRCULAR_ELEMENTS = Set.of("wind_dir");

    /** 站点列表缓存有效期（毫秒）：空间一致性逐报文判定，避免每条报文都全表查询 */
    private static final long STATION_CACHE_TTL_MS = 30_000;

    private volatile List<Station> stationCache = List.of();
    private volatile long stationCacheAt;

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
                // 实时推送与最新数据缓存同样只写质控通过的数据：与 /realtime/curve 的口径保持一致，
                // 否则订阅者/实时接口会看到被本环节判定为可疑、在曲线里根本不会出现的尖峰值
                realtimeWebSocketHandler.push(obs);
                realtimeCache.save(obs);
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

    /** 极值检查 + 时间一致性检查 + 空间一致性检查 */
    private List<String> check(ObsData obs) {
        List<String> reasons = new ArrayList<>();
        Map<String, Double> elements = obs.getElements();

        checkExtremes(elements, reasons);
        checkTimeConsistency(obs, elements, reasons);
        checkSpatial(obs, elements, reasons);

        return reasons;
    }

    /** 极值检查：要素级 max/min 参数（如 qc.temp.max / qc.temp.min） */
    private void checkExtremes(Map<String, Double> elements, List<String> reasons) {
        elements.forEach((element, value) -> {
            double max = thresholdService.getThreshold("qc." + element + ".max", Double.MAX_VALUE);
            double min = thresholdService.getThreshold("qc." + element + ".min", -Double.MAX_VALUE);
            if (value > max || value < min) {
                reasons.add("极值检查[" + element + "=" + value + ", 有效区间 " + min + "~" + max + "]");
            }
        });
    }

    /**
     * 时间一致性检查：与「最近一个被质控接受的值」的变化量超过 qc.&lt;要素&gt;.change.max 判定可疑。
     *
     * 覆盖全部要素（此前循环里只判了 temp）。未配置阈值的要素取 Double.MAX_VALUE，
     * 自然不会命中，因此新增要素只需在 sys_config 里补一条阈值。
     *
     * 基准只取 passed/revised：若纳入 raw，紧邻的上一条若是刚被极值检查拒绝的尖峰，
     * 本条正常值会被连带误判，每个尖峰污染其后一条正常数据。
     */
    private void checkTimeConsistency(ObsData obs, Map<String, Double> elements, List<String> reasons) {
        // stop 取观测时刻的整秒：采集 Agent 先写 raw 再投递 MQ，质控处理时该条已入库，
        // 不设上界的话 last() 取到的就是本条自身，比较值恒等于当前值，检查永不触发
        Instant stop = obs.getTs().truncatedTo(ChronoUnit.SECONDS);
        Map<String, Double> previous = obsReader.queryLastAcceptedValues(obs.getStationCode(),
                stop.minusSeconds(CONSISTENCY_LOOKBACK_SECONDS), stop);
        if (previous.isEmpty()) {
            return;
        }
        elements.forEach((element, value) -> {
            if (CIRCULAR_ELEMENTS.contains(element)) {
                return;
            }
            Double last = previous.get(element);
            double changeMax = thresholdService.getThreshold("qc." + element + ".change.max", Double.MAX_VALUE);
            if (last != null && Math.abs(value - last) > changeMax) {
                reasons.add("时间一致性[" + element + ": " + last + " → " + value
                        + ", 变化量超限 " + changeMax + "]");
            }
        });
    }

    /**
     * 空间一致性检查：与最近站点同时刻的值偏差超过 qc.&lt;要素&gt;.spatial.max 判定可疑。
     *
     * 未配置该阈值的要素不检查；无可用邻站（未配置坐标或超出距离上限）或邻站无同时刻样本时整体跳过。
     * 邻站取值只认观测值（passed/revised），不采用插补合成值。
     */
    private void checkSpatial(ObsData obs, Map<String, Double> elements, List<String> reasons) {
        Station self = findStation(obs.getStationCode());
        if (self == null) {
            return;
        }
        Station neighbor = findNearestStation(self);
        if (neighbor == null) {
            return;
        }
        long tolerance = resolveToleranceSeconds();
        List<ObsData> neighborObs = obsReader.queryObservedRange(neighbor.getStationCode(),
                obs.getTs().minusSeconds(tolerance), obs.getTs().plusSeconds(tolerance));
        Map<String, Double> neighborElements = nearestByTime(neighborObs, obs.getTs());
        if (neighborElements.isEmpty()) {
            return;
        }

        elements.forEach((element, value) -> {
            if (CIRCULAR_ELEMENTS.contains(element)) {
                return;
            }
            Double other = neighborElements.get(element);
            if (other == null) {
                return;
            }
            double spatialMax = thresholdService.getThreshold("qc." + element + ".spatial.max", Double.MAX_VALUE);
            double diff = Math.abs(value - other);
            if (diff > spatialMax) {
                reasons.add("空间一致性[" + element + ": 本站 " + value + " 与 "
                        + neighbor.getStationCode() + " " + other + " 相差 " + diff + ", 超限 " + spatialMax + "]");
            }
        });
    }

    /**
     * 近邻时间匹配容差（秒）。
     *
     * 该参数可在系统参数页被任意修改，必须钳制：极大值强转 long 会溢出成负数，
     * Instant.plusSeconds(负数) 直接抛异常，把整条质控链路打断；过小则永远匹配不到邻站样本。
     */
    private long resolveToleranceSeconds() {
        double configured = thresholdService.getThreshold("qc.spatial.match.tolerance.seconds",
                DEFAULT_SPATIAL_TOLERANCE_SECONDS);
        if (configured < 1 || configured > 24 * 3600) {
            log.warn("空间一致性匹配容差配置异常({}), 回退为默认值 {} 秒", configured, DEFAULT_SPATIAL_TOLERANCE_SECONDS);
            return (long) DEFAULT_SPATIAL_TOLERANCE_SECONDS;
        }
        return (long) configured;
    }

    /** 站点列表（30 秒缓存） */
    private List<Station> cachedStations() {
        long now = System.currentTimeMillis();
        if (stationCache.isEmpty() || now - stationCacheAt > STATION_CACHE_TTL_MS) {
            stationCache = stationMapper.selectList(new LambdaQueryWrapper<Station>());
            stationCacheAt = now;
        }
        return stationCache;
    }

    private Station findStation(String stationCode) {
        return cachedStations().stream()
                .filter(station -> stationCode.equals(station.getStationCode()))
                .findFirst()
                .orElse(null);
    }

    /** 最近站点：按 Haversine 距离，超上限视为不可用 */
    private Station findNearestStation(Station self) {
        if (self.getLongitude() == null || self.getLatitude() == null) {
            return null;
        }
        double maxDistance = thresholdService.getThreshold("qc.spatial.neighbor.max.distance.km",
                DEFAULT_SPATIAL_MAX_DISTANCE_KM);
        double selfLon = self.getLongitude().doubleValue();
        double selfLat = self.getLatitude().doubleValue();

        Station nearest = null;
        double nearestDistance = Double.MAX_VALUE;
        for (Station candidate : cachedStations()) {
            if (candidate.getId() == null || candidate.getId().equals(self.getId())
                    || candidate.getStatus() == null || candidate.getStatus() != 1
                    || candidate.getLongitude() == null || candidate.getLatitude() == null) {
                continue;
            }
            double distance = interpolationService.distanceKm(selfLon, selfLat,
                    candidate.getLongitude().doubleValue(), candidate.getLatitude().doubleValue());
            if (distance <= maxDistance && distance < nearestDistance) {
                nearestDistance = distance;
                nearest = candidate;
            }
        }
        return nearest;
    }

    /** 取与目标时刻最近的邻站观测的要素集，无样本返回空 */
    private Map<String, Double> nearestByTime(List<ObsData> candidates, Instant target) {
        ObsData best = null;
        long bestDiff = Long.MAX_VALUE;
        for (ObsData candidate : candidates) {
            long diff = Math.abs(Duration.between(candidate.getTs(), target).getSeconds());
            if (diff < bestDiff) {
                best = candidate;
                bestDiff = diff;
            }
        }
        return best == null ? Map.of() : best.getElements();
    }

    /** 可疑要素生成人工审核任务 */
    private void createReviewTasks(ObsData obs, List<String> reasons) {
        Station station = findStation(obs.getStationCode());
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
            task.setQcType(qcTypeOf(reason));
            task.setQcDetail(reason);
            task.setStatus(0);
            qcReviewTaskMapper.insert(task);
        }
    }

    /** 检验类型：1极值 2时间一致性 3空间一致性（与 qc_review_task.qc_type 的字典一致） */
    private int qcTypeOf(String reason) {
        if (reason.startsWith("极值")) {
            return 1;
        }
        return reason.startsWith("空间") ? 3 : 2;
    }

    /**
     * 从检验详情中提取要素名：要素名紧跟 '['，直到 '=' / ':' / ',' 中最早出现的分隔符为止。
     * 三类检验的文案格式不同——"极值检查[temp=55.0, ...]"、"时间一致性[temp: 10.0 → ...]"、
     * "空间一致性[temp: 本站 ...]",都要能解析：早先只按 '=' 切分会把后两类解析成 unknown，
     * 审核任务取不到要素值被静默丢弃。
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