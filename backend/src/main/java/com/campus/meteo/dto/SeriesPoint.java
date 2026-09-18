package com.campus.meteo.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 时序曲线单点
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Schema(description = "时序单点")
public class SeriesPoint {

    @Schema(description = "时间，格式 yyyy-MM-dd HH:mm:ss")
    private String time;

    @Schema(description = "要素值")
    private Double value;

    @Schema(description = "质控标记：raw/passed/suspect/interpolated/revised")
    private String qcFlag;
}