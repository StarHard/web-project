package com.campus.meteo.agent.collector;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * MQTT 采集配置（meteo.mqtt 前缀）
 */
@Data
@Component
@ConfigurationProperties(prefix = "meteo.mqtt")
public class MqttProperties {

    /** Broker 地址，如 tcp://localhost:1883 */
    private String broker;

    private String username;
    private String password;

    /** 上行主题：meteo/{stationCode}/up */
    private String topic;

    /** 客户端ID前缀 */
    private String clientIdPrefix = "meteo-collector";

    /** 链路体检周期（毫秒） */
    private long healthCheckDelayMs = 60_000;

    /**
     * 判定订阅失效的静默时长（毫秒）：连接正常但超过该时长未收到任何上行消息，
     * 认为订阅可能已静默失效，主动重订阅。仅在此前确实收到过 MQTT 消息时才启用该判定。
     */
    private long staleThresholdMs = 600_000;

    /**
     * 断线后等待 automaticReconnect 自行恢复的时长（毫秒），超过则主动发起重连作为兜底。
     * Paho 的自动重连只在「曾连接成功后断开」时生效，长时间未恢复说明它已放弃重试。
     */
    private long manualReconnectAfterMs = 300_000;
}
