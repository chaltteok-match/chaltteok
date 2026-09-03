package com.chaltteok.fundraising;

import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 질문에서 기업·목적·총액 여부를 읽는다.
 *
 * <p>목적 표현이 있으면 그 목적만 보고, 없으면 총액(목적별 합산)으로 본다.
 * {@code 타법인 증권취득 목적 자금}처럼 목적과 {@code 자금}이 같이 있어도 총액이 아니다.
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

    private FundraisingQuestionParser() {
    }

    static FundraisingQuestion parse(String question, List<String> knownCorpNames) {
        String text = question == null ? "" : question;
        String corpName = longestMatch(text, knownCorpNames);
        String purpose = PURPOSE_KEYWORDS.keySet().stream()
                .filter(text::contains)
                .findFirst()
                .orElse(null);
        if (purpose != null) {
            return new FundraisingQuestion(corpName, PURPOSE_KEYWORDS.get(purpose), purpose);
        }
        return new FundraisingQuestion(corpName, null, "총액");
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
