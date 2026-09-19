package com.campus.meteo.agent.qc;

import com.campus.meteo.common.constant.MqTopics;
import com.campus.meteo.common.constant.QcFlag;
import com.campus.meteo.entity.QcReviewTask;
import com.campus.meteo.entity.Station;
import com.campus.meteo.influx.ObsData;
import com.campus.meteo.influx.ObsReader;
import com.campus.meteo.influx.ObsWriter;
import com.campus.meteo.mapper.QcReviewTaskMapper;
import com.campus.meteo.mapper.StationMapper;
import com.campus.meteo.mq.MqProducer;
import com.rabbitmq.client.Channel;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyDouble;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 质控 Agent 判定逻辑单测（AGENTS.md 6.1 点名要求覆盖的核心逻辑）。
 * 走 onRawData 公开入口，用 Mockito 隔离 InfluxDB / Redis / MQ / Mapper，验证真实判定行为。
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("质控 Agent 判定逻辑")
class QcAgentTest {

    private static final long DELIVERY_TAG = 1L;
    private static final String STATION = "CAMPUS01";
    private static final String NEIGHBOR = "FARM02";
    private static final Instant OBS_TS = Instant.parse("2026-09-19T04:00:00Z");

    @Mock
    private ObsWriter obsWriter;
    @Mock
    private ObsReader obsReader;
    @Mock
    private QcThresholdService thresholdService;
    @Mock
    private QcReviewTaskMapper qcReviewTaskMapper;
    @Mock
    private StationMapper stationMapper;
    @Mock
    private StringRedisTemplate redisTemplate;
    @Mock
    private ValueOperations<String, String> valueOperations;
    @Mock
    private MqProducer mqProducer;
    @Mock
    private Channel channel;

    private QcAgent qcAgent;

    @BeforeEach
    void setUp() {
        qcAgent = new QcAgent(obsWriter, obsReader, thresholdService, qcReviewTaskMapper,
                stationMapper, redisTemplate, mqProducer, new InterpolationService());
        lenient().when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        lenient().when(valueOperations.setIfAbsent(anyString(), anyString(), any(Duration.class)))
                .thenReturn(true);
        lenient().when(stationMapper.selectList(any())).thenReturn(List.of(selfStation(), neighborStation()));
        lenient().when(obsReader.queryLastAcceptedValues(anyString(), any(), any())).thenReturn(Map.of());
        lenient().when(obsReader.queryObservedRange(anyString(), any(), any())).thenReturn(List.of());
        // 默认关闭全部检验阈值，避免 Mockito 对 double 返回 0.0 把正常值判成超限；
        // 需要验证某一类检查的用例自行打桩覆盖（后定义的 lenient 桩优先）。
        // 注意：只对「偏差/极值阈值」（键以 .max/.min 结尾）返回无限大以禁用；
        // 空间一致性的容差与距离会被强转 long 参与计算，返回无限大会溢出成负值导致 Instant 异常，
        // 因此给个不会误伤的正常缺省。
        lenient().when(thresholdService.getThreshold(anyString(), anyDouble()))
                .thenAnswer(invocation -> {
                    String key = invocation.getArgument(0);
                    if (key.endsWith("qc.spatial.match.tolerance.seconds")) {
                        return 300.0;
                    }
                    if (key.endsWith("qc.spatial.neighbor.max.distance.km")) {
                        return 50.0;
                    }
                    if (key.endsWith(".min")) {
                        return -Double.MAX_VALUE;
                    }
                    return Double.MAX_VALUE;
                });
    }

    @Test
    @DisplayName("要素均在有效区间内 → 标记 passed 并分发到告警链路")
    void shouldMarkPassedAndDispatchWhenAllChecksPass() throws Exception {
        stubTempThresholds(40.0, -10.0, 5.0);
        when(obsReader.queryLastAcceptedValues(eq(STATION), any(), any())).thenReturn(Map.of("temp", 24.0));

        ObsData obs = obs(Map.of("temp", 25.0));

        qcAgent.onRawData(obs, channel, DELIVERY_TAG);

        assertThat(obs.getQcFlag()).isEqualTo(QcFlag.PASSED.getValue());
        verify(obsWriter).writeObs(obs);
        verify(qcReviewTaskMapper, never()).insert(any(QcReviewTask.class));
        verify(mqProducer).send(eq(MqTopics.METEO_QC), eq(obs));
        verify(channel).basicAck(DELIVERY_TAG, false);
    }

