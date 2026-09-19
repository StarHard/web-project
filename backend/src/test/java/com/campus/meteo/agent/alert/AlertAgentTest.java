package com.campus.meteo.agent.alert;

import com.campus.meteo.entity.AlertNotify;
import com.campus.meteo.entity.AlertRecord;
import com.campus.meteo.entity.AlertRule;
import com.campus.meteo.influx.ObsData;
import com.campus.meteo.influx.ObsReader;
import com.campus.meteo.mapper.AlertNotifyMapper;
import com.campus.meteo.mapper.AlertRecordMapper;
import com.campus.meteo.service.SysConfigService;
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
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 告警 Agent 判定与升级逻辑单测（AGENTS.md 6.1 点名要求覆盖的核心逻辑）。
 * 走 onQcData 公开入口，验证阈值判定、抑制窗口、等级升级与自动解除四类行为。
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("告警 Agent 判定与升级逻辑")
class AlertAgentTest {

    private static final long DELIVERY_TAG = 2L;
    private static final String STATION = "CAMPUS01";
    private static final long STATION_ID = 1L;
    private static final int SUPPRESS_MINUTES = 30;

    @Mock
    private AlertContextCache contextCache;
    @Mock
    private AlertRecordMapper alertRecordMapper;
    @Mock
    private AlertNotifyMapper alertNotifyMapper;
    @Mock
    private ObsReader obsReader;
    @Mock
    private SysConfigService sysConfigService;
    @Mock
    private AlertWebSocketHandler alertWebSocketHandler;
    @Mock
    private StringRedisTemplate redisTemplate;
    @Mock
    private ValueOperations<String, String> valueOperations;
    @Mock
    private Channel channel;

    private AlertAgent alertAgent;

    @BeforeEach
    void setUp() {
        alertAgent = new AlertAgent(contextCache, alertRecordMapper, alertNotifyMapper, obsReader,
                sysConfigService, alertWebSocketHandler, redisTemplate);
        lenient().when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        lenient().when(valueOperations.setIfAbsent(anyString(), anyString(), any(Duration.class)))
                .thenReturn(true);
        lenient().when(sysConfigService.getInt(eq("alert.suppress.minutes"), anyInt()))
                .thenReturn(SUPPRESS_MINUTES);
        lenient().when(contextCache.getStationCode(anyLong())).thenReturn(STATION);
    }

    @Test
    @DisplayName("条件「大于」且超阈值 → 新建进行中告警并分发通知")
    void shouldCreateAlertWhenValueExceedsThreshold() throws Exception {
        AlertRule rule = rule(1L, "temp", 1, 30.0, 1, null, "web");
        stubSingleRule(rule);
        when(alertRecordMapper.selectOne(any())).thenReturn(null);

        alertAgent.onQcData(obs(Map.of("temp", 35.0)), channel, DELIVERY_TAG);

        AlertRecord saved = captureInsertedAlert();
        assertThat(saved.getLevel()).isEqualTo(1);
        assertThat(saved.getStatus()).isZero();
        assertThat(saved.getObsValue()).isEqualByComparingTo("35.0");
        assertThat(saved.getContent()).contains("暴雨蓝色预警");
        verify(alertNotifyMapper).insert(any(AlertNotify.class));
        verify(alertWebSocketHandler).broadcast(eq(STATION), eq(1), anyString(), anyString());
        verify(channel).basicAck(DELIVERY_TAG, false);
    }

    @Test
    @DisplayName("条件「小于」且低于阈值 → 触发告警")
    void shouldCreateAlertWhenValueBelowThreshold() throws Exception {
        AlertRule rule = rule(2L, "wind_speed", 2, 5.0, 2, null, "web");
        stubSingleRule(rule);
        when(alertRecordMapper.selectOne(any())).thenReturn(null);

        alertAgent.onQcData(obs(Map.of("wind_speed", 3.0)), channel, DELIVERY_TAG);

        assertThat(captureInsertedAlert().getLevel()).isEqualTo(2);
    }

