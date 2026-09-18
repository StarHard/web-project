package com.campus.meteo.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.math.BigDecimal;

/**
 * 地图站点聚合响应（供地图打点与告警角标）
 */
@Data
@Schema(description = "地图站点聚合响应")
public class StationMapResp {

    private Long id;
    private String stationCode;
    private String name;

    @Schema(description = "经度（GCJ-02）")
    private BigDecimal longitude;

    @Schema(description = "纬度（GCJ-02）")
    private BigDecimal latitude;

    @Schema(description = "在线：0离线 1在线")
    private Integer onlineFlag;

    @Schema(description = "运行状态：0停用 1正常 2维护中")
    private Integer status;

    @Schema(description = "当前未解除告警的最高等级：0无告警 1蓝 2黄 3橙 4红")
    private Integer alertLevel;
}