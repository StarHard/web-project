package com.campus.meteo.agent.forecast;

import com.campus.meteo.influx.ObsData;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * 统计降尺度预报模型（基线版）：
 * 预报值 = 小时气候态(目标时刻小时) + 近期距平 × 衰减因子
 *  - 小时气候态：由近期（最长7天）历史观测按"小时-of-day"平均得到，刻画日变化曲线
 *  - 近期距平：最近3个时次观测均值 - 起报时刻气候态，刻画当前偏离程度
 *  - 衰减因子：exp(-h/36)，距平影响随时效衰减（持续性假设）
 * 风向采用矢量平均；降水以气候态小时发生率×湿度距平修正生成概率与量级
 * 后续可无缝替换为 LSTM 等机器学习模型（实现 ForecastModel 接口即可）
 */
@Slf4j
@Component
public class StatisticalForecastModel {

    private static final ZoneId ZONE = ZoneId.of("Asia/Shanghai");
    /** 参与预报的要素（风向单独处理） */
    private static final List<String> ELEMENTS = List.of("temp", "humi", "pres", "wind_speed");

    /**
     * 生成 0-72h 逐小时预报
     *
     * @param history 近期历史观测（建议7天，至少24小时），可乱序
     * @param hours   预报时效（小时）
     * @return 预报目标时刻 → 要素值（temp/humi/pres/wind_speed/wind_dir/rain/pop）
     */
    public Map<Instant, Map<String, Double>> forecast(List<ObsData> history, int hours) {
        Map<Instant, Map<String, Double>> result = new TreeMap<>();
        if (history == null || history.size() < 3) {
            log.warn("历史观测不足（{}条），无法生成预报", history == null ? 0 : history.size());
            return result;
        }
        List<ObsData> sorted = new ArrayList<>(history);
        sorted.sort(Comparator.comparing(ObsData::getTs));
        ObsData base = sorted.get(sorted.size() - 1);
        Instant baseTime = base.getTs();

        // 1. 小时气候态（小时-of-day → 要素均值）
        Map<String, Map<Integer, Double>> climatology = buildClimatology(sorted);
        // 1.1 全局均值兜底：历史样本尚不足以覆盖全部钟点（如刚投运的站点）时使用
        Map<String, Double> globalMeans = buildGlobalMeans(sorted, climatology);
        // 2. 近期距平
        Map<String, Double> anomaly = buildAnomaly(sorted, climatology, globalMeans);

        for (int h = 1; h <= hours; h++) {
            Instant target = baseTime.plusSeconds((long) h * 3600);
            int hourOfDay = target.atZone(ZONE).getHour();
            double decay = Math.exp(-h / 36.0);
            Map<String, Double> point = new LinkedHashMap<>();

            for (String element : ELEMENTS) {
                Double clim = resolve(climatology, globalMeans, element, hourOfDay);
                if (clim == null) {
                    continue;
                }
                Double offset = anomaly.get(element);
                point.put(element, round(offset == null ? clim : clim + offset * decay));
            }
            // 风向：气候态主导方向 + 最近观测微调
            Double dirClim = resolve(climatology, globalMeans, "wind_dir", hourOfDay);
            if (dirClim != null) {
                double blended = blendAngle(dirClim, base.getElements().getOrDefault("wind_dir", dirClim), 0.7);
                point.put("wind_dir", round(blended));
            }
            // 降水：气候态发生率 × 湿度距平修正
            Double rainClim = resolve(climatology, globalMeans, "rain", hourOfDay);
            Double humiOffset = anomaly.get("humi");
            if (rainClim != null) {
                double baseRate = rainClim > 0.1 ? 0.6 : 0.08;
                double factor = humiOffset != null ? clamp(1 + humiOffset / 100.0, 0.3, 2.0) : 1.0;
                double pop = clamp(baseRate * factor * 100, 2, 95);
                double rain = rainClim * factor * decay;
                point.put("pop", round(pop));
                point.put("rain", round(Math.max(0, rain)));
            }
            if (!point.isEmpty()) {
                result.put(target, point);
            }
        }
        return result;
    }

    /** 取值：优先小时气候态，缺失时回退全局均值 */
    private Double resolve(Map<String, Map<Integer, Double>> climatology, Map<String, Double> globalMeans,
                           String element, int hourOfDay) {
        Map<Integer, Double> byHour = climatology.get(element);
        if (byHour != null) {
            Double value = byHour.get(hourOfDay);
            if (value != null) {
                return value;
            }
        }
        return globalMeans.get(element);
    }

