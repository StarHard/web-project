package com.campus.meteo.controller;

import com.campus.meteo.common.result.PageResult;
import com.campus.meteo.common.result.Result;
import com.campus.meteo.dto.AlertRuleSaveReq;
import com.campus.meteo.entity.AlertRule;
import com.campus.meteo.service.AlertService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 告警规则管理接口
 */
@Tag(name = "灾害告警-规则")
@RestController
@RequestMapping("/alert-rules")
@RequiredArgsConstructor
public class AlertRuleController {

    private final AlertService alertService;

    @Operation(summary = "告警规则分页查询")
    @GetMapping
    @PreAuthorize("hasAuthority('alert:view')")
    public Result<PageResult<AlertRule>> pageRules(@RequestParam(defaultValue = "1") long pageNum,
                                                   @RequestParam(defaultValue = "20") long pageSize,
                                                   @RequestParam(required = false) Long stationId,
                                                   @RequestParam(required = false) Integer alertType,
                                                   @RequestParam(required = false) Integer status) {
        return Result.ok(alertService.pageRules(pageNum, pageSize, stationId, alertType, status));
    }

    @Operation(summary = "新增告警规则")
    @PostMapping
    @PreAuthorize("hasAuthority('alert:manage')")
    public Result<Void> createRule(@Valid @RequestBody AlertRuleSaveReq req) {
        alertService.createRule(req);
        return Result.ok();
    }

    @Operation(summary = "修改告警规则")
    @PutMapping("/{id}")
    @PreAuthorize("hasAuthority('alert:manage')")
    public Result<Void> updateRule(@PathVariable Long id, @Valid @RequestBody AlertRuleSaveReq req) {
        alertService.updateRule(id, req);
        return Result.ok();
    }

    @Operation(summary = "删除告警规则")
    @DeleteMapping("/{id}")
    @PreAuthorize("hasAuthority('alert:manage')")
    public Result<Void> deleteRule(@PathVariable Long id) {
        alertService.deleteRule(id);
        return Result.ok();
    }

    @Operation(summary = "启用/停用告警规则")
    @PatchMapping("/{id}/status")
    @PreAuthorize("hasAuthority('alert:manage')")
    public Result<Void> toggleRule(@PathVariable Long id, @RequestParam boolean enabled) {
        alertService.toggleRuleStatus(id, enabled);
        return Result.ok();
    }
}
