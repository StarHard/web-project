package com.campus.meteo.influx;

import com.influxdb.client.InfluxDBClient;
import com.influxdb.client.QueryApi;
import com.influxdb.query.FluxRecord;
import com.influxdb.query.FluxTable;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.regex.Pattern;

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

    /** 不聚合，返回原始粒度 */
    private static final String WINDOW_RAW = "raw";
    /** 自动按时间跨度选择聚合窗口 */
    private static final String WINDOW_AUTO = "auto";

    /** 质控标记过滤：suspect 不参与对客展示，由人工审核闭环后再回写 */
    private static final String QC_FLAG_FILTER =
            "|> filter(fn: (r) => r.qc_flag == \"revised\" or r.qc_flag == \"passed\""
                    + " or r.qc_flag == \"interpolated\" or r.qc_flag == \"raw\")";

    /**
     * 合法聚合窗口：数字 + 单位（s/m/h/d）。
     * 该值会被拼接进 Flux 语句，必须严格校验，防止用户输入注入查询。
     */
    private static final Pattern WINDOW_PATTERN = Pattern.compile("^\\d{1,3}[smhd]$");

    /** 站点编码白名单：/realtime/** 为公开接口，站点编码同样会拼接进 Flux 语句 */
    private static final Pattern STATION_CODE_PATTERN = Pattern.compile("^[A-Za-z0-9_.-]{1,32}$");

    private final InfluxDBClient client;
    private final InfluxProperties properties;

    /**
     * 查询站点在时间范围内的原始要素序列（按时间升序），qc_flag 按 revised>passed>interpolated>raw 优先级取值
     *
     * 供预报、检验、告警等确实需要原始序列的链路使用，不做聚合。
     */
    public List<ObsData> queryRange(String stationCode, Instant start, Instant stop) {
        if (!isValidStationCode(stationCode)) {
            log.warn("非法站点编码，拒绝查询: {}", stationCode);
            return List.of();
        }
        String flux = """
                from(bucket: "%s")
                  |> range(start: %s, stop: %s)
                  |> filter(fn: (r) => r._measurement == "obs_min" and r.station_code == "%s")
                  %s
                  |> sort(columns: ["_time"])
                """.formatted(properties.getBucket(), start.toString(), stop.toString(),
                stationCode, QC_FLAG_FILTER);
        return executeQuery(flux, stationCode, false);
    }

    /**
     * 查询站点在时间范围内的要素序列，并在 InfluxDB 侧按窗口聚合（趋势展示用）
     *
     * 对客展示只需要趋势，返回原始分钟级数据会造成数十倍冗余传输：
     * 15 秒一条的原始数据 24 小时达 5760 点/站点，聚合到小时后仅 24 点。
     *
     * @param granularity 聚合窗口（如 5m/15m/1h/1d）；raw 表示不聚合；auto 按时间跨度自动选择
     */
    public List<ObsData> queryRangeAggregated(String stationCode, Instant start, Instant stop, String granularity) {
        String window = resolveWindow(start, stop, granularity);
        if (WINDOW_RAW.equals(window)) {
            return queryRange(stationCode, start, stop);
        }
        if (!isValidStationCode(stationCode)) {
            log.warn("非法站点编码，拒绝查询: {}", stationCode);
            return List.of();
        }
        String flux = """
                from(bucket: "%s")
                  |> range(start: %s, stop: %s)
                  |> filter(fn: (r) => r._measurement == "obs_min" and r.station_code == "%s")
                  %s
                  |> aggregateWindow(every: %s, fn: mean, createEmpty: false)
                  |> sort(columns: ["_time"])
                """.formatted(properties.getBucket(), start.toString(), stop.toString(),
                stationCode, QC_FLAG_FILTER, window);
        return executeQuery(flux, stationCode, true);
    }

    /**
     * 查询站点最新观测数据
     *
     * 用 Flux last() 在每个要素序列上各取最后一点，避免为取一条数据而拉取 24 小时全量原始序列。
     */
    public ObsData queryLatest(String stationCode) {
        if (!isValidStationCode(stationCode)) {
            log.warn("非法站点编码，拒绝查询: {}", stationCode);
            return null;
        }
        String flux = """
                from(bucket: "%s")
                  |> range(start: -24h)
                  |> filter(fn: (r) => r._measurement == "obs_min" and r.station_code == "%s")
                  %s
                  |> last(column: "_time")
                """.formatted(properties.getBucket(), stationCode, QC_FLAG_FILTER);

        // last() 后各要素可能来自不同时刻，按要素取优先级最高的标记值合并
        List<ObsData> parts = executeQuery(flux, stationCode, true);
        if (parts.isEmpty()) {
            return null;
        }
        Map<String, Double> elements = new LinkedHashMap<>();
        Map<String, Integer> elementRank = new HashMap<>();
        Instant ts = null;
        String bestFlag = null;
        for (ObsData part : parts) {
            int rank = flagRank(part.getQcFlag());
            part.getElements().forEach((element, value) -> {
                if (rank >= elementRank.getOrDefault(element, -1)) {
                    elementRank.put(element, rank);
                    elements.put(element, value);
                }
            });
            if (ts == null || part.getTs().isAfter(ts)) {
                ts = part.getTs();
            }
            if (bestFlag == null || rank > flagRank(bestFlag)) {
                bestFlag = part.getQcFlag();
            }
        }
        return ObsData.builder()
                .stationCode(stationCode)
                .ts(ts)
                .elements(elements)
                .qcFlag(bestFlag)
                .build();
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
     * 解析聚合窗口：auto 时按时间跨度选择，使返回点数稳定在百点以内
     */
    private String resolveWindow(Instant start, Instant stop, String granularity) {
        if (granularity == null || granularity.isBlank() || WINDOW_AUTO.equals(granularity.trim())) {
            long hours = Math.max(1, Duration.between(start, stop).toHours());
            if (hours <= 6) {
                return "5m";
            }
            if (hours <= 24) {
                return "15m";
            }
            return hours <= 24 * 7 ? "1h" : "1d";
        }
        String value = granularity.trim();
        if (WINDOW_RAW.equals(value)) {
            return WINDOW_RAW;
        }
        if (!WINDOW_PATTERN.matcher(value).matches()) {
            log.warn("非法聚合粒度，降级为自动选择: {}", granularity);
            return resolveWindow(start, stop, WINDOW_AUTO);
        }
        return value;
    }

    private boolean isValidStationCode(String stationCode) {
        return stationCode != null && STATION_CODE_PATTERN.matcher(stationCode).matches();
    }

    /**
     * 执行查询并在 Java 侧聚合为 ObsData 列表
     *
     * @param mergeAcrossFlags true 时把同一时刻各质控标记的要素合并，避免某要素仅以较低优先级标记
     *                         落桶时被整体丢弃（聚合查询下同一要素可能分散在不同标记中）
     */
    private List<ObsData> executeQuery(String flux, String stationCode, boolean mergeAcrossFlags) {
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
            List<ObsData> result = new ArrayList<>(byTimeAndFlag.size());
            byTimeAndFlag.forEach((time, byFlag) -> {
                String bestFlag = byFlag.keySet().stream()
                        .max(Comparator.comparingInt(this::flagRank))
                        .orElse("raw");
                Map<String, Double> elements;
                if (mergeAcrossFlags) {
                    // 按标记优先级从低到高覆盖：低优先级先写入，高优先级最终胜出，且不丢要素
                    elements = new LinkedHashMap<>();
                    byFlag.entrySet().stream()
                            .sorted(Comparator.comparingInt(entry -> flagRank(entry.getKey())))
                            .forEach(entry -> elements.putAll(entry.getValue()));
                } else {
                    elements = byFlag.get(bestFlag);
                }
                result.add(ObsData.builder()
                        .stationCode(stationCode)
                        .ts(time)
                        .elements(elements)
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