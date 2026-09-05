package com.chaltteok.exchange;

/**
 * 거래소공시 서식 갈래. 같은 '금액'이라도 서식마다 fact_key가 다르다.
 */
enum ExchangeTopic {
    /** 단일판매ㆍ공급계약체결 1,106건 */
    SUPPLY_CONTRACT,
    /** 신규시설투자등 43건 */
    FACILITY_INVESTMENT,
    /** 단일판매ㆍ공급계약해지 20건 */
    SUPPLY_TERMINATION,
    /**
     * 투자판단관련주요경영사항 300건.
     *
     * <p>자유 서술이라 fact를 만들지 않는다. 금액 문자열이 있는 79건도
     * {@code 5.9조원(보조금 0.7조원 포함)}처럼 단서가 붙어 숫자만 뽑으면 틀린다.
     * 그래서 이 갈래만 원문 chunk를 그대로 근거로 낸다.
     */
    INVESTMENT_JUDGEMENT;

    /** 전처리가 이 서식에서 사실을 뽑는지. 뽑지 않으면 원문 chunk로 답한다. */
    boolean hasFacts() {
        return this != INVESTMENT_JUDGEMENT;
    }
}
