package com.campus.meteo.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.time.LocalDate;

/**
 * 设备响应
 */
@Data
@Schema(description = "设备响应")
public class DeviceResp {

    private Long id;
    private Long stationId;
    private String deviceCode;
    private Integer deviceType;
    private String model;
    private String manufacturer;
    private LocalDate installDate;
    private Integer status;
}