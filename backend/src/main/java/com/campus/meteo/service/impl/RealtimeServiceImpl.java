package com.campus.meteo.service.impl;

import com.campus.meteo.common.exception.BizException;
import com.campus.meteo.common.result.ErrorCode;
import com.campus.meteo.dto.RealtimeCompareResp;
import com.campus.meteo.entity.Station;
import com.campus.meteo.influx.ObsData;
import com.campus.meteo.influx.ObsReader;
import com.campus.meteo.mapper.StationMapper;
import com.campus.meteo.service.RealtimeService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * 实时监测实现：多站点同要素对比
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class RealtimeServiceImpl implements RealtimeService {

    private static final ZoneId ZONE = ZoneId.of("Asia/Shanghai");
    private static final DateTimeFormatter TIME_FMT =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss").withZone(ZONE);

    /**
     * 单次对比的站点数上限。
     * 每个站点都要独立查一次时序库，不设上限时一个请求会被放大成 N 倍查询，
     * 且返回点数按站点数线性增长，拖慢前端绘图。
     */
    private static final int MAX_STATIONS = 6;

    /** 对比时间跨度上限（小时），与 /realtime/curve 的 hours 上限保持一致 */
    private static final int MAX_SPAN_HOURS = 168;

    private static final String DEFAULT_GRANULARITY = "auto";

    private final StationMapper stationMapper;
    private final ObsReader obsReader;

    @Override
    public RealtimeCompareResp compare(String stationIds, String element, String startTime,
                                       String endTime, String granularity) {
        List<Long> ids = parseStationIds(stationIds);
        if (!StringUtils.hasText(element)) {
            throw new BizException(ErrorCode.PARAM_ERROR, "对比要素不能为空");
        }
        String targetElement = element.trim();

        Instant start = parseTime(startTime, Instant.now().minusSeconds(24 * 3600));
        Instant stop = parseTime(endTime, Instant.now());
        if (!stop.isAfter(start)) {
            throw new BizException(ErrorCode.PARAM_ERROR, "结束时间必须晚于开始时间");
        }
        Instant spanLimit = start.plus(Duration.ofHours(MAX_SPAN_HOURS));
        if (stop.isAfter(spanLimit)) {
            log.warn("多站对比时间跨度超限，已截断至 {} 小时: start={}, stop={}", MAX_SPAN_HOURS, start, stop);
            stop = spanLimit;
        }

        List<Station> stations = resolveStations(ids);

        // 站点编码 → (时刻 → 要素值)；同时收集全站时刻并集作为统一时间轴
        Map<String, Map<Instant, Double>> byStation = new LinkedHashMap<>();
        Set<Instant> axis = new TreeSet<>();
        for (Station station : stations) {
            Map<Instant, Double> points = new TreeMap<>();
            for (ObsData obs : obsReader.queryRangeAggregated(station.getStationCode(), start, stop, granularity)) {
                Double value = obs.getElements().get(targetElement);
                if (value != null) {
                    points.put(obs.getTs(), value);
                    axis.add(obs.getTs());
                }
            }
            byStation.put(station.getStationCode(), points);
        }

        RealtimeCompareResp resp = new RealtimeCompareResp();
        resp.setElement(targetElement);
        resp.setGranularity(StringUtils.hasText(granularity) ? granularity.trim() : DEFAULT_GRANULARITY);
        resp.setStartTime(TIME_FMT.format(start));
        resp.setEndTime(TIME_FMT.format(stop));
        resp.setTimes(axis.stream().map(TIME_FMT::format).toList());
        stations.forEach(station -> resp.getSeries().add(buildSeries(station, byStation.get(station.getStationCode()), axis)));
        return resp;
    }

    /** 按统一时间轴对齐单站点序列，缺测补 null，并汇总该站统计量 */
    private RealtimeCompareResp.StationSeries buildSeries(Station station, Map<Instant, Double> points,
                                                          Set<Instant> axis) {
        RealtimeCompareResp.StationSeries series = new RealtimeCompareResp.StationSeries();
        series.setStationId(station.getId());
        series.setStationCode(station.getStationCode());
        series.setStationName(station.getName());

        List<Double> values = new ArrayList<>(axis.size());
        List<Double> present = new ArrayList<>();
        for (Instant time : axis) {
            Double value = points.get(time);
            values.add(value == null ? null : round(value));
            if (value != null) {
                present.add(value);
            }
        }
        series.setValues(values);
        series.setCount(present.size());
        if (!present.isEmpty()) {
            // axis 升序，present 亦为升序，末位即时间窗内最新值
            series.setLatest(round(present.get(present.size() - 1)));
            series.setMin(round(present.stream().mapToDouble(Double::doubleValue).min().orElse(0)));
            series.setMax(round(present.stream().mapToDouble(Double::doubleValue).max().orElse(0)));
            series.setAvg(round(present.stream().mapToDouble(Double::doubleValue).average().orElse(0)));
        }
        return series;
    }

    /**
     * 解析站点ID：去重并保持请求顺序；站点档案不存在的直接跳过
     * （站点列表页可能残留已被删除的站点，此时整单失败不合理）。
     */
    private List<Station> resolveStations(List<Long> ids) {
        Map<Long, Station> stationMap = stationMapper.selectBatchIds(ids).stream()
                .collect(Collectors.toMap(Station::getId, Function.identity()));
        List<Station> stations = ids.stream().map(stationMap::get).filter(Objects::nonNull).toList();
        if (stations.isEmpty()) {
            throw new BizException(ErrorCode.NOT_FOUND, "站点不存在或已删除");
        }
        if (stations.size() < ids.size()) {
            log.warn("多站对比存在无效站点ID，已忽略: 请求 {} 个，有效 {} 个", ids.size(), stations.size());
        }
        return stations;
    }

    private List<Long> parseStationIds(String stationIds) {
        if (!StringUtils.hasText(stationIds)) {
            throw new BizException(ErrorCode.PARAM_ERROR, "站点ID不能为空");
        }
        LinkedHashSet<Long> ids = new LinkedHashSet<>();
        for (String part : stationIds.split(",")) {
            String text = part.trim();
            if (text.isEmpty()) {
                continue;
            }
            try {
                ids.add(Long.parseLong(text));
            } catch (NumberFormatException e) {
                throw new BizException(ErrorCode.PARAM_ERROR, "站点ID格式非法: " + text);
            }
        }
        if (ids.isEmpty()) {
            throw new BizException(ErrorCode.PARAM_ERROR, "站点ID不能为空");
        }
        if (ids.size() > MAX_STATIONS) {
            throw new BizException(ErrorCode.PARAM_ERROR, "单次对比站点数不能超过 " + MAX_STATIONS + " 个");
        }
        return new ArrayList<>(ids);
    }

    /** 支持 yyyy-MM-dd 与 yyyy-MM-dd HH:mm:ss 两种格式 */
    private Instant parseTime(String text, Instant defaultValue) {
        if (!StringUtils.hasText(text)) {
            return defaultValue;
        }
        String value = text.trim();
        try {
            if (value.length() == 10) {
                return LocalDate.parse(value).atStartOfDay(ZONE).toInstant();
            }
            return LocalDateTime.parse(value, TIME_FMT).atZone(ZONE).toInstant();
        } catch (Exception e) {
            throw new BizException(ErrorCode.PARAM_ERROR, "时间格式非法: " + text + "，应为 yyyy-MM-dd HH:mm:ss");
        }
    }

    /** 统一保留两位小数，避免浮点尾差影响展示 */
    private Double round(double value) {
        return Math.round(value * 100.0) / 100.0;
    }
}
