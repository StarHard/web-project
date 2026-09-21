package com.campus.meteo.agent.alert;

import com.campus.meteo.entity.AlertRule;
import com.campus.meteo.influx.ObsData;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Answers;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.ai.chat.client.ChatClient;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

/**
 * 预警文案生成单测
 *
 * 重点验证两件事：一是提示词必须把告警事实（站点名、中文要素名、阈值、等级）交代清楚，
 * 模型才不会拿字段名或错阈值去写；二是任何失败都必须返回 null 让调用方回退模板文案，
 * 预警本身绝不能因大模型不可用而缺失。
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("预警文案生成")
class AlertTextGeneratorTest {

    private static final String STATION_NAME = "校园气象站一号";

    @Mock(answer = Answers.RETURNS_DEEP_STUBS)
    private ChatClient chatClient;
    @Mock
    private ChatClient.Builder builder;

    private AlertTextGenerator generator;

    @BeforeEach
    void setUp() {
        when(builder.defaultSystem(anyString())).thenReturn(builder);
        when(builder.build()).thenReturn(chatClient);
        generator = new AlertTextGenerator(builder);
    }

    @Test
    @DisplayName("生成成功：返回模型文案并去掉首尾空白")
    void shouldReturnGeneratedText() {
        when(chatClient.prompt().user(anyString()).call().content())
                .thenReturn("  建议暂停户外高空作业，加固临时搭建物与广告牌。  ");

        String text = generator.generate(STATION_NAME, rule(), obs(), 2, 14.3, null);

        assertThat(text).isEqualTo("建议暂停户外高空作业，加固临时搭建物与广告牌。");
    }

    @Test
    @DisplayName("提示词交代告警事实：站点名、中文要素名、阈值、观测快照")
    void shouldBuildPromptWithAlertFacts() {
        String prompt = generator.buildPrompt(STATION_NAME, rule(), obs(), 2, 14.3, null);

        assertThat(prompt)
                .contains("校园气象站一号")
                .contains("CAMPUS01")
                .contains("大风")
                .contains("黄色")
                .contains("风速(m/s) = 14.3")
                .contains("阈值 10")
                .contains("大于阈值")
                .contains("气温(℃) 25.4");
    }

    @Test
    @DisplayName("等级升级场景：提示词说明由哪一级升上来")
    void shouldMentionUpgradeInPrompt() {
        String prompt = generator.buildPrompt(STATION_NAME, rule(), obs(), 3, 14.3, 2);

        assertThat(prompt).contains("由黄色升级为橙色");
    }

    @Test
    @DisplayName("持续超限规则：提示词写明持续时长条件")
    void shouldMentionSustainedConditionInPrompt() {
        AlertRule rule = rule();
        rule.setCondition(3);

        String prompt = generator.buildPrompt(STATION_NAME, rule, obs(), 2, 14.3, null);

        assertThat(prompt).contains("持续 60 分钟超限");
    }

    @Test
    @DisplayName("文案超长时截断，避免超出 ai_content 列宽被静默丢弃")
    void shouldTruncateOverlongText() {
        when(chatClient.prompt().user(anyString()).call().content()).thenReturn("建".repeat(300));

        String text = generator.generate(STATION_NAME, rule(), obs(), 2, 14.3, null);

        assertThat(text).hasSize(200).endsWith("…");
    }

    @Test
    @DisplayName("大模型异常 → 返回 null，由调用方回退模板文案")
    void shouldReturnNullWhenLlmFails() {
        when(chatClient.prompt().user(anyString()).call().content())
                .thenThrow(new RuntimeException("Connection refused"));

        assertThat(generator.generate(STATION_NAME, rule(), obs(), 2, 14.3, null)).isNull();
    }

    @Test
    @DisplayName("模型返回空白 → 返回 null，不写入空建议")
    void shouldReturnNullWhenAnswerBlank() {
        when(chatClient.prompt().user(anyString()).call().content()).thenReturn("   ");

        assertThat(generator.generate(STATION_NAME, rule(), obs(), 2, 14.3, null)).isNull();
    }

    private AlertRule rule() {
        AlertRule rule = new AlertRule();
        rule.setId(9L);
        rule.setAlertType(2);
        rule.setElement("wind_speed");
        rule.setCondition(1);
        rule.setThreshold(BigDecimal.valueOf(10.0));
        rule.setDurationMin(60);
        rule.setLevel(2);
        return rule;
    }

    private ObsData obs() {
        return ObsData.builder()
                .stationCode("CAMPUS01")
                .ts(Instant.parse("2026-09-21T15:25:48Z"))
                .elements(Map.of("temp", 25.4, "wind_speed", 14.3))
                .msgId("msg-alert-text")
                .build();
    }
}
