package com.campus.meteo.agent.assistant;

import com.campus.meteo.dto.AssistantResp;
import com.campus.meteo.dto.ToolInvocation;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Answers;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 决策智能体单测：重点覆盖大模型不可用时的降级路径。
 * 大模型是外部依赖，故障不能让助手接口整体失败——演示与生产都需要前端能正常渲染。
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("决策智能体问答")
class MeteoAssistantAgentTest {

    private static final String QUESTION = "现在主校区站气温多少？";

    @Mock(answer = Answers.RETURNS_DEEP_STUBS)
    private ChatClient chatClient;

    @Mock
    private ToolCallRecorder toolCallRecorder;

    @InjectMocks
    private MeteoAssistantAgent agent;

    @Test
    @DisplayName("正常调用：返回大模型回答、标记 source=llm 并带回工具调用留痕")
    void shouldReturnLlmAnswer() {
        ReflectionTestUtils.setField(agent, "model", "deepseek-chat");
        when(chatClient.prompt().user(QUESTION).call().content()).thenReturn("主校区站气温 26.4℃。");
        when(toolCallRecorder.drain())
                .thenReturn(List.of(new ToolInvocation("queryRealtime", "站点 CAMPUS01", "气温(℃) 26.4")));

        AssistantResp resp = agent.ask(QUESTION);

        assertThat(resp.getSource()).isEqualTo("llm");
        assertThat(resp.getModel()).isEqualTo("deepseek-chat");
        assertThat(resp.getAnswer()).isEqualTo("主校区站气温 26.4℃。");
        assertThat(resp.getTools()).singleElement()
                .satisfies(tool -> assertThat(tool.getName()).isEqualTo("queryRealtime"));
        verify(toolCallRecorder).begin();
    }

    @Test
    @DisplayName("大模型故障：降级为提示信息，不抛异常，且留痕被清理")
    void shouldDegradeWhenLlmFails() {
        ReflectionTestUtils.setField(agent, "model", "deepseek-chat");
        when(chatClient.prompt().user(QUESTION).call().content())
                .thenThrow(new RuntimeException("Connection refused"));
        when(toolCallRecorder.drain()).thenReturn(List.of());

        AssistantResp resp = agent.ask(QUESTION);

        assertThat(resp.getSource()).isEqualTo("unavailable");
        assertThat(resp.getAnswer()).contains("大模型服务当前不可用").contains("实时监测");
        assertThat(resp.getTools()).isEmpty();
        verify(toolCallRecorder).drain();
    }

    @Test
    @DisplayName("成功路径留下关键业务动作日志：含模型、工具名与耗时，且不含问题原文")
    void shouldLogSuccessWithModelAndTools() {
        ReflectionTestUtils.setField(agent, "model", "deepseek-chat");
        when(chatClient.prompt().user(QUESTION).call().content()).thenReturn("主校区站气温 26.4℃。");
        when(toolCallRecorder.drain())
                .thenReturn(List.of(new ToolInvocation("queryRealtime", "站点 CAMPUS01", "气温(℃) 26.4")));

        List<ILoggingEvent> logs = captureLogs(() -> agent.ask(QUESTION));

        // 成功路径此前一行日志都没有，导致线上无法判断助手是否被使用、模型是否正常
        assertThat(logs).singleElement().satisfies(event -> {
            assertThat(event.getLevel().toString()).isEqualTo("INFO");
            assertThat(event.getFormattedMessage())
                    .contains("deepseek-chat")
                    .contains("toolCount=1")
                    .contains("queryRealtime")
                    .contains("costMs=");
        });
        // 用户输入不进日志
        assertThat(logs).allSatisfy(event -> assertThat(event.getFormattedMessage()).doesNotContain(QUESTION));
    }

    /** 在指定动作期间收集本类的日志事件，结束后解除挂载避免污染其它用例 */
    private List<ILoggingEvent> captureLogs(Runnable action) {
        Logger logger = (Logger) LoggerFactory.getLogger(MeteoAssistantAgent.class);
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        logger.addAppender(appender);
        try {
            action.run();
        } finally {
            logger.detachAppender(appender);
        }
        return appender.list;
    }
}
