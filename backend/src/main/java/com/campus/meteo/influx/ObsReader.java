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
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
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

    /** 质控标记过滤：纳入全部标记，可疑标记(suspect)在 Java 侧拒绝，避免底层 raw 尖峰泄漏 */
    private static final String QC_FLAG_FILTER =
            "|> filter(fn: (r) => r.qc_flag == \"revised\" or r.qc_flag == \"passed\""
                    + " or r.qc_flag == \"interpolated\" or r.qc_flag == \"raw\" or r.qc_flag == \"suspect\")";

    /**
     * 视为「质控未通过」的标记等级上限：raw(1) 与 suspect(0)。
     * 采集 Agent 先写 raw、质控 Agent 再写判定结果，若查询只过滤 suspect，
     * 被拒绝的异常尖峰会以 raw 名义泄漏到展示层。
     */
    private static final int RANK_UNVERIFIED = 1;

    /**
     * 仅观测值（质控通过或人工修正），不含插补合成值。
     * 用途：插补的锚点与跨站配对，以及告警判定、预报准确率检验——这些链路以实况为真值，
     * 掺入合成值会造成凭插补数据触发告警、准确率评分虚高。
     */
    private static final String QC_FLAG_OBSERVED =
            "|> filter(fn: (r) => r.qc_flag == \"passed\" or r.qc_flag == \"revised\")";

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
        return executeQuery(rawFlux(stationCode, start, stop, QC_FLAG_FILTER), stationCode);
    }

    /**
     * 查询站点在时间范围内的**仅观测值**序列（passed/revised），不含插补合成值
     *
     * 供两类用途：插补的锚点与跨站配对；告警判定与预报准确率检验。
     */
    public List<ObsData> queryObservedRange(String stationCode, Instant start, Instant stop) {
        if (!isValidStationCode(stationCode)) {
            log.warn("非法站点编码，拒绝查询: {}", stationCode);
            return List.of();
        }
        return executeQuery(rawFlux(stationCode, start, stop, QC_FLAG_OBSERVED), stationCode);
    }

    /**
     * 查询时间范围内**存在任意质控标记数据**的时刻集合（含 raw 与 suspect）
     *
     * 缺测判定用：只要某时刻存在任意标记的点，就说明设备有上报，
     * 不属于物理缺测。若此处混入「仅质控通过」的口径，被拒绝的时次会被误判为缺测，
     * 插补值会把真实存在的质控问题掩盖掉。
     */
    public Set<Instant> queryOccupiedTimestamps(String stationCode, Instant start, Instant stop) {
        if (!isValidStationCode(stationCode)) {
            log.warn("非法站点编码，拒绝查询: {}", stationCode);
            return Set.of();
        }
        String flux = """
                from(bucket: "%s")
                  |> range(start: %s, stop: %s)
                  |> filter(fn: (r) => r._measurement == "obs_min" and r.station_code == "%s")
                  |> keep(columns: ["_time"])
                """.formatted(properties.getBucket(), start.toString(), stop.toString(), stationCode);
        Set<Instant> times = new TreeSet<>();
        try {
            for (FluxTable table : client.getQueryApi().query(flux, properties.getOrg())) {
                for (FluxRecord record : table.getRecords()) {
                    if (record.getTime() != null) {
                        times.add(record.getTime());
                    }
                }
            }
        } catch (Exception e) {
            log.error("InfluxDB 查询异常(占用时刻): station={}, err={}", stationCode, e.getMessage());
            return Set.of();
        }
        return times;
    }

    /** 原始粒度查询语句：仅按 station 与质控标记过滤，不做聚合 */
    private String rawFlux(String stationCode, Instant start, Instant stop, String flagFilter) {
        return """
                from(bucket: "%s")
                  |> range(start: %s, stop: %s)
                  |> filter(fn: (r) => r._measurement == "obs_min" and r.station_code == "%s")
                  %s
                  |> sort(columns: ["_time"])
                """.formatted(properties.getBucket(), start.toString(), stop.toString(),
                stationCode, flagFilter);
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
        // 向下对齐结束边界，挤掉 aggregateWindow 会补出的末尾残窗
        Instant alignedStop = alignStopToWindow(start, stop, window);
        String flux = """
                from(bucket: "%s")
                  |> range(start: %s, stop: %s)
                  |> filter(fn: (r) => r._measurement == "obs_min" and r.station_code == "%s")
                  %s
                  |> aggregateWindow(every: %s, fn: mean, createEmpty: false)
                  |> sort(columns: ["_time"])
                """.formatted(properties.getBucket(), start.toString(), alignedStop.toString(),
                stationCode, QC_FLAG_FILTER, window);
        return executeQuery(flux, stationCode);
    }

    /**
     * 把聚合查询的结束边界向下对齐到窗口边界。
     *
     * Flux 的 aggregateWindow 会为范围末尾补一个**未闭合的残窗**：其 _time 直接等于查询的 stop
     * （实测相隔数秒的两次调用，末点时间戳随 now() 移动），值为该残窗内的均值——等于拿几分钟的
     * 数据冒充一整个窗口。对累计类要素尤其失真：大屏「累计降水」按「小时均值 × 1h」求和，
     * 残窗会让该小时的雨量被系统性低估。
     *
     * 向下对齐后末尾只剩完整窗口。若对齐结果不晚于 start（请求跨度不足一个窗口），
     * 保持原边界——宁可返回一个残窗，也不构造出空区间让界面突然无数据。
     *
     * 对齐基准为 Unix 纪元，与 aggregateWindow 的默认 offset: 0s 一致。
     */
    private Instant alignStopToWindow(Instant start, Instant stop, String window) {
        long windowMillis = windowToMillis(window);
        long epochMillis = stop.toEpochMilli();
        Instant aligned = Instant.ofEpochMilli(epochMillis - Math.floorMod(epochMillis, windowMillis));
        return aligned.isAfter(start) ? aligned : stop;
    }

    /** 聚合窗口字符串（如 30s/15m/1h/1d）换算为毫秒；调用前已由 WINDOW_PATTERN 校验格式 */
    private long windowToMillis(String window) {
        long amount = Long.parseLong(window.substring(0, window.length() - 1));
        return switch (window.charAt(window.length() - 1)) {
            case 's' -> amount * 1000L;
            case 'm' -> amount * 60_000L;
            case 'h' -> amount * 3_600_000L;
            default -> amount * 86_400_000L;
        };
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
        List<ObsData> parts = executeQuery(flux, stationCode);
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
     * 一次查询站点各要素在时间窗内**最后被质控接受**的值（时间一致性检验用）
     *
     * 只取 passed / revised：若把 raw 也算作基准，紧邻的上一条若是刚被极值检查拒绝的尖峰
     * （如 −55℃），本条正常值与之比较就会因变化量巨大而被误判为可疑——每个尖峰都会
     * 连带污染其后一条正常数据。基准语义应是「最近一个被认可的值」。
     *
     * 窗口必须排除本次观测自身：采集 Agent 先写 raw 再投递 MQ，质控 Agent 处理时
     * 该条已入库，若无 stop 边界，last() 取到的就是当前这条本身，比较值恒等于当前值，
     * 检查将永远不触发（此前即为此状态）。stop 取观测时刻的整秒——InfluxDB 写入精度为秒、
     * range 的 stop 为开区间，用整秒才能把该时刻排除掉。
     *
     * 回溯窗口（调用方传入）同时兜住了「长期无被接受值」的情形：窗口内取不到即放弃本轮判定，
     * 不会拿很久以前的陈旧值作比较。
     *
     * 注意 Flux last() 对每个序列表各返回一行（各 qc_flag 各一行），必须取 _time 最大的一条。
     */
    public Map<String, Double> queryLastAcceptedValues(String stationCode, Instant start, Instant stop) {
        if (!isValidStationCode(stationCode)) {
            log.warn("非法站点编码，拒绝查询: {}", stationCode);
            return Map.of();
        }
        String flux = """
                from(bucket: "%s")
                  |> range(start: %s, stop: %s)
                  |> filter(fn: (r) => r._measurement == "obs_min" and r.station_code == "%s")
                  %s
                  |> last(column: "_time")
                """.formatted(properties.getBucket(), start.toString(), stop.toString(),
                stationCode, QC_FLAG_OBSERVED);
        try {
            // 要素 → 目前见到的最新时刻
            Map<String, Instant> latestAt = new HashMap<>();
            Map<String, Double> latestValue = new LinkedHashMap<>();
            for (FluxTable table : client.getQueryApi().query(flux, properties.getOrg())) {
                for (FluxRecord record : table.getRecords()) {
                    if (record.getTime() == null || !(record.getValue() instanceof Number number)
                            || record.getField() == null) {
                        continue;
                    }
                    String field = record.getField();
                    Instant seen = latestAt.get(field);
                    if (seen == null || record.getTime().isAfter(seen)) {
                        latestAt.put(field, record.getTime());
                        latestValue.put(field, number.doubleValue());
                    }
                }
            }
            return latestValue;
        } catch (Exception e) {
            log.warn("InfluxDB 查询失败(最新要素值): station={}, err={}", stationCode, e.getMessage());
            return Map.of();
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

            List<ObsData> result = new ArrayList<>(byTimeAndFlag.size());
            byTimeAndFlag.forEach((time, byFlag) -> {
                Map<String, Double> elements = mergeVerifiedElements(byFlag);
                // 全部要素均未通过质控的时刻不对外展示
                if (elements.isEmpty()) {
                    return;
                }
                String bestFlag = byFlag.keySet().stream()
                        .max(Comparator.comparingInt(this::flagRank))
                        .orElse("raw");
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

    /**
     * 合并同一时刻各质控标记的要素值，并剔除未通过质控的部分。
     *
     * 同一要素可能以多种标记共存（聚合查询下同一小时桶内各标记分别聚合），
     * 按标记优先级取值，高优先级胜出；若某要素只有 raw / suspect 版本，
     * 说明质控未通过或判定为可疑，按缺测处理，不能以 raw 名义展示。
     */
    private Map<String, Double> mergeVerifiedElements(Map<String, Map<String, Double>> byFlag) {
        Map<String, Double> elements = new LinkedHashMap<>();
        Map<String, Integer> elementRank = new HashMap<>();
        byFlag.forEach((flag, values) -> {
            int rank = flagRank(flag);
            values.forEach((element, value) -> {
                if (rank >= elementRank.getOrDefault(element, -1)) {
                    elementRank.put(element, rank);
                    elements.put(element, value);
                }
            });
        });
        elements.keySet().removeIf(element -> elementRank.getOrDefault(element, -1) <= RANK_UNVERIFIED);
        return elements;
    }

    /** 质控标记优先级：revised > passed > interpolated > raw > suspect（低于等于 raw 视为未通过质控） */
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