package com.campus.meteo.agent.collector;

import com.campus.meteo.influx.ObsData;
import com.campus.meteo.influx.ObsReader;
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
 * - 采用「大尺度天气背景 + 站点局地偏差」两层结构：天气系统尺度远大于站间距，
 *   相距十几公里的站点实况应高度相关。早先各站独立随机游走，长时间运行后会漂移到
 *   相差十几度，空间一致性检验（FR-QC-04）上线后立刻被识别为大量可疑数据——
 *   那是数据不真实，不是检验误判
 * - 局地偏差有界（气温 ±2℃ 量级），保证站间差异落在物理合理范围内
 * - 每条数据 5% 概率注入异常尖峰，用于演示质控Agent的极值拦截与人工审核任务
 * 开启方式：meteo.simulator.enabled=true；间隔 meteo.simulator.interval-ms（默认60秒）
 */
@Slf4j
@Component
@ConditionalOnProperty(name = "meteo.simulator.enabled", havingValue = "true")
public class DataSimulator {

    private final CollectorAgent collectorAgent;
    private final ObsReader obsReader;
    private final Random random = new Random();

    @Value("${meteo.simulator.interval-ms:60000}")
    private long intervalMs;

    /** 大尺度天气背景：要素 → 当前值（所有站点共享） */
    private final Map<String, Double> synoptic = new ConcurrentHashMap<>();
    /** 站点局地偏差：stationCode → 要素 → 相对天气背景的偏移 */
    private final Map<String, Map<String, Double>> localOffsets = new ConcurrentHashMap<>();
    /** 已初始化标记：首轮从库中最近观测续跑，避免重启瞬间产生跳变被时间一致性判为可疑 */
    private volatile boolean initialized;

    /**
     * 要素基线：[下限, 上限, 天气背景每步最大变化]
     * 说明：降水不参与随机游走，改由下方按「间歇性降水事件」单独生成——
     * 随机游走会让每个时次都有降水，24h 累计降水将出现数百毫米的荒谬值。
     */
    private static final Map<String, double[]> PROFILE = Map.of(
            "temp", new double[]{15, 35, 0.8},
            "humi", new double[]{30, 95, 2.0},
            "pres", new double[]{990, 1030, 0.5},
            "wind_speed", new double[]{0, 15, 1.5},
            "wind_dir", new double[]{0, 360, 20},
            "rad", new double[]{0, 800, 80},
            "vis", new double[]{3, 30, 2.0},
            "evap", new double[]{0, 1, 0.1});

    /** 局地偏差幅度上限（相对天气背景），约为天气背景步长的量级 */
    private static final double LOCAL_OFFSET_RATIO = 2.5;

    /** 降水事件发生概率 */
    private static final double RAIN_PROBABILITY = 0.12;
    /** 有雨时的雨强区间（mm/h） */
    private static final double RAIN_MIN = 0.5;
    private static final double RAIN_MAX = 6.0;

    public DataSimulator(CollectorAgent collectorAgent, ObsReader obsReader) {
        this.collectorAgent = collectorAgent;
        this.obsReader = obsReader;
    }

    @Scheduled(fixedDelayString = "${meteo.simulator.interval-ms:60000}", initialDelay = 5_000)
    public void publish() {
        if (!initialized) {
            initState();
            initialized = true;
        }
        stepSynoptic();
        simulate("CAMPUS01");
        simulate("FARM02");
    }

    /** 首轮初始化：优先从库中最近观测续跑，取不到再回落到基线中点 */
    private void initState() {
        ObsData latest = null;
        try {
            latest = obsReader.queryLatest("CAMPUS01");
        } catch (Exception e) {
            log.warn("模拟器读取最近观测失败，使用基线初始化: {}", e.getMessage());
        }
        Map<String, Double> seed = latest != null ? latest.getElements() : Map.of();
        PROFILE.forEach((element, profile) -> {
            Double stored = seed.get(element);
            synoptic.put(element, stored != null
                    ? clamp(stored, profile[0], profile[1])
                    : profile[0] + (profile[1] - profile[0]) / 2);
        });
        log.info("模拟器状态已初始化: 来源={}", latest != null ? "库中最近观测" : "基线中点");
    }

    /** 推进大尺度天气背景（所有站点共享同一次游走） */
    private void stepSynoptic() {
        synoptic.forEach((element, value) -> {
            double[] profile = PROFILE.get(element);
            if (profile != null) {
                double delta = (random.nextDouble() * 2 - 1) * profile[2];
                synoptic.put(element, clamp(value + delta, profile[0], profile[1]));
            }
        });
    }

    private void simulate(String stationCode) {
        Map<String, Double> offsets = localOffsets.computeIfAbsent(stationCode, k -> initOffsets());
        Map<String, Double> next = new ConcurrentHashMap<>();

        // 天气背景 + 有界局地偏差
        synoptic.forEach((element, base) -> {
            double[] profile = PROFILE.get(element);
            double offset = offsets.getOrDefault(element, 0.0);
            double bound = profile[2] * LOCAL_OFFSET_RATIO;
            double nextOffset = clamp(offset + (random.nextDouble() * 2 - 1) * profile[2] * 0.5,
                    -bound, bound);
            offsets.put(element, nextOffset);
            next.put(element, clamp(base + nextOffset, profile[0], profile[1]));
        });

        // 5% 概率注入气温异常尖峰（-50℃ 超出极值下限，演示质控拦截）
        if (random.nextInt(100) < 5) {
            double spike = -50 - random.nextDouble() * 10;
            next.put("temp", spike);
            log.info("模拟器注入异常气温尖峰: station={}, value={}", stationCode, spike);
        }
        // 降水：间歇性事件，单位为雨强 mm/h，多数时次为 0
        next.put("rain", random.nextDouble() < RAIN_PROBABILITY
                ? Math.round((RAIN_MIN + random.nextDouble() * (RAIN_MAX - RAIN_MIN)) * 10) / 10.0
                : 0.0);

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

    /** 局地偏差初值：小幅随机，使两站初始即略有差异而非完全一致 */
    private Map<String, Double> initOffsets() {
        Map<String, Double> offsets = new ConcurrentHashMap<>();
        PROFILE.forEach((element, profile) -> {
            double bound = profile[2] * LOCAL_OFFSET_RATIO;
            offsets.put(element, (random.nextDouble() * 2 - 1) * bound * 0.5);
        });
        return offsets;
    }

    private double clamp(double v, double min, double max) {
        return Math.max(min, Math.min(max, v));
    }
}
