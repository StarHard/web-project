package com.campus.meteo.security;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.campus.meteo.entity.User;
import com.campus.meteo.mapper.PermissionMapper;
import com.campus.meteo.mapper.RoleMapper;
import com.campus.meteo.mapper.UserMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * 按用户名加载用户及其角色/权限
 */
@Service
@RequiredArgsConstructor
public class CustomUserDetailsService implements UserDetailsService {

    private final UserMapper userMapper;
    private final RoleMapper roleMapper;
    private final PermissionMapper permissionMapper;

    @Override
    public UserDetails loadUserByUsername(String username) throws UsernameNotFoundException {
        User user = userMapper.selectOne(new LambdaQueryWrapper<User>().eq(User::getUsername, username));
        if (user == null) {
            throw new UsernameNotFoundException("用户不存在");
        }
        List<String> roleCodes = roleMapper.selectRoleCodesByUserId(user.getId());
        List<String> permCodes = permissionMapper.selectPermCodesByUserId(user.getId());
        return new LoginUser(user.getId(), user.getUsername(), user.getPassword(), user.getRealName(),
                user.getStatus() == 1, roleCodes, permCodes);
    }
}
