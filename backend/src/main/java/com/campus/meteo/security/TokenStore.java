package com.campus.meteo.security;

import lombok.RequiredArgsConstructor;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.time.Duration;

/**
 * Token 存取（Redis）：
 * 1. refresh token 以用户为键存储，重复登录覆盖旧令牌（单设备登录语义）
 * 2. 登出时 access/refresh token 进入黑名单直至自然过期
 */
@Service
@RequiredArgsConstructor
public class TokenStore {

    private static final String KEY_REFRESH = "meteo:token:refresh:";
    private static final String KEY_BLACKLIST = "meteo:token:black:";

    private final StringRedisTemplate redisTemplate;

    /** 保存用户的 refresh token */
    public void saveRefreshToken(Long userId, String refreshToken, Duration ttl) {
        redisTemplate.opsForValue().set(KEY_REFRESH + userId, refreshToken, ttl);
    }

    /** 校验 refresh token 与 Redis 中存储的是否一致 */
    public boolean matchRefreshToken(Long userId, String refreshToken) {
        String stored = redisTemplate.opsForValue().get(KEY_REFRESH + userId);
        return refreshToken.equals(stored);
    }

    /** 移除用户的 refresh token（登出） */
    public void removeRefreshToken(Long userId) {
        redisTemplate.delete(KEY_REFRESH + userId);
    }

    /** 将令牌加入黑名单 */
    public void blacklist(String token, Duration ttl) {
        if (!ttl.isNegative() && !ttl.isZero()) {
            redisTemplate.opsForValue().set(KEY_BLACKLIST + token, "1", ttl);
        }
    }

    /** 判断令牌是否在黑名单中 */
    public boolean isBlacklisted(String token) {
        return Boolean.TRUE.equals(redisTemplate.hasKey(KEY_BLACKLIST + token));
    }
}
