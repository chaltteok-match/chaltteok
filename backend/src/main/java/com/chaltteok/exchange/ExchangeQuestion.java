package com.chaltteok.exchange;

import java.time.LocalDate;

/**
 * 거래소공시 질문에서 읽어낸 조건.
 *
 * @param corpName   기업명. 식별하지 못하면 {@code null}
 * @param reportDate 질문이 지정한 공시일. 없으면 {@code null}
 * @param year       질문이 지정한 연도. 없으면 {@code null}
 * @param topic      묻는 서식 갈래
 * @param factKey    답할 항목의 fact_key
 * @param itemLabel  항목의 한국어 표기
 * @param largest    "가장 큰"처럼 최댓값을 묻는지
 */
record ExchangeQuestion(
        String corpName,
        LocalDate reportDate,
        Integer year,
        ExchangeTopic topic,
        String factKey,
        String itemLabel,
        boolean largest
) {
    boolean isIdentified() {
        return corpName != null && topic != null && factKey != null;
    }

    /** 문서를 한 건으로 좁힐 근거가 있는지. 최댓값 질문은 좁히지 않고 훑는다. */
    boolean hasDocumentFilter() {
        return reportDate != null || year != null || largest;
    }
}
