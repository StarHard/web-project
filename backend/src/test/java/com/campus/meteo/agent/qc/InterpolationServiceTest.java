package com.campus.meteo.agent.qc;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 缺测插补算法单测：纯计算，无需 Spring 上下文与 Mockito。
 */
@DisplayName("缺测插补算法")
class InterpolationServiceTest {

    private final InterpolationService service = new InterpolationService();

    @Test
    @DisplayName("采集间隔由数据推断：15 秒数据得 15、60 秒数据得 60")
    void shouldInferIntervalFromData() {
        assertThat(service.inferIntervalSeconds(series(6, 15))).isEqualTo(15L);
        assertThat(service.inferIntervalSeconds(series(6, 60))).isEqualTo(60L);
    }

    @Test
    @DisplayName("间隔推断取中位数：个别长缺口不会把间隔拉大而漏判缺测")
    void shouldUseMedianSoLongGapDoesNotInflateInterval() {
        // 前 5 个间隔 15 秒，最后一个间隔 300 秒
        List<Instant> timestamps = new ArrayList<>(series(5, 15));
        Instant last = timestamps.get(timestamps.size() - 1).plusSeconds(300);
        timestamps.add(last);

        assertThat(service.inferIntervalSeconds(timestamps)).isEqualTo(15L);
    }

    @Test
    @DisplayName("样本不足或间隔越界时返回 null 或钳制到边界")
    void shouldHandleInsufficientSamplesAndOutOfRangeInterval() {
        assertThat(service.inferIntervalSeconds(null)).isNull();
        assertThat(service.inferIntervalSeconds(List.of())).isNull();
        assertThat(service.inferIntervalSeconds(series(2, 15))).isNull();
        // 间隔 600 秒超出上限，钳制到 300
        assertThat(service.inferIntervalSeconds(series(4, 600))).isEqualTo(300L);
    }

    @Test
    @DisplayName("缺测判定阈值取 1.5 倍间隔：丢一个样本（恰为 2 倍）必须判为缺口")
    void shouldDetectGapWhenOneSampleIsLost() {
        assertThat(service.isGap(15, 15)).isFalse();
        assertThat(service.isGap(30, 15)).isTrue();
        assertThat(service.isGap(60, 60)).isFalse();
        assertThat(service.isGap(120, 60)).isTrue();
    }

    @Test
    @DisplayName("缺测槽位数 = 间隔倍数 - 1，至少为 1")
    void shouldCountMissingSlots() {
        assertThat(service.missingSlots(30, 15)).isEqualTo(1);
        assertThat(service.missingSlots(45, 15)).isEqualTo(2);
        assertThat(service.missingSlots(120, 60)).isEqualTo(1);
    }

    @Test
    @DisplayName("线性插值按时间比例取值")
    void shouldInterpolateLinearly() {
        Instant left = Instant.parse("2026-09-20T00:00:00Z");
        Instant right = left.plusSeconds(60);

        assertThat(service.interpolateValue("temp", left, 10.0, right, 13.0, left.plusSeconds(20)))
                .isEqualTo(11.0);
        assertThat(service.interpolateValue("temp", left, 10.0, right, 13.0, left.plusSeconds(30)))
                .isEqualTo(11.5);
    }

    @Test
    @DisplayName("风向按圆周插值：359°→1° 的中点应落在 0°/360° 附近而非反向的 180°")
    void shouldInterpolateWindDirectionOnCircle() {
        Instant left = Instant.parse("2026-09-20T00:00:00Z");
        Instant right = left.plusSeconds(60);

        Double result = service.interpolateValue(InterpolationService.WIND_DIR,
                left, 359.0, right, 1.0, left.plusSeconds(30));

        assertThat(result).isNotNull();
        assertThat(result < 10 || result > 350)
                .as("圆周插值应落在 0°/360° 附近，实际 %s（算术插值会得到 180°）", result)
                .isTrue();
    }

