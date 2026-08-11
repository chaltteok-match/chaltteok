/**
 * 질문 분류·답변 조립 및 평가용 {@code GET /answer} API.
 * 외부 DTO는 {@code dto}, 오류 처리는 {@code web}에 둔다.
 * 공시별 검색은 {@link AnswerHandler}가, 공통 흐름 조립·HCX 호출은 {@link AnswerService}가 담당한다.
 * 검색·계산·LLM 로직은 Controller에 두지 않는다.
 */
package com.chaltteok.answer;
