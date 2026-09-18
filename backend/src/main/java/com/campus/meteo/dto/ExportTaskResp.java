package com.campus.meteo.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

/**
 * 导出任务状态响应
 */
@Data
@Schema(description = "导出任务状态响应")
public class ExportTaskResp {

    @Schema(description = "任务ID")
    private String taskId;

    @Schema(description = "状态：RUNNING 执行中 / SUCCESS 已完成 / FAILED 失败")
    private String status;

    @Schema(description = "导出文件名（成功后返回）")
    private String fileName;

    @Schema(description = "下载地址（成功后返回）")
    private String downloadUrl;

    @Schema(description = "失败原因")
    private String message;

    @Schema(description = "创建时间")
    private String createTime;
}