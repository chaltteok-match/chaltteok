package com.chaltteok.answer.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.validation.constraints.NotNull;

/**
 * 평가 API 정상 응답. docs/api-contract.md §4.2.
 * 다섯 필드 모두 필수이며 null을 반환하지 않는다.
 */
public record AnswerSuccessResponse(
        @JsonProperty(required = true)
        @NotNull
        String questionId,

        @JsonProperty(required = true)
        @NotNull
        String question,

        @JsonProperty(required = true)
        @NotNull
        String retrievedContext,

        @JsonProperty(required = true)
        @NotNull
        String thinkTrace,

        @JsonProperty(required = true)
        @NotNull
        String answer
) {
}
