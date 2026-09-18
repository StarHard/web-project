package com.campus.meteo.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.campus.meteo.entity.User;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

/**
 * 用户 Mapper
 */
@Mapper
public interface UserMapper extends BaseMapper<User> {

    /**
     * 用户名占用检查（包含已逻辑删除的记录）
     *
     * 唯一索引 uk_username 对物理行生效，逻辑删除不会释放用户名。
     */
    @Select("<script>SELECT COUNT(1) FROM `user` WHERE username = #{username}"
            + "<if test='excludeId != null'> AND id &lt;&gt; #{excludeId}</if></script>")
    long countByUsernameIncludingDeleted(@Param("username") String username,
                                         @Param("excludeId") Long excludeId);
}