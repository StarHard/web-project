package com.campus.meteo.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.util.Map;

/**
 * 站点最新观测响应
 *
 * 缓存与降级查询两条路径返回同一结构：早先缓存路径返回的是采集侧原始报文串，
 * 降级路径返回 ts + elements，客户端需要按 source 分别解析，且两条路径的质控口径不同。
 */
@Data
@Schema(description = "站点最新观测响应")
public class RealtimeLatestResp {

    @Schema(description = "数据来源：cache 缓存 / influx 时序库 / none 无数据")
    private String source;

    private String stationCode;

    @Schema(description = "观测时刻（ISO-8601，UTC）")
    private String ts;

    @Schema(description = "要素值")
    private Map<String, Double> elements;

    @Schema(description = "质控标记：passed / revised / interpolated")
    private String qcFlag;
}
