package com.chaltteok.treasury;

/**
 * 자기주식취득결정 질문에서 읽어낸 의도.
 *
 * @param corpName  코퍼스에 적재된 법인명
 * @param factKey   특정 항목. 없으면 예정 주식 수와 금액을 함께 본다
 * @param itemLabel 질문에 쓰인 항목 표현
 */
record TreasuryQuestion(String corpName, String factKey, String itemLabel) {
    /** 공시를 낸 기업을 다른 후보로 바꾼 질문. 상대 기업이 질문에 함께 나올 때 쓴다. */
    TreasuryQuestion withCorpName(String other) {
        return new TreasuryQuestion(other, factKey, itemLabel);
    }


    boolean asksForSpecificItem() {
        return factKey != null;
    }
}