    @Test
    @DisplayName("任一锚点缺值时不插补")
    void shouldReturnNullWhenAnchorMissing() {
        Instant left = Instant.parse("2026-09-20T00:00:00Z");
        Instant right = left.plusSeconds(60);

        assertThat(service.interpolateValue("temp", left, null, right, 13.0, left.plusSeconds(30)))
                .isNull();
        assertThat(service.interpolateValue("temp", left, 10.0, right, null, left.plusSeconds(30)))
                .isNull();
        assertThat(service.interpolateValue("temp", left, 10.0, right, 13.0, null)).isNull();
    }

    @Test
    @DisplayName("偏差订正：本站在邻站基础上偏暖，偏差为正")
    void shouldComputeBiasWithCorrectSign() {
        List<InterpolationService.ObsDataView> own = views(12, 20.0);
        List<InterpolationService.ObsDataView> neighbor = views(12, 15.0);

        InterpolationService.Bias bias = service.computeBias(own, neighbor,
                List.of("temp"), 7);

        assertThat(bias.values().get("temp")).isEqualTo(5.0);
        assertThat(bias.pairCount()).isEqualTo(12);
    }

    @Test
    @DisplayName("偏差订正：无配对时次时不计入样本")
    void shouldNotPairOutsideTolerance() {
        List<InterpolationService.ObsDataView> own = views(5, 20.0);
        // 邻站整体偏移 300 秒（超过本站 60 秒的跨度），任何时次都落在 7 秒容差之外
        List<InterpolationService.ObsDataView> neighbor = new ArrayList<>();
        for (InterpolationService.ObsDataView view : views(5, 15.0)) {
            neighbor.add(new InterpolationService.ObsDataView(
                    view.ts().plusSeconds(300), view.elements()));
        }

        InterpolationService.Bias bias = service.computeBias(own, neighbor, List.of("temp"), 7);

        assertThat(bias.pairCount()).isZero();
        assertThat(bias.values()).isEmpty();
    }

    @Test
    @DisplayName("Haversine 距离：演示两站(南京栖霞—江宁)约 18 公里")
    void shouldComputeDistanceBetweenDemoStations() {
        double distance = service.distanceKm(118.914000, 32.103000, 118.842000, 31.953000);

        assertThat(distance).isBetween(15.0, 21.0);
    }

    @Test
    @DisplayName("就近匹配：容差内取最近，超容差返回 null")
    void shouldFindNearestWithinTolerance() {
        Instant target = Instant.parse("2026-09-20T00:00:30Z");
        List<InterpolationService.ObsDataView> candidates = List.of(
                new InterpolationService.ObsDataView(Instant.parse("2026-09-20T00:00:00Z"), Map.of("temp", 1.0)),
                new InterpolationService.ObsDataView(Instant.parse("2026-09-20T00:00:35Z"), Map.of("temp", 2.0)));

        InterpolationService.ObsDataView nearest = service.nearest(candidates, target, 10);
        assertThat(nearest).isNotNull();
        assertThat(nearest.elements().get("temp")).isEqualTo(2.0);

        assertThat(service.nearest(candidates, Instant.parse("2026-09-20T01:00:00Z"), 10)).isNull();
    }

    /** 生成 count 个等间隔时刻，起点固定便于断言 */
    private List<Instant> series(int count, int stepSeconds) {
        Instant start = Instant.parse("2026-09-20T00:00:00Z");
        List<Instant> timestamps = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            timestamps.add(start.plusSeconds((long) i * stepSeconds));
        }
        return timestamps;
    }

    /** 生成 count 个等间隔观测视图，temp 恒为给定值 */
    private List<InterpolationService.ObsDataView> views(int count, double temp) {
        Instant start = Instant.parse("2026-09-20T00:00:00Z");
        List<InterpolationService.ObsDataView> views = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            Map<String, Double> elements = new LinkedHashMap<>();
            elements.put("temp", temp);
            views.add(new InterpolationService.ObsDataView(start.plusSeconds(i * 15L), elements));
        }
        return views;
    }
}