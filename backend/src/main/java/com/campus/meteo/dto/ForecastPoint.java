package com.campus.meteo.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 预报对比单点
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Schema(description = "预报对比单点")
public class ForecastPoint {

    @Schema(description = "预报目标时间，格式 yyyy-MM-dd HH:mm:ss")
    private String time;

    @Schema(description = "要素值")
    private Double value;
}