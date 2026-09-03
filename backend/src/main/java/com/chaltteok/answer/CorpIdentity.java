package com.chaltteok.answer;

/**
 * 한 법인을 가리키는 식별자 묶음. 적재된 공시 문서에서 그대로 가져온다.
 *
 * @param corpName   DART 공식 사명. 답변과 검색에 쓰는 정본이다
 * @param listedName 상장 표기. {@code 현대자동차}의 {@code 현대차}처럼 공식 사명과 다를 수 있다
 * @param stockCode  6자리 종목코드
 * @param corpCode   8자리 DART 고유번호
 */
public record CorpIdentity(String corpName, String listedName, String stockCode, String corpCode) {
}
