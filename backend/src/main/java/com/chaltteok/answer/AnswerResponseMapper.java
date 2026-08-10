package com.chaltteok.answer;

import com.chaltteok.answer.dto.AnswerSuccessResponse;

/**
 * 내부 {@link AnswerResult} → 외부 평가 API DTO 변환.
 * 검색·계산·LLM 로직은 포함하지 않는다.
 */
public final class AnswerResponseMapper {

    private AnswerResponseMapper() {
    }

    public static AnswerSuccessResponse toSuccessResponse(AnswerResult result) {
        return new AnswerSuccessResponse(
                result.questionId(),
                result.question(),
                result.retrievedContext(),
                result.thinkTrace(),
                result.answer()
        );
    }
}
