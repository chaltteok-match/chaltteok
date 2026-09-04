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
     * <p>인증·설정 오류와 429가 아닌 4xx는 다시 걸어도 같은 결과다. 응답에 답변이 없는
     * 경우도 재시도 대상이 아니다({@code temperature}가 낮아 같은 응답이 온다).
     *
     * <p>{@code INVALID_RESPONSE}는 <b>모델이 빈 텍스트를 준 경우만</b> 가리킨다. 끊긴 연결은
     * {@code TIMEOUT}, 읽을 수 없는 응답 본문은 Spring AI 예외를 거쳐 {@code UPSTREAM}이 되어
     * 둘 다 재시도된다({@code SpringAiHcxClient} 참조).
     */
    public boolean isRetryable() {
        return errorType == HcxErrorType.RATE_LIMIT
                || errorType == HcxErrorType.TIMEOUT
                || errorType == HcxErrorType.UPSTREAM;
    }
}
