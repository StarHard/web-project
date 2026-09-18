package com.campus.meteo.controller;

import com.campus.meteo.common.result.PageResult;
import com.campus.meteo.common.result.Result;
import com.campus.meteo.dto.SysConfigSaveReq;
import com.campus.meteo.entity.OperationLog;
import com.campus.meteo.entity.SysConfig;
import com.campus.meteo.service.OperationLogService;
import com.campus.meteo.service.SysConfigService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 系统管理接口（参数与操作日志）
 */
@Tag(name = "系统管理")
@RestController
@RequestMapping("/sys")
@RequiredArgsConstructor
public class SysController {

    private final SysConfigService sysConfigService;
    private final OperationLogService operationLogService;

    @Operation(summary = "系统参数分页（keyword 匹配键或说明）")
    @GetMapping("/configs")
    @PreAuthorize("hasAuthority('sys:manage')")
    public Result<PageResult<SysConfig>> pageConfigs(@RequestParam(defaultValue = "1") long pageNum,
                                                     @RequestParam(defaultValue = "20") long pageSize,
                                                     @RequestParam(required = false) String keyword) {
        return Result.ok(sysConfigService.page(pageNum, pageSize, keyword));
    }

    @Operation(summary = "修改系统参数（修改后缓存立即失效）")
    @PutMapping("/configs/{id}")
    @PreAuthorize("hasAuthority('sys:manage')")
    public Result<Void> updateConfig(@PathVariable Long id, @Valid @RequestBody SysConfigSaveReq req) {
        sysConfigService.update(id, req);
        operationLogService.record("系统管理", "修改系统参数", "configId=" + id + ", value=" + req.getConfigValue());
        return Result.ok();
    }

    @Operation(summary = "操作日志分页")
    @GetMapping("/logs/operations")
    @PreAuthorize("hasAuthority('sys:manage')")
    public Result<PageResult<OperationLog>> pageLogs(@RequestParam(defaultValue = "1") long pageNum,
                                                     @RequestParam(defaultValue = "20") long pageSize,
                                                     @RequestParam(required = false) Long userId,
                                                     @RequestParam(required = false) String module,
                                                     @RequestParam(required = false) String startTime,
                                                     @RequestParam(required = false) String endTime) {
        return Result.ok(operationLogService.page(pageNum, pageSize, userId, module, startTime, endTime));
    }
}