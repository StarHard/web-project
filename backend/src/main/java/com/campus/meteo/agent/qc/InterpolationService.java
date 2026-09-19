package com.campus.meteo.agent.qc;

import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 缺测插补算法（FR-QC-05）：纯计算，无外部依赖，便于单测。
 *
 * 提供四件事：
 * 1. 由数据自身推断采集间隔——设备间隔不固定（开发环境 15 秒、生产 60 秒），
 *    写死间隔会把正常数据判成缺测
 * 2. 线性插值与风向圆周插值
 * 3. 跨站偏差订正（邻近站点回归的简化形式）
 * 4. Haversine 距离
 */
@Component
public class InterpolationService {

    /**
     * 参与插补的连续型要素。
     * 降水(rain)刻意排除：它是间歇性事件，多数时次为 0，线性插值会凭空造出连续性降水。
     */
    public static final List<String> INTERPOLATABLE_ELEMENTS =
            List.of("temp", "humi", "pres", "wind_speed", "rad", "vis", "evap");

    /** 风向：角度需按圆周插值，否则 359°→1° 会插出反向的 180° */
    public static final String WIND_DIR = "wind_dir";

    /** 推断出的采集间隔允许范围（秒），超出该范围视为推断失败 */
    private static final long MIN_INTERVAL_SECONDS = 5;
    private static final long MAX_INTERVAL_SECONDS = 300;

    /**
     * 由观测时刻序列推断采集间隔（秒）：取相邻时间差的中位数，钳制到 [5, 300]。
     * 用中位数而非均值，避免个别长缺口把间隔拉大从而漏判缺测。
     *
     * @param timestamps 升序的观测时刻
     * @return 推断的间隔秒数；样本不足 3 条返回 null
     */
    public Long inferIntervalSeconds(List<Instant> timestamps) {
        if (timestamps == null || timestamps.size() < 3) {
            return null;
        }
        List<Instant> sorted = new ArrayList<>(timestamps);
        sorted.sort(Comparator.naturalOrder());

        List<Long> deltas = new ArrayList<>(sorted.size() - 1);
        for (int i = 1; i < sorted.size(); i++) {
            long seconds = Duration.between(sorted.get(i - 1), sorted.get(i)).getSeconds();
            if (seconds > 0) {
                deltas.add(seconds);
            }
        }
        if (deltas.size() < 2) {
            return null;
        }
        deltas.sort(Comparator.naturalOrder());
        long median = deltas.get(deltas.size() / 2);
        if (median < MIN_INTERVAL_SECONDS) {
            return MIN_INTERVAL_SECONDS;
        }
        return Math.min(median, MAX_INTERVAL_SECONDS);
    }

    /**
     * 判定相邻两点之间是否缺测。
     * 阈值取 1.5 倍间隔：丢失一个样本时间差恰为 2 倍，若阈值取 2 倍会漏判。
     */
    public boolean isGap(long deltaSeconds, long intervalSeconds) {
        return deltaSeconds > Math.round(intervalSeconds * 1.5);
    }

    /** 缺口内缺测的槽位数 = round(delta/interval) - 1，至少 1 */
    public int missingSlots(long deltaSeconds, long intervalSeconds) {
        return Math.max(1, (int) Math.round(deltaSeconds * 1.0 / intervalSeconds) - 1);
    }

    /**
     * 插补单点值。风向走圆周插值，其余要素线性插值。
     *
     * @param ratio 目标时刻在 [leftTs, rightTs] 中的位置，取值 (0,1)
     * @return 插补值；任一锚点缺值返回 null
     */
    public Double interpolateValue(String element, Instant leftTs, Double leftValue,
                                   Instant rightTs, Double rightValue, Instant targetTs) {
        if (leftValue == null || rightValue == null || leftTs == null || rightTs == null || targetTs == null) {
            return null;
        }
        long total = Duration.between(leftTs, rightTs).getSeconds();
        if (total <= 0) {
            return null;
        }
        double ratio = Duration.between(leftTs, targetTs).getSeconds() * 1.0 / total;
        ratio = Math.max(0, Math.min(1, ratio));
        return WIND_DIR.equals(element)
                ? interpolateAngle(leftValue, rightValue, ratio)
                : leftValue + (rightValue - leftValue) * ratio;
    }

