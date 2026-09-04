package com.chaltteok.hcx;

public enum HcxErrorType {
    CONFIGURATION,
    AUTHENTICATION,
    /** 우리 요청이 잘못됐다. 429가 아닌 4xx. 다시 걸어도 같다. */
    BAD_REQUEST,
    RATE_LIMIT,
    TIMEOUT,
    UPSTREAM,
    INVALID_RESPONSE
}
