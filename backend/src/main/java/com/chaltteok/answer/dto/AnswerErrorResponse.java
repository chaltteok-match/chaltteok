package com.chaltteok.answer.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.validation.constraints.NotNull;

/**
 * 평가 API 오류 응답. docs/api-contract.md §7.2.
 * {@code question_id}만 null을 허용한다(요청에서 식별자를 받지 못한 경우).
 */
public record AnswerErrorResponse(
        @JsonProperty(required = true)
        String questionId,

        @JsonProperty(required = true)
        @NotNull
        String errorCode,

        @JsonProperty(required = true)
        @NotNull
        String message,

        @JsonProperty(required = true)
        boolean retryable
) {
    public static AnswerErrorResponse of(String questionId, ErrorCode errorCode, String message) {
        return new AnswerErrorResponse(
                questionId,
                errorCode.name(),
                message,
                errorCode.retryable()
        );
    }
}
