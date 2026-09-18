package com.campus.meteo.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * 站点维护请求
 */
@Data
@Schema(description = "站点维护请求")
public class StationSaveReq {

    @NotBlank(message = "站点编码不能为空")
    @Size(max = 32, message = "站点编码长度不能超过32")
    @Schema(description = "站点编码（MQTT 上报标识）")
    private String stationCode;

    @NotBlank(message = "站点名称不能为空")
    @Size(max = 64, message = "站点名称长度不能超过64")
    @Schema(description = "站点名称")
    private String name;

    @Schema(description = "省")
    private String province;

    @Schema(description = "市")
    private String city;

    @Schema(description = "区县")
    private String district;

    @NotNull(message = "经度不能为空")
    @Schema(description = "经度（GCJ-02）")
    private BigDecimal longitude;

    @NotNull(message = "纬度不能为空")
    @Schema(description = "纬度（GCJ-02）")
    private BigDecimal latitude;

    @Schema(description = "海拔（米）")
    private BigDecimal altitude;

    @Schema(description = "类型：1校园 2农业 3区域")
    private Integer stationType;

    @Schema(description = "状态：0停用 1正常 2维护中")
    private Integer status;

    @Schema(description = "投运日期")
    private LocalDate commissionDate;
}