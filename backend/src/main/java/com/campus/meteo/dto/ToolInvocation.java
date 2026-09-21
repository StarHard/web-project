package com.campus.meteo.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Data;

/**
 * 智能助手的工具调用留痕
 *
 * 前端据此展示「AI 查了什么、拿到了什么」——这是证明回答基于系统真实数据、
 * 而非模型凭常识编造的关键证据，比只展示一段回答更有说服力。
 */
@Data
@AllArgsConstructor
@Schema(description = "智能助手的工具调用留痕")
public class ToolInvocation {

    @Schema(description = "工具名，如 queryRealtime")
    private String name;

    @Schema(description = "调用参数摘要")
    private String arguments;

    @Schema(description = "工具返回内容")
    private String result;
}
