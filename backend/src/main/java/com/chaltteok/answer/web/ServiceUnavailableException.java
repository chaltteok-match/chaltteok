package com.chaltteok.answer.web;

/**
 * HyperCLOVA X·DB 등 필수 의존성의 일시 장애. docs/api-contract.md §7 → HTTP 503.
 */
public class ServiceUnavailableException extends RuntimeException {

    private final String questionId;

    public ServiceUnavailableException(String questionId, String message) {
        super(message);
        this.questionId = questionId;
    }

    public ServiceUnavailableException(String questionId, String message, Throwable cause) {
        super(message, cause);
        this.questionId = questionId;
    }

    public String questionId() {
        return questionId;
    }
}
