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
}
