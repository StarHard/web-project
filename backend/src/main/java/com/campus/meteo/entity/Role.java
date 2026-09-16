package com.campus.meteo.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.EqualsAndHashCode;

/**
 * 角色表实体
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("`role`")
public class Role extends BaseEntity {

    /** 角色编码：ADMIN/FORECASTER/OPERATOR/USER */
    private String roleCode;

    /** 角色名称 */
    private String roleName;
}
