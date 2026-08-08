package com.chaltteok.answer;

/**
 * 내부 답변 결과. docs/api-contract.md §8.2.
 * 외부 {@code AnswerSuccessResponse}보다 많은 정보를 가질 수 있으나,
 * 초기 구현에서는 외부 다섯 필드에 대응하는 값만 담는다.
 */
public record AnswerResult(
        String questionId,
        String question,
        String retrievedContext,
        String thinkTrace,
        String answer
) {
}
