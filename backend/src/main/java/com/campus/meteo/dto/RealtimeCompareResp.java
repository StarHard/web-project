package com.campus.meteo.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.util.ArrayList;
import java.util.List;

/**
 * 多站点同要素对比响应（FR-RT-04）
 *
 * 各站点采样时刻并不严格一致，若各站点各自返回点位序列，前端无法共用一条时间轴。
 * 因此时间轴由服务端取并集，各站点序列按该轴对齐，缺测位置补 null，
 * 前端可直接按数组下标绘图。
 */
@Data
@Schema(description = "多站点同要素对比响应")
public class RealtimeCompareResp {

    @Schema(description = "对比要素")
    private String element;

    @Schema(description = "请求的聚合粒度（raw/5m/15m/1h/1d/auto，auto 由服务端按时间跨度选定窗口）")
    private String granularity;

    @Schema(description = "开始时间（yyyy-MM-dd HH:mm:ss）")
    private String startTime;

    @Schema(description = "结束时间（yyyy-MM-dd HH:mm:ss）")
    private String endTime;

    @Schema(description = "统一时间轴（yyyy-MM-dd HH:mm:ss），所有站点序列按此对齐")
    private List<String> times = new ArrayList<>();

    @Schema(description = "各站点对比序列，顺序与请求的站点顺序一致")
    private List<StationSeries> series = new ArrayList<>();

    /**
     * 单站点对比序列
     */
    @Data
    @Schema(description = "单站点对比序列")
    public static class StationSeries {

        private Long stationId;

        private String stationCode;

        private String stationName;

        @Schema(description = "与 times 等长，缺测位置为 null")
        private List<Double> values = new ArrayList<>();

        @Schema(description = "时间窗内最后一个有效值")
        private Double latest;

        private Double min;

        private Double max;

        private Double avg;

        @Schema(description = "有效样本数（不含缺测）")
        private Integer count;
    }
}
