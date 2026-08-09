package com.chaltteok.hcx;

public class HcxClientException extends RuntimeException {

    private final HcxErrorType errorType;

    public HcxClientException(HcxErrorType errorType, String message) {
        super(message);
        this.errorType = errorType;
    }

    public HcxClientException(HcxErrorType errorType, String message, Throwable cause) {
        super(message, cause);
        this.errorType = errorType;
    }

    public HcxErrorType getErrorType() {
        return errorType;
    }
}
