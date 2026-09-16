package com.campus.meteo.common.result;

import com.fasterxml.jackson.annotation.JsonInclude;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Getter;

/**
 * 统一响应结构，规范见《RESTful API接口规范》1.2 节
 */
@Getter
@Schema(description = "统一响应结构")
@JsonInclude(JsonInclude.Include.NON_NULL)
public class Result<T> {

    @Schema(description = "错误码，0 表示成功")
    private final int code;

    @Schema(description = "提示信息")
    private final String message;

    @Schema(description = "业务数据")
    private final T data;

    @Schema(description = "服务器毫秒时间戳")
    private final long timestamp;

    private Result(int code, String message, T data) {
        this.code = code;
        this.message = message;
        this.data = data;
        this.timestamp = System.currentTimeMillis();
    }

    public static <T> Result<T> ok() {
        return new Result<>(ErrorCode.SUCCESS.getCode(), ErrorCode.SUCCESS.getMessage(), null);
    }

    public static <T> Result<T> ok(T data) {
        return new Result<>(ErrorCode.SUCCESS.getCode(), ErrorCode.SUCCESS.getMessage(), data);
    }

    public static <T> Result<T> fail(ErrorCode errorCode) {
        return new Result<>(errorCode.getCode(), errorCode.getMessage(), null);
    }

    public static <T> Result<T> fail(ErrorCode errorCode, String message) {
        return new Result<>(errorCode.getCode(), message, null);
    }
}
