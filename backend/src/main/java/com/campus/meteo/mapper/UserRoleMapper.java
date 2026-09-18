package com.campus.meteo.mapper;

import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

/**
 * 用户-角色关联 Mapper（关联表无独立实体，直接操作）
 */
@Mapper
public interface UserRoleMapper {

    /** 清空用户的角色绑定（用于全量重设） */
    @Delete("DELETE FROM user_role WHERE user_id = #{userId}")
    int deleteByUserId(@Param("userId") Long userId);

    /** 按角色编码批量绑定 */
    @Insert("<script>INSERT INTO user_role (user_id, role_id) "
            + "SELECT #{userId}, id FROM role WHERE role_code IN "
            + "<foreach collection='roleCodes' item='code' open='(' separator=',' close=')'>#{code}</foreach>"
            + "</script>")
    int insertByRoleCodes(@Param("userId") Long userId, @Param("roleCodes") List<String> roleCodes);

    /** 统计角色被引用次数（删除角色前校验） */
    @Select("SELECT COUNT(1) FROM user_role WHERE role_id = #{roleId}")
    long countByRoleId(@Param("roleId") Long roleId);
}