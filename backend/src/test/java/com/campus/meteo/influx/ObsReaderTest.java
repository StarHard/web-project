package com.campus.meteo.influx;

import com.influxdb.client.InfluxDBClient;
import com.influxdb.client.QueryApi;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * 聚合查询的结束边界对齐单测。
 *
 * 背景：Flux 的 aggregateWindow 会为范围末尾补一个未闭合的残窗，_time 直接等于查询的 stop。
 * 该点实为残窗均值，会让「小时均值 × 1h」这类累计口径系统性偏小。
 * 这里捕获实际下发的 Flux 语句，验证边界确实被向下对齐，而不只是随手改了个变量。
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("观测序列聚合查询")
class ObsReaderTest {

    private static final String ORG = "meteo";

    /** 非整点，便于观察对齐结果 */
    private static final Instant START = Instant.parse("2026-09-19T16:37:41Z");
    private static final Instant STOP = Instant.parse("2026-09-20T02:37:41Z");

    @Mock
    private InfluxDBClient client;
    @Mock
    private QueryApi queryApi;
    @Mock
    private InfluxProperties properties;

    @InjectMocks
    private ObsReader obsReader;

    @Test
    @DisplayName("按小时聚合：结束边界向下对齐到整点，末尾不再有残窗")
    void shouldAlignStopToHourBoundary() {
        String flux = captureFlux("1h", START, STOP);

        assertThat(flux).contains("aggregateWindow(every: 1h");
        assertThat(flux).contains("stop: 2026-09-20T02:00:00Z");
        assertThat(flux).doesNotContain("stop: 2026-09-20T02:37:41Z");
    }

    @Test
    @DisplayName("auto 24 小时：按 15 分钟窗口对齐")
    void shouldAlignStopToAutoWindow() {
        Instant start = Instant.parse("2026-09-19T02:37:41Z");

        String flux = captureFlux("auto", start, STOP);

        assertThat(flux).contains("aggregateWindow(every: 15m");
        assertThat(flux).contains("stop: 2026-09-20T02:30:00Z");
    }

    @Test
    @DisplayName("raw 粒度不对齐：原始序列需要保留到当前时刻")
    void shouldNotAlignWhenRaw() {
        String flux = captureFlux("raw", START, STOP);

        assertThat(flux).doesNotContain("aggregateWindow");
        assertThat(flux).contains("stop: 2026-09-20T02:37:41Z");
    }

    @Test
    @DisplayName("请求跨度不足一个窗口时保持原边界，不构造出空区间")
    void shouldKeepStopWhenSpanShorterThanWindow() {
        Instant start = Instant.parse("2026-09-20T00:00:00Z");

        String flux = captureFlux("1d", start, STOP);

        // 对齐结果恰好等于 start，若仍向下对齐会得到空区间，界面会突然无数据
        assertThat(flux).contains("aggregateWindow(every: 1d");
        assertThat(flux).contains("stop: 2026-09-20T02:37:41Z");
    }

    @Test
    @DisplayName("非法站点编码直接拒绝，不访问时序库")
    void shouldRejectMalformedStationCode() {
        assertThat(obsReader.queryRangeAggregated("bad code!", START, STOP, "1h")).isEmpty();

        verifyNoInteractions(client);
    }

    /** 执行一次聚合查询并返回实际下发的 Flux 语句 */
    private String captureFlux(String granularity, Instant start, Instant stop) {
        when(client.getQueryApi()).thenReturn(queryApi);
        when(properties.getBucket()).thenReturn("meteo");
        when(properties.getOrg()).thenReturn(ORG);
        when(queryApi.query(anyString(), eq(ORG))).thenReturn(List.of());

        assertThat(obsReader.queryRangeAggregated("CAMPUS01", start, stop, granularity)).isEmpty();

        ArgumentCaptor<String> fluxCaptor = ArgumentCaptor.forClass(String.class);
        verify(queryApi).query(fluxCaptor.capture(), eq(ORG));
        return fluxCaptor.getValue();
    }
}