package com.chaltteok.fundraising;

import com.chaltteok.answer.CorpNameResolver;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
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
 * <p>{@code 제외한}·{@code 빼고}가 목적 바로 뒤에 있으면 그 목적만 뺀 합계다.
 * {@code 시설자금과 운영자금을 제외한 총액}처럼 접속사만으로 이어진 목적은
 * 뒤 목적의 포함·제외를 따른다. {@code 시설자금을 포함하고 타법인 증권취득을 제외한 총액}처럼
 * 포함과 제외가 같이 나와도 제외에 붙은 목적만 뺀다. {@code 포함한}만 있으면 전체 합계다.
 * {@code 어떻게 바뀌었나}·{@code 변경된 내역}처럼 바뀐 과정을 물으면 이력 질문이다.
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

    private static final Map<String, String> ISSUANCE_KEYWORDS = new LinkedHashMap<>();

    static {
        Map<String, String> raw = Map.ofEntries(
                Map.entry("예정발행가", FundraisingAnswerHandler.PLANNED_ISSUE_PRICE_FACT_KEY),
                Map.entry("예정 발행가", FundraisingAnswerHandler.PLANNED_ISSUE_PRICE_FACT_KEY),
                Map.entry("확정발행가", FundraisingAnswerHandler.ISSUE_PRICE_FACT_KEY),
                Map.entry("확정 발행가", FundraisingAnswerHandler.ISSUE_PRICE_FACT_KEY),
                Map.entry("발행가액", FundraisingAnswerHandler.ISSUE_PRICE_FACT_KEY),
                Map.entry("발행가", FundraisingAnswerHandler.ISSUE_PRICE_FACT_KEY),
                Map.entry("액면가액", FundraisingAnswerHandler.PAR_VALUE_FACT_KEY),
                Map.entry("액면가", FundraisingAnswerHandler.PAR_VALUE_FACT_KEY),
                Map.entry("신주의 종류와 수", FundraisingAnswerHandler.NEW_SHARES_FACT_KEY),
                Map.entry("신주 수", FundraisingAnswerHandler.NEW_SHARES_FACT_KEY),
                Map.entry("신주수", FundraisingAnswerHandler.NEW_SHARES_FACT_KEY),
                Map.entry("신주", FundraisingAnswerHandler.NEW_SHARES_FACT_KEY)
        );
        raw.entrySet().stream()
                .sorted(Comparator.comparingInt((Map.Entry<String, String> entry) -> entry.getKey().length())
                        .reversed())
                .forEach(entry -> ISSUANCE_KEYWORDS.put(entry.getKey(), entry.getValue()));
    }

    private static final String[] TOTAL_MARKERS = {"총액", "총 조달", "합계"};
    /**
     * 값 하나가 아니라 바뀐 과정을 묻는지.
     * {@code 정정}은 공시 한 건을 가리키는 말로도 쓰이므로 빼 둔다.
     * {@code 변경}은 그런 용법이 드물어 이력으로 본다.
     */
    private static final Pattern HISTORY = Pattern.compile(
            "변경|바뀌|바뀐|바뀔|달라진|이력|추이|변화|어떻게\\s*변");

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
            Set<String> excluded = excludedPurposes(text);
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
        String issuance = ISSUANCE_KEYWORDS.keySet().stream()
                .filter(text::contains)
                .findFirst()
                .orElse(null);
        if (issuance != null) {
            return new FundraisingQuestion(
                    corpName, ISSUANCE_KEYWORDS.get(issuance), issuance, Set.of(), history);
        }
        return new FundraisingQuestion(corpName, null, "자금 사용 목적 합계", Set.of(), history);
    }

    private static boolean asksToExclude(String text) {
        return text.contains("제외") || text.contains("빼고");
    }

    /**
     * 목적 표현 뒤에, 다음 목적 전까지 구간에 {@code 제외}·{@code 빼고}가 있으면 뺀다.
     * {@code 과}·{@code 와}·{@code 및}·{@code ,}만으로 이어진 앞 목적은 뒤 목적과 같이 본다.
     */
    private static Set<String> excludedPurposes(String text) {
        if (!asksToExclude(text)) {
            return Set.of();
        }
        List<PurposeSpan> spans = purposeSpans(text);
        boolean[] excluded = new boolean[spans.size()];
        for (int index = 0; index < spans.size(); index++) {
            PurposeSpan span = spans.get(index);
            int windowEnd = index + 1 < spans.size() ? spans.get(index + 1).start() : text.length();
            excluded[index] = asksToExclude(text.substring(span.end(), windowEnd));
        }
        for (int index = spans.size() - 2; index >= 0; index--) {
            PurposeSpan current = spans.get(index);
            PurposeSpan next = spans.get(index + 1);
            if (conjunctionOnly(text.substring(current.end(), next.start()))) {
                excluded[index] = excluded[index + 1];
            }
        }
        Set<String> keys = new LinkedHashSet<>();
        for (int index = 0; index < spans.size(); index++) {
            if (excluded[index]) {
                keys.add(spans.get(index).factKey());
            }
        }
        return keys;
    }

    private static boolean conjunctionOnly(String gap) {
        String compact = gap.replaceAll("\\s+", "");
        return !compact.isEmpty() && compact.matches("(과|와|및|,|·)+");
    }

    private static List<PurposeSpan> purposeSpans(String text) {
        boolean[] covered = new boolean[text.length()];
        List<PurposeSpan> spans = new ArrayList<>();
        for (Map.Entry<String, String> entry : PURPOSE_KEYWORDS.entrySet()) {
            String keyword = entry.getKey();
            int from = 0;
            while (true) {
                int start = text.indexOf(keyword, from);
                if (start < 0) {
                    break;
                }
                int end = start + keyword.length();
                if (!covered(covered, start, end)) {
                    mark(covered, start, end);
                    spans.add(new PurposeSpan(start, end, entry.getValue()));
                }
                from = start + 1;
            }
        }
        spans.sort(Comparator.comparingInt(PurposeSpan::start));
        return spans;
    }

    private static boolean covered(boolean[] used, int start, int end) {
        for (int index = start; index < end; index++) {
            if (used[index]) {
                return true;
            }
        }
        return false;
    }

    private static void mark(boolean[] used, int start, int end) {
        for (int index = start; index < end; index++) {
            used[index] = true;
        }
    }

    private record PurposeSpan(int start, int end, String factKey) {
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
