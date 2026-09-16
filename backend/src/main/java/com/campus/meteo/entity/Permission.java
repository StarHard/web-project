package com.campus.meteo.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.EqualsAndHashCode;

/**
 * 权限表实体
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("`permission`")
public class Permission extends BaseEntity {

    /** 权限编码，如 station:view */
    private String permCode;

    /** 权限名称 */
    private String permName;
}
