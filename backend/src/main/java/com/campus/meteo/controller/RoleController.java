package com.campus.meteo.controller;

import com.campus.meteo.common.result.Result;
import com.campus.meteo.dto.RoleResp;
import com.campus.meteo.dto.RoleSaveReq;
import com.campus.meteo.entity.Permission;
import com.campus.meteo.service.RoleService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 角色与权限接口
 */
@Tag(name = "角色管理")
@RestController
@RequestMapping("/roles")
@RequiredArgsConstructor
public class RoleController {

    private final RoleService roleService;

    @Operation(summary = "角色列表（含权限编码）")
    @GetMapping
    @PreAuthorize("hasAuthority('sys:manage')")
    public Result<List<RoleResp>> list() {
        return Result.ok(roleService.list());
    }

    @Operation(summary = "权限清单（供角色权限配置选择）")
    @GetMapping("/permissions")
    @PreAuthorize("hasAuthority('sys:manage')")
    public Result<List<Permission>> permissions() {
        return Result.ok(roleService.listPermissions());
    }

    @Operation(summary = "新增角色（含权限分配）")
    @PostMapping
    @PreAuthorize("hasAuthority('sys:manage')")
    public Result<Void> create(@Valid @RequestBody RoleSaveReq req) {
        roleService.create(req);
        return Result.ok();
    }

    @Operation(summary = "修改角色（权限全量重设）")
    @PutMapping("/{id}")
    @PreAuthorize("hasAuthority('sys:manage')")
    public Result<Void> update(@PathVariable Long id, @Valid @RequestBody RoleSaveReq req) {
        roleService.update(id, req);
        return Result.ok();
    }
}