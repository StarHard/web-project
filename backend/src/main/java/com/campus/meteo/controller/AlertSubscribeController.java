package com.campus.meteo.controller;

import com.campus.meteo.common.result.Result;
import com.campus.meteo.dto.AlertSubscribeReq;
import com.campus.meteo.entity.AlertSubscribe;
import com.campus.meteo.service.AlertSubscribeService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 告警订阅接口（仅操作当前登录用户自身订阅）
 */
@Tag(name = "告警订阅")
@RestController
@RequestMapping("/alert-subscribes")
@RequiredArgsConstructor
public class AlertSubscribeController {

    private final AlertSubscribeService alertSubscribeService;

    @Operation(summary = "我的告警订阅")
    @GetMapping
    @PreAuthorize("isAuthenticated()")
    public Result<List<AlertSubscribe>> mySubscribes() {
        return Result.ok(alertSubscribeService.mySubscribes());
    }

    @Operation(summary = "订阅/更新订阅（站点+类型+渠道）")
    @PostMapping
    @PreAuthorize("isAuthenticated()")
    public Result<Void> subscribe(@Valid @RequestBody AlertSubscribeReq req) {
        alertSubscribeService.subscribe(req);
        return Result.ok();
    }

    @Operation(summary = "取消订阅")
    @DeleteMapping
    @PreAuthorize("isAuthenticated()")
    public Result<Void> unsubscribe(@RequestParam(required = false) Long stationId,
                                    @RequestParam(required = false) Integer alertType) {
        alertSubscribeService.unsubscribe(stationId, alertType);
        return Result.ok();
    }
}