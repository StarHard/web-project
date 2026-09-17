package com.campus.meteo.controller;

import com.campus.meteo.common.result.PageResult;
import com.campus.meteo.common.result.Result;
import com.campus.meteo.entity.AlertRecord;
import com.campus.meteo.service.AlertService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * 告警记录查询接口
 */
@Tag(name = "灾害告警-记录")
@RestController
@RequestMapping("/alerts")
@RequiredArgsConstructor
public class AlertController {

    private final AlertService alertService;

    @Operation(summary = "告警历史分页查询")
    @GetMapping
    @PreAuthorize("hasAuthority('alert:view')")
    public Result<PageResult<AlertRecord>> pageRecords(@RequestParam(defaultValue = "1") long pageNum,
                                                       @RequestParam(defaultValue = "20") long pageSize,
                                                       @RequestParam(required = false) Long stationId,
                                                       @RequestParam(required = false) Integer level,
                                                       @RequestParam(required = false) Integer alertType,
                                                       @RequestParam(required = false) Integer status,
                                                       @RequestParam(required = false) String startTime,
                                                       @RequestParam(required = false) String endTime) {
        return Result.ok(alertService.pageRecords(pageNum, pageSize, stationId, level, alertType, status, startTime, endTime));
    }

    @Operation(summary = "告警统计（按等级/状态/站点）")
    @GetMapping("/stat")
    @PreAuthorize("hasAuthority('alert:view')")
    public Result<Map<String, Object>> stat(@RequestParam(required = false) String startTime,
                                            @RequestParam(required = false) String endTime) {
        return Result.ok(alertService.stat(startTime, endTime));
    }

    @Operation(summary = "手动解除告警")
    @PatchMapping("/{id}/relieve")
    @PreAuthorize("hasAuthority('alert:manage')")
    public Result<Void> relieve(@PathVariable Long id) {
        alertService.relieve(id);
        return Result.ok();
    }
}
