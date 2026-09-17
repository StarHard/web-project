package com.campus.meteo.agent.alert;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.campus.meteo.entity.AlertNotify;
import com.campus.meteo.entity.AlertRecord;
import com.campus.meteo.entity.AlertRule;
import com.campus.meteo.influx.ObsData;
import com.campus.meteo.influx.ObsReader;
import com.campus.meteo.mapper.AlertNotifyMapper;
import com.campus.meteo.mapper.AlertRecordMapper;
import com.campus.meteo.mq.RabbitConfig;
import com.campus.meteo.service.SysConfigService;
import com.rabbitmq.client.Channel;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.amqp.support.AmqpHeaders;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.messaging.handler.annotation.Header;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.math.BigDecimal;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * 告警Agent：
 * 1. 消费质控后数据（topic.meteo.qc）
 * 2. 规则判定（大于/小于/持续N分钟超限）、告警等级升级、抑制窗口去重
 * 3. 生成告警记录与多渠道通知明细（web 即时推送；短信/邮件/微信落库待发）
 * 4. 条件恢复后自动解除进行中的告警
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class AlertAgent {

    private static final DateTimeFormatter TIME_FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    /** 告警类型名称 */
    private static final String[] TYPE_NAMES = {"", "暴雨", "大风", "高温", "寒潮", "冰雹"};
    /** 告警等级名称：1蓝 2黄 3橙 4红 */
    private static final String[] LEVEL_NAMES = {"", "蓝色", "黄色", "橙色", "红色"};

    private final AlertContextCache contextCache;
    private final AlertRecordMapper alertRecordMapper;
    private final AlertNotifyMapper alertNotifyMapper;
    private final ObsReader obsReader;
    private final SysConfigService sysConfigService;
    private final AlertWebSocketHandler alertWebSocketHandler;
    private final StringRedisTemplate redisTemplate;

    @RabbitListener(queues = RabbitConfig.QUEUE_METEO_QC_ALERT)
    public void onQcData(ObsData obs, Channel channel,
                         @Header(AmqpHeaders.DELIVERY_TAG) long deliveryTag) throws IOException {
        try {
            if (isDuplicated(obs.getMsgId())) {
                channel.basicAck(deliveryTag, false);
                return;
            }
            Long stationId = contextCache.getStationId(obs.getStationCode());
            if (stationId == null) {
                log.warn("告警判定跳过：未知站点 {}", obs.getStationCode());
                channel.basicAck(deliveryTag, false);
                return;
            }

            Set<Long> triggeredRuleIds = new HashSet<>();
            for (AlertRule rule : contextCache.getEnabledRules()) {
                if (!ruleMatchesStation(rule, stationId)) {
                    continue;
                }
                Double value = obs.getElements().get(rule.getElement());
                if (value == null) {
                    continue;
                }
                if (isTriggered(rule, obs, value)) {
                    triggeredRuleIds.add(rule.getId());
                    handleTrigger(rule, obs, stationId, value);
                }
            }
            relieveRecovered(stationId, triggeredRuleIds);
            channel.basicAck(deliveryTag, false);
        } catch (Exception e) {
            log.error("告警判定失败: station={}, msgId={}, err={}",
                    obs.getStationCode(), obs.getMsgId(), e.getMessage(), e);
            channel.basicNack(deliveryTag, false, false);
        }
    }

    /** 规则是否作用于该站点（station_id=0 为全局规则） */
    private boolean ruleMatchesStation(AlertRule rule, Long stationId) {
        return rule.getStationId() == 0 || rule.getStationId().equals(stationId);
    }

    /** 阈值判定 */
    private boolean isTriggered(AlertRule rule, ObsData obs, double value) {
        double threshold = rule.getThreshold().doubleValue();
        return switch (rule.getCondition()) {
            case 1 -> value > threshold;                                   // 大于
            case 2 -> value < threshold;                                   // 小于
            case 3 -> isSustainedExceed(rule, obs, threshold);             // 持续N分钟超限
            default -> false;
        };
    }

    /** 持续超限判定：窗口内 ≥80% 的时次超过阈值且样本数 ≥2 */
    private boolean isSustainedExceed(AlertRule rule, ObsData obs, double threshold) {
        int durationMin = rule.getDurationMin() != null ? rule.getDurationMin() : 60;
        List<ObsData> window = obsReader.queryRange(obs.getStationCode(),
                obs.getTs().minusSeconds((long) durationMin * 60), obs.getTs());
        List<Double> values = window.stream()
                .map(o -> o.getElements().get(rule.getElement()))
                .filter(v -> v != null)
                .toList();
        if (values.size() < 2) {
            return false;
        }
        long exceed = values.stream().filter(v -> v > threshold).count();
        return exceed * 100 / values.size() >= 80;
    }

    /** 触发处理：抑制窗口去重 → 等级升级或新建告警 → 多渠道通知 */
    private void handleTrigger(AlertRule rule, ObsData obs, Long stationId, double value) {
        int suppressMin = sysConfigService.getInt("alert.suppress.minutes", 30);
        AlertRecord active = alertRecordMapper.selectOne(new LambdaQueryWrapper<AlertRecord>()
                .eq(AlertRecord::getRuleId, rule.getId())
                .eq(AlertRecord::getStationId, stationId)
                .eq(AlertRecord::getStatus, 0)
                .orderByDesc(AlertRecord::getAlertTime)
                .last("LIMIT 1"));

        LocalDateTime now = LocalDateTime.now();
        if (active != null) {
            // 抑制窗口内：忽略重复触发
            if (active.getAlertTime().plusMinutes(suppressMin).isAfter(now)) {
                return;
            }
            // 持续超限且配置了更高等级：升级
            if (rule.getUpgradeLevel() != null && rule.getUpgradeLevel() > active.getLevel()) {
                active.setStatus(2);
                alertRecordMapper.updateById(active);
                createAlert(rule, obs, stationId, value, rule.getUpgradeLevel(), now,
                        active.getLevel(), true);
            }
            return;
        }
        createAlert(rule, obs, stationId, value, rule.getLevel(), now, null, false);
    }

    /** 新建告警记录并分发通知 */
    private void createAlert(AlertRule rule, ObsData obs, Long stationId, double value,
                             int level, LocalDateTime alertTime, Integer fromLevel, boolean upgraded) {
        String stationCode = contextCache.getStationCode(stationId);
        String typeName = TYPE_NAMES[rule.getAlertType()];
        String content = upgraded
                ? String.format("%s%s预警升级：%s站 %s=%.1f 持续超过阈值 %.1f（由%s升级）",
                        typeName, LEVEL_NAMES[level], stationCode, rule.getElement(), value,
                        rule.getThreshold().doubleValue(), LEVEL_NAMES[fromLevel])
                : String.format("%s%s预警：%s站 %s=%.1f 超过阈值 %.1f",
                        typeName, LEVEL_NAMES[level], stationCode, rule.getElement(), value,
                        rule.getThreshold().doubleValue());

        AlertRecord record = new AlertRecord();
        record.setRuleId(rule.getId());
        record.setStationId(stationId);
        record.setLevel(level);
        record.setAlertTime(alertTime);
        record.setObsValue(BigDecimal.valueOf(value));
        record.setContent(content);
        record.setStatus(0);
        alertRecordMapper.insert(record);

        // 多渠道通知明细：web 即时送达；短信/邮件/微信落库待发（通道适配器后续接入）
        for (String channel : rule.getChannels().split(",")) {
            AlertNotify notify = new AlertNotify();
            notify.setAlertId(record.getId());
            notify.setTarget(channel.trim());
            switch (channel.trim()) {
                case "web" -> {
                    notify.setChannel(1);
                    notify.setSendStatus(1);
                    notify.setSendTime(LocalDateTime.now());
                }
                case "sms" -> notify.setChannel(2);
                case "email" -> notify.setChannel(3);
                case "wechat" -> notify.setChannel(4);
                default -> {
                    continue;
                }
            }
            alertNotifyMapper.insert(notify);
        }
        // Web 渠道实时推送
        alertWebSocketHandler.broadcast(stationCode, level, content, alertTime.format(TIME_FMT));
        log.info("告警触发: station={}, rule={}, level={}, msgId={}",
                stationCode, rule.getId(), level, obs.getMsgId());
    }

    /** 自动解除：本周期未触发且超出抑制窗口的进行中告警 */
    private void relieveRecovered(Long stationId, Set<Long> triggeredRuleIds) {
        int suppressMin = sysConfigService.getInt("alert.suppress.minutes", 30);
        List<AlertRecord> actives = alertRecordMapper.selectList(new LambdaQueryWrapper<AlertRecord>()
                .eq(AlertRecord::getStationId, stationId)
                .eq(AlertRecord::getStatus, 0));
        LocalDateTime now = LocalDateTime.now();
        for (AlertRecord active : actives) {
            if (!triggeredRuleIds.contains(active.getRuleId())
                    && active.getAlertTime().plusMinutes(suppressMin).isBefore(now)) {
                active.setStatus(1);
                active.setRelieveTime(now);
                alertRecordMapper.updateById(active);
                log.info("告警解除: station={}, rule={}", stationId, active.getRuleId());
            }
        }
    }

    /** msgId 幂等去重（键空间与质控Agent隔离，避免同一条数据跨队列误判为重复） */
    private boolean isDuplicated(String msgId) {
        if (msgId == null) {
            return false;
        }
        Boolean first = redisTemplate.opsForValue()
                .setIfAbsent("meteo:mq:dedup:alert:" + msgId, "1", Duration.ofMinutes(10));
        return !Boolean.TRUE.equals(first);
    }
}