    @Test
    @DisplayName("未超阈值 → 不产生告警")
    void shouldNotAlertWhenThresholdNotExceeded() throws Exception {
        stubSingleRule(rule(1L, "temp", 1, 30.0, 1, null, "web"));

        alertAgent.onQcData(obs(Map.of("temp", 25.0)), channel, DELIVERY_TAG);

        verify(alertRecordMapper, never()).insert(any(AlertRecord.class));
        verify(alertWebSocketHandler, never()).broadcast(anyString(), anyInt(), anyString(), anyString());
    }

    @Test
    @DisplayName("条件「持续超限」且窗口内 ≥80% 时次超阈值 → 触发告警")
    void shouldAlertWhenSustainedExceedReachesEightyPercent() throws Exception {
        AlertRule rule = rule(3L, "rain", 3, 20.0, 3, 60, "web");
        stubSingleRule(rule);
        when(alertRecordMapper.selectOne(any())).thenReturn(null);
        // 5 个时次中 4 个超阈值 = 80%。持续超限判定走「仅观测值」查询，不含插补合成值
        when(obsReader.queryObservedRange(eq(STATION), any(), any())).thenReturn(List.of(
                obsAt(Instant.parse("2026-09-19T00:00:00Z"), Map.of("rain", 30.0)),
                obsAt(Instant.parse("2026-09-19T00:10:00Z"), Map.of("rain", 30.0)),
                obsAt(Instant.parse("2026-09-19T00:20:00Z"), Map.of("rain", 30.0)),
                obsAt(Instant.parse("2026-09-19T00:30:00Z"), Map.of("rain", 25.0)),
                obsAt(Instant.parse("2026-09-19T00:40:00Z"), Map.of("rain", 5.0))));

        alertAgent.onQcData(obsAt(Instant.parse("2026-09-19T00:40:00Z"), Map.of("rain", 25.0)),
                channel, DELIVERY_TAG);

        assertThat(captureInsertedAlert().getLevel()).isEqualTo(3);
    }

    @Test
    @DisplayName("条件「持续超限」但窗口样本不足 2 条 → 不触发")
    void shouldNotAlertWhenSustainedWindowHasTooFewSamples() throws Exception {
        AlertRule rule = rule(3L, "rain", 3, 20.0, 3, 60, "web");
        stubSingleRule(rule);
        when(obsReader.queryObservedRange(eq(STATION), any(), any()))
                .thenReturn(List.of(obsAt(Instant.parse("2026-09-19T00:00:00Z"), Map.of("rain", 30.0))));

        alertAgent.onQcData(obsAt(Instant.parse("2026-09-19T00:10:00Z"), Map.of("rain", 30.0)),
                channel, DELIVERY_TAG);

        verify(alertRecordMapper, never()).insert(any(AlertRecord.class));
    }

    @Test
    @DisplayName("抑制窗口内的重复触发 → 忽略，不新建也不升级")
    void shouldIgnoreTriggerInsideSuppressWindow() throws Exception {
        stubSingleRule(rule(1L, "temp", 1, 30.0, 1, 3, "web"));
        AlertRecord active = activeRecord(1L, 1, LocalDateTime.now().minusMinutes(5));
        when(alertRecordMapper.selectOne(any())).thenReturn(active);

        alertAgent.onQcData(obs(Map.of("temp", 35.0)), channel, DELIVERY_TAG);

        verify(alertRecordMapper, never()).insert(any(AlertRecord.class));
        verify(alertRecordMapper, never()).updateById(any(AlertRecord.class));
    }

