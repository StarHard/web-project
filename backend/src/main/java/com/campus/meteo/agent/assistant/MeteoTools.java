package com.campus.meteo.agent.assistant;

import com.campus.meteo.common.exception.BizException;
import com.campus.meteo.common.result.ErrorCode;
import com.campus.meteo.common.result.PageResult;
import com.campus.meteo.dto.ForecastCompareResp;
import com.campus.meteo.dto.ForecastPoint;
import com.campus.meteo.dto.HistoryResp;
import com.campus.meteo.dto.RealtimeLatestResp;
import com.campus.meteo.dto.SeriesPoint;
import com.campus.meteo.dto.StationMapResp;
import com.campus.meteo.dto.StationResp;
import com.campus.meteo.entity.AlertRecord;
import com.campus.meteo.service.AlertService;
import com.campus.meteo.service.DataQueryService;
import com.campus.meteo.service.ForecastService;
import com.campus.meteo.service.RealtimeService;
import com.campus.meteo.service.StationService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;
import java.util.stream.Collectors;

/**
 * 决策智能体的工具集：把系统既有的实时/历史/预报/告警/站点能力暴露给大模型
 *
 * 设计约束：
 * 1. 只做只读查询，全部委托既有 Service，工具层不写任何数据访问逻辑（分层：Agent → Service）；
 * 2. 返回值一律为紧凑中文文本而非原始 DTO——模型只需要结论与关键数值，回传全量序列既浪费
 *    token 又容易让模型在长序列里挑错数；且气象数值会被直接采信，因此「无数据」必须显式说出来，
 *    不能返回空串让模型自行发挥；
 * 3. 工具内部异常统一转为中文提示返回，避免异常抛给模型后它改用常识值编造。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class MeteoTools {

    private static final ZoneId ZONE = ZoneId.of("Asia/Shanghai");
    private static final DateTimeFormatter TIME_FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    /** 要素中文别名 → 时序库字段名：模型常按中文提问，此处统一归一，避免查不到字段 */
    private static final Map<String, String> ELEMENT_ALIAS = Map.ofEntries(
            Map.entry("气温", "temp"), Map.entry("温度", "temp"),
            Map.entry("湿度", "humi"), Map.entry("相对湿度", "humi"),
            Map.entry("气压", "pres"),
            Map.entry("风速", "wind_speed"),
            Map.entry("风向", "wind_dir"),
            Map.entry("降水", "rain"), Map.entry("降雨", "rain"),
            Map.entry("雨量", "rain"), Map.entry("雨强", "rain"),
            Map.entry("辐射", "rad"), Map.entry("太阳辐射", "rad"),
            Map.entry("能见度", "vis"),
            Map.entry("蒸发", "evap"));

    /** 要素展示名（含单位） */
    private static final Map<String, String> ELEMENT_LABEL = Map.of(
            "temp", "气温(℃)", "humi", "相对湿度(%)", "pres", "气压(hPa)",
            "wind_speed", "风速(m/s)", "wind_dir", "风向(°)", "rain", "雨强(mm/h)",
            "rad", "辐射(W/m²)", "vis", "能见度(km)", "evap", "蒸发(mm)");

    private static final Map<Integer, String> ALERT_LEVEL_LABEL =
            Map.of(1, "蓝色", 2, "黄色", 3, "橙色", 4, "红色");

    private static final Map<Integer, String> ALERT_STATUS_LABEL =
            Map.of(0, "进行中", 1, "已解除", 2, "已升级");

    private static final Map<Integer, String> STATION_TYPE_LABEL =
            Map.of(1, "校园", 2, "农业", 3, "区域");

    private static final Map<Integer, String> STATION_STATUS_LABEL =
            Map.of(0, "停用", 1, "正常", 2, "维护中");

    /**
     * 助手侧历史查询的时间跨度上限。
     * 历史接口按原始粒度取数后在 Java 侧聚合，跨度越大单次查询越重，
     * 决策问答只需要近期趋势，超限直接截断而不报错。
     */
    private static final int MAX_HISTORY_DAYS = 7;

    private static final int MAX_ALERT_HOURS = 168;

    private static final int MAX_ALERT_RECORDS = 20;

    private final RealtimeService realtimeService;
    private final DataQueryService dataQueryService;
    private final AlertService alertService;
    private final ForecastService forecastService;
    private final StationService stationService;
    private final ToolCallRecorder recorder;

    @Tool(description = "查询系统内全部气象站点的编码、名称、在线状态与当前最高告警等级。"
            + "当用户未指定站点、或不确定站点编码时，先调用本工具确认。")
    public String listStations() {
        return guard("listStations", "全部站点", () -> {
            List<StationMapResp> stations = stationService.mapData();
            if (stations.isEmpty()) {
                return "系统内暂无可用站点。";
            }
            return "共 %d 个站点：\n%s".formatted(stations.size(), stations.stream()
                    .map(s -> "- %s（编码 %s，%s，当前告警：%s）".formatted(s.getName(), s.getStationCode(),
                            onlineLabel(s.getOnlineFlag()), alertLevelLabel(s.getAlertLevel())))
                    .collect(Collectors.joining("\n")));
        });
    }

    @Tool(description = "查询指定站点最新一次质控通过的观测数据，含气温、湿度、气压、风速、风向、雨强、辐射、能见度、蒸发。"
            + "回答实时天气类问题前必须调用本工具取真实数值。")
    public String queryRealtime(
            @ToolParam(description = "站点编码，如 CAMPUS01；不确定时可先调用 listStations") String stationCode) {
        return guard("queryRealtime", "站点 " + arg(stationCode), () -> {
            StationMapResp station = requireStation(stationCode);
            RealtimeLatestResp resp = realtimeService.latest(station.getStationCode());
            if (!"cache".equals(resp.getSource()) && !"influx".equals(resp.getSource())) {
                return "站点 %s（%s）当前无可用观测数据：可能设备离线，或该时次数据未通过质控。"
                        .formatted(station.getName(), station.getStationCode());
            }
            return "站点 %s（%s）最新观测时间 %s，质控标记 %s：%s".formatted(
                    station.getName(), station.getStationCode(), toLocalTime(resp.getTs()),
                    resp.getQcFlag(), formatElements(resp.getElements()));
        });
    }

    @Tool(description = "查询指定站点历史观测的统计摘要：样本数、均值、最高/最低值及出现时间、最新值。"
            + "适用于「昨天最高气温多少」「近三天风速如何」这类需要历史数值的问题。")
    public String queryHistory(
            @ToolParam(description = "站点编码") String stationCode,
            @ToolParam(description = "要素名，可用中文（气温/湿度/气压/风速/风向/降水/辐射/能见度/蒸发）"
                    + "或字段名（temp/humi/pres/wind_speed/wind_dir/rain/rad/vis/evap）；留空返回全部要素",
                    required = false) String element,
            @ToolParam(description = "开始时间，格式 yyyy-MM-dd HH:mm:ss 或 yyyy-MM-dd；留空为 24 小时前",
                    required = false) String startTime,
            @ToolParam(description = "结束时间，格式同开始时间；留空为当前时刻", required = false) String endTime,
            @ToolParam(description = "聚合粒度：min 分钟 / hour 小时 / day 天，默认 hour", required = false)
            String granularity) {
        return guard("queryHistory", "站点 %s，要素 %s，%s 至 %s".formatted(
                arg(stationCode), arg(element), arg(startTime), arg(endTime)), () -> {
            StationMapResp station = requireStation(stationCode);
            Instant stop = parseTime(endTime, Instant.now());
            Instant start = parseTime(startTime, stop.minus(Duration.ofDays(1)));
            String note = "";
            if (Duration.between(start, stop).toDays() > MAX_HISTORY_DAYS) {
                start = stop.minus(Duration.ofDays(MAX_HISTORY_DAYS));
                note = "（查询跨度超过 %d 天，已截断为最近 %d 天）".formatted(MAX_HISTORY_DAYS, MAX_HISTORY_DAYS);
            }
            String gran = StringUtils.hasText(granularity) ? granularity.trim() : "hour";
            String target = normalizeElement(element);

            HistoryResp resp = dataQueryService.history(station.getId(), target,
                    format(start), format(stop), gran, null);
            if (resp.getSeries().isEmpty()) {
                return "站点 %s（%s）在 %s 至 %s 内无%s的可用观测数据%s。".formatted(
                        station.getName(), station.getStationCode(), format(start), format(stop),
                        elementLabel(target), note);
            }
            String body = resp.getSeries().entrySet().stream()
                    .map(entry -> summarizeSeries(entry.getKey(), entry.getValue()))
                    .collect(Collectors.joining("\n"));
            return "站点 %s（%s）%s 至 %s 的统计摘要%s（粒度 %s）：\n%s".formatted(
                    station.getName(), station.getStationCode(), format(start), format(stop), note, gran, body);
        });
    }

    @Tool(description = "查询最近的灾害告警记录，含告警等级、站点、触发值、描述与处理状态。"
            + "回答「有没有预警」「哪些站点在告警」类问题前必须调用本工具。")
    public String queryAlerts(
            @ToolParam(description = "回溯小时数，默认 24，最大 168", required = false) Integer hours,
            @ToolParam(description = "站点编码，留空表示查询全部站点", required = false) String stationCode) {
        return guard("queryAlerts", "回溯 %s 小时，站点 %s".formatted(arg(hours), arg(stationCode)), () -> {
            int back = hours == null ? 24 : Math.min(Math.max(hours, 1), MAX_ALERT_HOURS);
            Long stationId = StringUtils.hasText(stationCode)
                    ? requireStation(stationCode).getId() : null;
            Instant stop = Instant.now();
            Instant start = stop.minus(Duration.ofHours(back));

            PageResult<AlertRecord> page = alertService.pageRecords(1, MAX_ALERT_RECORDS, stationId,
                    null, null, null, format(start), format(stop));
            if (page.getList().isEmpty()) {
                return "最近 %d 小时内无告警记录。".formatted(back);
            }
            Map<Long, String> stationNames = stationNames();
            String body = page.getList().stream()
                    .map(record -> "- %s %s告警，站点 %s，触发值 %s，%s，状态 %s".formatted(
                            TIME_FMT.format(record.getAlertTime()),
                            ALERT_LEVEL_LABEL.getOrDefault(record.getLevel(), "未知等级"),
                            stationNames.getOrDefault(record.getStationId(), "未知站点"),
                            record.getObsValue() == null ? "-"
                                    : record.getObsValue().stripTrailingZeros().toPlainString(),
                            record.getContent(),
                            ALERT_STATUS_LABEL.getOrDefault(record.getStatus(), "未知")))
                    .collect(Collectors.joining("\n"));
            return "最近 %d 小时内共 %d 条告警（最多列出 %d 条）：\n%s"
                    .formatted(back, page.getTotal(), MAX_ALERT_RECORDS, body);
        });
    }

    @Tool(description = "查询指定站点未来若干小时的多模型预报（stat 统计模型 / ml 机器学习模型 / manual 人工订正），"
            + "返回各模型曲线的时次范围与最高/最低值。回答未来天气趋势类问题前必须调用本工具。")
    public String queryForecast(
            @ToolParam(description = "站点编码") String stationCode,
            @ToolParam(description = "要素名，中文或字段名，默认 temp（气温）", required = false) String element,
            @ToolParam(description = "预报时效（小时），1-72，默认 24", required = false) Integer rangeHours) {
        return guard("queryForecast", "站点 %s，要素 %s，未来 %s 小时".formatted(
                arg(stationCode), arg(element), arg(rangeHours)), () -> {
            StationMapResp station = requireStation(stationCode);
            String target = StringUtils.hasText(element) ? normalizeElement(element) : "temp";
            int range = rangeHours == null ? 24 : Math.min(Math.max(rangeHours, 1), 72);

            ForecastCompareResp resp = forecastService.compare(station.getStationCode(), target, range);
            if (resp.getModels().isEmpty()) {
                return "站点 %s（%s）未来 %d 小时内暂无%s的预报数据。".formatted(
                        station.getName(), station.getStationCode(), range, elementLabel(target));
            }
            String body = resp.getModels().entrySet().stream()
                    .map(entry -> summarizeForecast(entry.getKey(), entry.getValue()))
                    .collect(Collectors.joining("\n"));
            return "站点 %s（%s）未来 %d 小时 %s 预报：\n%s".formatted(
                    station.getName(), station.getStationCode(), range, elementLabel(target), body);
        });
    }

    @Tool(description = "查询站点档案详情：名称、编码、行政区划、经纬度、海拔、站点类型、运行状态、在线状态与最后上报时间。")
    public String getStationInfo(
            @ToolParam(description = "站点编码") String stationCode) {
        return guard("getStationInfo", "站点 " + arg(stationCode), () -> {
            StationMapResp brief = requireStation(stationCode);
            StationResp station = stationService.detail(brief.getId());
            String region = List.of(station.getProvince(), station.getCity(), station.getDistrict())
                    .stream().filter(StringUtils::hasText).collect(Collectors.joining(" "));
            return "站点 %s（编码 %s）：行政区划 %s，经纬度 %s,%s，海拔 %s 米，站点类型 %s，运行状态 %s，%s，最后上报 %s"
                    .formatted(station.getName(), station.getStationCode(),
                            StringUtils.hasText(region) ? region : "未填写",
                            station.getLongitude(), station.getLatitude(), station.getAltitude(),
                            STATION_TYPE_LABEL.getOrDefault(station.getStationType(), "未知"),
                            STATION_STATUS_LABEL.getOrDefault(station.getStatus(), "未知"),
                            onlineLabel(station.getOnlineFlag()),
                            station.getLastReportTime() == null ? "无记录" : TIME_FMT.format(station.getLastReportTime()));
        });
    }

    /**
     * 工具执行统一兜底：业务异常（参数非法/站点不存在）与外部依赖异常都转成中文提示返回。
     * 让模型拿到「查不到」的明确结论，而不是因工具报错改用常识值编造气象数据。
     *
     * 成功与失败都会写入调用留痕：失败信息同样是「AI 确实查了但没查到」的证据，
     * 只记录成功会让前端把「查不到」误显示成「没查」。
     */
    private String guard(String toolName, String arguments, Supplier<String> action) {
        String result;
        try {
            result = action.get();
        } catch (BizException e) {
            log.warn("工具调用未通过: tool={}, err={}", toolName, e.getMessage());
            result = e.getMessage();
        } catch (Exception e) {
            log.error("工具调用异常: tool={}, err={}", toolName, e.getMessage(), e);
            result = "查询失败（%s）。请调整参数后重试，或明确告知用户该数据当前不可用。".formatted(e.getMessage());
        }
        recorder.record(toolName, arguments, result);
        return result;
    }

    /** 参数摘要里的空值统一显示为 -，避免出现 "站点 null" */
    private static String arg(Object value) {
        return value == null ? "-" : String.valueOf(value);
    }

    /** 站点编码 → 站点摘要，编码不存在时给出可用编码，便于模型自行纠正 */
    private StationMapResp requireStation(String stationCode) {
        if (!StringUtils.hasText(stationCode)) {
            throw new BizException(ErrorCode.PARAM_ERROR, "站点编码不能为空。" + availableStations());
        }
        String code = stationCode.trim();
        return stationService.mapData().stream()
                .filter(station -> code.equalsIgnoreCase(station.getStationCode()))
                .findFirst()
                .orElseThrow(() -> new BizException(ErrorCode.NOT_FOUND,
                        "站点编码 %s 不存在或已停用。%s".formatted(code, availableStations())));
    }

    private String availableStations() {
        List<StationMapResp> stations = stationService.mapData();
        if (stations.isEmpty()) {
            return "系统内暂无可用站点。";
        }
        return "可用站点：" + stations.stream()
                .map(station -> "%s(%s)".formatted(station.getStationCode(), station.getName()))
                .collect(Collectors.joining("、"));
    }

    private Map<Long, String> stationNames() {
        return stationService.mapData().stream().collect(Collectors.toMap(
                StationMapResp::getId,
                station -> "%s(%s)".formatted(station.getName(), station.getStationCode()),
                (a, b) -> a));
    }

    /** 历史序列摘要：只给统计量，不回传整条曲线 */
    private String summarizeSeries(String element, List<SeriesPoint> points) {
        List<SeriesPoint> valid = points.stream().filter(point -> point.getValue() != null).toList();
        if (valid.isEmpty()) {
            return "- %s：无有效数据".formatted(elementLabel(element));
        }
        double avg = valid.stream().mapToDouble(SeriesPoint::getValue).average().orElse(0);
        SeriesPoint max = valid.stream().max(Comparator.comparingDouble(SeriesPoint::getValue)).orElseThrow();
        SeriesPoint min = valid.stream().min(Comparator.comparingDouble(SeriesPoint::getValue)).orElseThrow();
        SeriesPoint latest = valid.get(valid.size() - 1);
        return "- %s：样本 %d 个，均值 %.1f，最高 %.1f（%s），最低 %.1f（%s），最新 %.1f（%s）".formatted(
                elementLabel(element), valid.size(), avg,
                max.getValue(), max.getTime(), min.getValue(), min.getTime(),
                latest.getValue(), latest.getTime());
    }

    /** 预报曲线摘要：按时间升序给出时次范围与极值 */
    private String summarizeForecast(String model, List<ForecastPoint> points) {
        List<ForecastPoint> valid = points.stream().filter(point -> point.getValue() != null)
                .sorted(Comparator.comparing(ForecastPoint::getTime)).toList();
        if (valid.isEmpty()) {
            return "- %s：无有效数据".formatted(model);
        }
        ForecastPoint max = valid.stream().max(Comparator.comparingDouble(ForecastPoint::getValue)).orElseThrow();
        ForecastPoint min = valid.stream().min(Comparator.comparingDouble(ForecastPoint::getValue)).orElseThrow();
        ForecastPoint latest = valid.get(valid.size() - 1);
        return "- %s：%d 个时次（%s 至 %s），最高 %.1f（%s），最低 %.1f（%s），末值 %.1f（%s）".formatted(
                model, valid.size(), valid.get(0).getTime(), latest.getTime(),
                max.getValue(), max.getTime(), min.getValue(), min.getTime(),
                latest.getValue(), latest.getTime());
    }

    private String formatElements(Map<String, Double> elements) {
        if (elements == null || elements.isEmpty()) {
            return "无要素数据";
        }
        return elements.entrySet().stream()
                .filter(entry -> entry.getValue() != null)
                .map(entry -> "%s %.1f".formatted(elementLabel(entry.getKey()), entry.getValue()))
                .collect(Collectors.joining("，"));
    }

    /** 中文要素名归一为时序库字段名；未知名称原样透传，由下游接口校验后给出提示 */
    private String normalizeElement(String element) {
        if (!StringUtils.hasText(element)) {
            return null;
        }
        String value = element.trim();
        return ELEMENT_ALIAS.getOrDefault(value, value);
    }

    private String elementLabel(String element) {
        return element == null ? "全部要素" : ELEMENT_LABEL.getOrDefault(element, element);
    }

    private String onlineLabel(Integer onlineFlag) {
        return Integer.valueOf(1).equals(onlineFlag) ? "在线" : "离线";
    }

    private String alertLevelLabel(Integer level) {
        return level == null || level == 0 ? "无告警" : ALERT_LEVEL_LABEL.getOrDefault(level, "未知等级");
    }

    /** 支持 yyyy-MM-dd 与 yyyy-MM-dd HH:mm:ss 两种格式 */
    private Instant parseTime(String text, Instant defaultValue) {
        if (!StringUtils.hasText(text)) {
            return defaultValue;
        }
        String value = text.trim();
        try {
            return value.length() == 10
                    ? LocalDate.parse(value).atStartOfDay(ZONE).toInstant()
                    : LocalDateTime.parse(value, TIME_FMT).atZone(ZONE).toInstant();
        } catch (Exception e) {
            throw new BizException(ErrorCode.PARAM_ERROR,
                    "时间格式非法：%s，应为 yyyy-MM-dd HH:mm:ss 或 yyyy-MM-dd".formatted(text));
        }
    }

    private String format(Instant instant) {
        return TIME_FMT.format(LocalDateTime.ofInstant(instant, ZONE));
    }

    /** 实时观测时刻为 ISO-8601(UTC)，转换为本地时间便于模型与用户阅读 */
    private String toLocalTime(String isoTime) {
        if (!StringUtils.hasText(isoTime)) {
            return "未知";
        }
        try {
            return format(Instant.parse(isoTime));
        } catch (Exception e) {
            return isoTime;
        }
    }
}
