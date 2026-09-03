package com.chaltteok.fundraising;

/**
 * 유상증자결정 질문에서 읽어낸 의도.
 *
 * @param corpName  코퍼스에 적재된 법인명
 * @param factKey   목적별 항목. 총액 질문이면 {@code null}
 * @param itemLabel 질문에 쓰인 목적 표현. 합계 질문이면 {@code "자금 사용 목적 합계"}
 */
record FundraisingQuestion(String corpName, String factKey, String itemLabel) {

    boolean isTotal() {
        return factKey == null;
    }
}