    @Test
    @DisplayName("超出抑制窗口且规则配置更高等级 → 原告警置为已升级并新建高等级告警")
    void shouldUpgradeActiveAlertBeyondSuppressWindow() throws Exception {
        stubSingleRule(rule(1L, "temp", 1, 30.0, 1, 3, "web"));
        AlertRecord active = activeRecord(1L, 1, LocalDateTime.now().minusMinutes(60));
        when(alertRecordMapper.selectOne(any())).thenReturn(active);

        alertAgent.onQcData(obs(Map.of("temp", 35.0)), channel, DELIVERY_TAG);

        assertThat(active.getStatus()).isEqualTo(2);
        verify(alertRecordMapper).updateById(active);
        AlertRecord upgraded = captureInsertedAlert();
        assertThat(upgraded.getLevel()).isEqualTo(3);
        assertThat(upgraded.getContent()).contains("预警升级");
    }

    @Test
    @DisplayName("未知站点 → 跳过判定")
    void shouldSkipUnknownStation() throws Exception {
        when(contextCache.getStationId("GHOST01")).thenReturn(null);

        ObsData ghost = ObsData.builder()
                .stationCode("GHOST01")
                .ts(Instant.parse("2026-09-19T04:00:00Z"))
                .elements(Map.of("temp", 99.0))
                .msgId("msg-alert-ghost")
                .build();
        alertAgent.onQcData(ghost, channel, DELIVERY_TAG);

        verify(alertRecordMapper, never()).insert(any(AlertRecord.class));
        verify(channel).basicAck(DELIVERY_TAG, false);
    }

    @Test
    @DisplayName("本轮未触发且超出抑制窗口 → 自动解除进行中告警")
    void shouldRelieveRecoveredAlert() throws Exception {
        when(contextCache.getStationId(STATION)).thenReturn(STATION_ID);
        when(contextCache.getEnabledRules()).thenReturn(List.of());
        AlertRecord active = activeRecord(1L, 1, LocalDateTime.now().minusMinutes(60));
        when(alertRecordMapper.selectList(any())).thenReturn(List.of(active));

        alertAgent.onQcData(obs(Map.of("temp", 25.0)), channel, DELIVERY_TAG);

        assertThat(active.getStatus()).isEqualTo(1);
        assertThat(active.getRelieveTime()).isNotNull();
        verify(alertRecordMapper).updateById(active);
    }

    /** 单条规则作用于站点的常规桩：站点可解析 + 启用规则一条 */
    private void stubSingleRule(AlertRule rule) {
        when(contextCache.getStationId(STATION)).thenReturn(STATION_ID);
        when(contextCache.getEnabledRules()).thenReturn(List.of(rule));
    }

    private AlertRecord captureInsertedAlert() {
        ArgumentCaptor<AlertRecord> captor = ArgumentCaptor.forClass(AlertRecord.class);
        verify(alertRecordMapper).insert(captor.capture());
        return captor.getValue();
    }

    private AlertRecord activeRecord(Long ruleId, int level, LocalDateTime alertTime) {
        AlertRecord record = new AlertRecord();
        record.setId(100L);
        record.setRuleId(ruleId);
        record.setStationId(STATION_ID);
        record.setLevel(level);
        record.setAlertTime(alertTime);
        record.setStatus(0);
        return record;
    }

    private AlertRule rule(Long id, String element, int condition, double threshold,
                           int level, Integer upgradeLevel, String channels) {
        AlertRule rule = new AlertRule();
        rule.setId(id);
        rule.setStationId(0L);
        rule.setAlertType(1);
        rule.setElement(element);
        rule.setCondition(condition);
        rule.setThreshold(BigDecimal.valueOf(threshold));
        rule.setDurationMin(60);
        rule.setLevel(level);
        rule.setUpgradeLevel(upgradeLevel);
        rule.setChannels(channels);
        rule.setStatus(1);
        return rule;
    }

    private ObsData obs(Map<String, Double> elements) {
        return obsAt(Instant.parse("2026-09-19T01:00:00Z"), elements);
    }

    private ObsData obsAt(Instant ts, Map<String, Double> elements) {
        return ObsData.builder()
                .stationCode(STATION)
                .ts(ts)
                .elements(elements)
                .msgId("msg-alert-" + ts.getEpochSecond())
                .build();
    }
}