package com.campus.meteo.dto;

import com.fasterxml.jackson.databind.JsonNode;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

/**
 * 站点响应（档案 + 设备清单 + 最新数据摘要）
 */
@Data
@Schema(description = "站点响应")
public class StationResp {

    private Long id;
    private String stationCode;
    private String name;
    private String province;
    private String city;
    private String district;
    private BigDecimal longitude;
    private BigDecimal latitude;
    private BigDecimal altitude;
    private Integer stationType;
    private Integer status;

    @Schema(description = "在线：0离线 1在线")
    private Integer onlineFlag;

    @Schema(description = "最后上报时间")
    private LocalDateTime lastReportTime;

    private LocalDate commissionDate;

    @Schema(description = "设备清单（仅详情接口返回）")
    private List<DeviceResp> devices;

    @Schema(description = "最新观测摘要（实时缓存，质控后数据：ts/qcFlag/elements）")
    private JsonNode latest;
}