package com.chaltteok.exchange;

/**
 * 거래소공시 서식 갈래. 같은 '금액'이라도 서식마다 fact_key가 다르다.
 *
 * <p>투자판단관련주요경영사항은 자유 서술이라 사실이 없어 갈래를 두지 않는다.
 * 근거 chunk로만 적재되므로 이 Handler가 다루지 않는다.
 */
enum ExchangeTopic {
    /** 단일판매ㆍ공급계약체결 1,106건 */
    SUPPLY_CONTRACT,
    /** 신규시설투자등 43건 */
    FACILITY_INVESTMENT,
    /** 단일판매ㆍ공급계약해지 20건 */
    SUPPLY_TERMINATION
}
