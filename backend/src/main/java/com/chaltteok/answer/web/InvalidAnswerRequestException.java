package com.chaltteok.answer.web;

/**
 * 평가 API 요청 파라미터 검증 실패. docs/api-contract.md §3.1, §7.4.
 */
public class InvalidAnswerRequestException extends RuntimeException {

    private final String questionId;

    public InvalidAnswerRequestException(String questionId, String message) {
        super(message);
        this.questionId = questionId;
    }

    /**
     * 요청에서 유효한 question_id를 받았으면 그 값, 아니면 null.
     */
    public String questionId() {
        return questionId;
    }
}
