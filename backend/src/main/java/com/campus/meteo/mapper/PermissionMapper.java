package com.campus.meteo.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.campus.meteo.entity.Permission;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

/**
 * 权限 Mapper
 */
@Mapper
public interface PermissionMapper extends BaseMapper<Permission> {

    /**
     * 查询用户拥有的权限编码（经 user_role → role_permission 关联）
     */
    @Select("""
            SELECT DISTINCT p.perm_code
            FROM user_role ur
            JOIN role_permission rp ON rp.role_id = ur.role_id
            JOIN permission p ON p.id = rp.perm_id
            WHERE ur.user_id = #{userId}
            """)
    List<String> selectPermCodesByUserId(@Param("userId") Long userId);
}
