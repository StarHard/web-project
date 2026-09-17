package com.campus.meteo.influx;

import com.influxdb.client.InfluxDBClient;
import com.influxdb.client.QueryApi;
import com.influxdb.query.FluxRecord;
import com.influxdb.query.FluxTable;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * 观测数据查询（规范：必须限定 station_code 与时间范围，禁止无界查询）
 *
 * 实现说明：不使用 Flux pivot——pivot 会消费掉 _field/_value 列，
 * 导致 Java 侧无法逐条读取要素值。此处改为在 Java 侧按 (时间, qc_flag) 聚合，
 * 并按 qc_flag 优先级挑选最优版本的要素集合。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ObsReader {

    private final InfluxDBClient client;
    private final InfluxProperties properties;

    /**
     * 查询站点在时间范围内的要素序列（按时间升序），qc_flag 按 revised>passed>interpolated>raw 优先级取值
     */
    public List<ObsData> queryRange(String stationCode, Instant start, Instant stop) {
        String flux = """
                from(bucket: "%s")
                  |> range(start: %s, stop: %s)
                  |> filter(fn: (r) => r._measurement == "obs_min" and r.station_code == "%s")
                  |> filter(fn: (r) => r.qc_flag == "revised" or r.qc_flag == "passed" or r.qc_flag == "interpolated" or r.qc_flag == "raw")
                  |> sort(columns: ["_time"])
                """.formatted(properties.getBucket(), start.toString(), stop.toString(), stationCode);
        return executeQuery(flux, stationCode);
    }

    /**
     * 查询站点最新一条观测数据
     */
    public ObsData queryLatest(String stationCode) {
        List<ObsData> recent = queryRange(stationCode, Instant.now().minusSeconds(24 * 3600), Instant.now());
        return recent.isEmpty() ? null : recent.get(recent.size() - 1);
    }

    /**
     * 查询站点某要素在指定时间之后的最后一个值（时间一致性检验用）
     */
    public Double queryLastElementValue(String stationCode, String element, Instant start) {
        String flux = """
                from(bucket: "%s")
                  |> range(start: %s)
                  |> filter(fn: (r) => r._measurement == "obs_min" and r.station_code == "%s" and r._field == "%s"
                             and (r.qc_flag == "passed" or r.qc_flag == "revised" or r.qc_flag == "raw"))
                  |> last(column: "_time")
                """.formatted(properties.getBucket(), start.toString(), stationCode, element);
        try {
            List<FluxTable> tables = client.getQueryApi().query(flux, properties.getOrg());
            return tables.stream().flatMap(t -> t.getRecords().stream())
                    .map(r -> (Double) r.getValue())
                    .filter(v -> v != null)
                    .findFirst().orElse(null);
        } catch (Exception e) {
            log.warn("InfluxDB 查询失败: station={}, element={}, err={}", stationCode, element, e.getMessage());
            return null;
        }
    }

    /**
     * 执行查询并在 Java 侧聚合为 ObsData 列表
     */
    private List<ObsData> executeQuery(String flux, String stationCode) {
        try {
            QueryApi queryApi = client.getQueryApi();
            List<FluxTable> tables = queryApi.query(flux, properties.getOrg());

            // 时间 → (qc_flag → (要素 → 值))
            Map<Instant, Map<String, Map<String, Double>>> byTimeAndFlag = new TreeMap<>();
            for (FluxTable table : tables) {
                for (FluxRecord record : table.getRecords()) {
                    Instant time = record.getTime();
                    if (time == null || !(record.getValue() instanceof Number num)) {
                        continue;
                    }
                    String qcFlag = record.getValueByKey(ObsWriter.TAG_QC) instanceof String s ? s : "raw";
                    String field = record.getField();
                    if (field == null) {
                        continue;
                    }
                    byTimeAndFlag.computeIfAbsent(time, k -> new LinkedHashMap<>())
                            .computeIfAbsent(qcFlag, k -> new LinkedHashMap<>())
                            .put(field, num.doubleValue());
                }
            }

            // 每个时间点取优先级最高的 qc_flag 版本
            List<ObsData> result = new java.util.ArrayList<>();
            byTimeAndFlag.forEach((time, byFlag) -> {
                String bestFlag = byFlag.keySet().stream()
                        .max(java.util.Comparator.comparingInt(this::flagRank))
                        .orElse("raw");
                result.add(ObsData.builder()
                        .stationCode(stationCode)
                        .ts(time)
                        .elements(byFlag.get(bestFlag))
                        .qcFlag(bestFlag)
                        .build());
            });
            return result;
        } catch (Exception e) {
            log.error("InfluxDB 查询异常: station={}, err={}", stationCode, e.getMessage(), e);
            return List.of();
        }
    }

    /** 质控标记优先级：revised > passed > interpolated > raw（suspect 不参与对客展示） */
    private int flagRank(String flag) {
        return switch (flag == null ? "" : flag) {
            case "revised" -> 4;
            case "passed" -> 3;
            case "interpolated" -> 2;
            case "raw" -> 1;
            default -> 0;
        };
    }
}