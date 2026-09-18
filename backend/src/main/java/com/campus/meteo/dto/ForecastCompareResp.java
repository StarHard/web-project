package com.campus.meteo.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 多模型预报对比响应
 */
@Data
@Schema(description = "多模型预报对比响应")
public class ForecastCompareResp {

    private String stationCode;

    @Schema(description = "对比要素")
    private String element;

    @Schema(description = "预报时效（小时）")
    private Integer rangeHours;

    @Schema(description = "模型 → 曲线点位（模型标识如 stat/ml/manual）")
    private Map<String, List<ForecastPoint>> models = new LinkedHashMap<>();
}