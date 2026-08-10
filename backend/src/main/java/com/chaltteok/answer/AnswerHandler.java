package com.chaltteok.answer;

/**
 * 공시 유형별 검색·판단 Handler 계약.
 * 실제 정기공시·주요사항보고서 등은 이후 단계에서 구현한다.
 */
public interface AnswerHandler {

    /**
     * 이 Handler가 주어진 질문을 처리할지 여부.
     * 고급 분류기 이전에는 단순 규칙·키워드로도 충분하다.
     */
    boolean supports(String question);

    /**
     * 근거 검색·판단 결과만 반환한다. HCX 호출은 {@link AnswerService}가 담당한다.
     */
    AnswerHandlerResult handle(String questionId, String question);
}
