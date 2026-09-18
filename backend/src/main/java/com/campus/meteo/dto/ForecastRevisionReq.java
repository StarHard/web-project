package com.campus.meteo.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.math.BigDecimal;

/**
 * 预报订正请求（目标时次由路径参数 id 指定，为 epoch 秒）
 */
@Data
@Schema(description = "预报订正请求")
public class ForecastRevisionReq {

    @NotBlank(message = "站点编码不能为空")
    @Schema(description = "站点编码")
    private String stationCode;

    @NotBlank(message = "要素不能为空")
    @Schema(description = "要素：temp/humi/pres/wind_speed/wind_dir/rain 等")
    private String element;

    @NotNull(message = "原始预报值不能为空")
    @Schema(description = "原始预报值")
    private BigDecimal originValue;

    @NotNull(message = "订正值不能为空")
    @Schema(description = "订正值")
    private BigDecimal revisedValue;

    @Size(max = 255, message = "订正依据长度不能超过255")
    @Schema(description = "订正依据")
    private String reason;
}