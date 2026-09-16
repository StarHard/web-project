package com.campus.meteo.controller;

import com.campus.meteo.common.result.Result;
import com.campus.meteo.dto.LoginReq;
import com.campus.meteo.dto.LoginResp;
import com.campus.meteo.security.LoginUser;
import com.campus.meteo.security.SecurityUtils;
import com.campus.meteo.service.AuthService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import lombok.RequiredArgsConstructor;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 认证接口
 */
@Tag(name = "认证")
@RestController
@RequestMapping("/auth")
@RequiredArgsConstructor
public class AuthController {

    private final AuthService authService;

    @Operation(summary = "登录")
    @PostMapping("/login")
    public Result<LoginResp> login(@Valid @RequestBody LoginReq req) {
        return Result.ok(authService.login(req));
    }

    @Operation(summary = "刷新令牌")
    @PostMapping("/refresh")
    public Result<LoginResp> refresh(@RequestBody RefreshReq req) {
        return Result.ok(authService.refresh(req.refreshToken()));
    }

    @Operation(summary = "登出")
    @PostMapping("/logout")
    public Result<Void> logout(@RequestHeader(value = "Authorization", required = false) String authorization) {
        if (StringUtils.hasText(authorization) && authorization.startsWith("Bearer ")) {
            authService.logout(authorization.substring(7));
        }
        return Result.ok();
    }

    @Operation(summary = "当前用户信息")
    @GetMapping("/profile")
    public Result<ProfileResp> profile() {
        LoginUser user = SecurityUtils.getCurrentUser();
        if (user == null) {
            return Result.fail(com.campus.meteo.common.result.ErrorCode.UNAUTHORIZED);
        }
        return Result.ok(new ProfileResp(user.getUserId(), user.getUsername(), user.getRoleCodes()));
    }

    public record RefreshReq(@NotBlank(message = "refreshToken 不能为空") String refreshToken) {
    }

    public record ProfileResp(Long id, String username, List<String> roles) {
    }
}
