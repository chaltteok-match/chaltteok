package com.chaltteok.fundraising;

import java.util.Set;

/**
 * 유상증자결정 질문에서 읽어낸 의도.
 *
 * @param corpName          코퍼스에 적재된 법인명
 * @param factKey           목적별 항목. 합계·제외 합계 질문이면 {@code null}
 * @param itemLabel         질문에 쓰인 목적 표현. 전체 합계면 {@code "자금 사용 목적 합계"}.
 *                          목적을 뺀 합계는 {@code "…을 제외한 목적별 금액"}이며
 *                          {@code 자금 사용 목적 합계}라고 쓰지 않는다.
 * @param excludedFactKeys  합계에서 빼는 목적. 없으면 빈 집합
 * @param history           바뀐 과정을 묻는지. {@code 정정한}만으로는 거짓
 */
record FundraisingQuestion(
        String corpName,
        String factKey,
        String itemLabel,
        Set<String> excludedFactKeys,
        boolean history
) {
    /** 공시를 낸 기업을 다른 후보로 바꾼 질문. 상대 기업이 질문에 함께 나올 때 쓴다. */
    FundraisingQuestion withCorpName(String other) {
        return new FundraisingQuestion(other, factKey, itemLabel, excludedFactKeys, history);
    }


    FundraisingQuestion {
        excludedFactKeys = excludedFactKeys == null || excludedFactKeys.isEmpty()
                ? Set.of()
                : Set.copyOf(excludedFactKeys);
    }

    boolean isTotal() {
        return factKey == null;
    }

    boolean excludesPurposes() {
        return !excludedFactKeys.isEmpty();
    }
}
