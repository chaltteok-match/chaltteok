package com.chaltteok.ownership;

import com.chaltteok.answer.CorpNameResolver;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 질문에서 기업과 대량보유 항목을 읽는다.
 *
 * <p>항목을 특정하지 않으면 보고자·주식 수·지분율을 함께 본다.
 */
final class OwnershipQuestionParser {

    static final String HOLDER_FACT_KEY = "ownership_holder";
    static final String SHARES_FACT_KEY = "ownership_shares";
    static final String RATIO_FACT_KEY = "ownership_ratio";
    static final String REPORT_TYPE_FACT_KEY = "ownership_report_type";
    static final String PURPOSE_FACT_KEY = "ownership_purpose";

    private static final Map<String, String> ITEM_KEYWORDS = new LinkedHashMap<>();

    static {
        Map<String, String> raw = Map.ofEntries(
                Map.entry("보유주식등의 수", SHARES_FACT_KEY),
                Map.entry("보유주식수", SHARES_FACT_KEY),
                Map.entry("보유주식", SHARES_FACT_KEY),
                Map.entry("보유비율", RATIO_FACT_KEY),
                Map.entry("지분율", RATIO_FACT_KEY),
                Map.entry("대표보고자", HOLDER_FACT_KEY),
                Map.entry("보고자", HOLDER_FACT_KEY),
                Map.entry("보유자", HOLDER_FACT_KEY),
                Map.entry("보유목적", PURPOSE_FACT_KEY),
                Map.entry("보고사유", PURPOSE_FACT_KEY),
                Map.entry("보고구분", REPORT_TYPE_FACT_KEY)
        );
        raw.entrySet().stream()
                .sorted(Comparator.comparingInt((Map.Entry<String, String> entry) -> entry.getKey().length())
                        .reversed())
                .forEach(entry -> ITEM_KEYWORDS.put(entry.getKey(), entry.getValue()));
    }

    private OwnershipQuestionParser() {
    }

    static OwnershipQuestion parse(String question, CorpNameResolver corpNames) {
        String text = question == null ? "" : question;
        String corpName = corpNames.resolve(text);
        String item = ITEM_KEYWORDS.keySet().stream()
                .filter(text::contains)
                .findFirst()
                .orElse(null);
        String factKey = item == null ? null : ITEM_KEYWORDS.get(item);
        String itemLabel = item == null ? "대량보유 현황" : item;
        return new OwnershipQuestion(corpName, factKey, itemLabel);
    }
}
