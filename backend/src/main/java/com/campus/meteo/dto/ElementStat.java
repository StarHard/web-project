package com.campus.meteo.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

/**
 * 单要素统计结果
 */
@Data
@Schema(description = "单要素统计结果")
public class ElementStat {

    @Schema(description = "样本数")
    private long count;

    @Schema(description = "平均值")
    private Double avg;

    @Schema(description = "最大值")
    private Double max;

    @Schema(description = "最大值出现时间，格式 yyyy-MM-dd HH:mm:ss")
    private String maxTime;

    @Schema(description = "最小值")
    private Double min;

    @Schema(description = "最小值出现时间，格式 yyyy-MM-dd HH:mm:ss")
    private String minTime;

    @Schema(description = "累计值（降水/蒸发等累积量要素）")
    private Double sum;
}