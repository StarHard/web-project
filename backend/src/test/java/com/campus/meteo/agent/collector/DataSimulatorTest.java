package com.campus.meteo.agent.collector;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

/**
 * 数据模拟器的物理合理性测试。
 *
 * <p>这个测试类存在的意义：「数据不真实」不会抛异常，它只会安静地产生
 * 凌晨 600 W/m² 的辐射、每小时都下一场雨、气温一小时跳 14℃ 这类不可能的数据，
 * 而且会一路穿过质控进入告警与预报。因此把真实大气的硬约束与统计特征固化成断言，
 * 让「改参数改坏了物理」在单测阶段就暴露。
 *
 * <p>断言口径与质控阈值对齐：qc.temp.change.max=5 ℃/h、qc.pres.change.max=6 hPa/h、
 * qc.humi.change.max=15 %/h —— 这里的单位都是「每小时」，与 {@code QcAgent} 里
 * 直接比较相邻样本绝对差值的实现不同（那是另一个待修的问题）。
 */
class DataSimulatorTest {

    private static final ZoneId ZONE = ZoneId.systemDefault();

    /** 采样日固定为 2026 年 9 月，保证结果可复现（模拟器本身的噪声幅度很小，不足以影响结论） */
    private static final int MONTH = 9;

    /** 低于该气温即视为注入的异常尖峰（模拟器注入区间为 -50 ~ -60℃） */
    private static final double SPIKE_THRESHOLD = -40.0;

    private final DataSimulator simulator = new DataSimulator(mock(CollectorAgent.class));

    /** 一条带时刻的采样 */
    private record Sample(int hour, Map<String, Double> values) {
    }

    private long at(int day, int hour) {
        return LocalDateTime.of(2026, MONTH, day, hour, 0).atZone(ZONE).toInstant().toEpochMilli();
    }

    /** 按小时采样一天 */
    private List<Sample> oneDay(String station) {
        List<Sample> samples = new ArrayList<>();
        for (int hour = 0; hour < 24; hour++) {
            samples.add(new Sample(hour, simulator.computeElements(station, at(20, hour))));
        }
        return samples;
    }

    /** 按小时采样一周，用于统计特征 */
    private List<Sample> oneWeek(String station) {
        List<Sample> samples = new ArrayList<>();
        for (int day = 14; day <= 20; day++) {
            for (int hour = 0; hour < 24; hour++) {
                samples.add(new Sample(hour, simulator.computeElements(station, at(day, hour))));
            }
        }
        return samples;
    }

    private boolean isSpike(Sample sample) {
        return sample.values().get("temp") < SPIKE_THRESHOLD;
    }

    private double mean(List<Sample> samples, int fromHour, int toHourExclusive, String key) {
        double sum = 0;
        int count = 0;
        for (Sample sample : samples) {
            if (sample.hour() >= fromHour && sample.hour() < toHourExclusive && !isSpike(sample)) {
                sum += sample.values().get(key);
                count++;
            }
        }
        return count == 0 ? Double.NaN : sum / count;
    }

    private double meanOf(List<Sample> samples, String key) {
        return samples.stream().filter(s -> !isSpike(s)).mapToDouble(s -> s.values().get(key)).average().orElse(Double.NaN);
    }

    private double correlation(List<Sample> a, List<Sample> b, String key) {
        List<double[]> pairs = new ArrayList<>();
        for (int i = 0; i < Math.min(a.size(), b.size()); i++) {
            if (isSpike(a.get(i)) || isSpike(b.get(i))) {
                continue;
            }
            pairs.add(new double[]{a.get(i).values().get(key), b.get(i).values().get(key)});
        }
        double meanX = pairs.stream().mapToDouble(p -> p[0]).average().orElse(0);
        double meanY = pairs.stream().mapToDouble(p -> p[1]).average().orElse(0);
        double num = 0;
        double dx = 0;
        double dy = 0;
        for (double[] p : pairs) {
            num += (p[0] - meanX) * (p[1] - meanY);
            dx += Math.pow(p[0] - meanX, 2);
            dy += Math.pow(p[1] - meanY, 2);
        }
        return dx == 0 || dy == 0 ? Double.NaN : num / Math.sqrt(dx * dy);
    }

    /** 相邻小时变化量的最大值（跳过尖峰样本，尖峰是刻意注入的、不参与物理判定） */
    private double maxHourlyChange(List<Sample> samples, String key) {
        double max = 0;
        for (int i = 1; i < samples.size(); i++) {
            Sample previous = samples.get(i - 1);
            Sample current = samples.get(i);
            if (isSpike(previous) || isSpike(current)) {
                continue;
            }
            max = Math.max(max, Math.abs(current.values().get(key) - previous.values().get(key)));
        }
        return max;
    }

