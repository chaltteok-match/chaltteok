package com.chaltteok.ownership;

import com.chaltteok.answer.CorpNameResolver;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.regex.Pattern;

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
    static final String REPORT_REASON_FACT_KEY = "ownership_report_reason";

    /**
     * 값 하나가 아니라 바뀐 과정을 묻는지.
     * {@code 정정}은 공시 한 건을 가리키는 말로도 쓰이므로 빼 둔다.
     * {@code 해소}는 보고 사건 이력을 가리키는 말로 본다.
     */
    private static final Pattern HISTORY = Pattern.compile(
            "변경|바뀌|바뀐|바뀔|달라진|이력|추이|변화|해소|어떻게\\s*변");

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
                Map.entry("보고사유", REPORT_REASON_FACT_KEY),
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
        String compactQuestion = compact(text);
        String item = ITEM_KEYWORDS.keySet().stream()
                .filter(keyword -> compactQuestion.contains(compact(keyword)))
                .findFirst()
                .orElse(null);
        String factKey = item == null ? null : ITEM_KEYWORDS.get(item);
        String itemLabel = item == null ? "대량보유 현황" : item;
        boolean history = HISTORY.matcher(text).find();
        return new OwnershipQuestion(corpName, factKey, itemLabel, history);
    }

    /** 평가 질의의 띄어쓰기 차이를 무시한다. {@code 보유 목적}과 {@code 보유목적}을 같게 본다. */
    private static String compact(String value) {
        return value.replaceAll("\\s+", "");
    }
}
