package com.campus.meteo.agent.qc;

import com.campus.meteo.entity.Station;
import com.campus.meteo.influx.ObsData;
import com.campus.meteo.influx.ObsReader;
import com.campus.meteo.influx.ObsWriter;
import com.campus.meteo.mapper.StationMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 缺测插补编排单测：Mockito 隔离 InfluxDB / MySQL / Redis，验证缺口判定与回填行为。
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("缺测插补编排")
class MissingDataInterpolatorTest {

    private static final String STATION = "CAMPUS01";
    private static final String NEIGHBOR = "FARM02";
    private static final Instant START = Instant.now().minusSeconds(3600);
    private static final int STEP = 15;

    @Mock
    private StationMapper stationMapper;
    @Mock
    private ObsReader obsReader;
    @Mock
    private ObsWriter obsWriter;
    @Mock
    private StringRedisTemplate redisTemplate;
    @Mock
    private ValueOperations<String, String> valueOperations;

    private MissingDataInterpolator interpolator;

    @BeforeEach
    void setUp() {
        interpolator = new MissingDataInterpolator(stationMapper, obsReader, obsWriter,
                new InterpolationService(), redisTemplate);
        ReflectionTestUtils.setField(interpolator, "enabled", true);
        ReflectionTestUtils.setField(interpolator, "scanWindowHours", 3);
        ReflectionTestUtils.setField(interpolator, "maxGapSlots", 2);
        ReflectionTestUtils.setField(interpolator, "maxNeighborSlots", 30);
        ReflectionTestUtils.setField(interpolator, "maxNeighborDistanceKm", 50.0);
        ReflectionTestUtils.setField(interpolator, "minPairedSamples", 10);
        lenient().when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        lenient().when(valueOperations.setIfAbsent(anyString(), anyString(), any(Duration.class)))
                .thenReturn(true);
    }

    @Test
    @DisplayName("序列连续无缺口 → 不写入任何插补点")
    void shouldWriteNothingWhenSeriesIsContinuous() {
        stubStations(station(STATION, 1L));
        stubObserved(STATION, List.of(
                obs(0, 10.0), obs(STEP, 10.5), obs(STEP * 2, 11.0), obs(STEP * 3, 11.5)));
        stubOccupied(0, STEP, STEP * 2, STEP * 3);

        MissingDataInterpolator.InterpolationResult result = interpolator.interpolateOnce();

        assertThat(result.pointsWritten()).isZero();
        assertThat(result.gapsSkipped()).isZero();
        verify(obsWriter, never()).writeObs(any());
    }

    @Test
    @DisplayName("缺口 2 槽 → 线性插值回填 2 点，标记 interpolated")
    void shouldFillShortGapLinearly() {
        // 30s 处 10.0、75s 处 13.0，中间缺 45s 与 60s 两个槽位
        stubStations(station(STATION, 1L));
        stubObserved(STATION, List.of(
                obs(0, 9.5), obs(STEP, 9.8), obs(STEP * 2, 10.0),
                obs(STEP * 5, 13.0), obs(STEP * 6, 13.4), obs(STEP * 7, 13.8)));
        stubOccupied(0, STEP, STEP * 2, STEP * 5, STEP * 6, STEP * 7);

        MissingDataInterpolator.InterpolationResult result = interpolator.interpolateOnce();

        assertThat(result.pointsWritten()).isEqualTo(2);
        List<ObsData> written = captureWritten(2);
        assertThat(written).extracting(ObsData::getTs)
                .containsExactly(START.plusSeconds(STEP * 3), START.plusSeconds(STEP * 4));
        assertThat(written).allSatisfy(obs -> assertThat(obs.getQcFlag()).isEqualTo("interpolated"));
        assertThat(written.get(0).getElements().get("temp")).isEqualTo(11.0);
        assertThat(written.get(1).getElements().get("temp")).isEqualTo(12.0);
    }

