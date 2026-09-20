package com.campus.meteo.service.impl;

import com.campus.meteo.common.exception.BizException;
import com.campus.meteo.common.result.ErrorCode;
import com.campus.meteo.agent.realtime.RealtimeCache;
import com.campus.meteo.dto.RealtimeCompareResp;
import com.campus.meteo.dto.RealtimeLatestResp;
import com.campus.meteo.entity.Station;
import com.campus.meteo.influx.ObsData;
import com.campus.meteo.influx.ObsReader;
import com.campus.meteo.mapper.StationMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 多站点数据对比单测：重点验证时间轴并集对齐与统计口径
 * （两站采样时刻不一致时，直接按各自序列绘图会错位，必须按统一时间轴补 null）。
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("多站点数据对比")
class RealtimeServiceImplTest {

    private static final ZoneId ZONE = ZoneId.of("Asia/Shanghai");

    @Mock
    private StationMapper stationMapper;
    @Mock
    private ObsReader obsReader;
    @Mock
    private RealtimeCache realtimeCache;

    @InjectMocks
    private RealtimeServiceImpl realtimeService;

    @Test
    @DisplayName("最新观测：命中缓存时不再查时序库")
    void shouldServeLatestFromCache() {
        Instant ts = at(10, 0);
        when(realtimeCache.find("CAMPUS01")).thenReturn(
                ObsData.builder().stationCode("CAMPUS01").ts(ts).elements(Map.of("temp", 21.5)).qcFlag("passed").build());

        RealtimeLatestResp resp = realtimeService.latest("CAMPUS01");

        assertThat(resp.getSource()).isEqualTo("cache");
        assertThat(resp.getStationCode()).isEqualTo("CAMPUS01");
        assertThat(resp.getQcFlag()).isEqualTo("passed");
        assertThat(resp.getElements()).containsEntry("temp", 21.5);
        assertThat(resp.getTs()).isEqualTo(ts.toString());
        // 缓存命中不应产生时序库查询
        verify(obsReader, never()).queryLatest(anyString());
    }

    @Test
    @DisplayName("最新观测：缓存缺失时降级查时序库")
    void shouldFallbackToInfluxWhenCacheMisses() {
        Instant ts = at(10, 15);
        when(realtimeCache.find("CAMPUS01")).thenReturn(null);
        when(obsReader.queryLatest("CAMPUS01")).thenReturn(
                ObsData.builder().stationCode("CAMPUS01").ts(ts).elements(Map.of("temp", 22.0)).qcFlag("revised").build());

        RealtimeLatestResp resp = realtimeService.latest("CAMPUS01");

        assertThat(resp.getSource()).isEqualTo("influx");
        assertThat(resp.getQcFlag()).isEqualTo("revised");
        assertThat(resp.getTs()).isEqualTo(ts.toString());
    }

    @Test
    @DisplayName("最新观测：两条路径都无数据时返回 none")
    void shouldReportNoneWhenNoData() {
        when(realtimeCache.find("CAMPUS01")).thenReturn(null);
        when(obsReader.queryLatest("CAMPUS01")).thenReturn(null);

        RealtimeLatestResp resp = realtimeService.latest("CAMPUS01");

        assertThat(resp.getSource()).isEqualTo("none");
        assertThat(resp.getElements()).isNull();
        assertThat(resp.getTs()).isNull();
    }

    @Test
    @DisplayName("时间轴取并集，各站序列对齐补 null 并给出统计量")
    void shouldAlignSeriesOnUnionTimeAxis() {
        Instant t1 = at(10, 0);
        Instant t2 = at(10, 15);
        Instant t3 = at(10, 30);
        when(stationMapper.selectBatchIds(any())).thenReturn(List.of(station(1L, "CAMPUS01", "校园站"), station(2L, "FARM02", "农气站")));
        when(obsReader.queryRangeAggregated(eq("CAMPUS01"), any(), any(), anyString()))
                .thenReturn(List.of(obs(t1, 20.0), obs(t2, 22.0)));
        when(obsReader.queryRangeAggregated(eq("FARM02"), any(), any(), anyString()))
                .thenReturn(List.of(obs(t2, 30.0), obs(t3, 31.0)));

        RealtimeCompareResp resp = realtimeService.compare("1,2", "temp",
                "2026-09-20 09:00:00", "2026-09-20 12:00:00", "auto");

        assertThat(resp.getTimes()).containsExactly("2026-09-20 10:00:00", "2026-09-20 10:15:00", "2026-09-20 10:30:00");
        assertThat(resp.getSeries()).hasSize(2);

        RealtimeCompareResp.StationSeries campus = resp.getSeries().get(0);
        assertThat(campus.getStationCode()).isEqualTo("CAMPUS01");
        assertThat(campus.getStationName()).isEqualTo("校园站");
        assertThat(campus.getValues()).containsExactly(20.0, 22.0, null);
        assertThat(campus.getCount()).isEqualTo(2);
        assertThat(campus.getLatest()).isEqualTo(22.0);
        assertThat(campus.getMin()).isEqualTo(20.0);
        assertThat(campus.getMax()).isEqualTo(22.0);
        assertThat(campus.getAvg()).isEqualTo(21.0);

        RealtimeCompareResp.StationSeries farm = resp.getSeries().get(1);
        assertThat(farm.getValues()).containsExactly(null, 30.0, 31.0);
        assertThat(farm.getCount()).isEqualTo(2);
        assertThat(farm.getLatest()).isEqualTo(31.0);
        assertThat(farm.getAvg()).isEqualTo(30.5);
    }

