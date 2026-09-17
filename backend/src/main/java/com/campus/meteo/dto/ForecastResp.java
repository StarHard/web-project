package com.campus.meteo.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Builder;
import lombok.Data;

import java.util.List;
import java.util.Map;

/**
 * 预报产品响应
 */
@Data
@Builder
@Schema(description = "预报产品响应")
public class ForecastResp {

    @Schema(description = "站点编码")
    private String stationCode;

    @Schema(description = "模型标识：stat/ml/manual")
    private String model;

    @Schema(description = "发布时刻")
    private String issueTime;

    @Schema(description = "预报时效（小时）")
    private int rangeHours;

    @Schema(description = "逐小时预报序列")
    private List<Point> points;

    @Data
    @Builder
    @Schema(description = "单时效预报")
    public static class Point {

        @Schema(description = "预报目标时刻")
        private String time;

        @Schema(description = "各要素预报值：temp/humi/pres/wind_speed/wind_dir/rain/pop")
        private Map<String, Double> elements;
    }
}