    /**
     * 角度插值：单位圆矢量加权后回转角度，处理 0/360 环绕。
     * 与 StatisticalForecastModel.blendAngle 同一思路。
     */
    public double interpolateAngle(double from, double to, double ratio) {
        double fromRad = Math.toRadians(from);
        double toRad = Math.toRadians(to);
        double x = (1 - ratio) * Math.cos(fromRad) + ratio * Math.cos(toRad);
        double y = (1 - ratio) * Math.sin(fromRad) + ratio * Math.sin(toRad);
        if (x == 0 && y == 0) {
            return (from + 360) % 360;
        }
        return (Math.toDegrees(Math.atan2(y, x)) + 360) % 360;
    }

    /**
     * 跨站偏差订正：按要素计算 mean(本站) - mean(邻站)，只用两站都在容差内的配对时次。
     *
     * 返回的偏差用于「邻站值 + bias」估算本站缺测值（邻近站点回归的简化形式：
     * 相当于强制回归系数为 1、只订正系统性偏差）。
     *
     * @param toleranceSeconds 配对容差，通常取半个采集间隔
     */
    public Bias computeBias(List<ObsDataView> own, List<ObsDataView> neighbor,
                            List<String> elements, long toleranceSeconds) {
        Map<String, Double> diffSum = new HashMap<>();
        Map<String, Integer> count = new HashMap<>();
        int pairs = 0;

        for (ObsDataView mine : own) {
            ObsDataView theirs = nearest(neighbor, mine.ts(), toleranceSeconds);
            if (theirs == null) {
                continue;
            }
            pairs++;
            for (String element : elements) {
                Double a = mine.elements().get(element);
                Double b = theirs.elements().get(element);
                if (a == null || b == null) {
                    continue;
                }
                diffSum.merge(element, a - b, Double::sum);
                count.merge(element, 1, Integer::sum);
            }
        }

        Map<String, Double> bias = new LinkedHashMap<>();
        diffSum.forEach((element, sum) -> bias.put(element, sum / count.get(element)));
        return new Bias(bias, pairs);
    }

    /** 在候选序列中找与目标时刻最近的观测，超出容差返回 null */
    public ObsDataView nearest(List<ObsDataView> candidates, Instant target, long toleranceSeconds) {
        ObsDataView best = null;
        long bestDiff = Long.MAX_VALUE;
        for (ObsDataView candidate : candidates) {
            long diff = Math.abs(Duration.between(candidate.ts(), target).getSeconds());
            if (diff <= toleranceSeconds && diff < bestDiff) {
                best = candidate;
                bestDiff = diff;
            }
        }
        return best;
    }

    /** Haversine 距离（公里），入参为 GCJ-02 经纬度；该坐标系在小范围内做距离比较足够 */
    public double distanceKm(double lon1, double lat1, double lon2, double lat2) {
        final double earthRadiusKm = 6371.0;
        double dLat = Math.toRadians(lat2 - lat1);
        double dLon = Math.toRadians(lon2 - lon1);
        double a = Math.sin(dLat / 2) * Math.sin(dLat / 2)
                + Math.cos(Math.toRadians(lat1)) * Math.cos(Math.toRadians(lat2))
                * Math.sin(dLon / 2) * Math.sin(dLon / 2);
        return earthRadiusKm * 2 * Math.atan2(Math.sqrt(a), Math.sqrt(1 - a));
    }

    /** 偏差订正结果：各要素偏差 + 参与配对的时次数量 */
    public record Bias(Map<String, Double> values, int pairCount) {
    }

    /** 算法内部使用的观测视图，避免算法层依赖 InfluxDB 模型 */
    public record ObsDataView(Instant ts, Map<String, Double> elements) {
    }
}