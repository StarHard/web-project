package com.campus.meteo.agent.assistant;

import com.campus.meteo.dto.AssistantResp;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * 决策智能体：把自然语言问题交给大模型，由模型规划并调用气象数据工具后作答
 *
 * 与采集/质控/预报/告警/报表五个「数据 Agent」的区别：后者是 MQ 流水线上的数据处理角色，
 * 本类是面向用户的决策层，具备工具调用与任务规划能力。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class MeteoAssistantAgent {

    /** 大模型不可用时的降级话术：明确告知原因并指向可用的功能入口，避免演示时白屏 */
    private static final String UNAVAILABLE_ANSWER =
            "大模型服务当前不可用（未配置 LLM_API_KEY 或网络不通），"
                    + "可先通过「实时监测」「历史数据」「精细预报」「灾害告警」页面获取数据。";

    private final ChatClient meteoChatClient;

    @Value("${spring.ai.openai.chat.options.model:unknown}")
    private String model;

    /** 单轮问答（会话记忆与工具调用在后续迭代接入） */
    public AssistantResp ask(String question) {
        AssistantResp resp = new AssistantResp();
        resp.setModel(model);
        try {
            String answer = meteoChatClient.prompt()
                    .user(question)
                    .call()
                    .content();
            resp.setSource("llm");
            resp.setAnswer(answer);
        } catch (Exception e) {
            // 大模型是外部依赖，故障不应让助手接口整体失败：
            // 这里降级为提示信息，保证前端仍可正常渲染（演示与生产都需要）
            log.warn("大模型调用失败，降级返回: model={}, err={}", model, e.getMessage());
            resp.setSource("unavailable");
            resp.setAnswer(UNAVAILABLE_ANSWER);
        }
        return resp;
    }
}