    @Test
    @DisplayName("要素超出有效区间 → 标记 suspect、生成极值审核任务且不进告警链路")
    void shouldMarkSuspectAndCreateExtremeReviewTaskWhenOutOfRange() throws Exception {
        stubTempThresholds(40.0, -10.0, 5.0);

        ObsData obs = obs(Map.of("temp", 55.0));

        qcAgent.onRawData(obs, channel, DELIVERY_TAG);

        assertThat(obs.getQcFlag()).isEqualTo(QcFlag.SUSPECT.getValue());
        QcReviewTask task = captureSingleReviewTask();
        assertThat(task.getElement()).isEqualTo("temp");
        assertThat(task.getQcType()).isEqualTo(1);
        assertThat(task.getObsValue()).isEqualByComparingTo("55.0");
        assertThat(task.getStatus()).isZero();
        // 可疑数据不得进入告警链路，须由人工审核闭环
        verify(mqProducer, never()).send(anyString(), any());
    }

    @Test
    @DisplayName("时间一致性变化量超限 → 标记 suspect 且审核任务能取到要素名")
    void shouldCreateReviewTaskForTimeConsistencyViolation() throws Exception {
        stubTempThresholds(40.0, -10.0, 5.0);
        // 上一时点 10℃，本次 25℃，变化 15℃ 超过 change.max=5
        when(obsReader.queryLastAcceptedValues(eq(STATION), any(), any())).thenReturn(Map.of("temp", 10.0));

        ObsData obs = obs(Map.of("temp", 25.0));

        qcAgent.onRawData(obs, channel, DELIVERY_TAG);

        assertThat(obs.getQcFlag()).isEqualTo(QcFlag.SUSPECT.getValue());
        QcReviewTask task = captureSingleReviewTask();
        // 回归点：详情文案为 "时间一致性[temp: 10.0 → 25.0, ...]"，无 '=' 分隔符
        assertThat(task.getElement()).isEqualTo("temp");
        assertThat(task.getQcType()).isEqualTo(2);
        assertThat(task.getQcDetail()).contains("时间一致性");
    }

    @Test
    @DisplayName("时间一致性覆盖全部要素：湿度超限同样标记可疑")
    void shouldCheckTimeConsistencyForNonTemperatureElements() throws Exception {
        // 只给湿度配阈值，气温阈值取默认的 MAX_VALUE（不检查）
        lenient().when(thresholdService.getThreshold(eq("qc.humi.change.max"), anyDouble())).thenReturn(15.0);
        when(obsReader.queryLastAcceptedValues(eq(STATION), any(), any())).thenReturn(Map.of("humi", 40.0));

        ObsData obs = obs(Map.of("humi", 80.0));

        qcAgent.onRawData(obs, channel, DELIVERY_TAG);

        assertThat(obs.getQcFlag()).isEqualTo(QcFlag.SUSPECT.getValue());
        QcReviewTask task = captureSingleReviewTask();
        assertThat(task.getElement()).isEqualTo("humi");
        assertThat(task.getQcType()).isEqualTo(2);
    }

    @Test
    @DisplayName("时间一致性查询带上界（排除本条自身），否则比较值恒等于当前值永不触发")
    void shouldQueryPreviousValuesWithUpperBound() throws Exception {
        stubTempThresholds(40.0, -10.0, 5.0);

        qcAgent.onRawData(obs(Map.of("temp", 25.0)), channel, DELIVERY_TAG);

        ArgumentCaptor<Instant> startCaptor = ArgumentCaptor.forClass(Instant.class);
        ArgumentCaptor<Instant> stopCaptor = ArgumentCaptor.forClass(Instant.class);
        verify(obsReader).queryLastAcceptedValues(eq(STATION), startCaptor.capture(), stopCaptor.capture());
        assertThat(stopCaptor.getValue()).isEqualTo(OBS_TS);
        assertThat(startCaptor.getValue()).isEqualTo(OBS_TS.minusSeconds(2 * 3600));
    }

