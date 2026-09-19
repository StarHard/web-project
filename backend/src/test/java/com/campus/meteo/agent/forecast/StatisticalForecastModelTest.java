package com.campus.meteo.agent.forecast;

import com.campus.meteo.influx.ObsData;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 统计降尺度预报模型单测：纯函数逻辑，无需 Spring 上下文。
 * 重点验证边界退化、时序确定性、风向环绕与距平衰减四项容易写错的细节。
 */
@DisplayName("统计预报模型")
class StatisticalForecastModelTest {

    private static final String STATION = "CAMPUS01";
    private static final Instant START = Instant.parse("2026-09-12T00:00:00Z");

    private final StatisticalForecastModel model = new StatisticalForecastModel();

    @Test
    @DisplayName("历史观测不足 3 条 → 返回空预报而非抛异常")
    void shouldReturnEmptyWhenHistoryTooShort() {
        assertThat(model.forecast(null, 12)).isEmpty();
        assertThat(model.forecast(List.of(observation(0, 20.0), observation(1, 20.0)), 12)).isEmpty();
    }

    @Test
    @DisplayName("按请求时效生成逐小时预报，目标时刻严格升序")
    void shouldGenerateHourlyForecastInAscendingOrder() {
        Map<Instant, Map<String, Double>> forecast = model.forecast(history(168, 20.0), 24);

        assertThat(forecast.keySet()).hasSize(24);
        assertThat(new ArrayList<>(forecast.keySet())).isSorted();
        assertThat(forecast.values()).allSatisfy(point ->
                assertThat(point).containsKeys("temp", "humi", "pres", "wind_speed", "wind_dir", "rain", "pop"));
    }

    @Test
    @DisplayName("输入乱序与有序结果一致")
    void shouldBeOrderIndependent() {
        List<ObsData> ordered = history(168, 20.0);
        List<ObsData> shuffled = new ArrayList<>(ordered);
        Collections.shuffle(shuffled, new Random(42));

        assertThat(model.forecast(shuffled, 12)).isEqualTo(model.forecast(ordered, 12));
    }

    @Test
    @DisplayName("风向矢量平均：359°/1° 混合不应折算出 180° 的反向结果")
    void shouldAverageWindDirectionAsVector() {
        Map<Instant, Map<String, Double>> forecast = model.forecast(history(168, 20.0), 6);

        assertThat(forecast.values()).allSatisfy(point -> {
            Double direction = point.get("wind_dir");
            assertThat(direction).isNotNull();
            assertThat(direction < 10 || direction > 350)
                    .as("风向应落在 0°/360° 附近，实际 %s（算术平均会得到约 180°）", direction)
                    .isTrue();
        });
    }

    @Test
    @DisplayName("降水概率落在 2~95 区间，雨量非负")
    void shouldClampPrecipitationOutputs() {
        Map<Instant, Map<String, Double>> forecast = model.forecast(history(168, 20.0), 72);

        assertThat(forecast.values()).allSatisfy(point -> {
            assertThat(point.get("pop")).isBetween(2.0, 95.0);
            assertThat(point.get("rain")).isGreaterThanOrEqualTo(0.0);
        });
    }

    @Test
    @DisplayName("距平随时效衰减：同一钟点下 h=24 比 h=48 更接近当前正距平")
    void shouldDecayAnomalyWithLeadTime() {
        List<ObsData> data = history(168, 20.0);
        // 末端 3 个时次显著偏暖，制造正距平
        for (int i = data.size() - 3; i < data.size(); i++) {
            data.get(i).getElements().put("temp", 30.0);
        }

        Map<Instant, Map<String, Double>> forecast = model.forecast(data, 48);
        Instant base = data.get(data.size() - 1).getTs();
        // h=24 与 h=48 落在同一钟点，气候态相同，差异只来自衰减因子
        double at24 = forecast.get(base.plusSeconds(24 * 3600L)).get("temp");
        double at48 = forecast.get(base.plusSeconds(48 * 3600L)).get("temp");

        assertThat(at24).isGreaterThan(at48);
    }

    /** 构造 hours 条逐小时历史观测；风向按「天」交替 359°/1°，用于验证矢量平均 */
    private List<ObsData> history(int hours, double temp) {
        List<ObsData> list = new ArrayList<>();
        for (int i = 0; i < hours; i++) {
            Map<String, Double> elements = new LinkedHashMap<>();
            elements.put("temp", temp);
            elements.put("humi", 60.0);
            elements.put("pres", 1000.0);
            elements.put("wind_speed", 2.0);
            elements.put("wind_dir", (i / 24) % 2 == 0 ? 359.0 : 1.0);
            elements.put("rain", 0.5);
            ObsData obs = observation(i, temp);
            obs.setElements(elements);
            list.add(obs);
        }
        return list;
    }

    private ObsData observation(int hourOffset, double temp) {
        return ObsData.builder()
                .stationCode(STATION)
                .ts(START.plusSeconds(hourOffset * 3600L))
                .elements(new LinkedHashMap<>(Map.of("temp", temp)))
                .build();
    }
}