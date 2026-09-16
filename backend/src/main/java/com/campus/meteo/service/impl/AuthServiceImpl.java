package com.campus.meteo.service.impl;

import com.campus.meteo.common.exception.BizException;
import com.campus.meteo.common.result.ErrorCode;
import com.campus.meteo.dto.LoginReq;
import com.campus.meteo.dto.LoginResp;
import com.campus.meteo.security.CustomUserDetailsService;
import com.campus.meteo.security.JwtTokenProvider;
import com.campus.meteo.security.LoginUser;
import com.campus.meteo.security.TokenStore;
import com.campus.meteo.service.AuthService;
import io.jsonwebtoken.Claims;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.authentication.DisabledException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Service;

import java.time.Duration;

/**
 * 认证服务实现
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AuthServiceImpl implements AuthService {

    private final AuthenticationManager authenticationManager;
    private final JwtTokenProvider tokenProvider;
    private final TokenStore tokenStore;
    private final CustomUserDetailsService userDetailsService;

    @Override
    public LoginResp login(LoginReq req) {
        Authentication authentication;
        try {
            authentication = authenticationManager.authenticate(
                    new UsernamePasswordAuthenticationToken(req.getUsername(), req.getPassword()));
        } catch (BadCredentialsException e) {
            throw new BizException(ErrorCode.BAD_CREDENTIALS);
        } catch (DisabledException e) {
            throw new BizException(ErrorCode.ACCOUNT_DISABLED);
        }
        LoginUser user = (LoginUser) authentication.getPrincipal();

        String accessToken = tokenProvider.createAccessToken(user.getUserId(), user.getUsername());
        String refreshToken = tokenProvider.createRefreshToken(user.getUserId(), user.getUsername());
        tokenStore.saveRefreshToken(user.getUserId(), refreshToken,
                Duration.ofSeconds(tokenProvider.getRefreshExpireSeconds()));
        log.info("用户登录成功: userId={}, username={}", user.getUserId(), user.getUsername());

        return buildResp(accessToken, refreshToken, user);
    }

    @Override
    public LoginResp refresh(String refreshToken) {
        Claims claims = tokenProvider.parse(refreshToken);
        if (claims == null || !tokenProvider.isRefreshToken(claims)) {
            throw new BizException(ErrorCode.UNAUTHORIZED, "refreshToken 无效或已过期");
        }
        Long userId = tokenProvider.getUserId(claims);
        if (!tokenStore.matchRefreshToken(userId, refreshToken)) {
            throw new BizException(ErrorCode.UNAUTHORIZED, "refreshToken 已失效，请重新登录");
        }
        String username = tokenProvider.getUsername(claims);

        String newAccessToken = tokenProvider.createAccessToken(userId, username);
        String newRefreshToken = tokenProvider.createRefreshToken(userId, username);
        tokenStore.saveRefreshToken(userId, newRefreshToken,
                Duration.ofSeconds(tokenProvider.getRefreshExpireSeconds()));

        LoginUser user = (LoginUser) userDetailsService.loadUserByUsername(username);
        return buildResp(newAccessToken, newRefreshToken, user);
    }

    @Override
    public void logout(String accessToken) {
        Claims claims = tokenProvider.parse(accessToken);
        if (claims == null) {
            return;
        }
        Long userId = tokenProvider.getUserId(claims);
        tokenStore.removeRefreshToken(userId);
        // access token 黑名单至自然过期
        long remainMillis = claims.getExpiration().getTime() - System.currentTimeMillis();
        if (remainMillis > 0) {
            tokenStore.blacklist(accessToken, Duration.ofMillis(remainMillis));
        }
        log.info("用户登出: userId={}", userId);
    }

    private LoginResp buildResp(String accessToken, String refreshToken, LoginUser user) {
        return LoginResp.builder()
                .accessToken(accessToken)
                .refreshToken(refreshToken)
                .expiresIn(0)
                .user(LoginResp.UserInfo.builder()
                        .id(user.getUserId())
                        .username(user.getUsername())
                        .roles(user.getRoleCodes())
                        .build())
                .build();
    }
}
