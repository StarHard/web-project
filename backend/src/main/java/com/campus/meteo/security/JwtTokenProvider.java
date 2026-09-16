package com.campus.meteo.security;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Date;

/**
 * JWT 令牌生成与解析（jjwt 0.12 API）
 */
@Slf4j
@Component
public class JwtTokenProvider {

    private static final String CLAIM_USER_ID = "uid";
    private static final String CLAIM_TYPE = "typ";
    private static final String TYPE_ACCESS = "access";
    private static final String TYPE_REFRESH = "refresh";

    private final SecretKey key;
    private final long accessExpireMinutes;
    private final long refreshExpireDays;

    public JwtTokenProvider(@Value("${meteo.jwt.secret}") String secret,
                            @Value("${meteo.jwt.access-token-expire-minutes}") long accessExpireMinutes,
                            @Value("${meteo.jwt.refresh-token-expire-days}") long refreshExpireDays) {
        this.key = Keys.hmacShaKeyFor(secret.getBytes(StandardCharsets.UTF_8));
        this.accessExpireMinutes = accessExpireMinutes;
        this.refreshExpireDays = refreshExpireDays;
    }

    /** 生成访问令牌 */
    public String createAccessToken(Long userId, String username) {
        return createToken(userId, username, TYPE_ACCESS, Duration.ofMinutes(accessExpireMinutes));
    }

    /** 生成刷新令牌 */
    public String createRefreshToken(Long userId, String username) {
        return createToken(userId, username, TYPE_REFRESH, Duration.ofDays(refreshExpireDays));
    }

    private String createToken(Long userId, String username, String type, Duration ttl) {
        Date now = new Date();
        return Jwts.builder()
                .subject(username)
                .claim(CLAIM_USER_ID, userId)
                .claim(CLAIM_TYPE, type)
                .issuedAt(now)
                .expiration(new Date(now.getTime() + ttl.toMillis()))
                .signWith(key)
                .compact();
    }

    /**
     * 解析并校验令牌
     *
     * @return Claims；令牌无效或已过期返回 null
     */
    public Claims parse(String token) {
        try {
            return Jwts.parser().verifyWith(key).build()
                    .parseSignedClaims(token).getPayload();
        } catch (JwtException | IllegalArgumentException e) {
            log.debug("JWT 解析失败: {}", e.getMessage());
            return null;
        }
    }

    public boolean isAccessToken(Claims claims) {
        return claims != null && TYPE_ACCESS.equals(claims.get(CLAIM_TYPE, String.class));
    }

    public boolean isRefreshToken(Claims claims) {
        return claims != null && TYPE_REFRESH.equals(claims.get(CLAIM_TYPE, String.class));
    }

    public Long getUserId(Claims claims) {
        return claims.get(CLAIM_USER_ID, Long.class);
    }

    public String getUsername(Claims claims) {
        return claims.getSubject();
    }

    public long getRefreshExpireSeconds() {
        return refreshExpireDays * 24 * 3600;
    }
}
