package com.campus.meteo.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Builder;
import lombok.Data;

import java.util.List;

/**
 * 登录响应：令牌 + 用户基本信息
 */
@Data
@Builder
@Schema(description = "登录响应")
public class LoginResp {

    @Schema(description = "访问令牌")
    private String accessToken;

    @Schema(description = "刷新令牌")
    private String refreshToken;

    @Schema(description = "访问令牌有效期（秒）")
    private long expiresIn;

    @Schema(description = "用户信息")
    private UserInfo user;

    @Data
    @Builder
    @Schema(description = "用户信息")
    public static class UserInfo {

        @Schema(description = "用户ID")
        private Long id;

        @Schema(description = "用户名")
        private String username;

        @Schema(description = "姓名")
        private String realName;

        @Schema(description = "角色编码列表")
        private List<String> roles;
    }
}
