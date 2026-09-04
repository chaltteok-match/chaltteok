package com.chaltteok.bond;

/**
 * 전환·교환사채 발행결정 질문에서 읽어낸 의도.
 *
 * @param corpName  코퍼스에 적재된 법인명
 * @param form      전환사채 또는 교환사채
 * @param factKey   특정 항목. 없으면 권면총액과 전환/교환가액을 함께 본다
 * @param itemLabel 질문에 쓰인 항목 표현
 */
record BondQuestion(String corpName, BondForm form, String factKey, String itemLabel) {

    boolean asksForSpecificItem() {
        return factKey != null;
    }
}
