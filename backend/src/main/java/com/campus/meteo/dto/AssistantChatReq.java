package com.campus.meteo.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

/**
 * 智能助手提问请求
 */
@Data
@Schema(description = "智能助手提问请求")
public class AssistantChatReq {

    @NotBlank(message = "提问内容不能为空")
    @Size(max = 500, message = "提问内容不能超过 500 字")
    @Schema(description = "自然语言提问", example = "昨天哪个站点风速最大？有没有超过预警线？")
    private String question;
}
