package com.chaltteok.financial;

import com.chaltteok.disclosure.model.enums.Scope;
import java.util.List;

/**
 * 정기공시 재무 질문에서 읽어낸 의도.
 *
 * @param corpNames     질문에 나온 법인명. 나온 순서대로 담는다. 기업 간 비교면 둘 이상이다
 * @param wantsCorpRanking 코퍼스 전체를 훑어야 답할 수 있는 질문인지
 * @param lostCounterpart  비교 상대 기업을 알아보지 못했는지
 * @param targetYear    묻는 대상 연도
 * @param baseYear      비교 질문의 기준 연도. 비교가 아니면 {@code null}
 * @param factKey       추출 항목 표준명
 * @param itemLabel     질문에 쓰인 항목 표현
 * @param scope         연결·별도 기준
 * @param scopeExplicit 질문이 기준을 명시했는지 여부
 */
public record FinancialQuestion(
        List<String> corpNames,
        Integer targetYear,
        Integer baseYear,
        String factKey,
        String itemLabel,
        Scope scope,
        boolean scopeExplicit,
        boolean wantsCorpRanking,
        boolean lostCounterpart,
        FinancialRatio ratio
) {

    public FinancialQuestion {
        corpNames = corpNames == null ? List.of() : List.copyOf(corpNames);
    }

    /** 첫 기업. 단일 조회 경로가 쓴다. */
    public String corpName() {
        return corpNames.isEmpty() ? null : corpNames.getFirst();
    }

    /** 기업이 둘 이상이면 기업 간 비교다. */
    public boolean isCorpComparison() {
        return corpNames.size() >= 2;
    }

    public boolean isComparison() {
        return baseYear != null;
    }

    public boolean isAnswerable() {
        return corpName() != null && targetYear != null && factKey != null;
    }

    /** 질문에 연도가 없어 다른 근거로 연도를 정했을 때 쓴다. */
    public FinancialQuestion withTargetYear(Integer year) {
        return new FinancialQuestion(corpNames, year, baseYear, factKey, itemLabel,
                scope, scopeExplicit, wantsCorpRanking, lostCounterpart, ratio);
    }

    /** 표에 실린 값이 아니라 두 값으로 계산해야 하는 질문인지. */
    public boolean isRatio() {
        return ratio != null;
    }
}
