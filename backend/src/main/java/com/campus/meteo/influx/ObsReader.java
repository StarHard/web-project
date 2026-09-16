package com.campus.meteo.influx;

import com.campus.meteo.common.constant.QcFlag;
import com.influxdb.client.InfluxDBClient;
import com.influxdb.client.QueryApi;
import com.influxdb.query.FluxRecord;
import com.influxdb.query.FluxTable;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 观测数据查询（规范：必须限定 station_code 与时间范围，禁止无界查询）
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
                  |> group(columns: ["_time", "qc_flag"])
                  |> pivot(rowKey: ["_time"], columnKey: ["_field"], valueColumn: "_value")
                  |> group()
                  |> sort(columns: ["_time"])
                """.formatted(properties.getBucket(), start.toString(), stop.toString(), stationCode);

        return executeQuery(flux, stationCode);
    }

    /**
     * 查询站点最新一条观测数据
     */
    public ObsData queryLatest(String stationCode) {
        String flux = """
                from(bucket: "%s")
                  |> range(start: -24h)
                  |> filter(fn: (r) => r._measurement == "obs_min" and r.station_code == "%s")
                  |> last(column: "_time")
                  |> group()
                  |> pivot(rowKey: ["_time"], columnKey: ["_field"], valueColumn: "_value")
                """.formatted(properties.getBucket(), stationCode);

        List<ObsData> result = executeQuery(flux, stationCode);
        return result.isEmpty() ? null : result.get(result.size() - 1);
    }

    /**
     * 查询站点某要素在时间范围内的最后一个值（时间一致性检验用）
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
            QueryApi queryApi = client.getQueryApi();
            List<FluxTable> tables = queryApi.query(flux, properties.getOrg());
            return tables.stream().flatMap(t -> t.getRecords().stream())
                    .map(r -> (Double) r.getValue())
                    .findFirst().orElse(null);
        } catch (Exception e) {
            log.warn("InfluxDB 查询失败: station={}, element={}, err={}", stationCode, element, e.getMessage());
            return null;
        }
    }

    private List<ObsData> executeQuery(String flux, String stationCode) {
        try {
            QueryApi queryApi = client.getQueryApi();
            List<FluxTable> tables = queryApi.query(flux, properties.getOrg());
            Map<Instant, Map<String, Double>> byTime = new LinkedHashMap<>();
            Map<Instant, String> qcByTime = new LinkedHashMap<>();
            for (FluxTable table : tables) {
                for (FluxRecord record : table.getRecords()) {
                    Instant time = record.getTime();
                    Map<String, Double> elements = byTime.computeIfAbsent(time, k -> new LinkedHashMap<>());
                    if (record.getValue() instanceof Number num) {
                        elements.put(record.getField(), num.doubleValue());
                    }
                    Object qc = record.getValueByKey(ObsWriter.TAG_QC);
                    if (qc instanceof String qcFlag) {
                        // 优先级：revised > passed > interpolated > raw
                        mergeQcFlag(qcByTime, time, qcFlag);
                    }
                }
            }
            List<ObsData> result = new ArrayList<>();
            byTime.forEach((time, elements) -> result.add(ObsData.builder()
                    .stationCode(stationCode)
                    .ts(time)
                    .elements(elements)
                    .qcFlag(qcByTime.getOrDefault(time, QcFlag.RAW.getValue()))
                    .build()));
            return result;
        } catch (Exception e) {
            log.error("InfluxDB 查询异常: station={}, err={}", stationCode, e.getMessage(), e);
            return List.of();
        }
    }

    private void mergeQcFlag(Map<Instant, String> qcByTime, Instant time, String qcFlag) {
        qcByTime.put(time, betterFlag(qcByTime.get(time), qcFlag));
    }

    /** 语义优先级：revised > passed > interpolated > raw（suspect 不参与对客展示） */
    private String betterFlag(String a, String b) {
        int rank = switch (b) {
            case "revised" -> 4;
            case "passed" -> 3;
            case "interpolated" -> 2;
            case "raw" -> 1;
            default -> 0;
        };
        int rankA = a == null ? 0 : switch (a) {
            case "revised" -> 4;
            case "passed" -> 3;
            case "interpolated" -> 2;
            case "raw" -> 1;
            default -> 0;
        };
        return rank > rankA ? b : a;
    }
}
