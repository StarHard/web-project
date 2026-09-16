package com.campus.meteo.service;

import com.campus.meteo.dto.LoginReq;
import com.campus.meteo.dto.LoginResp;

/**
 * 认证服务
 */
public interface AuthService {

    /** 登录：校验密码并颁发双令牌 */
    LoginResp login(LoginReq req);

    /** 刷新访问令牌 */
    LoginResp refresh(String refreshToken);

    /** 登出：令牌加入黑名单 */
    void logout(String accessToken);
}
