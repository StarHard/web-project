package com.campus.meteo.config;

import com.campus.meteo.agent.assistant.MeteoTools;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.vectorstore.SimpleVectorStore;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 决策智能体配置：Spring AI 的 ChatClient（OpenAI 兼容协议 → 国产大模型）
 *
 * 系统提示词约束「只依据工具返回的真实数据作答、缺数据就说无数据」，
 * 这是气象决策场景的底线——大模型幻觉出的气温/预警会被直接采信并影响处置动作。
 * 工具（实时/历史/预报/告警/站点查询、知识库检索）由 MeteoTools 提供，此处统一注册为默认工具，
 * 由模型按问题自行规划调用；工具只读，写操作不对外开放给模型。
 */
@Configuration
public class AiConfig {

    private static final String SYSTEM_PROMPT = """
            你是校园气象智能决策助手，服务对象是高校后勤、宿管与农业试验站管理人员。
            回答要求：
            1. 只依据工具返回的真实观测、预报与告警数据作答，严禁编造或推测数值；
            2. 涉及具体站点数据时必须先调用工具查询，不得凭常识作答；数据缺失时明确说明「无数据」；
            3. 涉及预警信号含义、防灾避险规范、农业与出行建议等常识性内容时，
               必须先调用 searchKnowledge 检索知识库并注明依据的文章标题；未检索到依据时如实说明；
            4. 涉及风险时给出可执行的处置建议（如暂停户外活动、加固设施、推迟灌溉）；
            5. 要素名称与单位用中文，数值保留一位小数；
            6. 回答控制在 200 字以内，先给结论再给依据；
            7. 用纯文本作答，不要使用 Markdown 标记（如 ** 加粗、# 标题），可用换行与「-」分点。
            """;

    @Bean
    public ChatClient meteoChatClient(ChatClient.Builder builder, MeteoTools meteoTools) {
        return builder.defaultSystem(SYSTEM_PROMPT).defaultTools(meteoTools).build();
    }

    /**
     * 知识库向量存储（内存实现）
     *
     * 语料是「气象服务」文章，规模只有数十篇，进程内索引足够且免去额外中间件；
     * 检索侧只依赖 VectorStore 接口，日后要换 Redis/Milvus 只需替换这里的实现。
     */
    @Bean
    public VectorStore knowledgeVectorStore(EmbeddingModel embeddingModel) {
        return SimpleVectorStore.builder(embeddingModel).build();
    }
}
