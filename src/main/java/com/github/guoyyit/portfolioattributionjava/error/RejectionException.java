package com.github.guoyyit.portfolioattributionjava.error;

public class RejectionException extends RuntimeException {

    private final ErrorCode errorCode;

    public RejectionException(ErrorCode errorCode, String message) {
        super(message);
        this.errorCode = errorCode;
    }

    public ErrorCode getErrorCode() {
        return errorCode;
    }
}
