package com.campus.meteo.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.util.List;

/**
 * 智能助手回答响应
 */
@Data
@Schema(description = "智能助手回答响应")
public class AssistantResp {

    @Schema(description = "回答内容")
    private String answer;

    @Schema(description = "回答来源：llm 大模型 / unavailable 大模型不可用（未配置 LLM_API_KEY 或网络不通）")
    private String source;

    @Schema(description = "实际使用的模型标识")
    private String model;

    @Schema(description = "本轮问答实际调用的数据工具（按调用顺序），大模型不可用时为空")
    private List<ToolInvocation> tools;
}
