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

import java.time.Duration;
import java.time.Instant;
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
                stationMapper, redisTemplate, mqProducer);
        lenient().when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        lenient().when(valueOperations.setIfAbsent(anyString(), anyString(), any(Duration.class)))
                .thenReturn(true);
    }

    @Test
    @DisplayName("要素均在有效区间内 → 标记 passed 并分发到告警链路")
    void shouldMarkPassedAndDispatchWhenAllChecksPass() throws Exception {
        stubTempThresholds(40.0, -10.0, 5.0);
        when(obsReader.queryLastElementValue(eq(STATION), eq("temp"), any())).thenReturn(24.0);

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
        // 显式返回 null 表示「无上一时点值」。注意 Mockito 对 Double 返回类型默认给 0.0
        // 而非 null，若不显式打桩会凭空多出一条时间一致性超限理由。
        when(obsReader.queryLastElementValue(eq(STATION), eq("temp"), any())).thenReturn(null);
        stubStation();

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
    @DisplayName("时间一致性变化率超限 → 标记 suspect 且审核任务能取到要素名")
    void shouldCreateReviewTaskForTimeConsistencyViolation() throws Exception {
        stubTempThresholds(40.0, -10.0, 5.0);
        // 上一时点 10℃，本次 25℃，变化 15℃ 超过 change.max=5
        when(obsReader.queryLastElementValue(eq(STATION), eq("temp"), any())).thenReturn(10.0);
        stubStation();

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
    @DisplayName("msgId 重复 → 直接 ack，不重复入库与分发")
    void shouldSkipDuplicatedMessage() throws Exception {
        when(valueOperations.setIfAbsent(anyString(), anyString(), any(Duration.class))).thenReturn(false);

        qcAgent.onRawData(obs(Map.of("temp", 25.0)), channel, DELIVERY_TAG);

        verify(obsWriter, never()).writeObs(any());
        verify(mqProducer, never()).send(anyString(), any());
        verify(channel).basicAck(DELIVERY_TAG, false);
    }

    private void stubTempThresholds(double max, double min, double changeMax) {
        when(thresholdService.getThreshold(eq("qc.temp.max"), anyDouble())).thenReturn(max);
        when(thresholdService.getThreshold(eq("qc.temp.min"), anyDouble())).thenReturn(min);
        when(thresholdService.getThreshold(eq("qc.temp.change.max"), anyDouble())).thenReturn(changeMax);
    }

    private void stubStation() {
        Station station = new Station();
        station.setId(9L);
        when(stationMapper.selectOne(any())).thenReturn(station);
    }

    private QcReviewTask captureSingleReviewTask() {
        ArgumentCaptor<QcReviewTask> captor = ArgumentCaptor.forClass(QcReviewTask.class);
        verify(qcReviewTaskMapper).insert(captor.capture());
        return captor.getValue();
    }

    private ObsData obs(Map<String, Double> elements) {
        return ObsData.builder()
                .stationCode(STATION)
                .ts(Instant.parse("2026-09-19T04:00:00Z"))
                .elements(elements)
                .msgId("msg-qc-1")
                .build();
    }
}