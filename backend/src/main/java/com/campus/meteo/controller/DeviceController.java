package com.campus.meteo.controller;

import com.campus.meteo.common.result.PageResult;
import com.campus.meteo.common.result.Result;
import com.campus.meteo.dto.DeviceResp;
import com.campus.meteo.dto.DeviceSaveReq;
import com.campus.meteo.dto.MaintenanceSaveReq;
import com.campus.meteo.entity.MaintenanceRecord;
import com.campus.meteo.service.DeviceService;
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
 * 设备与运维记录接口
 */
@Tag(name = "设备管理")
@RestController
@RequiredArgsConstructor
public class DeviceController {

    private final DeviceService deviceService;

    @Operation(summary = "设备列表（按站点过滤）")
    @GetMapping("/devices")
    @PreAuthorize("hasAuthority('device:view')")
    public Result<List<DeviceResp>> list(@RequestParam(required = false) Long stationId) {
        return Result.ok(deviceService.listByStation(stationId));
    }

    @Operation(summary = "新增设备")
    @PostMapping("/devices")
    @PreAuthorize("hasAuthority('device:edit')")
    public Result<Void> create(@Valid @RequestBody DeviceSaveReq req) {
        deviceService.create(req);
        return Result.ok();
    }

    @Operation(summary = "修改设备")
    @PutMapping("/devices/{id}")
    @PreAuthorize("hasAuthority('device:edit')")
    public Result<Void> update(@PathVariable Long id, @Valid @RequestBody DeviceSaveReq req) {
        deviceService.update(id, req);
        return Result.ok();
    }

    @Operation(summary = "删除设备")
    @DeleteMapping("/devices/{id}")
    @PreAuthorize("hasAuthority('device:edit')")
    public Result<Void> delete(@PathVariable Long id) {
        deviceService.delete(id);
        return Result.ok();
    }

    @Operation(summary = "运维记录分页")
    @GetMapping("/maintenance-records")
    @PreAuthorize("hasAuthority('device:view')")
    public Result<PageResult<MaintenanceRecord>> pageMaintenance(@RequestParam(defaultValue = "1") long pageNum,
                                                                 @RequestParam(defaultValue = "20") long pageSize,
                                                                 @RequestParam(required = false) Long stationId,
                                                                 @RequestParam(required = false) Long deviceId) {
        return Result.ok(deviceService.pageMaintenance(pageNum, pageSize, stationId, deviceId));
    }

    @Operation(summary = "新增运维记录")
    @PostMapping("/maintenance-records")
    @PreAuthorize("hasAuthority('device:edit')")
    public Result<Void> createMaintenance(@Valid @RequestBody MaintenanceSaveReq req) {
        deviceService.createMaintenance(req);
        return Result.ok();
    }
}