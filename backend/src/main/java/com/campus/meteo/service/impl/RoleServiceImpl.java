package com.campus.meteo.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.campus.meteo.common.exception.BizException;
import com.campus.meteo.common.result.ErrorCode;
import com.campus.meteo.dto.RoleResp;
import com.campus.meteo.dto.RoleSaveReq;
import com.campus.meteo.entity.Permission;
import com.campus.meteo.entity.Role;
import com.campus.meteo.mapper.PermissionMapper;
import com.campus.meteo.mapper.RoleMapper;
import com.campus.meteo.mapper.RolePermissionMapper;
import com.campus.meteo.service.OperationLogService;
import com.campus.meteo.service.RoleService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * 角色与权限分配实现
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class RoleServiceImpl implements RoleService {

    private final RoleMapper roleMapper;
    private final RolePermissionMapper rolePermissionMapper;
    private final PermissionMapper permissionMapper;
    private final OperationLogService operationLogService;

    @Override
    public List<RoleResp> list() {
        return roleMapper.selectList(new LambdaQueryWrapper<Role>().orderByAsc(Role::getId))
                .stream().map(this::toResp).toList();
    }

    @Override
    public List<Permission> listPermissions() {
        return permissionMapper.selectList(new LambdaQueryWrapper<Permission>().orderByAsc(Permission::getId));
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void create(RoleSaveReq req) {
        if (roleMapper.selectCount(new LambdaQueryWrapper<Role>()
                .eq(Role::getRoleCode, req.getRoleCode())) > 0) {
            throw new BizException(ErrorCode.PARAM_ERROR, "角色编码已存在: " + req.getRoleCode());
        }
        Role role = new Role();
        role.setRoleCode(req.getRoleCode());
        role.setRoleName(req.getRoleName());
        roleMapper.insert(role);

        bindPermissions(role.getId(), req.getPermCodes());
        operationLogService.record("角色管理", "新增角色", req.getRoleCode());
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void update(Long id, RoleSaveReq req) {
        if (roleMapper.selectById(id) == null) {
            throw new BizException(ErrorCode.NOT_FOUND, "角色不存在");
        }
        Role role = new Role();
        role.setId(id);
        role.setRoleName(req.getRoleName());
        roleMapper.updateById(role);

        bindPermissions(id, req.getPermCodes());
        operationLogService.record("角色管理", "修改角色权限", "roleId=" + id);
        log.info("角色权限已更新: roleId={}, perms={}", id, req.getPermCodes());
    }

    /** 全量重设角色权限；permCodes 为 null 表示不改动 */
    private void bindPermissions(Long roleId, List<String> permCodes) {
        if (permCodes == null) {
            return;
        }
        rolePermissionMapper.deleteByRoleId(roleId);
        if (!permCodes.isEmpty()) {
            rolePermissionMapper.insertByPermCodes(roleId, permCodes);
        }
    }

    private RoleResp toResp(Role role) {
        RoleResp resp = new RoleResp();
        resp.setId(role.getId());
        resp.setRoleCode(role.getRoleCode());
        resp.setRoleName(role.getRoleName());
        resp.setPermCodes(rolePermissionMapper.selectPermCodesByRoleId(role.getId()));
        return resp;
    }
}