    @Test
    @DisplayName("统计量只按有效样本计算，缺测不参与")
    void shouldIgnoreMissingSamplesInStats() {
        Instant t1 = at(10, 0);
        Instant t2 = at(10, 15);
        when(stationMapper.selectBatchIds(any())).thenReturn(List.of(station(1L, "CAMPUS01", "校园站")));
        when(obsReader.queryRangeAggregated(eq("CAMPUS01"), any(), any(), anyString()))
                .thenReturn(List.of(obs(t1, 10.0), obs(t2, 20.0)));

        RealtimeCompareResp resp = realtimeService.compare("1", "temp",
                "2026-09-20 09:00:00", "2026-09-20 12:00:00", "auto");

        assertThat(resp.getSeries().get(0).getAvg()).isEqualTo(15.0);
        assertThat(resp.getSeries().get(0).getCount()).isEqualTo(2);
    }

    @Test
    @DisplayName("无数据时返回空时间轴，站点序列仍保留（便于前端展示空态）")
    void shouldReturnEmptyAxisWhenNoData() {
        when(stationMapper.selectBatchIds(any())).thenReturn(List.of(station(1L, "CAMPUS01", "校园站")));
        when(obsReader.queryRangeAggregated(eq("CAMPUS01"), any(), any(), anyString())).thenReturn(List.of());

        RealtimeCompareResp resp = realtimeService.compare("1", "temp",
                "2026-09-20 09:00:00", "2026-09-20 12:00:00", "auto");

        assertThat(resp.getTimes()).isEmpty();
        assertThat(resp.getSeries()).hasSize(1);
        assertThat(resp.getSeries().get(0).getValues()).isEmpty();
        assertThat(resp.getSeries().get(0).getCount()).isZero();
        assertThat(resp.getSeries().get(0).getLatest()).isNull();
    }

    @Test
    @DisplayName("时间跨度超过 7 天时截断，避免单请求拉取无界数据")
    void shouldClampOverlongTimeSpan() {
        when(stationMapper.selectBatchIds(any())).thenReturn(List.of(station(1L, "CAMPUS01", "校园站")));
        when(obsReader.queryRangeAggregated(eq("CAMPUS01"), any(), any(), anyString())).thenReturn(List.of());

        realtimeService.compare("1", "temp", "2026-09-01 00:00:00", "2026-09-20 00:00:00", "auto");

        ArgumentCaptor<Instant> stopCaptor = ArgumentCaptor.forClass(Instant.class);
        verify(obsReader).queryRangeAggregated(eq("CAMPUS01"), eq(at(0, 0, 1)), stopCaptor.capture(), anyString());
        assertThat(stopCaptor.getValue()).isEqualTo(at(0, 0, 8));
    }

    @Test
    @DisplayName("站点ID格式非法直接拒绝")
    void shouldRejectMalformedStationId() {
        assertThatThrownBy(() -> realtimeService.compare("1,abc", "temp", null, null, "auto"))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("站点ID格式非法");
    }

    @Test
    @DisplayName("站点数超过上限直接拒绝")
    void shouldRejectTooManyStations() {
        assertThatThrownBy(() -> realtimeService.compare("1,2,3,4,5,6,7", "temp", null, null, "auto"))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("不能超过 6 个");
    }

    @Test
    @DisplayName("站点均不存在时返回资源不存在")
    void shouldRejectWhenNoStationResolved() {
        when(stationMapper.selectBatchIds(any())).thenReturn(List.of());

        assertThatThrownBy(() -> realtimeService.compare("99", "temp", null, null, "auto"))
                .isInstanceOf(BizException.class)
                .extracting(e -> ((BizException) e).getErrorCode())
                .isEqualTo(ErrorCode.NOT_FOUND);
    }

    @Test
    @DisplayName("对比要素为空直接拒绝")
    void shouldRejectBlankElement() {
        assertThatThrownBy(() -> realtimeService.compare("1", " ", null, null, "auto"))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("对比要素不能为空");
    }

    @Test
    @DisplayName("结束时间早于开始时间直接拒绝")
    void shouldRejectReversedTimeRange() {
        assertThatThrownBy(() -> realtimeService.compare("1", "temp",
                "2026-09-20 12:00:00", "2026-09-20 09:00:00", "auto"))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("结束时间必须晚于开始时间");
    }

    private Station station(Long id, String code, String name) {
        Station station = new Station();
        station.setId(id);
        station.setStationCode(code);
        station.setName(name);
        return station;
    }

    private ObsData obs(Instant ts, double value) {
        Map<String, Double> elements = new LinkedHashMap<>();
        elements.put("temp", value);
        return ObsData.builder().stationCode("CAMPUS01").ts(ts).elements(elements).qcFlag("passed").build();
    }

    /** 2026-09-20 当天的指定时刻（Asia/Shanghai） */
    private Instant at(int hour, int minute) {
        return LocalDateTime.of(2026, 9, 20, hour, minute).atZone(ZONE).toInstant();
    }

    private Instant at(int hour, int minute, int day) {
        return LocalDateTime.of(2026, 9, day, hour, minute).atZone(ZONE).toInstant();
    }
}
