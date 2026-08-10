package com.chaltteok.answer.dto;

import org.springframework.http.HttpStatus;

/**
 * 평가 API 오류 코드. docs/api-contract.md §7.3.
 */
public enum ErrorCode {
    /** HTTP 400, retryable=false — question_id 또는 question 누락·공백 */
    INVALID_REQUEST(HttpStatus.BAD_REQUEST, false),

    /** HTTP 405, retryable=false — /answer에 GET 이외의 메서드 사용 */
    METHOD_NOT_ALLOWED(HttpStatus.METHOD_NOT_ALLOWED, false),

    /** HTTP 500, retryable=false — 분류되지 않은 서버 내부 오류 */
    INTERNAL_SERVER_ERROR(HttpStatus.INTERNAL_SERVER_ERROR, false),

    /** HTTP 503, retryable=true — HyperCLOVA X 또는 DB 등의 일시 장애 */
    SERVICE_UNAVAILABLE(HttpStatus.SERVICE_UNAVAILABLE, true);

    private final HttpStatus httpStatus;
    private final boolean retryable;

    ErrorCode(HttpStatus httpStatus, boolean retryable) {
        this.httpStatus = httpStatus;
        this.retryable = retryable;
    }

    public HttpStatus httpStatus() {
        return httpStatus;
    }

    public boolean retryable() {
        return retryable;
    }
}
