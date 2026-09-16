package com.campus.meteo.influx;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * InfluxDB 连接配置（meteo.influx 前缀）
 */
@Data
@Component
@ConfigurationProperties(prefix = "meteo.influx")
public class InfluxProperties {

    private String url;
    private String token;
    private String org;
    private String bucket;
}