    /** Magnus 公式计算露点（℃） */
    private double dewPoint(double temp, double humidity) {
        double a = 17.27;
        double b = 237.7;
        double alpha = Math.log(humidity / 100.0) + a * temp / (b + temp);
        return b * alpha / (a - alpha);
    }

    @Test
    @DisplayName("辐射随太阳高度角变化：夜间严格为 0，正午显著高于清晨与傍晚")
    void radiationFollowsSun() {
        List<Sample> samples = oneDay("CAMPUS01");

        // 9 月下旬日长约 13 小时，日出约 5:30、日落约 18:30
        for (int hour : new int[]{0, 1, 2, 3, 4, 19, 20, 21, 22, 23}) {
            assertEquals(0.0, samples.get(hour).values().get("rad"), 1e-9,
                    "夜间 " + hour + " 时的辐射必须严格为 0");
        }

        double noon = samples.get(12).values().get("rad");
        assertTrue(noon > 100, "正午辐射应有实质数值，实际 " + noon);
        assertTrue(noon > samples.get(7).values().get("rad"), "正午辐射应高于清晨 7 时");
        assertTrue(noon > samples.get(17).values().get("rad"), "正午辐射应高于傍晚 17 时");
    }

    @Test
    @DisplayName("气温有日变化：午后显著高于日出前")
    void temperatureHasDiurnalCycle() {
        List<Sample> samples = oneDay("CAMPUS01");
        double afternoon = mean(samples, 13, 16, "temp");
        double beforeDawn = mean(samples, 3, 6, "temp");
        assertTrue(afternoon - beforeDawn > 3.0,
                "午后应明显高于日出前，实际午后 " + afternoon + "℃、日出前 " + beforeDawn + "℃");
    }

    @Test
    @DisplayName("湿度与气温反相：越热越干")
    void humidityAntiCorrelatesWithTemperature() {
        double r = correlationOf(oneWeek("CAMPUS01"));
        assertTrue(r < -0.3, "气温与湿度的相关系数应显著为负，实际 " + r);
    }

    /** 在同一份采样上求「气温 vs 湿度」的相关系数 */
    private double correlationOf(List<Sample> samples) {
        List<Sample> a = new ArrayList<>();
        List<Sample> b = new ArrayList<>();
        for (Sample s : samples) {
            a.add(new Sample(s.hour(), Map.of("temp", s.values().get("temp"))));
            b.add(new Sample(s.hour(), Map.of("temp", s.values().get("humi"))));
        }
        return correlation(a, b, "temp");
    }

    @Test
    @DisplayName("降水与湿度、辐射耦合：有雨时更湿，白天有雨时辐射更低")
    void rainCouplesWithHumidityAndRadiation() {
        List<Sample> samples = oneWeek("CAMPUS01");

        List<Sample> wet = samples.stream().filter(s -> s.values().get("rain") > 0.05).toList();
        List<Sample> dry = samples.stream().filter(s -> s.values().get("rain") <= 0.05).toList();
        assertTrue(wet.size() >= 3, "一周内应有降水样本可供检验，实际 " + wet.size());

        double wetHumi = meanOf(wet, "humi");
        double dryHumi = meanOf(dry, "humi");
        assertTrue(wetHumi > dryHumi + 5.0,
                "有雨时湿度应明显更高，实际有雨 " + wetHumi + "%、无雨 " + dryHumi + "%");

        List<Sample> wetDay = wet.stream().filter(s -> s.hour() >= 9 && s.hour() <= 15).toList();
        List<Sample> dryDay = dry.stream().filter(s -> s.hour() >= 9 && s.hour() <= 15).toList();
        if (!wetDay.isEmpty() && !dryDay.isEmpty()) {
            double wetRad = meanOf(wetDay, "rad");
            double dryRad = meanOf(dryDay, "rad");
            assertTrue(wetRad < dryRad,
                    "白天有雨时辐射应更低，实际有雨 " + wetRad + "、无雨 " + dryRad);
        }
    }