    @Test
    @DisplayName("风向不参与时间一致性判定（圆周量差值无意义）")
    void shouldSkipCircularElementInTimeConsistency() throws Exception {
        lenient().when(thresholdService.getThreshold(eq("qc.wind_dir.change.max"), anyDouble())).thenReturn(20.0);
        when(obsReader.queryLastAcceptedValues(eq(STATION), any(), any())).thenReturn(Map.of("wind_dir", 359.0));

        qcAgent.onRawData(obs(Map.of("wind_dir", 1.0)), channel, DELIVERY_TAG);

        // 359° 与 1° 实际只差 2°，不应被判为超限
        assertThat(capturedQcFlag()).isEqualTo(QcFlag.PASSED.getValue());
        verify(qcReviewTaskMapper, never()).insert(any(QcReviewTask.class));
    }

    @Test
    @DisplayName("空间一致性：与邻站同时刻偏差超限 → 标记 suspect 且审核类型为 3")
    void shouldCreateSpatialReviewTaskWhenDeviationExceedsThreshold() throws Exception {
        // 只给气温配空间阈值
        lenient().when(thresholdService.getThreshold(eq("qc.temp.spatial.max"), anyDouble())).thenReturn(5.0);
        lenient().when(thresholdService.getThreshold(eq("qc.spatial.match.tolerance.seconds"), anyDouble()))
                .thenReturn(300.0);
        lenient().when(thresholdService.getThreshold(eq("qc.spatial.neighbor.max.distance.km"), anyDouble()))
                .thenReturn(50.0);
        when(obsReader.queryObservedRange(eq(NEIGHBOR), any(), any()))
                .thenReturn(List.of(neighborObs(Map.of("temp", 20.0))));

        ObsData obs = obs(Map.of("temp", 30.0));

        qcAgent.onRawData(obs, channel, DELIVERY_TAG);

        assertThat(obs.getQcFlag()).isEqualTo(QcFlag.SUSPECT.getValue());
        QcReviewTask task = captureSingleReviewTask();
        assertThat(task.getElement()).isEqualTo("temp");
        assertThat(task.getQcType()).isEqualTo(3);
        assertThat(task.getQcDetail()).contains("空间一致性");
    }

    @Test
    @DisplayName("空间一致性：偏差在阈值内 → 不标记可疑")
    void shouldPassWhenSpatialDeviationWithinThreshold() throws Exception {
        lenient().when(thresholdService.getThreshold(eq("qc.temp.spatial.max"), anyDouble())).thenReturn(5.0);
        lenient().when(thresholdService.getThreshold(eq("qc.spatial.match.tolerance.seconds"), anyDouble()))
                .thenReturn(300.0);
        lenient().when(thresholdService.getThreshold(eq("qc.spatial.neighbor.max.distance.km"), anyDouble()))
                .thenReturn(50.0);
        when(obsReader.queryObservedRange(eq(NEIGHBOR), any(), any()))
                .thenReturn(List.of(neighborObs(Map.of("temp", 27.0))));

        ObsData obs = obs(Map.of("temp", 30.0));

        qcAgent.onRawData(obs, channel, DELIVERY_TAG);

        assertThat(obs.getQcFlag()).isEqualTo(QcFlag.PASSED.getValue());
        verify(qcReviewTaskMapper, never()).insert(any(QcReviewTask.class));
    }

    @Test
    @DisplayName("空间一致性：邻站超出距离上限 → 跳过检查")
    void shouldSkipSpatialCheckWhenNeighborTooFar() throws Exception {
        lenient().when(thresholdService.getThreshold(eq("qc.temp.spatial.max"), anyDouble())).thenReturn(5.0);
        // 距离上限设为 1 公里，而两演示站点相距约 18 公里
        when(thresholdService.getThreshold(eq("qc.spatial.neighbor.max.distance.km"), anyDouble()))
                .thenReturn(1.0);

        ObsData obs = obs(Map.of("temp", 30.0));

        qcAgent.onRawData(obs, channel, DELIVERY_TAG);

        assertThat(obs.getQcFlag()).isEqualTo(QcFlag.PASSED.getValue());
        // 未找到可用邻站时不应发起邻站数据查询
        verify(obsReader, never()).queryObservedRange(eq(NEIGHBOR), any(), any());
    }

