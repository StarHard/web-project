package com.campus.meteo.controller;

import com.campus.meteo.agent.assistant.MeteoAssistantAgent;
import com.campus.meteo.common.result.Result;
import com.campus.meteo.dto.AssistantChatReq;
import com.campus.meteo.dto.AssistantResp;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 智能助手接口
 */
@Slf4j
@Tag(name = "智能助手")
@RestController
@RequestMapping("/assistant")
@RequiredArgsConstructor
public class AssistantController {

    private final MeteoAssistantAgent assistantAgent;

    @Operation(summary = "气象决策助手对话", description = "自然语言提问，由大模型规划并调用气象数据工具后作答；大模型不可用时返回 source=unavailable 的提示")
    @PostMapping("/chat")
    @PreAuthorize("isAuthenticated()")
    public Result<AssistantResp> chat(@Valid @RequestBody AssistantChatReq req) {
        return Result.ok(assistantAgent.ask(req.getQuestion()));
    }
}
