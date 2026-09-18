package com.campus.meteo.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import lombok.Data;

/**
 * 系统参数修改请求
 */
@Data
@Schema(description = "系统参数修改请求")
public class SysConfigSaveReq {

    @NotBlank(message = "参数值不能为空")
    @Schema(description = "参数值")
    private String configValue;

    @Schema(description = "说明")
    private String remark;
}