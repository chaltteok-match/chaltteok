package com.chaltteok.financial;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 요약재무정보 두 값으로 계산하는 비율.
 *
 * <p>표에 실린 값이 아니라 우리가 계산해 내는 값이다. 그래서 분자·분모의 근거를 둘 다
 * 제시하고, 계산식을 처리 요약에 남긴다. 한쪽이라도 없으면 계산하지 않는다.
 */
enum FinancialRatio {

    OPERATING_MARGIN("영업이익률", "operating_profit", "영업이익", "revenue", "매출액"),
    NET_MARGIN("순이익률", "net_income", "당기순이익", "revenue", "매출액"),
    DEBT_RATIO("부채비율", "total_liabilities", "부채총계", "total_equity", "자본총계"),
    EQUITY_RATIO("자기자본비율", "total_equity", "자본총계", "total_assets", "자산총계"),
    RETURN_ON_EQUITY("자기자본이익률", "net_income", "당기순이익", "total_equity", "자본총계"),
    RETURN_ON_ASSETS("총자산이익률", "net_income", "당기순이익", "total_assets", "자산총계");

    /** 질문에 나올 수 있는 표현. 가장 긴 것이 이기도록 파서가 길이순으로 본다. */
    private static final Map<String, FinancialRatio> KEYWORDS = keywords();

    private final String label;
    private final String numeratorKey;
    private final String numeratorLabel;
    private final String denominatorKey;
    private final String denominatorLabel;

    FinancialRatio(
            String label,
            String numeratorKey,
            String numeratorLabel,
            String denominatorKey,
            String denominatorLabel
    ) {
        this.label = label;
        this.numeratorKey = numeratorKey;
        this.numeratorLabel = numeratorLabel;
        this.denominatorKey = denominatorKey;
        this.denominatorLabel = denominatorLabel;
    }

    private static Map<String, FinancialRatio> keywords() {
        Map<String, FinancialRatio> map = new LinkedHashMap<>();
        map.put("영업이익률", OPERATING_MARGIN);
        map.put("영업이익율", OPERATING_MARGIN);
        map.put("매출액영업이익률", OPERATING_MARGIN);
        map.put("순이익률", NET_MARGIN);
        map.put("순이익율", NET_MARGIN);
        map.put("매출액순이익률", NET_MARGIN);
        map.put("당기순이익률", NET_MARGIN);
        map.put("부채비율", DEBT_RATIO);
        map.put("자기자본비율", EQUITY_RATIO);
        map.put("자기자본이익률", RETURN_ON_EQUITY);
        map.put("자기자본순이익률", RETURN_ON_EQUITY);
        map.put("ROE", RETURN_ON_EQUITY);
        map.put("roe", RETURN_ON_EQUITY);
        map.put("총자산이익률", RETURN_ON_ASSETS);
        map.put("총자산순이익률", RETURN_ON_ASSETS);
        map.put("ROA", RETURN_ON_ASSETS);
        map.put("roa", RETURN_ON_ASSETS);
        return Map.copyOf(map);
    }

    static Map<String, FinancialRatio> keywordMap() {
        return KEYWORDS;
    }

    String label() {
        return label;
    }

    String numeratorKey() {
        return numeratorKey;
    }

    String numeratorLabel() {
        return numeratorLabel;
    }

    String denominatorKey() {
        return denominatorKey;
    }

    String denominatorLabel() {
        return denominatorLabel;
    }
}
