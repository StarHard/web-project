package com.campus.meteo.controller;

import com.campus.meteo.common.result.PageResult;
import com.campus.meteo.common.result.Result;
import com.campus.meteo.dto.UserResp;
import com.campus.meteo.dto.UserSaveReq;
import com.campus.meteo.service.UserService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
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
 * 用户管理接口
 */
@Tag(name = "用户管理")
@RestController
@RequestMapping("/users")
@RequiredArgsConstructor
public class UserController {

    private final UserService userService;

    @Operation(summary = "用户分页列表（含角色）")
    @GetMapping
    @PreAuthorize("hasAuthority('user:manage')")
    public Result<PageResult<UserResp>> page(@RequestParam(defaultValue = "1") long pageNum,
                                             @RequestParam(defaultValue = "20") long pageSize,
                                             @RequestParam(required = false) String keyword,
                                             @RequestParam(required = false) Integer status) {
        return Result.ok(userService.page(pageNum, pageSize, keyword, status));
    }

    @Operation(summary = "新增用户（含角色分配）")
    @PostMapping
    @PreAuthorize("hasAuthority('user:manage')")
    public Result<Void> create(@Valid @RequestBody UserSaveReq req) {
        userService.create(req);
        return Result.ok();
    }

    @Operation(summary = "修改用户（登录名不可改，密码留空表示不修改）")
    @PutMapping("/{id}")
    @PreAuthorize("hasAuthority('user:manage')")
    public Result<Void> update(@PathVariable Long id, @Valid @RequestBody UserSaveReq req) {
        userService.update(id, req);
        return Result.ok();
    }

    @Operation(summary = "启用/禁用账号")
    @PatchMapping("/{id}/status")
    @PreAuthorize("hasAuthority('user:manage')")
    public Result<Void> changeStatus(@PathVariable Long id, @RequestParam boolean enabled) {
        userService.changeStatus(id, enabled);
        return Result.ok();
    }
}