    @Test
    @DisplayName("槽位附近已有任意标记的数据 → 该槽位不补（物理缺测才算缺测）")
    void shouldSkipSlotThatAlreadyHasAnyFlaggedData() {
        stubStations(station(STATION, 1L));
        stubObserved(STATION, List.of(
                obs(0, 9.5), obs(STEP, 9.8), obs(STEP * 2, 10.0),
                obs(STEP * 5, 13.0), obs(STEP * 6, 13.4), obs(STEP * 7, 13.8)));
        // 45s 处存在一条被质控拒绝的数据（任意标记即算占用）
        stubOccupied(0, STEP, STEP * 2, STEP * 3, STEP * 5, STEP * 6, STEP * 7);

        MissingDataInterpolator.InterpolationResult result = interpolator.interpolateOnce();

        assertThat(result.pointsWritten()).isEqualTo(1);
        assertThat(captureWritten(1).get(0).getTs()).isEqualTo(START.plusSeconds(STEP * 4));
    }

    @Test
    @DisplayName("幂等：已回填过的时次再次扫描不再重复补")
    void shouldBeIdempotentAcrossScans() {
        stubStations(station(STATION, 1L));
        stubObserved(STATION, List.of(
                obs(0, 9.5), obs(STEP, 9.8), obs(STEP * 2, 10.0),
                obs(STEP * 5, 13.0), obs(STEP * 6, 13.4), obs(STEP * 7, 13.8)));
        // 上一轮已写入的插补点也表现为「已占用时刻」
        stubOccupied(0, STEP, STEP * 2, STEP * 3, STEP * 4, STEP * 5, STEP * 6, STEP * 7);

        MissingDataInterpolator.InterpolationResult result = interpolator.interpolateOnce();

        assertThat(result.pointsWritten()).isZero();
        verify(obsWriter, never()).writeObs(any());
    }

    @Test
    @DisplayName("缺口超过邻站回归上限 → 放弃并计数，不伪造长中断")
    void shouldSkipGapLongerThanNeighborLimit() {
        ReflectionTestUtils.setField(interpolator, "maxNeighborSlots", 3);
        // 30s → 120s 的间隔为 90s，按 15s 间隔折算 5 个槽位
        stubStations(station(STATION, 1L));
        stubObserved(STATION, List.of(
                obs(0, 10.0), obs(STEP, 10.2), obs(STEP * 2, 10.4),
                obs(STEP * 8, 12.0), obs(STEP * 9, 12.2), obs(STEP * 10, 12.4)));
        stubOccupied(0, STEP, STEP * 2, STEP * 8, STEP * 9, STEP * 10);

        MissingDataInterpolator.InterpolationResult result = interpolator.interpolateOnce();

        assertThat(result.pointsWritten()).isZero();
        assertThat(result.gapsSkipped()).isEqualTo(1);
        verify(obsWriter, never()).writeObs(any());
    }

    @Test
    @DisplayName("观测样本不足 3 条 → 无法推断间隔，整站跳过")
    void shouldSkipStationWithTooFewSamples() {
        stubStations(station(STATION, 1L));
        stubObserved(STATION, List.of(obs(0, 10.0), obs(STEP, 10.5)));

        MissingDataInterpolator.InterpolationResult result = interpolator.interpolateOnce();

        assertThat(result.stationsScanned()).isEqualTo(1);
        assertThat(result.pointsWritten()).isZero();
    }

