package com.campus.meteo.service;

import com.campus.meteo.common.result.PageResult;
import com.campus.meteo.dto.UserResp;
import com.campus.meteo.dto.UserSaveReq;

/**
 * 用户管理服务
 */
public interface UserService {

    /** 用户分页（keyword 匹配登录名或姓名） */
    PageResult<UserResp> page(long pageNum, long pageSize, String keyword, Integer status);

    void create(UserSaveReq req);

    /** 修改用户信息与角色绑定（登录名不可修改） */
    void update(Long id, UserSaveReq req);

    /** 启用/禁用账号 */
    void changeStatus(Long id, boolean enabled);
}