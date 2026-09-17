package com.campus.meteo.security;

import lombok.Getter;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.userdetails.UserDetails;

import java.util.Collection;
import java.util.List;

/**
 * 登录用户主体：角色编码加 ROLE_ 前缀（hasRole 使用），权限编码原样（hasAuthority 使用）
 */
@Getter
public class LoginUser implements UserDetails {

    private final Long userId;
    private final String username;
    private final String password;
    private final String realName;
    private final boolean enabled;
    private final List<String> roleCodes;
    private final List<String> permCodes;

    public LoginUser(Long userId, String username, String password, String realName, boolean enabled,
                     List<String> roleCodes, List<String> permCodes) {
        this.userId = userId;
        this.username = username;
        this.password = password;
        this.realName = realName;
        this.enabled = enabled;
        this.roleCodes = roleCodes;
        this.permCodes = permCodes;
    }

    @Override
    public Collection<? extends GrantedAuthority> getAuthorities() {
        return java.util.stream.Stream.concat(
                roleCodes.stream().map(r -> new SimpleGrantedAuthority("ROLE_" + r)),
                permCodes.stream().map(SimpleGrantedAuthority::new)
        ).toList();
    }

    @Override
    public String getPassword() {
        return password;
    }

    @Override
    public String getUsername() {
        return username;
    }

    @Override
    public boolean isEnabled() {
        return enabled;
    }

    @Override
    public boolean isAccountNonExpired() {
        return true;
    }

    @Override
    public boolean isAccountNonLocked() {
        return true;
    }

    @Override
    public boolean isCredentialsNonExpired() {
        return true;
    }
}
