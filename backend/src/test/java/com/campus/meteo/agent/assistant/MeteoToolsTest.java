package com.campus.meteo.agent.assistant;

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
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 决策智能体工具集单测
 *
 * 重点不是「查询有没有返回」，而是三件容易出事的事：
 * 1. 工具报错时不能把异常抛给模型（否则模型会改用常识值编造气象数据），必须降级为中文提示；
 * 2. 中文要素名要归一成时序库字段名，否则下游查不到字段；
 * 3. 站点编码写错时要回带可用编码，让模型能自我纠正而不是反复瞎猜。
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("决策智能体工具集")
class MeteoToolsTest {

    @Mock
    private RealtimeService realtimeService;
    @Mock
    private DataQueryService dataQueryService;
    @Mock
    private AlertService alertService;
    @Mock
    private ForecastService forecastService;
    @Mock
    private StationService stationService;
    @Mock
    private ToolCallRecorder recorder;

    @InjectMocks
    private MeteoTools tools;

    private StationMapResp station(Long id, String code, String name, int onlineFlag, int alertLevel) {
        StationMapResp resp = new StationMapResp();
        resp.setId(id);
        resp.setStationCode(code);
        resp.setName(name);
        resp.setOnlineFlag(onlineFlag);
        resp.setAlertLevel(alertLevel);
        return resp;
    }

    @Test
    @DisplayName("站点清单：给出编码、在线状态与当前告警等级")
    void shouldListStations() {
        when(stationService.mapData()).thenReturn(List.of(
                station(1L, "CAMPUS01", "主校区站", 1, 2),
                station(2L, "AGRI01", "农业试验站", 0, 0)));

        String result = tools.listStations();

        assertThat(result)
                .contains("CAMPUS01").contains("主校区站").contains("在线").contains("黄色")
                .contains("AGRI01").contains("农业试验站").contains("离线").contains("无告警");
    }

    @Test
    @DisplayName("实时观测：UTC 时刻转本地时间，要素带中文名与单位")
    void shouldFormatRealtimeObservation() {
        when(stationService.mapData()).thenReturn(List.of(station(1L, "CAMPUS01", "主校区站", 1, 0)));
        RealtimeLatestResp latest = new RealtimeLatestResp();
        latest.setSource("cache");
        latest.setStationCode("CAMPUS01");
        latest.setTs("2026-09-21T02:30:00Z");
        latest.setQcFlag("passed");
        latest.setElements(new LinkedHashMap<>(Map.of("temp", 26.4, "wind_speed", 3.2)));
        when(realtimeService.latest("CAMPUS01")).thenReturn(latest);

        String result = tools.queryRealtime("CAMPUS01");

        assertThat(result)
                .contains("2026-09-21 10:30:00")
                .contains("气温(℃) 26.4")
                .contains("风速(m/s) 3.2")
                .contains("passed");
    }

    @Test
    @DisplayName("实时观测：无数据时明确说明，不得返回空串")
    void shouldStateNoDataForRealtime() {
        when(stationService.mapData()).thenReturn(List.of(station(1L, "CAMPUS01", "主校区站", 0, 0)));
        RealtimeLatestResp latest = new RealtimeLatestResp();
        latest.setSource("none");
        when(realtimeService.latest("CAMPUS01")).thenReturn(latest);

        String result = tools.queryRealtime("CAMPUS01");

        assertThat(result).contains("无可用观测数据");
    }

    @Test
    @DisplayName("工具留痕：成功与失败都写入调用记录，供前端展示「AI 查了什么」")
    void shouldRecordToolInvocation() {
        when(stationService.mapData()).thenReturn(List.of(station(1L, "CAMPUS01", "主校区站", 1, 0)));
        RealtimeLatestResp latest = new RealtimeLatestResp();
        latest.setSource("none");
        when(realtimeService.latest("CAMPUS01")).thenReturn(latest);

        tools.queryRealtime("CAMPUS01");
        tools.queryRealtime("ST999");

        verify(recorder).record(eq("queryRealtime"), eq("站点 CAMPUS01"), eq("站点 主校区站（CAMPUS01）当前无可用观测数据：可能设备离线，或该时次数据未通过质控。"));
        verify(recorder).record(eq("queryRealtime"), eq("站点 ST999"), anyString());
    }

    @Test
    @DisplayName("站点编码写错：回带可用编码便于模型自我纠正")
    void shouldHintAvailableStationsWhenCodeInvalid() {
        when(stationService.mapData()).thenReturn(List.of(station(1L, "CAMPUS01", "主校区站", 1, 0)));

        String result = tools.queryRealtime("ST999");

        assertThat(result).contains("ST999").contains("不存在").contains("CAMPUS01(主校区站)");
    }

