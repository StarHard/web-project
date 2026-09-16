package com.campus.meteo.security;

import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;

/**
 * 安全上下文工具：获取当前登录用户
 */
public final class SecurityUtils {

    private SecurityUtils() {
    }

    /** 获取当前登录用户，未登录返回 null */
    public static LoginUser getCurrentUser() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication != null && authentication.getPrincipal() instanceof LoginUser loginUser) {
            return loginUser;
        }
        return null;
    }

    /** 获取当前登录用户ID，未登录抛出异常 */
    public static Long getCurrentUserId() {
        LoginUser user = getCurrentUser();
        if (user == null) {
            throw new IllegalStateException("当前无登录用户");
        }
        return user.getUserId();
    }
}
