package com.campus.meteo.service;

import com.campus.meteo.dto.RoleResp;
import com.campus.meteo.dto.RoleSaveReq;
import com.campus.meteo.entity.Permission;

import java.util.List;

/**
 * 角色与权限分配服务
 */
public interface RoleService {

    /** 角色列表（含各自权限编码） */
    List<RoleResp> list();

    /** 权限清单（供角色权限配置选择） */
    List<Permission> listPermissions();

    void create(RoleSaveReq req);

    /** 修改角色名称与权限（权限全量重设） */
    void update(Long id, RoleSaveReq req);
}