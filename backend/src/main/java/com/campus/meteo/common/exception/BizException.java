package com.campus.meteo.common.exception;

import com.campus.meteo.common.result.ErrorCode;
import lombok.Getter;

/**
 * 业务异常，由全局异常处理器统一捕获转换为 Result
 */
@Getter
public class BizException extends RuntimeException {

    private final ErrorCode errorCode;

    public BizException(ErrorCode errorCode) {
        super(errorCode.getMessage());
        this.errorCode = errorCode;
    }

    public BizException(ErrorCode errorCode, String message) {
        super(message);
        this.errorCode = errorCode;
    }
}
