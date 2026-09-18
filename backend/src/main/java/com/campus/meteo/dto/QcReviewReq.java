package com.campus.meteo.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import lombok.Data;

import java.math.BigDecimal;

/**
 * 质控任务审核请求
 */
@Data
@Schema(description = "质控任务审核请求")
public class QcReviewReq {

    @NotBlank(message = "审核动作不能为空")
    @Schema(description = "审核动作：confirm 确认有效 / revise 修正 / void 作废")
    private String action;

    @Schema(description = "修正后的值（action=revise 时必填）")
    private BigDecimal revisedValue;
}