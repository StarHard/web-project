package com.campus.meteo.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.time.LocalDate;

/**
 * 运维记录新增请求
 */
@Data
@Schema(description = "运维记录新增请求")
public class MaintenanceSaveReq {

    @NotNull(message = "站点不能为空")
    @Schema(description = "站点ID")
    private Long stationId;

    @Schema(description = "设备ID（为空表示站点级运维）")
    private Long deviceId;

    @NotNull(message = "运维类型不能为空")
    @Schema(description = "类型：1检定 2维修 3更换 4巡检")
    private Integer type;

    @NotBlank(message = "运维内容不能为空")
    @Size(max = 512, message = "运维内容长度不能超过512")
    @Schema(description = "内容描述")
    private String content;

    @NotBlank(message = "操作人不能为空")
    @Schema(description = "操作人")
    private String operator;

    @NotNull(message = "运维日期不能为空")
    @Schema(description = "运维日期")
    private LocalDate maintDate;
}