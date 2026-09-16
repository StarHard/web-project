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
import org.eclipse.paho.client.mqttv3.IMqttMessageListener;
import org.eclipse.paho.client.mqttv3.MqttClient;
import org.eclipse.paho.client.mqttv3.MqttConnectOptions;
import org.eclipse.paho.client.mqttv3.MqttException;
import org.eclipse.paho.client.mqttv3.MqttMessage;
import org.eclipse.paho.client.mqttv3.persist.MemoryPersistence;
import org.springframework.data.redis.core.StringRedisTemplate;
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
 * 3. 更新 Redis 站点最新数据缓存与 MySQL 站点在线状态
 * 4. 发布标准化数据到 MQ topic.meteo.raw 供质控Agent消费
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class CollectorAgent {

    public static final String REDIS_KEY_LATEST = "meteo:realtime:";

    private final MqttProperties mqttProperties;
    private final ObsWriter obsWriter;
    private final MqProducer mqProducer;
    private final StationMapper stationMapper;
    private final StringRedisTemplate redisTemplate;
    private final ObjectMapper objectMapper;
    private final com.campus.meteo.agent.realtime.RealtimeWebSocketHandler realtimeWebSocketHandler;

    private MqttClient mqttClient;

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
            options.setCleanSession(true);
            mqttClient.connect(options);
            mqttClient.subscribe(mqttProperties.getTopic(), 1, new UpMessageListener());
            log.info("采集Agent已启动, 订阅主题: {}", mqttProperties.getTopic());
        } catch (MqttException e) {
            // 采集链路为系统主干，启动失败直接抛出阻断应用启动
            throw new IllegalStateException("MQTT 连接失败: " + e.getMessage(), e);
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

            // 2. Redis 缓存最新数据（实时接口直读）
            redisTemplate.opsForValue().set(REDIS_KEY_LATEST + stationCode, payload);

            // 3. 更新站点在线状态与最后上报时间
            updateStationStatus(stationCode);

            // 4. 分发到质控链路
            mqProducer.send(MqTopics.METEO_RAW, obs);

            // 5. WebSocket 实时推送
            realtimeWebSocketHandler.push(obs);
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
            processMessage(topic, message);
        }
    }
}
