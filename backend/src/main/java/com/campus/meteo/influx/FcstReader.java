package com.campus.meteo.influx;

import com.influxdb.client.InfluxDBClient;
import com.influxdb.client.QueryApi;
import com.influxdb.query.FluxRecord;
import com.influxdb.query.FluxTable;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 预报产品查询：同一目标时刻存在多次发布时，取最新一次发布（issue 最大）的值
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class FcstReader {

    private final InfluxDBClient client;
    private final InfluxProperties properties;

    /**
     * 查询站点指定模型在时间范围内的预报序列（目标时刻升序）
     */
    public Map<Instant, Map<String, Double>> query(String stationCode, String model, Instant start, Instant stop) {
        String flux = """
                from(bucket: "%s")
                  |> range(start: %s, stop: %s)
                  |> filter(fn: (r) => r._measurement == "fcst" and r.station_code == "%s" and r.model == "%s")
                """.formatted(properties.getBucket(), start.toString(), stop.toString(), stationCode, model);
        try {
            QueryApi queryApi = client.getQueryApi();
            java.util.List<FluxTable> tables = queryApi.query(flux, properties.getOrg());
            // 目标时刻 → (issue → 要素值)
            Map<Instant, Map<Long, Map<String, Double>>> byTime = new ConcurrentHashMap<>();
            for (FluxTable table : tables) {
                for (FluxRecord record : table.getRecords()) {
                    Instant target = record.getTime();
                    String issueStr = (String) record.getValueByKey(FcstWriter.TAG_ISSUE);
                    if (issueStr == null || !(record.getValue() instanceof Number num)) {
                        continue;
                    }
                    long issue = Long.parseLong(issueStr);
                    byTime.computeIfAbsent(target, k -> new ConcurrentHashMap<>())
                            .computeIfAbsent(issue, k -> new ConcurrentHashMap<>())
                            .put(record.getField(), num.doubleValue());
                }
            }
            // 每个目标时刻取最新发布
            Map<Instant, Map<String, Double>> result = new TreeMap<>();
            byTime.forEach((target, byIssue) ->
                    byIssue.entrySet().stream()
                            .max(Map.Entry.comparingByKey())
                            .ifPresent(e -> result.put(target, e.getValue())));
            return result;
        } catch (Exception e) {
            log.error("预报查询异常: station={}, model={}, err={}", stationCode, model, e.getMessage(), e);
            return Map.of();
        }
    }
}
