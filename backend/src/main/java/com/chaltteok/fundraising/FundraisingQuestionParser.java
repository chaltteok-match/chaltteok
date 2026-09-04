package com.chaltteok.fundraising;

import com.chaltteok.answer.CorpNameResolver;

import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * 질문에서 기업·목적·총액·제외·정정 이력 여부를 읽는다.
 *
 * <p>{@code 총액}·{@code 총 조달금액}·{@code 합계}가 있으면 합계 질문으로 본다.
 * 다만 목적 표현 바로 뒤에 마커가 붙으면({@code 시설자금 총액}, {@code 시설자금 합계})
 * 그 목적만 본다. {@code 시설자금을 포함한 총 조달금액}처럼 목적과 마커 사이에
 * 다른 말이 끼면 합계다.
 *
 * <p>{@code 제외한}·{@code 빼고}와 목적·총액 표지가 함께 있으면 그 목적을 뺀 합계다.
 * {@code 어떻게 바뀌었나}처럼 변경 과정을 물으면 이력 질문이다.
 * {@code 정정한}·{@code 정정공시}만 있으면 그 공시 한 건을 고르는 말로 본다.
 */
final class FundraisingQuestionParser {

    /**
     * 긴 표현을 먼저 본다. {@code 타법인 증권취득}이 {@code 타법인}보다 앞에 있어야
     * 세부 목적을 놓치지 않는다.
     */
    private static final Map<String, String> PURPOSE_KEYWORDS = new LinkedHashMap<>();

    static {
        Map<String, String> raw = Map.ofEntries(
                Map.entry("타법인 증권취득", FundraisingAnswerHandler.SECURITIES_ACQUISITION_FACT_KEY),
                Map.entry("타법인", FundraisingAnswerHandler.SECURITIES_ACQUISITION_FACT_KEY),
                Map.entry("영업양수자금", FundraisingAnswerHandler.BUSINESS_ACQUISITION_FACT_KEY),
                Map.entry("영업양수", FundraisingAnswerHandler.BUSINESS_ACQUISITION_FACT_KEY),
                Map.entry("채무상환자금", FundraisingAnswerHandler.DEBT_REPAYMENT_FACT_KEY),
                Map.entry("채무상환", FundraisingAnswerHandler.DEBT_REPAYMENT_FACT_KEY),
                Map.entry("시설자금", FundraisingAnswerHandler.FACILITY_FACT_KEY),
                Map.entry("운영자금", FundraisingAnswerHandler.OPERATING_FACT_KEY),
                Map.entry("기타자금", FundraisingAnswerHandler.OTHER_FACT_KEY));
        raw.entrySet().stream()
                .sorted(Comparator.comparingInt((Map.Entry<String, String> entry) -> entry.getKey().length())
                        .reversed())
                .forEach(entry -> PURPOSE_KEYWORDS.put(entry.getKey(), entry.getValue()));
    }

    private static final String[] TOTAL_MARKERS = {"총액", "총 조달", "합계"};
    /** 값 하나가 아니라 바뀐 과정을 묻는지. {@code 정정}만으로는 단건이다. */
    private static final Pattern HISTORY = Pattern.compile(
            "바뀌|바뀐|바뀔|달라진|이력|추이|변화|어떻게\\s*변");

    private FundraisingQuestionParser() {
    }

    static FundraisingQuestion parse(String question, CorpNameResolver corpNames) {
        String text = question == null ? "" : question;
        String corpName = corpNames.resolve(text);
        boolean history = HISTORY.matcher(text).find();
        String purposeAttachedToMarker = purposeImmediatelyFollowedByTotalMarker(text);
        if (purposeAttachedToMarker != null) {
            return new FundraisingQuestion(
                    corpName,
                    PURPOSE_KEYWORDS.get(purposeAttachedToMarker),
                    purposeAttachedToMarker,
                    Set.of(),
                    history);
        }
        if (asksForTotal(text)) {
            Set<String> excluded = asksToExclude(text) ? purposesIn(text) : Set.of();
            return new FundraisingQuestion(
                    corpName, null, totalLabel(excluded), excluded, history);
        }
        String purpose = PURPOSE_KEYWORDS.keySet().stream()
                .filter(text::contains)
                .findFirst()
                .orElse(null);
        if (purpose != null) {
            return new FundraisingQuestion(
                    corpName, PURPOSE_KEYWORDS.get(purpose), purpose, Set.of(), history);
        }
        return new FundraisingQuestion(corpName, null, "자금 사용 목적 합계", Set.of(), history);
    }

    private static boolean asksToExclude(String text) {
        return text.contains("제외") || text.contains("빼고");
    }

    private static Set<String> purposesIn(String text) {
        Set<String> keys = new LinkedHashSet<>();
        for (Map.Entry<String, String> entry : PURPOSE_KEYWORDS.entrySet()) {
            if (text.contains(entry.getKey())) {
                keys.add(entry.getValue());
            }
        }
        return keys;
    }

    private static String totalLabel(Set<String> excluded) {
        if (excluded.isEmpty()) {
            return "자금 사용 목적 합계";
        }
        // 같은 fact에 짧은 키워드와 긴 키워드가 함께 있으면 긴 쪽만 쓴다.
        Set<String> labels = new LinkedHashSet<>();
        Set<String> seenKeys = new LinkedHashSet<>();
        for (Map.Entry<String, String> entry : PURPOSE_KEYWORDS.entrySet()) {
            if (excluded.contains(entry.getValue()) && seenKeys.add(entry.getValue())) {
                labels.add(entry.getKey());
            }
        }
        return "자금 사용 목적 합계(" + String.join(", ", labels) + " 제외)";
    }

    private static String purposeImmediatelyFollowedByTotalMarker(String text) {
        for (String purpose : PURPOSE_KEYWORDS.keySet()) {
            int index = text.indexOf(purpose);
            if (index < 0) {
                continue;
            }
            int after = skipWhitespace(text, index + purpose.length());
            for (String marker : TOTAL_MARKERS) {
                if (text.startsWith(marker, after)) {
                    return purpose;
                }
            }
        }
        return null;
    }

    private static int skipWhitespace(String text, int start) {
        int index = start;
        while (index < text.length() && Character.isWhitespace(text.charAt(index))) {
            index++;
        }
        return index;
    }

    private static boolean asksForTotal(String text) {
        for (String marker : TOTAL_MARKERS) {
            if (text.contains(marker)) {
                return true;
            }
        }
        return false;
    }

}
