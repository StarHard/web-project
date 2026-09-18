package com.campus.meteo.controller;

import com.campus.meteo.common.result.PageResult;
import com.campus.meteo.common.result.Result;
import com.campus.meteo.dto.ReportGenerateReq;
import com.campus.meteo.entity.ReportFile;
import com.campus.meteo.service.ReportService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 统计报表接口
 */
@Tag(name = "统计报表")
@RestController
@RequestMapping("/reports")
@RequiredArgsConstructor
public class ReportController {

    private final ReportService reportService;

    @Operation(summary = "报表文件分页")
    @GetMapping
    @PreAuthorize("isAuthenticated()")
    public Result<PageResult<ReportFile>> page(@RequestParam(defaultValue = "1") long pageNum,
                                               @RequestParam(defaultValue = "20") long pageSize,
                                               @RequestParam(required = false) Long stationId,
                                               @RequestParam(required = false) Integer reportType,
                                               @RequestParam(required = false) String period) {
        return Result.ok(reportService.page(pageNum, pageSize, stationId, reportType, period));
    }

    @Operation(summary = "手动生成报表，返回报表记录ID")
    @PostMapping("/generate")
    @PreAuthorize("hasAuthority('report:generate')")
    public Result<Long> generate(@Valid @RequestBody ReportGenerateReq req) {
        return Result.ok(reportService.generate(req));
    }

    @Operation(summary = "下载报表文件")
    @GetMapping("/{id}/download")
    @PreAuthorize("isAuthenticated()")
    public ResponseEntity<byte[]> download(@PathVariable Long id) {
        ReportService.ReportDownload file = reportService.download(id);
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION,
                        "attachment; filename=\"" + file.fileName() + "\"")
                .contentType(MediaType.APPLICATION_OCTET_STREAM)
                .body(file.content());
    }
}