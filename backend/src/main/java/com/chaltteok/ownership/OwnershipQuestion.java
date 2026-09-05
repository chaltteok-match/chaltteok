package com.chaltteok.ownership;

/**
 * 대량보유상황보고서 질문에서 읽어낸 의도.
 *
 * @param corpName  코퍼스에 적재된 법인명
 * @param factKey   특정 항목. 없으면 보고자·주식 수·지분율을 함께 본다
 * @param itemLabel 질문에 쓰인 항목 표현
 * @param history   바뀐 과정을 묻는지. {@code 정정한}만으로는 거짓
 */
record OwnershipQuestion(String corpName, String factKey, String itemLabel, boolean history) {
    /** 공시를 낸 기업을 다른 후보로 바꾼 질문. 상대 기업이 질문에 함께 나올 때 쓴다. */
    OwnershipQuestion withCorpName(String other) {
        return new OwnershipQuestion(other, factKey, itemLabel, history);
    }


    boolean asksForSpecificItem() {
        return factKey != null;
    }

    boolean asksForPurposeHistory() {
        return history && OwnershipQuestionParser.PURPOSE_FACT_KEY.equals(factKey);
    }
}