    @Test
    @DisplayName("长缺口且两站配对样本充足 → 走邻近站点回归，值 = 邻站值 + 偏差")
    void shouldFallBackToNeighborRegressionForLongGap() {
        Station self = station(STATION, 1L);
        Station neighbor = station(NEIGHBOR, 2L);
        neighbor.setLongitude(new BigDecimal("118.842000"));
        neighbor.setLatitude(new BigDecimal("31.953000"));
        self.setLongitude(new BigDecimal("118.914000"));
        self.setLatitude(new BigDecimal("32.103000"));
        stubStations(self, neighbor);

        // 本站 30s → 90s 之间缺 3 个槽位（超过 maxGapSlots=2，走回归）
        List<ObsData> own = new ArrayList<>();
        for (int i = 0; i < 14; i++) {
            if (i == 3 || i == 4 || i == 5) {
                continue;
            }
            own.add(obs(i * STEP, 20.0 + i * 0.5));
        }
        stubObserved(STATION, own);
        // 邻站同期数据完整，恒比本站低 5℃
        List<ObsData> theirs = new ArrayList<>();
        for (int i = 0; i < 14; i++) {
            theirs.add(obs(i * STEP, 15.0 + i * 0.5));
        }
        stubObserved(NEIGHBOR, theirs);
        stubOccupied(0, STEP, STEP * 2, STEP * 6, STEP * 7, STEP * 8);

        MissingDataInterpolator.InterpolationResult result = interpolator.interpolateOnce();

        assertThat(result.pointsWritten()).isEqualTo(3);
        List<ObsData> written = captureWritten(3);
        assertThat(written).extracting(ObsData::getTs).containsExactly(
                START.plusSeconds(STEP * 3), START.plusSeconds(STEP * 4), START.plusSeconds(STEP * 5));
        // 邻站 16.5/17.0/17.5 加上 +5 的系统性偏差
        assertThat(written).extracting(obs -> obs.getElements().get("temp"))
                .containsExactly(21.5, 22.0, 22.5);
        assertThat(written).allSatisfy(obs -> assertThat(obs.getQcFlag()).isEqualTo("interpolated"));
    }

    @Test
    @DisplayName("单站处理失败不影响其余站点")
    void shouldContinueWhenOneStationFails() {
        stubStations(station("BAD01", 1L), station(STATION, 2L));
        when(obsReader.queryObservedRange(eq("BAD01"), any(), any()))
                .thenThrow(new IllegalStateException("查询失败"));
        stubObserved(STATION, List.of(
                obs(0, 9.5), obs(STEP, 9.8), obs(STEP * 2, 10.0),
                obs(STEP * 5, 13.0), obs(STEP * 6, 13.4), obs(STEP * 7, 13.8)));
        stubOccupied(0, STEP, STEP * 2, STEP * 5, STEP * 6, STEP * 7);

        MissingDataInterpolator.InterpolationResult result = interpolator.interpolateOnce();

        assertThat(result.stationsScanned()).isEqualTo(1);
        assertThat(result.pointsWritten()).isEqualTo(2);
    }

    @Test
    @DisplayName("未取到锁 → 本轮跳过且不查询任何站点")
    void shouldSkipWhenLockNotAcquired() {
        when(valueOperations.setIfAbsent(anyString(), anyString(), any(Duration.class)))
                .thenReturn(false);

        MissingDataInterpolator.InterpolationResult result = interpolator.interpolateOnce();

        assertThat(result.skippedByLock()).isTrue();
        assertThat(result.stationsScanned()).isZero();
        verify(stationMapper, never()).selectList(any());
    }

    private void stubStations(Station... stations) {
        lenient().when(stationMapper.selectList(any())).thenReturn(List.of(stations));
    }

    private void stubObserved(String stationCode, List<ObsData> data) {
        when(obsReader.queryObservedRange(eq(stationCode), any(), any())).thenReturn(data);
    }

    private void stubOccupied(Integer... offsets) {
        Set<Instant> times = new LinkedHashSet<>();
        for (Integer offset : offsets) {
            times.add(START.plusSeconds(offset));
        }
        lenient().when(obsReader.queryOccupiedTimestamps(anyString(), any(), any())).thenReturn(times);
    }

    private List<ObsData> captureWritten(int expected) {
        ArgumentCaptor<ObsData> captor = ArgumentCaptor.forClass(ObsData.class);
        verify(obsWriter, org.mockito.Mockito.times(expected)).writeObs(captor.capture());
        return captor.getAllValues();
    }

    private ObsData obs(int offsetSeconds, double temp) {
        Map<String, Double> elements = new LinkedHashMap<>();
        elements.put("temp", temp);
        return ObsData.builder()
                .stationCode(STATION)
                .ts(START.plusSeconds(offsetSeconds))
                .elements(elements)
                .build();
    }

    private Station station(String code, Long id) {
        Station station = new Station();
        station.setId(id);
        station.setStationCode(code);
        station.setStatus(1);
        station.setOnlineFlag(1);
        return station;
    }
}