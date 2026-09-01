package com.chaltteok.financial;

import com.chaltteok.disclosure.model.enums.Scope;

/**
 * 정기공시 재무 질문에서 읽어낸 의도.
 *
 * @param corpName      코퍼스에 존재하는 법인명
 * @param targetYear    묻는 대상 연도
 * @param baseYear      비교 질문의 기준 연도. 비교가 아니면 {@code null}
 * @param factKey       추출 항목 표준명
 * @param itemLabel     질문에 쓰인 항목 표현
 * @param scope         연결·별도 기준
 * @param scopeExplicit 질문이 기준을 명시했는지 여부
 */
public record FinancialQuestion(
        String corpName,
        Integer targetYear,
        Integer baseYear,
        String factKey,
        String itemLabel,
        Scope scope,
        boolean scopeExplicit
) {

    public boolean isComparison() {
        return baseYear != null;
    }

    public boolean isAnswerable() {
        return corpName != null && targetYear != null && factKey != null;
    }
}
