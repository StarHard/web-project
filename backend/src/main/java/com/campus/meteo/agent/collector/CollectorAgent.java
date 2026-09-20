package com.campus.meteo.agent.collector;

import com.campus.meteo.common.constant.MqTopics;
import com.campus.meteo.common.constant.QcFlag;
import com.campus.meteo.influx.ObsData;
import com.campus.meteo.influx.ObsWriter;
import com.campus.meteo.mapper.StationMapper;
import com.campus.meteo.entity.Station;
import com.campus.meteo.mq.MqProducer;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.eclipse.paho.client.mqttv3.IMqttDeliveryToken;
import org.eclipse.paho.client.mqttv3.IMqttMessageListener;
import org.eclipse.paho.client.mqttv3.MqttCallbackExtended;
import org.eclipse.paho.client.mqttv3.MqttClient;
import org.eclipse.paho.client.mqttv3.MqttConnectOptions;
import org.eclipse.paho.client.mqttv3.MqttException;
import org.eclipse.paho.client.mqttv3.MqttMessage;
import org.eclipse.paho.client.mqttv3.persist.MemoryPersistence;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * 采集Agent：
 * 1. 订阅 MQTT 上行主题 meteo/{stationCode}/up
 * 2. 报文解析与单位归一，写入 InfluxDB（qc_flag=raw）
 * 3. 更新 MySQL 站点在线状态与最后上报时间
 * 4. 发布标准化数据到 MQ topic.meteo.raw 供质控Agent消费
 * 5. 链路体检兜底（FR-QC-01）：定时检查连接与订阅是否仍有效，失效则自愈
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class CollectorAgent {

    private final MqttProperties mqttProperties;
    private final ObsWriter obsWriter;
    private final MqProducer mqProducer;
    private final StationMapper stationMapper;
    private final ObjectMapper objectMapper;

    private MqttClient mqttClient;

    /** 最近一次通过 MQTT 收到上行消息的时刻（0 表示从未收到） */
    private volatile long lastMqttMessageAt;
    /** 最近一次主动重订阅的时刻，用于避免同一段静默期内反复重订阅刷日志 */
    private volatile long lastResubscribeAt;
    /** 连接断开起始时刻（0 表示当前连接正常） */
    private volatile long disconnectedSince;

    @PostConstruct
    public void start() {
        try {
            mqttClient = new MqttClient(mqttProperties.getBroker(),
                    mqttProperties.getClientIdPrefix() + "-" + UUID.randomUUID(),
                    new MemoryPersistence());
            MqttConnectOptions options = new MqttConnectOptions();
            options.setUserName(mqttProperties.getUsername());
            options.setPassword(mqttProperties.getPassword().toCharArray());
            options.setAutomaticReconnect(true);
            // cleanSession=true 时 Broker 不保留会话，重连后订阅必然丢失，必须重新订阅
            options.setCleanSession(true);
            // 断线/重连回调：负责重连后恢复订阅
            mqttClient.setCallback(new ConnectionCallback());
            mqttClient.connect(options);
            subscribe();
            log.info("采集Agent已启动, 订阅主题: {}", mqttProperties.getTopic());
        } catch (MqttException e) {
            // 采集链路为系统主干，启动失败直接抛出阻断应用启动
            throw new IllegalStateException("MQTT 连接失败: " + e.getMessage(), e);
        }
    }

    /** 订阅上行主题（启动时、重连后、体检发现失效时均需调用） */
    private void subscribe() throws MqttException {
        mqttClient.subscribe(mqttProperties.getTopic(), 1, new UpMessageListener());
        log.info("已订阅上行主题: {}", mqttProperties.getTopic());
    }

    /**
     * 链路体检兜底（FR-QC-01）：MQTT 推送链路的失败是静默的——断线或订阅丢失都不会报错，
     * 系统只是慢慢收不到数据。Paho 的 automaticReconnect 只在「曾连接成功后断开」时重连，
     * 且不恢复订阅；若连接看似正常而订阅已失效，此前没有任何机制能发现。
     *
     * 体检分两种情形：
     * 1. 未连接：等待自动重连；超过 manualReconnectAfterMs 仍未恢复，说明自动重连已放弃，主动重连
     * 2. 已连接但长时间收不到任何上行消息：判定订阅可能失效，主动重订阅
     *
     * 第 2 种判定只在此前确实收到过 MQTT 消息时启用——从未收到过消息时无法区分
     * 「没有站点在上报」与「订阅失效」，贸然重订阅只会造成误报。开发环境由模拟器直接调用
     * 处理逻辑、不经 MQTT，正属于这种情况。
     */
    @Scheduled(fixedDelayString = "${meteo.mqtt.health-check-delay-ms:60000}", initialDelay = 30_000)
    public void healthCheck() {
        if (mqttClient == null) {
            return;
        }
        long now = System.currentTimeMillis();

        if (!mqttClient.isConnected()) {
            if (disconnectedSince == 0) {
                disconnectedSince = now;
                log.warn("MQTT 链路体检：当前未连接，等待 automaticReconnect 恢复");
                return;
            }
            long downMs = now - disconnectedSince;
            if (downMs >= mqttProperties.getManualReconnectAfterMs()) {
                log.warn("MQTT 已断开 {} 秒仍未恢复，主动发起重连", downMs / 1000);
                reconnectManually();
            }
            return;
        }
        disconnectedSince = 0;

        long last = lastMqttMessageAt;
        if (last == 0) {
            return;
        }
        long silentMs = now - last;
        if (silentMs < mqttProperties.getStaleThresholdMs()) {
            return;
        }
        if (lastResubscribeAt != 0 && now - lastResubscribeAt < mqttProperties.getStaleThresholdMs()) {
            return;
        }
        log.warn("MQTT 连接正常但已 {} 秒未收到任何上行消息，判定订阅可能失效，主动重订阅 {}",
                silentMs / 1000, mqttProperties.getTopic());
        lastResubscribeAt = now;
        try {
            subscribe();
        } catch (MqttException e) {
            log.error("重订阅失败: {}", e.getMessage(), e);
        }
    }

    /** 主动重连（自动重连已放弃时的最后手段），成功后立即恢复订阅 */
    private void reconnectManually() {
        try {
            mqttClient.reconnect();
            subscribe();
            disconnectedSince = 0;
            log.info("MQTT 主动重连成功");
        } catch (MqttException e) {
            log.error("MQTT 主动重连失败，下轮体检将继续尝试: {}", e.getMessage());
        }
    }

    @PreDestroy
    public void stop() {
        try {
            if (mqttClient != null && mqttClient.isConnected()) {
                mqttClient.disconnect();
            }
        } catch (MqttException e) {
            log.warn("MQTT 断开异常: {}", e.getMessage());
        }
    }

    /** MQTT 上行消息统一入口：记录链路活跃时刻后再处理报文 */
    private void onMqttMessage(String topic, MqttMessage message) {
        lastMqttMessageAt = System.currentTimeMillis();
        processMessage(topic, message);
    }

    /**
     * 上行消息处理（可被模拟器复用）
     */
    void processMessage(String topic, MqttMessage message) {
        String msgId = message.getId() + "-" + System.currentTimeMillis();
        String payload = new String(message.getPayload(), StandardCharsets.UTF_8);
        try {
            JsonNode root = objectMapper.readTree(payload);
            String stationCode = extractStationCode(topic, root);
            ObsData obs = parseObs(stationCode, root, msgId);
            if (obs.getElements().isEmpty()) {
                log.warn("报文无有效要素, 忽略: station={}, payload={}", stationCode, payload);
                return;
            }

            // 1. 原始数据入时序库
            obs.setQcFlag(QcFlag.RAW.getValue());
            obsWriter.writeObs(obs);

            // 2. 更新站点在线状态与最后上报时间
            // 注意：在线状态与数据新鲜度按「是否上报」判定，与质控结论无关，故留在采集侧；
            // 而对外展示的最新数据缓存由质控Agent写入（见 RealtimeCache）
            updateStationStatus(stationCode);

            // 3. 分发到质控链路
            mqProducer.send(MqTopics.METEO_RAW, obs);

            log.info("采集完成: station={}, msgId={}", stationCode, msgId);
        } catch (Exception e) {
            log.error("报文处理失败: topic={}, msgId={}, payload={}, err={}",
                    topic, msgId, payload, e.getMessage(), e);
        }
    }

    private String extractStationCode(String topic, JsonNode root) {
        // 主题格式 meteo/{stationCode}/up，兼容报文内显式携带 stationCode
        String[] parts = topic.split("/");
        if (parts.length >= 3) {
            return parts[parts.length - 2];
        }
        return root.path("stationCode").asText();
    }

    private ObsData parseObs(String stationCode, JsonNode root, String msgId) {
        long ts = root.path("ts").asLong(System.currentTimeMillis());
        Map<String, Double> elements = new HashMap<>();
        JsonNode el = root.path("elements");
        el.fields().forEachRemaining(entry -> {
            if (entry.getValue().isNumber()) {
                elements.put(entry.getKey(), entry.getValue().asDouble());
            }
        });
        return ObsData.builder()
                .stationCode(stationCode)
                .ts(Instant.ofEpochMilli(ts))
                .elements(elements)
                .msgId(msgId)
                .build();
    }

    private void updateStationStatus(String stationCode) {
        try {
            Station station = stationMapper.selectOne(new LambdaQueryWrapper<Station>()
                    .eq(Station::getStationCode, stationCode));
            if (station != null) {
                Station update = new Station();
                update.setId(station.getId());
                update.setOnlineFlag(1);
                update.setLastReportTime(LocalDateTime.now());
                stationMapper.updateById(update);
            } else {
                log.warn("未知站点编码: {}", stationCode);
            }
        } catch (Exception e) {
            log.warn("更新站点在线状态失败: station={}, err={}", stationCode, e.getMessage());
        }
    }

    private class UpMessageListener implements IMqttMessageListener {
        @Override
        public void messageArrived(String topic, MqttMessage message) {
            onMqttMessage(topic, message);
        }
    }

    /**
     * 连接回调：
     * Paho 的 automaticReconnect 只负责重建连接，不会恢复订阅，
     * 因此必须在 connectComplete 中重新 subscribe，否则 Broker 重启后采集链路会静默断流。
     */
    private class ConnectionCallback implements MqttCallbackExtended {

        @Override
        public void connectComplete(boolean reconnect, String serverURI) {
            if (reconnect) {
                log.warn("MQTT 连接已恢复: {}，重新订阅上行主题", serverURI);
                try {
                    subscribe();
                } catch (MqttException e) {
                    log.error("重连后恢复订阅失败: {}", e.getMessage(), e);
                }
            }
        }

        @Override
        public void connectionLost(Throwable cause) {
            log.error("MQTT 连接断开: {}，等待自动重连", cause == null ? "未知原因" : cause.getMessage());
        }

        @Override
        public void messageArrived(String topic, MqttMessage message) {
            onMqttMessage(topic, message);
        }

        @Override
        public void deliveryComplete(IMqttDeliveryToken token) {
            // 采集Agent仅订阅不发布，无需处理
        }
    }
}
