package com.campus.meteo.service.impl;

import com.campus.meteo.common.exception.BizException;
import com.campus.meteo.common.result.ErrorCode;
import com.campus.meteo.dto.ElementStat;
import com.campus.meteo.dto.HistoryResp;
import com.campus.meteo.dto.SeriesPoint;
import com.campus.meteo.dto.StatsResp;
import com.campus.meteo.entity.Station;
import com.campus.meteo.influx.ObsData;
import com.campus.meteo.influx.ObsReader;
import com.campus.meteo.mapper.StationMapper;
import com.campus.meteo.service.DataQueryService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.YearMonth;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/**
 * 历史数据查询与统计实现
 *
 * 说明：聚合与统计在 Java 侧完成，InfluxDB 只负责按站点+时间范围取原始序列
 * （遵循 AGENTS.md 3.5「查询必须限定 station_code 与时间范围」）。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class DataQueryServiceImpl implements DataQueryService {

    private static final DateTimeFormatter TIME_FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
    private static final ZoneId ZONE = ZoneId.systemDefault();

    /**
     * 累积型要素：聚合与统计取累计值。
     * rain 的口径是雨强（mm/h），聚合取均值而非求和，否则小时值会被累加放大。
     */
    private static final Set<String> CUMULATIVE_ELEMENTS = Set.of("evap");

    private static final Set<String> GRANULARITIES = Set.of("min", "hour", "day");

    /** 气候对比检索的历史年数（限定范围，避免无界查询） */
    private static final int CLIMATE_YEARS = 5;

    private final StationMapper stationMapper;
    private final ObsReader obsReader;

    @Override
    public HistoryResp history(Long stationId, String elements, String startTime, String endTime,
                               String granularity, String qcFlag) {
        Station station = resolveStation(stationId);
        String gran = StringUtils.hasText(granularity) ? granularity : "min";
        if (!GRANULARITIES.contains(gran)) {
            throw new BizException(ErrorCode.PARAM_ERROR, "不支持的聚合粒度: " + gran);
        }
        Instant start = parseTime(startTime, Instant.now().minusSeconds(24 * 3600));
        Instant stop = parseTime(endTime, Instant.now());
        if (stop.isBefore(start)) {
            throw new BizException(ErrorCode.PARAM_ERROR, "结束时间不能早于开始时间");
        }

        List<String> wanted = parseElements(elements);
        List<ObsData> obsList = obsReader.queryRange(station.getStationCode(), start, stop);

        // 时间桶 → 要素 → 值列表
        Map<Instant, Map<String, List<Double>>> buckets = new TreeMap<>();
        // 时间桶 → 该桶最优质控标记
        Map<Instant, String> bestFlag = new HashMap<>();
        for (ObsData obs : obsList) {
            if (StringUtils.hasText(qcFlag) && !qcFlag.equals(obs.getQcFlag())) {
                continue;
            }
            Instant key = bucket(obs.getTs(), gran);
            Map<String, List<Double>> byElement = buckets.computeIfAbsent(key, k -> new LinkedHashMap<>());
            obs.getElements().forEach((element, value) -> {
                if (value != null && (wanted.isEmpty() || wanted.contains(element))) {
                    byElement.computeIfAbsent(element, k -> new ArrayList<>()).add(value);
                }
            });
            bestFlag.merge(key, obs.getQcFlag(), (a, b) -> flagRank(a) >= flagRank(b) ? a : b);
        }

        HistoryResp resp = new HistoryResp();
        resp.setStationId(station.getId());
        resp.setStationCode(station.getStationCode());
        resp.setGranularity(gran);
        buckets.forEach((key, byElement) -> {
            String time = TIME_FMT.format(LocalDateTime.ofInstant(key, ZONE));
            byElement.forEach((element, values) -> {
                if (values.isEmpty()) {
                    return;
                }
                resp.getSeries().computeIfAbsent(element, k -> new ArrayList<>())
                        .add(new SeriesPoint(time, round(aggregate(element, values)), bestFlag.get(key)));
            });
        });
        return resp;
    }

    @Override
    public StatsResp daily(Long stationId, String date) {
        Station station = resolveStation(stationId);
        LocalDate day = StringUtils.hasText(date) ? parseDate(date, "日期格式非法，应为 yyyy-MM-dd") : LocalDate.now();
        Instant start = day.atStartOfDay(ZONE).toInstant();
        Instant stop = day.plusDays(1).atStartOfDay(ZONE).toInstant();
        return summarize(station, day.toString(), start, stop, null);
    }

    @Override
    public StatsResp monthly(Long stationId, String month) {
        Station station = resolveStation(stationId);
        YearMonth ym = StringUtils.hasText(month) ? parseMonth(month) : YearMonth.now();
        Instant start = ym.atDay(1).atStartOfDay(ZONE).toInstant();
        Instant stop = ym.plusMonths(1).atDay(1).atStartOfDay(ZONE).toInstant();
        return summarize(station, ym.toString(), start, stop, null);
    }

    @Override
    public StatsResp yearly(Long stationId, String year) {
        Station station = resolveStation(stationId);
        int y = StringUtils.hasText(year) ? parseYear(year) : LocalDate.now().getYear();
        Instant start = LocalDate.of(y, 1, 1).atStartOfDay(ZONE).toInstant();
        Instant stop = LocalDate.of(y + 1, 1, 1).atStartOfDay(ZONE).toInstant();
        return summarize(station, String.valueOf(y), start, stop, null);
    }

    @Override
    public StatsResp extreme(Long stationId, String element, String startTime, String endTime) {
        Station station = resolveStation(stationId);
        if (!StringUtils.hasText(element)) {
            throw new BizException(ErrorCode.PARAM_ERROR, "要素名不能为空");
        }
        Instant start = parseTime(startTime, Instant.now().minusSeconds(24 * 3600));
        Instant stop = parseTime(endTime, Instant.now());
        if (stop.isBefore(start)) {
            throw new BizException(ErrorCode.PARAM_ERROR, "结束时间不能早于开始时间");
        }
        StatsResp resp = summarize(station, null, start, stop, List.of(element));
        ElementStat stat = resp.getStats().get(element);
        if (stat == null) {
            throw new BizException(ErrorCode.NOT_FOUND, "该时段内无要素数据: " + element);
        }
        return resp;
    }

    @Override
    public StatsResp climate(Long stationId, Integer month, String element) {
        Station station = resolveStation(stationId);
        if (month == null || month < 1 || month > 12) {
            throw new BizException(ErrorCode.PARAM_ERROR, "月份须在 1~12 之间");
        }
        if (!StringUtils.hasText(element)) {
            throw new BizException(ErrorCode.PARAM_ERROR, "要素名不能为空");
        }

        int currentYear = LocalDate.now().getYear();
        YearMonth latest = YearMonth.of(currentYear, month);
        YearMonth earliest = latest.minusYears(CLIMATE_YEARS - 1L);
        Instant start = earliest.atDay(1).atStartOfDay(ZONE).toInstant();
        Instant stop = latest.atEndOfMonth().plusDays(1).atStartOfDay(ZONE).toInstant();

        List<ObsData> obsList = obsReader.queryRange(station.getStationCode(), start, stop);

        // 年份 → 同期要素值（按月份过滤）
        Map<Integer, List<Double>> byYear = new TreeMap<>();
        for (ObsData obs : obsList) {
            LocalDateTime time = LocalDateTime.ofInstant(obs.getTs(), ZONE);
            if (time.getMonthValue() != month) {
                continue;
            }
            Double value = obs.getElements().get(element);
            if (value != null) {
                byYear.computeIfAbsent(time.getYear(), k -> new ArrayList<>()).add(value);
            }
        }

        StatsResp resp = new StatsResp();
        resp.setStationId(station.getId());
        resp.setStationCode(station.getStationCode());
        resp.setPeriod(month + "月同期");
        resp.setStartTime(TIME_FMT.format(LocalDateTime.ofInstant(start, ZONE)));
        resp.setEndTime(TIME_FMT.format(LocalDateTime.ofInstant(stop, ZONE)));
        List<Map<String, Object>> series = new ArrayList<>();
        byYear.forEach((year, values) -> {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("year", year);
            item.put("avg", round(values.stream().mapToDouble(Double::doubleValue).average().orElse(0)));
            item.put("count", values.size());
            series.add(item);
        });
        resp.setClimateSeries(series);
        return resp;
    }

    /** 按时间范围汇总要素统计 */
    private StatsResp summarize(Station station, String period, Instant start, Instant stop, List<String> elements) {
        List<ObsData> obsList = obsReader.queryRange(station.getStationCode(), start, stop);
        StatsResp resp = new StatsResp();
        resp.setStationId(station.getId());
        resp.setStationCode(station.getStationCode());
        resp.setPeriod(period);
        resp.setStartTime(TIME_FMT.format(LocalDateTime.ofInstant(start, ZONE)));
        resp.setEndTime(TIME_FMT.format(LocalDateTime.ofInstant(stop, ZONE)));
        resp.setStats(summarizeElements(obsList, elements == null ? List.of() : elements));
        return resp;
    }

    private Map<String, ElementStat> summarizeElements(List<ObsData> obsList, List<String> wanted) {
        // 要素 → (时间, 值) 序列
        Map<String, List<Map.Entry<Instant, Double>>> collected = new LinkedHashMap<>();
        for (ObsData obs : obsList) {
            obs.getElements().forEach((element, value) -> {
                if (value != null && (wanted.isEmpty() || wanted.contains(element))) {
                    collected.computeIfAbsent(element, k -> new ArrayList<>()).add(Map.entry(obs.getTs(), value));
                }
            });
        }

        Map<String, ElementStat> result = new LinkedHashMap<>();
        collected.forEach((element, pairs) -> {
            ElementStat stat = new ElementStat();
            stat.setCount(pairs.size());
            stat.setAvg(round(pairs.stream().mapToDouble(Map.Entry::getValue).average().orElse(0)));
            stat.setSum(round(pairs.stream().mapToDouble(Map.Entry::getValue).sum()));
            pairs.stream().max(Map.Entry.comparingByValue()).ifPresent(entry -> {
                stat.setMax(round(entry.getValue()));
                stat.setMaxTime(TIME_FMT.format(LocalDateTime.ofInstant(entry.getKey(), ZONE)));
            });
            pairs.stream().min(Map.Entry.comparingByValue()).ifPresent(entry -> {
                stat.setMin(round(entry.getValue()));
                stat.setMinTime(TIME_FMT.format(LocalDateTime.ofInstant(entry.getKey(), ZONE)));
            });
            result.put(element, stat);
        });
        return result;
    }

    private Station resolveStation(Long stationId) {
        if (stationId == null) {
            throw new BizException(ErrorCode.PARAM_ERROR, "站点ID不能为空");
        }
        Station station = stationMapper.selectById(stationId);
        if (station == null) {
            throw new BizException(ErrorCode.NOT_FOUND, "站点不存在");
        }
        return station;
    }

    /** 累积量要素取累计，其余取平均 */
    private double aggregate(String element, List<Double> values) {
        if (CUMULATIVE_ELEMENTS.contains(element)) {
            return values.stream().mapToDouble(Double::doubleValue).sum();
        }
        return values.stream().mapToDouble(Double::doubleValue).average().orElse(0);
    }

    private Instant bucket(Instant ts, String granularity) {
        LocalDateTime time = LocalDateTime.ofInstant(ts, ZONE);
        return switch (granularity) {
            case "hour" -> time.truncatedTo(ChronoUnit.HOURS).atZone(ZONE).toInstant();
            case "day" -> time.toLocalDate().atStartOfDay(ZONE).toInstant();
            default -> ts;
        };
    }

    /** 支持 yyyy-MM-dd 与 yyyy-MM-dd HH:mm:ss 两种格式 */
    private Instant parseTime(String text, Instant defaultValue) {
        if (!StringUtils.hasText(text)) {
            return defaultValue;
        }
        try {
            if (text.length() == 10) {
                return LocalDate.parse(text).atStartOfDay(ZONE).toInstant();
            }
            return LocalDateTime.parse(text, TIME_FMT).atZone(ZONE).toInstant();
        } catch (Exception e) {
            throw new BizException(ErrorCode.PARAM_ERROR, "时间格式非法: " + text + "，应为 yyyy-MM-dd HH:mm:ss");
        }
    }

    private LocalDate parseDate(String text, String errorMessage) {
        try {
            return LocalDate.parse(text);
        } catch (Exception e) {
            throw new BizException(ErrorCode.PARAM_ERROR, errorMessage);
        }
    }

    private YearMonth parseMonth(String text) {
        try {
            return YearMonth.parse(text);
        } catch (Exception e) {
            throw new BizException(ErrorCode.PARAM_ERROR, "月份格式非法，应为 yyyy-MM");
        }
    }

    private int parseYear(String text) {
        try {
            return Integer.parseInt(text);
        } catch (NumberFormatException e) {
            throw new BizException(ErrorCode.PARAM_ERROR, "年份格式非法，应为 yyyy");
        }
    }

    private List<String> parseElements(String elements) {
        if (!StringUtils.hasText(elements)) {
            return List.of();
        }
        return Arrays.stream(elements.split(",")).map(String::trim).filter(StringUtils::hasText).toList();
    }

    /** 质控标记优先级：revised > passed > interpolated > raw */
    private int flagRank(String flag) {
        return switch (flag == null ? "" : flag) {
            case "revised" -> 4;
            case "passed" -> 3;
            case "interpolated" -> 2;
            case "raw" -> 1;
            default -> 0;
        };
    }

    /** 统一保留两位小数，避免浮点尾差影响展示 */
    private Double round(double value) {
        return Math.round(value * 100.0) / 100.0;
    }
}