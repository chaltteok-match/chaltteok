package com.chaltteok.fundraising;

import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 질문에서 기업·목적·총액 여부를 읽는다.
 *
 * <p>{@code 총액}·{@code 총 조달금액}·{@code 합계}가 있으면 목적 표현보다 총액(목적 합계)을
 * 우선한다. {@code 시설자금을 포함한 총 조달금액}처럼 목적이 끼어도 총액 질문이다.
 * 그런 말이 없고 목적 표현만 있으면 그 목적만 본다.
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

    private FundraisingQuestionParser() {
    }

    static FundraisingQuestion parse(String question, List<String> knownCorpNames) {
        String text = question == null ? "" : question;
        String corpName = longestMatch(text, knownCorpNames);
        if (asksForTotal(text)) {
            return new FundraisingQuestion(corpName, null, "자금 사용 목적 합계");
        }
        String purpose = PURPOSE_KEYWORDS.keySet().stream()
                .filter(text::contains)
                .findFirst()
                .orElse(null);
        if (purpose != null) {
            return new FundraisingQuestion(corpName, PURPOSE_KEYWORDS.get(purpose), purpose);
        }
        return new FundraisingQuestion(corpName, null, "자금 사용 목적 합계");
    }

    private static boolean asksForTotal(String text) {
        for (String marker : TOTAL_MARKERS) {
            if (text.contains(marker)) {
                return true;
            }
        }
        return false;
    }

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
}
