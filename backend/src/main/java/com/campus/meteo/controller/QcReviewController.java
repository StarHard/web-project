package com.campus.meteo.controller;

import com.campus.meteo.common.result.PageResult;
import com.campus.meteo.common.result.Result;
import com.campus.meteo.dto.QcReviewReq;
import com.campus.meteo.entity.QcReviewTask;
import com.campus.meteo.service.QcReviewService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 质控人工审核接口
 */
@Tag(name = "质控审核")
@RestController
@RequestMapping("/qc-tasks")
@RequiredArgsConstructor
public class QcReviewController {

    private final QcReviewService qcReviewService;

    @Operation(summary = "审核任务分页")
    @GetMapping
    @PreAuthorize("hasAuthority('qc:review')")
    public Result<PageResult<QcReviewTask>> page(@RequestParam(defaultValue = "1") long pageNum,
                                                 @RequestParam(defaultValue = "20") long pageSize,
                                                 @RequestParam(required = false) Long stationId,
                                                 @RequestParam(required = false) Integer status,
                                                 @RequestParam(required = false) String startTime,
                                                 @RequestParam(required = false) String endTime) {
        return Result.ok(qcReviewService.page(pageNum, pageSize, stationId, status, startTime, endTime));
    }

    @Operation(summary = "审核：confirm 确认有效 / revise 修正 / void 作废")
    @PatchMapping("/{id}/review")
    @PreAuthorize("hasAuthority('qc:review')")
    public Result<Void> review(@PathVariable Long id, @Valid @RequestBody QcReviewReq req) {
        qcReviewService.review(id, req);
        return Result.ok();
    }

    @Operation(summary = "手动触发一轮缺测插补（FR-QC-05），返回本轮回填统计")
    @PostMapping("/interpolate")
    @PreAuthorize("hasAuthority('qc:review')")
    public Result<QcReviewService.InterpolationSummary> interpolate() {
        return Result.ok(qcReviewService.interpolateMissing());
    }
}