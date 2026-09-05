package com.chaltteok.treasury;

import com.chaltteok.answer.CorpNameResolver;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 질문에서 기업과 자기주식 취득 항목을 읽는다.
 */
final class TreasuryQuestionParser {

    static final String SHARES_FACT_KEY = "treasury_planned_common_shares";
    static final String AMOUNT_FACT_KEY = "treasury_planned_common_amount";

    private static final Map<String, String> ITEM_KEYWORDS = new LinkedHashMap<>();

    static {
        Map<String, String> raw = Map.ofEntries(
                Map.entry("취득예정주식", SHARES_FACT_KEY),
                Map.entry("예정주식", SHARES_FACT_KEY),
                Map.entry("주식 수", SHARES_FACT_KEY),
                Map.entry("주식수", SHARES_FACT_KEY),
                Map.entry("취득예정금액", AMOUNT_FACT_KEY),
                Map.entry("예정금액", AMOUNT_FACT_KEY),
                Map.entry("취득금액", AMOUNT_FACT_KEY)
        );
        raw.entrySet().stream()
                .sorted(Comparator.comparingInt((Map.Entry<String, String> entry) -> entry.getKey().length())
                        .reversed())
                .forEach(entry -> ITEM_KEYWORDS.put(entry.getKey(), entry.getValue()));
    }

    private TreasuryQuestionParser() {
    }

    static TreasuryQuestion parse(String question, CorpNameResolver corpNames) {
        String text = question == null ? "" : question;
        String corpName = corpNames.resolve(text);
        String compactQuestion = compact(text);
        String item = ITEM_KEYWORDS.keySet().stream()
                .filter(keyword -> compactQuestion.contains(compact(keyword)))
                .findFirst()
                .orElse(null);
        String factKey = item == null ? null : ITEM_KEYWORDS.get(item);
        String itemLabel = item == null ? "자기주식 취득예정" : item;
        return new TreasuryQuestion(corpName, factKey, itemLabel);
    }

    /** 평가 질의의 띄어쓰기 차이를 무시한다. {@code 취득 예정 금액}과 {@code 취득예정금액}을 같게 본다. */
    private static String compact(String value) {
        return value.replaceAll("\\s+", "");
    }
}
