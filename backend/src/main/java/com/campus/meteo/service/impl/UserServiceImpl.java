package com.campus.meteo.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.campus.meteo.common.exception.BizException;
import com.campus.meteo.common.result.ErrorCode;
import com.campus.meteo.common.result.PageResult;
import com.campus.meteo.dto.UserResp;
import com.campus.meteo.dto.UserSaveReq;
import com.campus.meteo.entity.User;
import com.campus.meteo.mapper.RoleMapper;
import com.campus.meteo.mapper.UserMapper;
import com.campus.meteo.mapper.UserRoleMapper;
import com.campus.meteo.security.SecurityUtils;
import com.campus.meteo.service.OperationLogService;
import com.campus.meteo.service.UserService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.util.List;

/**
 * 用户管理实现
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class UserServiceImpl implements UserService {

    private final UserMapper userMapper;
    private final UserRoleMapper userRoleMapper;
    private final RoleMapper roleMapper;
    private final PasswordEncoder passwordEncoder;
    private final OperationLogService operationLogService;

    @Override
    public PageResult<UserResp> page(long pageNum, long pageSize, String keyword, Integer status) {
        Page<User> page = userMapper.selectPage(new Page<>(pageNum, pageSize),
                new LambdaQueryWrapper<User>()
                        .and(StringUtils.hasText(keyword), w -> w
                                .like(User::getUsername, keyword)
                                .or().like(User::getRealName, keyword))
                        .eq(status != null, User::getStatus, status)
                        .orderByAsc(User::getId));
        List<UserResp> list = page.getRecords().stream().map(this::toResp).toList();
        return PageResult.of(page.getTotal(), page.getCurrent(), page.getSize(), list);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void create(UserSaveReq req) {
        if (userMapper.countByUsernameIncludingDeleted(req.getUsername(), null) > 0) {
            throw new BizException(ErrorCode.USER_EXISTS,
                    "用户名已存在（已删除用户的用户名仍被唯一索引占用，请更换）");
        }
        if (!StringUtils.hasText(req.getPassword())) {
            throw new BizException(ErrorCode.PARAM_ERROR, "新增用户必须设置初始密码");
        }
        User user = new User();
        user.setUsername(req.getUsername());
        user.setPassword(passwordEncoder.encode(req.getPassword()));
        user.setRealName(req.getRealName());
        user.setPhone(req.getPhone());
        user.setEmail(req.getEmail());
        user.setStatus(1);
        userMapper.insert(user);

        bindRoles(user.getId(), req.getRoleCodes());
        operationLogService.record("用户管理", "新增用户", req.getUsername());
        log.info("新增用户: id={}, username={}", user.getId(), user.getUsername());
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void update(Long id, UserSaveReq req) {
        if (userMapper.selectById(id) == null) {
            throw new BizException(ErrorCode.NOT_FOUND, "用户不存在");
        }
        User user = new User();
        user.setId(id);
        user.setRealName(req.getRealName());
        user.setPhone(req.getPhone());
        user.setEmail(req.getEmail());
        // 登录名不参与修改；密码留空表示保持原密码
        if (StringUtils.hasText(req.getPassword())) {
            user.setPassword(passwordEncoder.encode(req.getPassword()));
        }
        userMapper.updateById(user);

        bindRoles(id, req.getRoleCodes());
        operationLogService.record("用户管理", "修改用户", "userId=" + id);
    }

    @Override
    public void changeStatus(Long id, boolean enabled) {
        User target = userMapper.selectById(id);
        if (target == null) {
            throw new BizException(ErrorCode.NOT_FOUND, "用户不存在");
        }
        // 禁止禁用自己，避免管理员把自身锁死
        if (!enabled && id.equals(SecurityUtils.getCurrentUserId())) {
            throw new BizException(ErrorCode.PARAM_ERROR, "不能禁用当前登录账号");
        }
        User update = new User();
        update.setId(id);
        update.setStatus(enabled ? 1 : 0);
        userMapper.updateById(update);
        operationLogService.record("用户管理", enabled ? "启用用户" : "禁用用户", "userId=" + id);
    }

    /** 全量重设用户角色绑定；roleCodes 为 null 表示不改动 */
    private void bindRoles(Long userId, List<String> roleCodes) {
        if (roleCodes == null) {
            return;
        }
        userRoleMapper.deleteByUserId(userId);
        if (!roleCodes.isEmpty()) {
            userRoleMapper.insertByRoleCodes(userId, roleCodes);
        }
    }

    private UserResp toResp(User user) {
        UserResp resp = new UserResp();
        resp.setId(user.getId());
        resp.setUsername(user.getUsername());
        resp.setRealName(user.getRealName());
        resp.setPhone(user.getPhone());
        resp.setEmail(user.getEmail());
        resp.setStatus(user.getStatus());
        resp.setCreateTime(user.getCreateTime());
        resp.setRoleCodes(roleMapper.selectRoleCodesByUserId(user.getId()));
        return resp;
    }
}