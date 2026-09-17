package com.campus.meteo.agent.forecast;

import com.campus.meteo.influx.FcstReader;
import com.campus.meteo.influx.ObsData;
import com.campus.meteo.influx.ObsReader;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * 预报检验服务：将历史预报产品与实况观测对齐，计算检验评分
 *  - 连续要素（气温/风速/湿度/气压）：MAE 平均绝对误差、RMSE 均方根误差、Bias 偏差
 *  - 降水（阈值 0.1mm/h）：TS 评分、POD 命中率、FAR 空报率
 * 对齐方式：观测分钟数据按小时聚合均值，与预报目标时刻按小时对齐
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class VerificationService {

    private static final ZoneId ZONE = ZoneId.of("Asia/Shanghai");
    private static final DateTimeFormatter TIME_FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
    /** 参与检验的连续要素 */
    private static final List<String> CONTINUOUS_ELEMENTS = List.of("temp", "humi", "pres", "wind_speed");
    /** 降水事件判定阈值（mm/h） */
    private static final double RAIN_THRESHOLD = 0.1;

    private final ObsReader obsReader;
    private final FcstReader fcstReader;

    /**
     * 检验最近 N 天的预报质量
     */
    public Map<String, Object> verify(String stationCode, String model, int days) {
        Instant now = Instant.now();
        Instant from = now.minusSeconds((long) days * 86400);

        // 实况按小时聚合
        Map<Long, Map<String, Double>> obsHourly = aggregateHourly(
                obsReader.queryRange(stationCode, from, now));
        // 预报序列
        Map<Instant, Map<String, Double>> fcst = fcstReader.query(stationCode, model, from, now);

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("stationCode", stationCode);
        result.put("model", model);
        result.put("days", days);
        result.put("generatedAt", LocalDateTime.now(ZONE).format(TIME_FMT));

        // 连续要素评分
        List<Map<String, Object>> elementStats = new ArrayList<>();
        for (String element : CONTINUOUS_ELEMENTS) {
            List<double[]> pairs = new ArrayList<>();
            for (Map.Entry<Instant, Map<String, Double>> entry : fcst.entrySet()) {
                Double f = entry.getValue().get(element);
                Map<String, Double> hourObs = obsHourly.get(truncateToHour(entry.getKey()));
                if (f == null || hourObs == null) {
                    continue;
                }
                Double o = hourObs.get(element);
                if (o != null) {
                    pairs.add(new double[]{f, o});
                }
            }
            if (!pairs.isEmpty()) {
                elementStats.add(elementStat(element, pairs));
            }
        }
        result.put("elements", elementStats);

        // 降水分类评分
        int hits = 0, misses = 0, falseAlarms = 0;
        for (Map.Entry<Instant, Map<String, Double>> entry : fcst.entrySet()) {
            Double fRain = entry.getValue().get("rain");
            Map<String, Double> hourObs = obsHourly.get(truncateToHour(entry.getKey()));
            if (fRain == null || hourObs == null || !hourObs.containsKey("rain")) {
                continue;
            }
            boolean forecastRain = fRain >= RAIN_THRESHOLD;
            boolean observedRain = hourObs.get("rain") >= RAIN_THRESHOLD;
            if (forecastRain && observedRain) {
                hits++;
            } else if (!forecastRain && observedRain) {
                misses++;
            } else if (forecastRain) {
                falseAlarms++;
            }
        }
        Map<String, Object> rainStat = new LinkedHashMap<>();
        int total = hits + misses + falseAlarms;
        rainStat.put("threshold", RAIN_THRESHOLD);
        rainStat.put("samples", total);
        rainStat.put("hits", hits);
        rainStat.put("misses", misses);
        rainStat.put("falseAlarms", falseAlarms);
        rainStat.put("ts", total > 0 ? round3(hits * 1.0 / total) : null);
        rainStat.put("pod", hits + misses > 0 ? round3(hits * 1.0 / (hits + misses)) : null);
        rainStat.put("far", hits + falseAlarms > 0 ? round3(falseAlarms * 1.0 / (hits + falseAlarms)) : null);
        result.put("rain", rainStat);
        return result;
    }

    /** 连续要素统计量 */
    private Map<String, Object> elementStat(String element, List<double[]> pairs) {
        double mae = 0, rmse = 0, bias = 0;
        for (double[] pair : pairs) {
            mae += Math.abs(pair[0] - pair[1]);
            rmse += Math.pow(pair[0] - pair[1], 2);
            bias += pair[0] - pair[1];
        }
        int n = pairs.size();
        Map<String, Object> stat = new LinkedHashMap<>();
        stat.put("element", element);
        stat.put("samples", n);
        stat.put("mae", round3(mae / n));
        stat.put("rmse", round3(Math.sqrt(rmse / n)));
        stat.put("bias", round3(bias / n));
        return stat;
    }

    /** 分钟观测按小时聚合（均值） */
    private Map<Long, Map<String, Double>> aggregateHourly(List<ObsData> obsList) {
        Map<Long, Map<String, List<Double>>> buckets = new TreeMap<>();
        for (ObsData obs : obsList) {
            long hour = truncateToHour(obs.getTs());
            obs.getElements().forEach((k, v) -> buckets
                    .computeIfAbsent(hour, h -> new LinkedHashMap<>())
                    .computeIfAbsent(k, e -> new ArrayList<>())
                    .add(v));
        }
        Map<Long, Map<String, Double>> result = new TreeMap<>();
        buckets.forEach((hour, byElement) -> {
            Map<String, Double> means = new LinkedHashMap<>();
            byElement.forEach((element, values) ->
                    means.put(element, values.stream().mapToDouble(Double::doubleValue).average().orElse(0)));
            result.put(hour, means);
        });
        return result;
    }

    /** 截断到小时（epoch 秒） */
    private long truncateToHour(Instant instant) {
        return instant.getEpochSecond() / 3600 * 3600;
    }

    private double round3(double v) {
        return Math.round(v * 1000) / 1000.0;
    }
}
