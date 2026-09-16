package com.campus.meteo.influx;

import com.influxdb.client.InfluxDBClient;
import com.influxdb.client.InfluxDBClientFactory;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * InfluxDB 客户端配置
 */
@Slf4j
@Configuration
public class InfluxConfig {

    @Bean(destroyMethod = "close")
    public InfluxDBClient influxDBClient(InfluxProperties properties) {
        InfluxDBClient client = InfluxDBClientFactory.create(
                properties.getUrl(), properties.getToken().toCharArray(), properties.getOrg(), properties.getBucket());
        log.info("InfluxDB 客户端初始化: url={}, org={}, bucket={}",
                properties.getUrl(), properties.getOrg(), properties.getBucket());
        return client;
    }
}