    @Test
    @DisplayName("历史查询：中文要素名归一为字段名，返回统计摘要而非整条曲线")
    void shouldNormalizeChineseElementAndSummarize() {
        when(stationService.mapData()).thenReturn(List.of(station(1L, "CAMPUS01", "主校区站", 1, 0)));
        HistoryResp history = new HistoryResp();
        history.setStationId(1L);
        history.setStationCode("CAMPUS01");
        history.setGranularity("hour");
        history.setSeries(new LinkedHashMap<>(Map.of("temp", List.of(
                new SeriesPoint("2026-09-20 10:00:00", 20.0, "passed"),
                new SeriesPoint("2026-09-20 14:00:00", 32.5, "passed"),
                new SeriesPoint("2026-09-20 20:00:00", 18.4, "passed")))));
        when(dataQueryService.history(eq(1L), eq("temp"), anyString(), anyString(), eq("hour"), isNull()))
                .thenReturn(history);

        String result = tools.queryHistory("CAMPUS01", "气温",
                "2026-09-20 00:00:00", "2026-09-21 00:00:00", null);

        assertThat(result)
                .contains("气温(℃)")
                .contains("样本 3")
                .contains("均值 23.6")
                .contains("最高 32.5（2026-09-20 14:00:00）")
                .contains("最低 18.4（2026-09-20 20:00:00）");
        verify(dataQueryService).history(eq(1L), eq("temp"),
                eq("2026-09-20 00:00:00"), eq("2026-09-21 00:00:00"), eq("hour"), isNull());
    }

    @Test
    @DisplayName("历史查询：跨度超上限时截断为最近 7 天并在回答中说明")
    void shouldTruncateTooLongHistoryRange() {
        when(stationService.mapData()).thenReturn(List.of(station(1L, "CAMPUS01", "主校区站", 1, 0)));
        when(dataQueryService.history(eq(1L), eq("temp"), anyString(), anyString(), eq("hour"), isNull()))
                .thenReturn(new HistoryResp());

        String result = tools.queryHistory("CAMPUS01", "temp",
                "2026-01-01 00:00:00", "2026-09-21 00:00:00", null);

        ArgumentCaptor<String> startCaptor = ArgumentCaptor.forClass(String.class);
        verify(dataQueryService).history(eq(1L), eq("temp"), startCaptor.capture(),
                eq("2026-09-21 00:00:00"), eq("hour"), isNull());
        assertThat(startCaptor.getValue()).isEqualTo("2026-09-14 00:00:00");
        assertThat(result).contains("已截断为最近 7 天").contains("无气温(℃)的可用观测数据");
    }

    @Test
    @DisplayName("历史查询：未知要素名原样透传，由下游接口校验")
    void shouldPassThroughUnknownElement() {
        when(stationService.mapData()).thenReturn(List.of(station(1L, "CAMPUS01", "主校区站", 1, 0)));
        when(dataQueryService.history(eq(1L), eq("pm25"), anyString(), anyString(), eq("hour"), isNull()))
                .thenReturn(new HistoryResp());

        tools.queryHistory("CAMPUS01", "pm25", null, null, null);

        verify(dataQueryService).history(eq(1L), eq("pm25"), anyString(), anyString(), eq("hour"), isNull());
    }

    @Test
    @DisplayName("历史查询：时间格式非法时返回中文提示而非抛异常")
    void shouldReturnHintForIllegalTime() {
        when(stationService.mapData()).thenReturn(List.of(station(1L, "CAMPUS01", "主校区站", 1, 0)));

        String result = tools.queryHistory("CAMPUS01", "temp", "昨天", null, null);

        assertThat(result).contains("时间格式非法");
    }

    @Test
    @DisplayName("历史查询：下游异常被兜底为提示，不抛给模型")
    void shouldDegradeWhenHistoryFails() {
        when(stationService.mapData()).thenReturn(List.of(station(1L, "CAMPUS01", "主校区站", 1, 0)));
        when(dataQueryService.history(eq(1L), eq("temp"), anyString(), anyString(), eq("hour"), isNull()))
                .thenThrow(new RuntimeException("InfluxDB 连接超时"));

        String result = tools.queryHistory("CAMPUS01", "temp", null, null, null);

        assertThat(result).contains("查询失败").contains("InfluxDB 连接超时");
    }

