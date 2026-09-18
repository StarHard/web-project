package com.campus.meteo.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.time.LocalDate;

/**
 * 设备维护请求
 */
@Data
@Schema(description = "设备维护请求")
public class DeviceSaveReq {

    @NotNull(message = "所属站点不能为空")
    @Schema(description = "所属站点ID")
    private Long stationId;

    @NotBlank(message = "设备编码不能为空")
    @Size(max = 32, message = "设备编码长度不能超过32")
    @Schema(description = "设备编码")
    private String deviceCode;

    @NotNull(message = "设备类型不能为空")
    @Schema(description = "类型：1风速风向 2雨量 3温湿压 4辐射 5蒸发 6能见度 7采集器")
    private Integer deviceType;

    @Schema(description = "型号")
    private String model;

    @Schema(description = "厂商")
    private String manufacturer;

    @Schema(description = "安装日期")
    private LocalDate installDate;

    @Schema(description = "状态：0停用 1正常 2故障 3检定中")
    private Integer status;
}