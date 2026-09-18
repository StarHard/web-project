package com.campus.meteo.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

import java.time.LocalDate;

/**
 * 报表生成请求
 */
@Data
@Schema(description = "报表生成请求")
public class ReportGenerateReq {

    @NotNull(message = "站点不能为空")
    @Schema(description = "站点ID")
    private Long stationId;

    @NotNull(message = "报表类型不能为空")
    @Schema(description = "类型：1日报 2月报 3年报 4极值 5气候对比")
    private Integer reportType;

    @NotNull(message = "统计起始日不能为空")
    @Schema(description = "统计起始日")
    private LocalDate periodStart;

    @Schema(description = "统计结束日（极值统计必填）")
    private LocalDate periodEnd;

    @Schema(description = "要素名（极值/气候对比必填）")
    private String element;
}