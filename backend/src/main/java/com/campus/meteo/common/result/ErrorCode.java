package com.campus.meteo.common.result;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.Getter;

/**
 * 统一错误码定义，编码规则见《RESTful API接口规范》1.4 节
 */
@Getter
public enum ErrorCode {

    /** 0 成功 */
    SUCCESS(0, "success"),

    /** 400xx 通用请求错误 */
    PARAM_ERROR(40001, "参数校验失败"),
    NOT_FOUND(40004, "资源不存在"),
    METHOD_NOT_ALLOWED(40005, "请求方式不支持"),
    DUPLICATE_SUBMIT(40006, "请勿重复提交"),

    /** 401xx 认证失败 */
    UNAUTHORIZED(40101, "未登录或Token已过期"),
    BAD_CREDENTIALS(40102, "用户名或密码错误"),
    ACCOUNT_DISABLED(40103, "账号已被禁用"),

    /** 403xx 权限不足 */
    FORBIDDEN(40301, "无访问权限"),

    /** 500xx 系统错误 */
    DB_ERROR(50001, "数据库服务异常"),
    MQ_ERROR(50002, "消息队列服务异常"),
    REDIS_ERROR(50003, "缓存服务异常"),
    INFLUX_ERROR(50004, "时序数据库服务异常"),
    SYSTEM_ERROR(50000, "系统繁忙，请稍后重试"),

    /** 60xxx 业务错误 */
    STATION_CODE_EXISTS(60001, "站点编码已存在"),
    ALERT_THRESHOLD_INVALID(60002, "告警规则阈值非法"),
    ALERT_RULE_NOT_FOUND(60003, "告警规则不存在"),
    USER_EXISTS(60004, "用户名已存在"),
    EXPORT_TASK_BUSY(60005, "导出任务过于频繁，请稍后重试"),
    DEVICE_CODE_EXISTS(60006, "设备编码已存在"),
    QC_TASK_NOT_FOUND(60007, "质控审核任务不存在"),
    QC_TASK_REVIEWED(60008, "该质控任务已审核，不能重复处理");

    private final int code;
    private final String message;

    ErrorCode(int code, String message) {
        this.code = code;
        this.message = message;
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public String defaultMessage() {
        return message;
    }
}