    @Test
    @DisplayName("小时变化率落在质控阈值内（阈值语义为「每小时」）")
    void hourlyChangeRateWithinQcThresholds() {
        List<Sample> samples = oneWeek("CAMPUS01");

        double maxTemp = maxHourlyChange(samples, "temp");
        double maxPres = maxHourlyChange(samples, "pres");
        double maxHumi = maxHourlyChange(samples, "humi");

        assertTrue(maxTemp <= 5.0, "气温小时变化应 ≤ 5 ℃/h（qc.temp.change.max），实际 " + maxTemp);
        assertTrue(maxPres <= 6.0, "气压小时变化应 ≤ 6 hPa/h（qc.pres.change.max），实际 " + maxPres);
        assertTrue(maxHumi <= 15.0, "湿度小时变化应 ≤ 15 %/h（qc.humi.change.max），实际 " + maxHumi);
    }

    @Test
    @DisplayName("露点不高于气温（热力学硬约束）")
    void dewPointNeverExceedsTemperature() {
        for (Sample sample : oneWeek("CAMPUS01")) {
            if (isSpike(sample)) {
                continue;
            }
            double temp = sample.values().get("temp");
            double humidity = sample.values().get("humi");
            assertTrue(dewPoint(temp, humidity) <= temp + 0.01,
                    "露点不得高于气温：T=" + temp + " RH=" + humidity);
        }
    }

    @Test
    @DisplayName("值域约束：湿度 0~100、气压在合理区间、风向归一化到 [0,360)")
    void valuesStayInPhysicalRange() {
        for (Sample sample : oneWeek("CAMPUS01")) {
            double humidity = sample.values().get("humi");
            double pressure = sample.values().get("pres");
            double direction = sample.values().get("wind_dir");
            double rain = sample.values().get("rain");

            assertTrue(humidity >= 0 && humidity <= 100, "湿度越界：" + humidity);
            assertTrue(pressure > 950 && pressure < 1060, "气压越界：" + pressure);
            assertTrue(direction >= 0 && direction < 360, "风向未归一化：" + direction);
            assertTrue(rain >= 0 && rain <= 10, "雨强越界：" + rain);
        }
    }

    @Test
    @DisplayName("风速分布合理：均值适中、超过 10 m/s 只是偶发而非长期超阈值")
    void windSpeedDistributionIsReasonable() {
        List<Sample> samples = oneWeek("CAMPUS01");
        List<Double> speeds = samples.stream().map(s -> s.values().get("wind_speed")).toList();

        double average = speeds.stream().mapToDouble(Double::doubleValue).average().orElse(0);
        double max = speeds.stream().mapToDouble(Double::doubleValue).max().orElse(0);
        long overThreshold = speeds.stream().filter(v -> v > 10.0).count();
        double overRatio = (double) overThreshold / speeds.size();

        assertTrue(average > 1.5 && average < 6.0, "风速均值应在 1.5~6 m/s，实际 " + average);
        assertTrue(max <= 20.0, "风速不应超过 20 m/s，实际 " + max);
        assertTrue(overRatio < 0.15,
                "超过告警阈值 10 m/s 的时次应少于 15%，实际 " + overRatio * 100 + "%（否则告警会长期常驻）");
    }

    @Test
    @DisplayName("降水是间歇事件，不是「每小时都有雨」")
    void rainIsIntermittent() {
        List<Sample> samples = oneWeek("CAMPUS01");
        long wet = samples.stream().filter(s -> s.values().get("rain") > 0.05).count();
        double ratio = (double) wet / samples.size();
        assertTrue(ratio > 0.02 && ratio < 0.35,
                "有雨时次占比应落在 2%~35%，实际 " + ratio * 100 + "%");
    }

    @Test
    @DisplayName("相距十几公里的两站实况高度相关，且偏差有界")
    void neighboringStationsAreHighlyCorrelated() {
        List<Sample> campus = oneWeek("CAMPUS01");
        List<Sample> farm = oneWeek("FARM02");

        assertTrue(correlation(campus, farm, "temp") > 0.9,
                "两站气温应高度相关，实际 " + correlation(campus, farm, "temp"));
        assertTrue(correlation(campus, farm, "humi") > 0.9,
                "两站湿度应高度相关，实际 " + correlation(campus, farm, "humi"));

        double maxDiff = 0;
        for (int i = 0; i < campus.size(); i++) {
            if (isSpike(campus.get(i)) || isSpike(farm.get(i))) {
                continue;
            }
            maxDiff = Math.max(maxDiff, Math.abs(campus.get(i).values().get("temp") - farm.get(i).values().get("temp")));
        }
        assertTrue(maxDiff < 4.0, "两站气温差应保持在物理合理范围内，实际最大 " + maxDiff + "℃");
    }
}