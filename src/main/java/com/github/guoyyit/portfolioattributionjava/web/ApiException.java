package com.github.guoyyit.portfolioattributionjava.web;

/** 业务拒绝异常：携带稳定的 ErrorCode。 */
public class ApiException extends RuntimeException {

    private final ErrorCode errorCode;

    public ApiException(ErrorCode errorCode, String message) {
        super(message);
        this.errorCode = errorCode;
    }

    public ErrorCode getErrorCode() {
        return errorCode;
    }
}
