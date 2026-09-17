package com.campus.meteo.agent.collector;

import lombok.extern.slf4j.Slf4j;
import org.eclipse.paho.client.mqttv3.MqttMessage;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.Random;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 数据模拟器：开发/测试环境模拟多站点传感器上报（直接走采集Agent处理逻辑，等价于收到 MQTT 消息）
 * - 要素值采用有界随机游走，贴近真实传感器连续变化特征（可通过时间一致性检验）
 * - 每条数据 5% 概率注入异常尖峰，用于演示质控Agent的极值拦截与人工审核任务
 * 开启方式：meteo.simulator.enabled=true；间隔 meteo.simulator.interval-ms（默认60秒）
 */
@Slf4j
@Component
@ConditionalOnProperty(name = "meteo.simulator.enabled", havingValue = "true")
public class DataSimulator {

    private final CollectorAgent collectorAgent;
    private final Random random = new Random();

    @Value("${meteo.simulator.interval-ms:60000}")
    private long intervalMs;

    /** 站点游走状态：stationCode → 当前各要素值 */
    private final Map<String, Map<String, Double>> state = new ConcurrentHashMap<>();

    /** 要素基线与游走步长：[下限, 上限, 每步最大变化] */
    private static final Map<String, double[]> PROFILE = Map.of(
            "temp", new double[]{15, 35, 0.8},
            "humi", new double[]{30, 95, 2.0},
            "pres", new double[]{990, 1030, 0.5},
            "wind_speed", new double[]{0, 15, 1.5},
            "wind_dir", new double[]{0, 360, 20},
            "rain", new double[]{0, 5, 1.0},
            "rad", new double[]{0, 800, 80},
            "vis", new double[]{3, 30, 2.0},
            "evap", new double[]{0, 1, 0.1});

    public DataSimulator(CollectorAgent collectorAgent) {
        this.collectorAgent = collectorAgent;
    }

    @Scheduled(fixedDelayString = "${meteo.simulator.interval-ms:60000}", initialDelay = 5_000)
    public void publish() {
        simulate("CAMPUS01");
        simulate("FARM02");
    }

    private void simulate(String stationCode) {
        Map<String, Double> current = state.computeIfAbsent(stationCode, k -> initState());
        Map<String, Double> next = new ConcurrentHashMap<>(current);
        // 有界随机游走
        next.forEach((element, value) -> {
            double[] p = PROFILE.get(element);
            if (p != null) {
                double delta = (random.nextDouble() * 2 - 1) * p[2];
                next.put(element, clamp(value + delta, p[0], p[1]));
            }
        });
        // 5% 概率注入气温异常尖峰（-50℃ 超出极值下限，演示质控拦截）
        double temp = next.get("temp");
        if (random.nextInt(100) < 5) {
            temp = -50 - random.nextDouble() * 10;
            log.info("模拟器注入异常气温尖峰: station={}, value={}", stationCode, temp);
        }
        next.put("temp", temp);
        state.put(stationCode, next);

        String payload = """
                {"stationCode":"%s","ts":%d,"elements":{
                "temp":%.1f,"humi":%.1f,"pres":%.1f,"wind_speed":%.1f,"wind_dir":%.0f,
                "rain":%.1f,"rad":%.1f,"vis":%.1f,"evap":%.2f}}
                """.formatted(stationCode, System.currentTimeMillis(),
                next.get("temp"), next.get("humi"), next.get("pres"),
                next.get("wind_speed"), next.get("wind_dir"), next.get("rain"),
                next.get("rad"), next.get("vis"), next.get("evap"));
        try {
            MqttMessage message = new MqttMessage(payload.getBytes(StandardCharsets.UTF_8));
            message.setQos(1);
            collectorAgent.processMessage("meteo/" + stationCode + "/up", message);
            log.debug("模拟器已上报: station={}", stationCode);
        } catch (Exception e) {
            log.error("模拟器上报失败: station={}, err={}", stationCode, e.getMessage());
        }
    }

    private Map<String, Double> initState() {
        Map<String, Double> init = new ConcurrentHashMap<>();
        PROFILE.forEach((element, p) -> init.put(element, p[0] + (p[1] - p[0]) / 2));
        return init;
    }

    private double clamp(double v, double min, double max) {
        return Math.max(min, Math.min(max, v));
    }
}
