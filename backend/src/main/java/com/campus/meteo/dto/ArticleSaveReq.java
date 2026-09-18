package com.campus.meteo.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Data;

/**
 * 气象服务内容维护请求
 */
@Data
@Schema(description = "气象服务内容维护请求")
public class ArticleSaveReq {

    @NotNull(message = "分类不能为空")
    @Schema(description = "分类：1农业气象 2旅游气象 3出行指数 4科普")
    private Integer category;

    @NotBlank(message = "标题不能为空")
    @Size(max = 128, message = "标题长度不能超过128")
    @Schema(description = "标题")
    private String title;

    @NotBlank(message = "内容不能为空")
    @Schema(description = "富文本内容")
    private String content;

    @Schema(description = "关联站点（可空）")
    private Long stationId;
}