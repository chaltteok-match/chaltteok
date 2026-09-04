package com.chaltteok.hcx;

import java.time.Duration;

public class HcxClientException extends RuntimeException {

    private final HcxErrorType errorType;
    private final Duration retryAfter;

    public HcxClientException(HcxErrorType errorType, String message) {
        this(errorType, message, null, null);
    }

    public HcxClientException(HcxErrorType errorType, String message, Throwable cause) {
        this(errorType, message, null, cause);
    }

    public HcxClientException(
            HcxErrorType errorType, String message, Duration retryAfter, Throwable cause) {
        super(message, cause);
        this.errorType = errorType;
        this.retryAfter = retryAfter;
    }

    public HcxErrorType getErrorType() {
        return errorType;
    }

    /**
     * 서버가 알려준 대기 시간. {@code Retry-After} 헤더가 없으면 {@code null}이다.
     * 있으면 우리 백오프보다 이 값을 따른다.
     */
    public Duration getRetryAfter() {
        return retryAfter;
    }

    /**
     * 다시 걸어 볼 만한 실패인지.
     *
     * <p>인증·설정 오류는 다시 걸어도 같은 결과다. 응답에 답변이 없는 경우도
     * 재시도 대상이 아니다({@code temperature}가 낮아 같은 응답이 온다).
     */
    public boolean isRetryable() {
        return errorType == HcxErrorType.RATE_LIMIT
                || errorType == HcxErrorType.TIMEOUT
                || errorType == HcxErrorType.UPSTREAM;
    }
}
