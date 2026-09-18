package com.campus.meteo.controller;

import com.campus.meteo.common.result.Result;
import com.campus.meteo.dto.ExportTaskResp;
import com.campus.meteo.service.ExportService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 历史数据异步导出接口
 */
@Tag(name = "数据导出")
@RestController
@RequestMapping("/export")
@RequiredArgsConstructor
public class ExportController {

    private final ExportService exportService;

    @Operation(summary = "提交导出任务（异步），返回任务ID；clientToken 用于防重复提交")
    @GetMapping
    @PreAuthorize("hasAuthority('data:export')")
    public Result<String> submit(@RequestParam Long stationId,
                                 @RequestParam(required = false) String elements,
                                 @RequestParam(required = false) String startTime,
                                 @RequestParam(required = false) String endTime,
                                 @RequestParam(defaultValue = "csv") String format,
                                 @RequestParam(required = false) String clientToken) {
        return Result.ok(exportService.submit(stationId, elements, startTime, endTime, format, clientToken));
    }

    @Operation(summary = "查询导出任务状态与下载地址")
    @GetMapping("/tasks/{taskId}")
    @PreAuthorize("hasAuthority('data:export')")
    public Result<ExportTaskResp> status(@PathVariable String taskId) {
        return Result.ok(exportService.status(taskId));
    }

    @Operation(summary = "下载导出文件")
    @GetMapping("/tasks/{taskId}/download")
    @PreAuthorize("hasAuthority('data:export')")
    public ResponseEntity<byte[]> download(@PathVariable String taskId) {
        ExportService.DownloadFile file = exportService.download(taskId);
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION,
                        "attachment; filename=\"" + file.fileName() + "\"")
                .contentType(MediaType.APPLICATION_OCTET_STREAM)
                .body(file.content());
    }
}