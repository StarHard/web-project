package com.campus.meteo.mapper;

import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

/**
 * 角色-权限关联 Mapper（关联表无独立实体，直接操作）
 */
@Mapper
public interface RolePermissionMapper {

    /** 清空角色的权限绑定（用于全量重设） */
    @Delete("DELETE FROM role_permission WHERE role_id = #{roleId}")
    int deleteByRoleId(@Param("roleId") Long roleId);

    /** 按权限编码批量绑定 */
    @Insert("<script>INSERT INTO role_permission (role_id, perm_id) "
            + "SELECT #{roleId}, id FROM permission WHERE perm_code IN "
            + "<foreach collection='permCodes' item='code' open='(' separator=',' close=')'>#{code}</foreach>"
            + "</script>")
    int insertByPermCodes(@Param("roleId") Long roleId, @Param("permCodes") List<String> permCodes);

    /** 查询角色拥有的权限编码 */
    @Select("SELECT p.perm_code FROM role_permission rp "
            + "JOIN permission p ON p.id = rp.perm_id WHERE rp.role_id = #{roleId}")
    List<String> selectPermCodesByRoleId(@Param("roleId") Long roleId);
}