    @Test
    @DisplayName("告警查询：输出等级中文名、站点名与状态")
    void shouldFormatAlertRecords() {
        when(stationService.mapData()).thenReturn(List.of(station(1L, "CAMPUS01", "主校区站", 1, 0)));
        AlertRecord record = new AlertRecord();
        record.setStationId(1L);
        record.setLevel(4);
        record.setAlertTime(LocalDateTime.of(2026, 9, 21, 9, 5, 0));
        record.setObsValue(new BigDecimal("18.60"));
        record.setContent("10 分钟平均风速超过 17.2m/s");
        record.setStatus(0);
        when(alertService.pageRecords(eq(1L), eq(20L), isNull(), isNull(), isNull(), isNull(),
                anyString(), anyString())).thenReturn(PageResult.of(1, 1, 20, List.of(record)));

        String result = tools.queryAlerts(24, null);

        assertThat(result)
                .contains("红色告警")
                .contains("主校区站(CAMPUS01)")
                .contains("18.6")
                .contains("进行中");
    }

    @Test
    @DisplayName("告警查询：无记录时明确说明无告警")
    void shouldStateNoAlertRecords() {
        when(alertService.pageRecords(eq(1L), eq(20L), isNull(), isNull(), isNull(), isNull(),
                anyString(), anyString())).thenReturn(PageResult.of(0, 1, 20, List.of()));

        String result = tools.queryAlerts(24, null);

        assertThat(result).contains("最近 24 小时内无告警记录");
    }

    @Test
    @DisplayName("告警查询：站点编码非法时按站点提示返回，不查询告警表")
    void shouldNotQueryAlertsWhenStationInvalid() {
        when(stationService.mapData()).thenReturn(List.of(station(1L, "CAMPUS01", "主校区站", 1, 0)));

        String result = tools.queryAlerts(24, "ST999");

        assertThat(result).contains("不存在").contains("CAMPUS01(主校区站)");
    }

    @Test
    @DisplayName("预报查询：多模型曲线给出时次范围与极值")
    void shouldSummarizeForecastModels() {
        when(stationService.mapData()).thenReturn(List.of(station(1L, "CAMPUS01", "主校区站", 1, 0)));
        ForecastCompareResp compare = new ForecastCompareResp();
        compare.setStationCode("CAMPUS01");
        compare.setElement("temp");
        compare.setRangeHours(24);
        compare.getModels().put("stat", List.of(
                new ForecastPoint("2026-09-21 20:00:00", 21.5),
                new ForecastPoint("2026-09-21 11:00:00", 26.0),
                new ForecastPoint("2026-09-21 15:00:00", 30.2)));
        when(forecastService.compare("CAMPUS01", "temp", 24)).thenReturn(compare);

        String result = tools.queryForecast("CAMPUS01", null, null);

        assertThat(result)
                .contains("气温(℃)")
                .contains("stat")
                .contains("2026-09-21 11:00:00 至 2026-09-21 20:00:00")
                .contains("最高 30.2（2026-09-21 15:00:00）")
                .contains("最低 21.5（2026-09-21 20:00:00）");
    }

    @Test
    @DisplayName("预报查询：无预报数据时明确说明")
    void shouldStateNoForecast() {
        when(stationService.mapData()).thenReturn(List.of(station(1L, "CAMPUS01", "主校区站", 1, 0)));
        when(forecastService.compare("CAMPUS01", "temp", 24)).thenReturn(new ForecastCompareResp());

        String result = tools.queryForecast("CAMPUS01", null, null);

        assertThat(result).contains("暂无").contains("气温(℃)");
    }

    @Test
    @DisplayName("站点档案：拼装行政区划、经纬度与状态")
    void shouldFormatStationInfo() {
        when(stationService.mapData()).thenReturn(List.of(station(1L, "CAMPUS01", "主校区站", 1, 0)));
        StationResp detail = new StationResp();
        detail.setId(1L);
        detail.setStationCode("CAMPUS01");
        detail.setName("主校区站");
        detail.setProvince("北京市");
        detail.setCity("北京市");
        detail.setDistrict("海淀区");
        detail.setLongitude(new BigDecimal("116.4"));
        detail.setLatitude(new BigDecimal("39.9"));
        detail.setAltitude(new BigDecimal("50"));
        detail.setStationType(1);
        detail.setStatus(1);
        detail.setOnlineFlag(1);
        detail.setLastReportTime(LocalDateTime.of(2026, 9, 21, 10, 25, 0));
        when(stationService.detail(1L)).thenReturn(detail);

        String result = tools.getStationInfo("CAMPUS01");

        assertThat(result)
                .contains("北京市 北京市 海淀区")
                .contains("116.4,39.9")
                .contains("海拔 50 米")
                .contains("校园")
                .contains("正常")
                .contains("在线")
                .contains("2026-09-21 10:25:00");
    }
}
