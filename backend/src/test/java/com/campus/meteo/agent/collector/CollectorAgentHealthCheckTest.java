package com.campus.meteo.agent.collector;

import com.campus.meteo.influx.ObsWriter;
import com.campus.meteo.mapper.StationMapper;
import com.campus.meteo.mq.MqProducer;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.eclipse.paho.client.mqttv3.IMqttMessageListener;
import org.eclipse.paho.client.mqttv3.MqttClient;
import org.eclipse.paho.client.mqttv3.MqttException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 采集链路体检兜底单测（FR-QC-01）。
 *
 * 体检逻辑依赖 MqttClient 的连接状态与「最近收到上行消息的时刻」，
 * 这里用 mock 客户端 + 反射设置内部状态来驱动各分支。
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("采集链路体检兜底")
class CollectorAgentHealthCheckTest {

    private static final String TOPIC = "meteo/+/up";

    @Mock
    private MqttClient mqttClient;
    @Mock
    private ObsWriter obsWriter;
    @Mock
    private MqProducer mqProducer;
    @Mock
    private StationMapper stationMapper;

    private MqttProperties properties;
    private CollectorAgent agent;

    @BeforeEach
    void setUp() {
        properties = new MqttProperties();
        properties.setBroker("tcp://localhost:1883");
        properties.setTopic(TOPIC);
        properties.setStaleThresholdMs(600_000);
        properties.setManualReconnectAfterMs(300_000);
        agent = new CollectorAgent(properties, obsWriter, mqProducer, stationMapper, new ObjectMapper());
        ReflectionTestUtils.setField(agent, "mqttClient", mqttClient);
    }

    @Test
    @DisplayName("连接正常且近期有消息 → 不做任何动作")
    void shouldDoNothingWhenHealthy() {
        when(mqttClient.isConnected()).thenReturn(true);
        setLastMessageAt(System.currentTimeMillis() - 1_000);

        agent.healthCheck();

        assertThatNoResubscribe();
    }

    @Test
    @DisplayName("从未通过 MQTT 收到过消息 → 不判定订阅失效（无法区分无站点上报与订阅丢失）")
    void shouldSkipStaleCheckWhenNeverReceivedMessage() {
        when(mqttClient.isConnected()).thenReturn(true);
        // lastMqttMessageAt 保持默认 0
        // 开发环境模拟器直接调用处理逻辑、不经 MQTT，正属此情形，不应误报
        agent.healthCheck();

        assertThatNoResubscribe();
    }

    @Test
    @DisplayName("连接正常但长时间无上行消息 → 主动重订阅")
    void shouldResubscribeWhenSilentTooLong() throws Exception {
        when(mqttClient.isConnected()).thenReturn(true);
        setLastMessageAt(System.currentTimeMillis() - properties.getStaleThresholdMs() - 1_000);

        agent.healthCheck();

        verify(mqttClient).subscribe(eq(TOPIC), eq(1), any(IMqttMessageListener.class));
    }

    @Test
    @DisplayName("同一段静默期内不重复重订阅（避免刷日志）")
    void shouldNotResubscribeRepeatedlyWithinSameSilentPeriod() throws Exception {
        when(mqttClient.isConnected()).thenReturn(true);
        setLastMessageAt(System.currentTimeMillis() - properties.getStaleThresholdMs() - 1_000);

        agent.healthCheck();
        agent.healthCheck();
        agent.healthCheck();

        verify(mqttClient, times(1)).subscribe(eq(TOPIC), eq(1), any(IMqttMessageListener.class));
    }

    @Test
    @DisplayName("刚断开 → 只记录并等待自动重连，不立即主动重连")
    void shouldWaitForAutomaticReconnectRightAfterDisconnect() throws Exception {
        when(mqttClient.isConnected()).thenReturn(false);

        agent.healthCheck();

        verify(mqttClient, never()).reconnect();
    }

    @Test
    @DisplayName("断开超过上限仍未恢复 → 主动重连并恢复订阅")
    void shouldReconnectManuallyWhenDownTooLong() throws Exception {
        when(mqttClient.isConnected()).thenReturn(false);
        // 首次体检记录断开起点，之后把起点前移，模拟已断开很久
        agent.healthCheck();
        ReflectionTestUtils.setField(agent, "disconnectedSince",
                System.currentTimeMillis() - properties.getManualReconnectAfterMs() - 1_000);

        agent.healthCheck();

        verify(mqttClient).reconnect();
        verify(mqttClient).subscribe(eq(TOPIC), eq(1), any(IMqttMessageListener.class));
    }

    @Test
    @DisplayName("主动重连失败 → 不抛出异常，下轮体检继续尝试")
    void shouldNotPropagateExceptionWhenManualReconnectFails() throws Exception {
        when(mqttClient.isConnected()).thenReturn(false);
        doThrow(new MqttException(MqttException.REASON_CODE_CLIENT_EXCEPTION))
                .when(mqttClient).reconnect();
        agent.healthCheck();
        ReflectionTestUtils.setField(agent, "disconnectedSince",
                System.currentTimeMillis() - properties.getManualReconnectAfterMs() - 1_000);

        // 定时任务中抛异常会中断后续调度，必须自行吞掉
        assertThatCode(() -> agent.healthCheck()).doesNotThrowAnyException();
    }

    @Test
    @DisplayName("重订阅失败 → 不抛出异常")
    void shouldNotPropagateExceptionWhenResubscribeFails() throws Exception {
        when(mqttClient.isConnected()).thenReturn(true);
        doThrow(new MqttException(MqttException.REASON_CODE_CLIENT_EXCEPTION))
                .when(mqttClient).subscribe(anyString(), anyInt(), any(IMqttMessageListener.class));
        setLastMessageAt(System.currentTimeMillis() - properties.getStaleThresholdMs() - 1_000);

        assertThatCode(() -> agent.healthCheck()).doesNotThrowAnyException();
    }

    @Test
    @DisplayName("客户端尚未初始化（启动失败场景）→ 体检直接返回")
    void shouldReturnWhenClientNotInitialized() {
        ReflectionTestUtils.setField(agent, "mqttClient", null);

        assertThatCode(() -> agent.healthCheck()).doesNotThrowAnyException();
    }

    private void setLastMessageAt(long timestamp) {
        ReflectionTestUtils.setField(agent, "lastMqttMessageAt", timestamp);
    }

    private void assertThatNoResubscribe() {
        assertThatCode(() -> verify(mqttClient, never())
                .subscribe(anyString(), anyInt(), any(IMqttMessageListener.class)))
                .doesNotThrowAnyException();
    }
}