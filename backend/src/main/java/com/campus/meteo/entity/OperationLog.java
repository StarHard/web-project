package com.campus.meteo.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.EqualsAndHashCode;

/**
 * 操作日志实体
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("operation_log")
public class OperationLog extends BaseEntity {

    /** 操作人 */
    private Long userId;

    /** 模块 */
    private String module;

    /** 操作 */
    private String operation;

    /** 请求方法 */
    private String method;

    /** 请求参数（脱敏后） */
    private String params;

    /** 来源IP */
    private String ip;

    /** 结果：0失败 1成功 */
    private Integer result;

    /** 耗时（毫秒） */
    private Integer costMs;
}