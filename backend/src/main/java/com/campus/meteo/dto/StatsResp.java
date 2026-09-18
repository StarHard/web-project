package com.campus.meteo.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 统计查询响应（日/月/年统计共用）
 */
@Data
@Schema(description = "统计查询响应")
public class StatsResp {

    private Long stationId;
    private String stationCode;

    @Schema(description = "统计周期标识：yyyy-MM-dd / yyyy-MM / yyyy")
    private String period;

    @Schema(description = "统计起止时间")
    private String startTime;
    private String endTime;

    @Schema(description = "要素 → 统计结果")
    private Map<String, ElementStat> stats = new LinkedHashMap<>();

    @Schema(description = "气候对比序列（仅 /stats/climate 返回）：年份 → 同期均值")
    private List<Map<String, Object>> climateSeries;
}