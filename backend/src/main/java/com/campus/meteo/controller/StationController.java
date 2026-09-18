package com.campus.meteo.controller;

import com.campus.meteo.common.result.PageResult;
import com.campus.meteo.common.result.Result;
import com.campus.meteo.dto.StationMapResp;
import com.campus.meteo.dto.StationResp;
import com.campus.meteo.dto.StationSaveReq;
import com.campus.meteo.service.StationService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 站点档案接口
 */
@Tag(name = "站点管理")
@RestController
@RequestMapping("/stations")
@RequiredArgsConstructor
public class StationController {

    private final StationService stationService;

    @Operation(summary = "站点分页列表（含在线状态与最新数据摘要）")
    @GetMapping
    @PreAuthorize("hasAuthority('station:view')")
    public Result<PageResult<StationResp>> page(@RequestParam(defaultValue = "1") long pageNum,
                                                @RequestParam(defaultValue = "20") long pageSize,
                                                @RequestParam(required = false) String keyword,
                                                @RequestParam(required = false) Integer status,
                                                @RequestParam(required = false) Integer onlineFlag) {
        return Result.ok(stationService.page(pageNum, pageSize, keyword, status, onlineFlag));
    }

    @Operation(summary = "地图站点聚合（坐标+状态+告警角标），公开")
    @GetMapping("/map")
    public Result<List<StationMapResp>> map() {
        return Result.ok(stationService.mapData());
    }

    @Operation(summary = "站点详情（含设备清单）")
    @GetMapping("/{id}")
    @PreAuthorize("hasAuthority('station:view')")
    public Result<StationResp> detail(@PathVariable Long id) {
        return Result.ok(stationService.detail(id));
    }

    @Operation(summary = "新增站点")
    @PostMapping
    @PreAuthorize("hasAuthority('station:edit')")
    public Result<Void> create(@Valid @RequestBody StationSaveReq req) {
        stationService.create(req);
        return Result.ok();
    }

    @Operation(summary = "修改站点")
    @PutMapping("/{id}")
    @PreAuthorize("hasAuthority('station:edit')")
    public Result<Void> update(@PathVariable Long id, @Valid @RequestBody StationSaveReq req) {
        stationService.update(id, req);
        return Result.ok();
    }

    @Operation(summary = "删除站点")
    @DeleteMapping("/{id}")
    @PreAuthorize("hasAuthority('station:edit')")
    public Result<Void> delete(@PathVariable Long id) {
        stationService.delete(id);
        return Result.ok();
    }
}