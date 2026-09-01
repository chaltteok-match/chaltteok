package com.chaltteok.financial;

import com.chaltteok.disclosure.model.enums.Scope;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 질문 문장에서 기업·연도·항목·연결기준을 읽어낸다.
 *
 * <p>고급 분류기 이전 단계이므로 규칙 기반으로 처리하고, 확신할 수 없으면
 * 값을 만들어 내지 않고 {@code null}로 둔다. 판단은 Handler가 한다.
 */
final class FinancialQuestionParser {

    private static final Pattern YEAR = Pattern.compile("(19|20)\\d{2}\\s*년");
    private static final Pattern COMPARISON = Pattern.compile(
            "대비|증감|증가|감소|늘어|늘었|줄어|줄었|비교|성장|얼마나");

    /** 긴 표현을 먼저 확인해야 '자산총계'가 '자산'으로 잘리지 않는다. */
    private static final Map<String, String> ITEM_KEYWORDS = new LinkedHashMap<>();

    static {
        Map<String, String> raw = Map.ofEntries(
                Map.entry("매출액", "revenue"),
                Map.entry("영업수익", "revenue"),
                Map.entry("매출", "revenue"),
                Map.entry("영업이익", "operating_profit"),
                Map.entry("당기순이익", "net_income"),
                Map.entry("순이익", "net_income"),
                Map.entry("자산총계", "total_assets"),
                Map.entry("총자산", "total_assets"),
                Map.entry("부채총계", "total_liabilities"),
                Map.entry("총부채", "total_liabilities"),
                Map.entry("자본총계", "total_equity"),
                Map.entry("총자본", "total_equity"));
        raw.entrySet().stream()
                .sorted(Comparator.comparingInt((Map.Entry<String, String> entry) -> entry.getKey().length())
                        .reversed())
                .forEach(entry -> ITEM_KEYWORDS.put(entry.getKey(), entry.getValue()));
    }

    private FinancialQuestionParser() {
    }

    static FinancialQuestion parse(String question, List<String> knownCorpNames) {
        String text = question == null ? "" : question;

        String corpName = longestMatch(text, knownCorpNames);
        String itemLabel = ITEM_KEYWORDS.keySet().stream()
                .filter(text::contains)
                .findFirst()
                .orElse(null);
        String factKey = itemLabel == null ? null : ITEM_KEYWORDS.get(itemLabel);

        List<Integer> years = years(text);
        Integer targetYear = years.isEmpty() ? null : years.get(years.size() - 1);
        Integer baseYear = null;
        if (targetYear != null && COMPARISON.matcher(text).find()) {
            // 연도를 두 개 쓴 질문이면 앞의 연도를 비교 기준으로 본다.
            baseYear = years.size() >= 2 ? years.get(0) : targetYear - 1;
        }

        boolean separate = text.contains("별도") || text.contains("개별");
        boolean consolidated = text.contains("연결");
        Scope scope = separate ? Scope.SEPARATE : Scope.CONSOLIDATED;

        return new FinancialQuestion(
                corpName, targetYear, baseYear, factKey, itemLabel, scope, separate || consolidated);
    }

    /** 여러 법인명이 포함되면 가장 긴 이름을 고른다. 예: 'NAVER'와 'NAVER클라우드'. */
    private static String longestMatch(String text, List<String> candidates) {
        String best = null;
        for (String candidate : candidates) {
            if (candidate != null && !candidate.isBlank() && text.contains(candidate)
                    && (best == null || candidate.length() > best.length())) {
                best = candidate;
            }
        }
        return best;
    }

    private static List<Integer> years(String text) {
        List<Integer> years = new ArrayList<>();
        Matcher matcher = YEAR.matcher(text);
        while (matcher.find()) {
            int year = Integer.parseInt(matcher.group().replaceAll("[^0-9]", ""));
            if (!years.contains(year)) {
                years.add(year);
            }
        }
        years.sort(Comparator.naturalOrder());
        return years;
    }
}