    @Test
    @DisplayName("尖峰被拒后，紧随其后的正常值不因比较基准被污染而误判")
    void shouldNotCascadeMisjudgementAfterRejectedSpike() throws Exception {
        stubTempThresholds(40.0, -10.0, 5.0);
        // 基准查询只认 passed/revised，尖峰(-55℃)被极值拒绝后不会成为基准，
        // 这里模拟基准仍是尖峰之前的正常值 20℃，本条 21℃ 属正常波动
        when(obsReader.queryLastAcceptedValues(eq(STATION), any(), any())).thenReturn(Map.of("temp", 20.0));

        ObsData obs = obs(Map.of("temp", 21.0));

        qcAgent.onRawData(obs, channel, DELIVERY_TAG);

        assertThat(capturedQcFlag()).isEqualTo(QcFlag.PASSED.getValue());
        verify(qcReviewTaskMapper, never()).insert(any(QcReviewTask.class));
    }

    @Test
    @DisplayName("msgId 重复 → 直接 ack，不重复入库与分发")
    void shouldSkipDuplicatedMessage() throws Exception {
        when(valueOperations.setIfAbsent(anyString(), anyString(), any(Duration.class))).thenReturn(false);

        qcAgent.onRawData(obs(Map.of("temp", 25.0)), channel, DELIVERY_TAG);

        verify(obsWriter, never()).writeObs(any());
        verify(mqProducer, never()).send(anyString(), any());
        verify(channel).basicAck(DELIVERY_TAG, false);
    }

    private void stubTempThresholds(double max, double min, double changeMax) {
        // 时间一致性可能在上一时点值为空时提前返回，导致部分阈值桩在个别用例中未被使用，
        // 统一用 lenient() 避免 strict-stubbing 的 UnnecessaryStubbing 误报
        lenient().when(thresholdService.getThreshold(eq("qc.temp.max"), anyDouble())).thenReturn(max);
        lenient().when(thresholdService.getThreshold(eq("qc.temp.min"), anyDouble())).thenReturn(min);
        lenient().when(thresholdService.getThreshold(eq("qc.temp.change.max"), anyDouble())).thenReturn(changeMax);
    }

    private String capturedQcFlag() {
        ArgumentCaptor<ObsData> captor = ArgumentCaptor.forClass(ObsData.class);
        verify(obsWriter).writeObs(captor.capture());
        return captor.getValue().getQcFlag();
    }

    private QcReviewTask captureSingleReviewTask() {
        ArgumentCaptor<QcReviewTask> captor = ArgumentCaptor.forClass(QcReviewTask.class);
        verify(qcReviewTaskMapper).insert(captor.capture());
        return captor.getValue();
    }

    private ObsData obs(Map<String, Double> elements) {
        return ObsData.builder()
                .stationCode(STATION)
                .ts(OBS_TS)
                .elements(elements)
                .msgId("msg-qc-1")
                .build();
    }

    private ObsData neighborObs(Map<String, Double> elements) {
        return ObsData.builder()
                .stationCode(NEIGHBOR)
                .ts(OBS_TS)
                .elements(elements)
                .build();
    }

    private Station selfStation() {
        Station station = new Station();
        station.setId(9L);
        station.setStationCode(STATION);
        station.setStatus(1);
        station.setLongitude(new BigDecimal("118.914000"));
        station.setLatitude(new BigDecimal("32.103000"));
        return station;
    }

    private Station neighborStation() {
        Station station = new Station();
        station.setId(10L);
        station.setStationCode(NEIGHBOR);
        station.setStatus(1);
        station.setLongitude(new BigDecimal("118.842000"));
        station.setLatitude(new BigDecimal("31.953000"));
        return station;
    }
}