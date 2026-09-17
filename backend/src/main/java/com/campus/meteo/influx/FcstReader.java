package com.campus.meteo.influx;

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
import java.util.TreeMap;

/**
 * 预报产品查询
 *  - query：面向预报展示，同一目标时刻取最新一次发布（issue 最大）
 *  - queryAll：面向预报检验（回算），返回全部发布记录的逐条时效
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class FcstReader {

    private final InfluxDBClient client;
    private final InfluxProperties properties;

    /** 单条预报时效：目标时刻 + 发布时刻 + 要素值 */
    public record FcstPoint(Instant targetTime, long issue, Map<String, Double> elements) {
    }

    /**
     * 查询站点指定模型在时间范围内的预报序列（目标时刻升序），仅取最新一次发布
     */
    public Map<Instant, Map<String, Double>> query(String stationCode, String model, Instant start, Instant stop) {
        List<FcstPoint> points = queryAll(stationCode, model, start, stop);
        long latestIssue = points.stream().mapToLong(FcstPoint::issue).max().orElse(Long.MIN_VALUE);
        Map<Instant, Map<String, Double>> result = new TreeMap<>();
        for (FcstPoint point : points) {
            if (point.issue() == latestIssue) {
                result.put(point.targetTime(), point.elements());
            }
        }
        return result;
    }

    /**
     * 查询全部发布记录（不做发布时刻过滤），供预报检验配对实况使用
     */
    public List<FcstPoint> queryAll(String stationCode, String model, Instant start, Instant stop) {
        String flux = """
                from(bucket: "%s")
                  |> range(start: %s, stop: %s)
                  |> filter(fn: (r) => r._measurement == "fcst" and r.station_code == "%s" and r.model == "%s")
                """.formatted(properties.getBucket(), start.toString(), stop.toString(), stationCode, model);
        try {
            QueryApi queryApi = client.getQueryApi();
            List<FluxTable> tables = queryApi.query(flux, properties.getOrg());
            Map<String, FcstPoint> byKey = new LinkedHashMap<>();
            for (FluxTable table : tables) {
                for (FluxRecord record : table.getRecords()) {
                    String issueStr = (String) record.getValueByKey(FcstWriter.TAG_ISSUE);
                    if (issueStr == null || record.getTime() == null || !(record.getValue() instanceof Number num)) {
                        continue;
                    }
                    long issue = Long.parseLong(issueStr);
                    String key = record.getTime().toEpochMilli() + "#" + issue;
                    FcstPoint point = byKey.get(key);
                    if (point == null) {
                        point = new FcstPoint(record.getTime(), issue, new LinkedHashMap<>());
                        byKey.put(key, point);
                    }
                    point.elements().put(record.getField(), num.doubleValue());
                }
            }
            List<FcstPoint> result = new ArrayList<>(byKey.values());
            result.sort((a, b) -> a.targetTime().compareTo(b.targetTime()));
            return result;
        } catch (Exception e) {
            log.error("预报查询异常: station={}, model={}, err={}", stationCode, model, e.getMessage(), e);
            return List.of();
        }
    }
}