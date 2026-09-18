package com.campus.meteo.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 历史数据查询响应（按要素分组的多条曲线）
 */
@Data
@Schema(description = "历史数据查询响应")
public class HistoryResp {

    private Long stationId;
    private String stationCode;

    @Schema(description = "聚合粒度：min/hour/day")
    private String granularity;

    @Schema(description = "要素 → 曲线点位，键为要素名")
    private Map<String, List<SeriesPoint>> series = new LinkedHashMap<>();
}