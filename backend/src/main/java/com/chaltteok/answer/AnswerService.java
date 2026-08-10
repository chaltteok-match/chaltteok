package com.chaltteok.answer;

import org.springframework.stereotype.Service;

/**
 * 질문 분류·답변 조립 진입점.
 * 초기 구현(docs/api-contract.md §8.3)에서는 검색·HCX 없이 더미 {@link AnswerResult}를 반환한다.
 */
@Service
public class AnswerService {

    public AnswerResult answer(String questionId, String question) {
        return new AnswerResult(
                questionId,
                question,
                "",
                "파라미터 검증 완료 → 더미 AnswerResult 반환 (검색·계산·HyperCLOVA X 미연동)",
                "임시 응답입니다. 공시 검색·계산·HyperCLOVA X 연동은 이후 단계에서 구현됩니다."
        );
    }
}
