package com.campus.meteo.agent.collector;

import lombok.extern.slf4j.Slf4j;
import org.eclipse.paho.client.mqttv3.MqttClient;
import org.eclipse.paho.client.mqttv3.MqttMessage;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.util.Random;

/**
 * 数据模拟器：开发/测试环境模拟多站点传感器分钟级上报（走真实 MQTT 链路，验证采集→质控全流程）
 * 开启方式：meteo.simulator.enabled=true，默认关闭
 */
@Slf4j
@Component
@ConditionalOnProperty(name = "meteo.simulator.enabled", havingValue = "true")
public class DataSimulator {

    private final CollectorAgent collectorAgent;
    private final Random random = new Random();

    public DataSimulator(CollectorAgent collectorAgent) {
        this.collectorAgent = collectorAgent;
    }

    /** 每分钟生成一次模拟数据 */
    @Scheduled(fixedDelay = 60_000, initialDelay = 5_000)
    public void publish() {
        simulate("CAMPUS01");
        simulate("FARM02");
    }

    private void simulate(String stationCode) {
        // 在合理范围内随机生成各要素（temp 15~35, humi 30~95, pres 990~1030, wind 0~15, rain 0~5, rad 0~800, vis 3~30, evap 0~1）
        String payload = """
                {"stationCode":"%s","ts":%d,"elements":{
                "temp":%.1f,"humi":%.1f,"pres":%.1f,"wind_speed":%.1f,"wind_dir":%.0f,
                "rain":%.1f,"rad":%.1f,"vis":%.1f,"evap":%.2f}}
                """.formatted(stationCode, System.currentTimeMillis(),
                15 + random.nextDouble() * 20,
                30 + random.nextDouble() * 65,
                990 + random.nextDouble() * 40,
                random.nextDouble() * 15,
                random.nextDouble() * 360,
                random.nextDouble() * 5,
                random.nextDouble() * 800,
                3 + random.nextDouble() * 27,
                random.nextDouble());
        try {
            MqttMessage message = new MqttMessage(payload.getBytes(StandardCharsets.UTF_8));
            message.setQos(1);
            // 模拟器与采集器同进程时直接走处理逻辑（等价于收到 MQTT 消息）
            collectorAgent.processMessage("meteo/" + stationCode + "/up", message);
            log.info("模拟器已上报: station={}", stationCode);
        } catch (Exception e) {
            log.error("模拟器上报失败: station={}, err={}", stationCode, e.getMessage());
        }
    }
}
