package com.chaltteok.ownership;

/**
 * 대량보유상황보고서 질문에서 읽어낸 의도.
 *
 * @param corpName  코퍼스에 적재된 법인명
 * @param factKey   특정 항목. 없으면 보고자·주식 수·지분율을 함께 본다
 * @param itemLabel 질문에 쓰인 항목 표현
 */
record OwnershipQuestion(String corpName, String factKey, String itemLabel) {

    boolean asksForSpecificItem() {
        return factKey != null;
    }
}