    /** 全局均值：小时气候态缺失钟点的兜底值（风向按矢量平均） */
    private Map<String, Double> buildGlobalMeans(List<ObsData> history,
                                                Map<String, Map<Integer, Double>> climatology) {
        Map<String, Double> means = new HashMap<>();
        List<String> allElements = new ArrayList<>(ELEMENTS);
        allElements.add("rain");
        for (String element : allElements) {
            double sum = 0;
            int count = 0;
            for (ObsData obs : history) {
                Double v = obs.getElements().get(element);
                if (v != null) {
                    sum += v;
                    count++;
                }
            }
            if (count > 0) {
                means.put(element, sum / count);
            }
        }
        // 风向：矢量平均（避免 359°/1° 直接平均出错）
        double cosSum = 0, sinSum = 0;
        int dirCount = 0;
        for (ObsData obs : history) {
            Double dir = obs.getElements().get("wind_dir");
            if (dir != null) {
                cosSum += Math.cos(Math.toRadians(dir));
                sinSum += Math.sin(Math.toRadians(dir));
                dirCount++;
            }
        }
        if (dirCount > 0 && (cosSum != 0 || sinSum != 0)) {
            means.put("wind_dir", (Math.toDegrees(Math.atan2(sinSum, cosSum)) + 360) % 360);
        } else if (climatology.get("wind_dir") != null && !climatology.get("wind_dir").isEmpty()) {
            means.put("wind_dir", climatology.get("wind_dir").values().iterator().next());
        }
        return means;
    }

    /** 构建小时气候态：要素 → 小时-of-day → 均值（风向矢量平均） */
    private Map<String, Map<Integer, Double>> buildClimatology(List<ObsData> history) {
        Map<String, Map<Integer, List<Double>>> buckets = new HashMap<>();
        Map<String, Map<Integer, double[]>> dirVectors = new HashMap<>();
        for (ObsData obs : history) {
            int hour = obs.getTs().atZone(ZONE).getHour();
            for (String element : ELEMENTS) {
                Double v = obs.getElements().get(element);
                if (v != null) {
                    buckets.computeIfAbsent(element, k -> new HashMap<>())
                            .computeIfAbsent(hour, k -> new ArrayList<>()).add(v);
                }
            }
            Double dir = obs.getElements().get("wind_dir");
            if (dir != null) {
                double rad = Math.toRadians(dir);
                dirVectors.computeIfAbsent("wind_dir", k -> new HashMap<>())
                        .computeIfAbsent(hour, k -> new double[2])[0] += Math.cos(rad);
                double[] vec = dirVectors.get("wind_dir").get(hour);
                vec[1] += Math.sin(rad);
            }
            Double rain = obs.getElements().get("rain");
            if (rain != null) {
                buckets.computeIfAbsent("rain", k -> new HashMap<>())
                        .computeIfAbsent(hour, k -> new ArrayList<>()).add(rain);
            }
        }
        Map<String, Map<Integer, Double>> climatology = new HashMap<>();
        buckets.forEach((element, byHour) -> {
            Map<Integer, Double> means = new HashMap<>();
            byHour.forEach((hour, values) -> means.put(hour, values.stream().mapToDouble(Double::doubleValue).average().orElse(0)));
            climatology.put(element, means);
        });
        // 风向气候态：矢量平均转角度
        Map<Integer, Double> dirMean = new HashMap<>();
        dirVectors.getOrDefault("wind_dir", Map.of()).forEach((hour, vec) -> {
            if (vec[0] != 0 || vec[1] != 0) {
                dirMean.put(hour, (Math.toDegrees(Math.atan2(vec[1], vec[0])) + 360) % 360);
            }
        });
        climatology.put("wind_dir", dirMean);
        return climatology;
    }

    /** 近期距平：最近3个时次均值 - 起报小时气候态（气候态缺失时用全局均值） */
    private Map<String, Double> buildAnomaly(List<ObsData> sorted, Map<String, Map<Integer, Double>> climatology,
                                             Map<String, Double> globalMeans) {
        Map<String, Double> anomaly = new HashMap<>();
        ObsData base = sorted.get(sorted.size() - 1);
        int baseHour = base.getTs().atZone(ZONE).getHour();
        List<ObsData> recent = sorted.subList(Math.max(0, sorted.size() - 3), sorted.size());

        for (String element : ELEMENTS) {
            List<Double> values = recent.stream()
                    .map(o -> o.getElements().get(element))
                    .filter(v -> v != null)
                    .toList();
            if (values.isEmpty()) {
                continue;
            }
            double recentMean = values.stream().mapToDouble(Double::doubleValue).average().orElse(0);
            Double clim = resolve(climatology, globalMeans, element, baseHour);
            if (clim != null) {
                anomaly.put(element, recentMean - clim);
            }
        }
        return anomaly;
    }

    /** 角度加权融合（处理 0/360 环绕） */
    private double blendAngle(double a, double b, double weightA) {
        double rad = weightA * Math.toRadians(a) + (1 - weightA) * Math.toRadians(b);
        double x = weightA * Math.cos(Math.toRadians(a)) + (1 - weightA) * Math.cos(Math.toRadians(b));
        double y = weightA * Math.sin(Math.toRadians(a)) + (1 - weightA) * Math.sin(Math.toRadians(b));
        if (x == 0 && y == 0) {
            return rad;
        }
        return (Math.toDegrees(Math.atan2(y, x)) + 360) % 360;
    }

    private double clamp(double v, double min, double max) {
        return Math.max(min, Math.min(max, v));
    }

    private double round(double v) {
        return Math.round(v * 10) / 10.0;
    }
}
