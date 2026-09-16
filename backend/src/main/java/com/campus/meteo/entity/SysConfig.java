package com.campus.meteo.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.EqualsAndHashCode;

/**
 * 系统参数实体
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("sys_config")
public class SysConfig extends BaseEntity {

    /** 参数键 */
    private String configKey;

    /** 参数值 */
    private String configValue;

    /** 说明 */
    private String remark;
}
