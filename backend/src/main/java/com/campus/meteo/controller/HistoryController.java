package com.campus.meteo.controller;

import com.campus.meteo.common.result.Result;
import com.campus.meteo.dto.HistoryResp;
import com.campus.meteo.dto.StatsResp;
import com.campus.meteo.service.DataQueryService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 历史数据查询与统计接口
 */
@Tag(name = "历史数据与统计")
@Validated
@RestController
@RequiredArgsConstructor
public class HistoryController {

    private final DataQueryService dataQueryService;

    @Operation(summary = "历史时序数据（granularity=min/hour/day）")
    @GetMapping("/history")
    @PreAuthorize("hasAuthority('data:query')")
    public Result<HistoryResp> history(@RequestParam Long stationId,
                                       @RequestParam(required = false) String elements,
                                       @RequestParam(required = false) String startTime,
                                       @RequestParam(required = false) String endTime,
                                       @RequestParam(defaultValue = "min") String granularity,
                                       @RequestParam(required = false) String qcFlag) {
        return Result.ok(dataQueryService.history(stationId, elements, startTime, endTime, granularity, qcFlag));
    }

    @Operation(summary = "日统计（均值/极值及出现时间）")
    @GetMapping("/stats/daily")
    @PreAuthorize("hasAuthority('data:query')")
    public Result<StatsResp> daily(@RequestParam Long stationId,
                                   @RequestParam(required = false) String date) {
        return Result.ok(dataQueryService.daily(stationId, date));
    }

    @Operation(summary = "月统计")
    @GetMapping("/stats/monthly")
    @PreAuthorize("hasAuthority('data:query')")
    public Result<StatsResp> monthly(@RequestParam Long stationId,
                                     @RequestParam(required = false) String month) {
        return Result.ok(dataQueryService.monthly(stationId, month));
    }

    @Operation(summary = "年统计")
    @GetMapping("/stats/yearly")
    @PreAuthorize("hasAuthority('data:query')")
    public Result<StatsResp> yearly(@RequestParam Long stationId,
                                    @RequestParam(required = false) String year) {
        return Result.ok(dataQueryService.yearly(stationId, year));
    }

    @Operation(summary = "极值统计")
    @GetMapping("/stats/extreme")
    @PreAuthorize("hasAuthority('data:query')")
    public Result<StatsResp> extreme(@RequestParam Long stationId,
                                     @RequestParam String element,
                                     @RequestParam(required = false) String startTime,
                                     @RequestParam(required = false) String endTime) {
        return Result.ok(dataQueryService.extreme(stationId, element, startTime, endTime));
    }

    @Operation(summary = "气候平均值对比（同期多年）")
    @GetMapping("/stats/climate")
    @PreAuthorize("hasAuthority('data:query')")
    public Result<StatsResp> climate(@RequestParam Long stationId,
                                     @RequestParam Integer month,
                                     @RequestParam String element) {
        return Result.ok(dataQueryService.climate(stationId, month, element));
    }
}