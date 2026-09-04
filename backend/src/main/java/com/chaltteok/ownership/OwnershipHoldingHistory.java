package com.chaltteok.ownership;

import com.chaltteok.disclosure.persistence.entity.DisclosureDocumentEntity;
import com.chaltteok.disclosure.persistence.entity.ExtractedFactEntity;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Collectors;

/**
 * 같은 보고자의 신규·변동·변경을 한 사건으로 잇는다.
 *
 * <p>코퍼스 보고구분은 {@code 변동}·{@code 변동ㆍ변경}·{@code 변경}·{@code 신규}뿐이다.
 * DART에서 {@code 변동}은 보유 비율이 1%p 이상 바뀐 보고라 수량 사슬에 넣는다.
 * 보고구분이 없으면 앞 보고에 붙이지 않는다. {@code 해소}는 이 코퍼스에 없으나
 * 값이 오면 사슬을 끝내는 보고로 본다.
 */
final class OwnershipHoldingHistory {

    /** HCX 실측 때 근거 15건이 입력 약 1,700토큰이었다. 그보다 긴 사슬은 잘릴 수 있다. */
    static final int MAX_COLLAPSED_STEPS = 15;

    enum ReportKind {
        INITIAL,
        CHANGE,
        CLEAR,
        UNKNOWN
    }

    record Step(
            DisclosureDocumentEntity document,
            String holder,
            ReportKind kind,
            String quantityKey,
            List<ExtractedFactEntity> facts
    ) {
        Step {
            Objects.requireNonNull(document);
            facts = List.copyOf(facts);
        }
    }

    private OwnershipHoldingHistory() {
    }

    static ReportKind classify(String rawType) {
        if (rawType == null || rawType.isBlank()) {
            return ReportKind.UNKNOWN;
        }
        String compact = rawType.replaceAll("\\s+", "");
        if (compact.contains("해소")) {
            return ReportKind.CLEAR;
        }
        if (compact.contains("변동") || compact.contains("변경")) {
            return ReportKind.CHANGE;
        }
        if (compact.contains("신규")) {
            return ReportKind.INITIAL;
        }
        return ReportKind.UNKNOWN;
    }

    static Step stepOf(DisclosureDocumentEntity document, List<ExtractedFactEntity> facts) {
        String holder = firstRaw(facts, OwnershipQuestionParser.HOLDER_FACT_KEY);
        String type = firstRaw(facts, OwnershipQuestionParser.REPORT_TYPE_FACT_KEY);
        return new Step(document, compact(holder), classify(type), quantityKey(facts), facts);
    }

    static List<List<Step>> eventChains(List<Step> steps) {
        Map<String, List<Step>> byHolder = new LinkedHashMap<>();
        for (Step step : steps) {
            if (step.holder() == null || step.holder().isBlank()) {
                continue;
            }
            byHolder.computeIfAbsent(step.holder(), ignored -> new ArrayList<>()).add(step);
        }
        List<List<Step>> chains = new ArrayList<>();
        for (List<Step> group : byHolder.values()) {
            List<Step> ordered = group.stream()
                    .sorted(byFilingOrder())
                    .toList();
            chains.addAll(splitByReportKind(preferCorrectionsByDay(ordered)));
        }
        return chains;
    }

    static List<Step> selectChain(List<List<Step>> chains, LocalDate reportDate) {
        if (reportDate != null) {
            List<List<Step>> matched = chains.stream()
                    .filter(chain -> chain.stream()
                            .anyMatch(step -> reportDate.equals(step.document().getRceptDt())))
                    .toList();
            return matched.size() == 1 ? matched.getFirst() : null;
        }
        return chains.size() == 1 ? chains.getFirst() : null;
    }

    /**
     * 같은 날 정정본이 있으면 원본은 빼고, 주식 수·지분율이 그대로인 단계는 접는다.
     * 값이 끝까지 같으면 처음과 마지막만 남겨 보고는 있었으나 수량은 같았음을 보인다.
     */
    static List<Step> collapseHistory(List<Step> alongChain) {
        List<Step> sameDay = preferCorrectionsByDay(alongChain);
        List<Step> changed = new ArrayList<>();
        String previous = null;
        for (Step step : sameDay) {
            if (step.quantityKey().equals(previous)) {
                continue;
            }
            previous = step.quantityKey();
            changed.add(step);
        }
        if (changed.size() >= 2 || sameDay.size() < 2) {
            return limitHistory(changed);
        }
        return List.of(sameDay.getFirst(), sameDay.getLast());
    }

    /**
     * 처음·마지막과 직전 대비 지분율(없으면 주식 수) 변동이 큰 단계를 남긴다.
     */
    static List<Step> limitHistory(List<Step> collapsed) {
        if (collapsed.size() <= MAX_COLLAPSED_STEPS) {
            return collapsed;
        }
        record Ranked(int index, double delta) {
        }
        List<Ranked> interior = new ArrayList<>();
        for (int index = 1; index < collapsed.size() - 1; index++) {
            interior.add(new Ranked(index, quantityDelta(collapsed.get(index - 1), collapsed.get(index))));
        }
        interior.sort(Comparator.comparingDouble(Ranked::delta).reversed().thenComparingInt(Ranked::index));
        Set<Integer> keep = new TreeSet<>();
        keep.add(0);
        keep.add(collapsed.size() - 1);
        for (Ranked ranked : interior) {
            if (keep.size() >= MAX_COLLAPSED_STEPS) {
                break;
            }
            keep.add(ranked.index());
        }
        return keep.stream().map(collapsed::get).toList();
    }

    static List<Step> preferCorrectionsByDay(List<Step> alongChain) {
        List<Step> sameDay = new ArrayList<>();
        int index = 0;
        while (index < alongChain.size()) {
            LocalDate date = alongChain.get(index).document().getRceptDt();
            List<Step> day = new ArrayList<>();
            while (index < alongChain.size()
                    && Objects.equals(alongChain.get(index).document().getRceptDt(), date)) {
                day.add(alongChain.get(index));
                index++;
            }
            List<Step> corrections = day.stream()
                    .filter(step -> step.document().isCorrection())
                    .toList();
            sameDay.addAll(corrections.isEmpty() ? day : corrections);
        }
        return sameDay;
    }

    static List<List<Step>> splitByReportKind(List<Step> ordered) {
        List<List<Step>> chains = new ArrayList<>();
        List<Step> current = new ArrayList<>();
        for (Step step : ordered) {
            if (current.isEmpty()) {
                current.add(step);
                continue;
            }
            ReportKind previous = current.getLast().kind();
            boolean continues = (step.kind() == ReportKind.CHANGE || step.kind() == ReportKind.CLEAR)
                    && (previous == ReportKind.INITIAL || previous == ReportKind.CHANGE);
            if (continues) {
                current.add(step);
            } else {
                chains.add(current);
                current = new ArrayList<>();
                current.add(step);
            }
        }
        if (!current.isEmpty()) {
            chains.add(current);
        }
        return chains;
    }

    private static double quantityDelta(Step previous, Step current) {
        Double previousRatio = parseKeyPart(previous.quantityKey(), 1);
        Double currentRatio = parseKeyPart(current.quantityKey(), 1);
        if (previousRatio != null && currentRatio != null) {
            return Math.abs(currentRatio - previousRatio);
        }
        Double previousShares = parseKeyPart(previous.quantityKey(), 0);
        Double currentShares = parseKeyPart(current.quantityKey(), 0);
        if (previousShares != null && currentShares != null) {
            return Math.abs(currentShares - previousShares);
        }
        return 0;
    }

    private static Double parseKeyPart(String quantityKey, int part) {
        if (quantityKey == null || !quantityKey.contains("|")) {
            return null;
        }
        String[] parts = quantityKey.split("\\|", -1);
        if (part >= parts.length || parts[part].isBlank()) {
            return null;
        }
        try {
            return Double.parseDouble(parts[part].replace(",", ""));
        } catch (NumberFormatException ignored) {
            return null;
        }
    }

    private static Comparator<Step> byFilingOrder() {
        return Comparator
                .comparing((Step step) -> step.document().getRceptDt(),
                        Comparator.nullsLast(LocalDate::compareTo))
                .thenComparing(step -> step.document().getRceptNo(),
                        Comparator.nullsLast(String::compareTo));
    }

    private static String quantityKey(List<ExtractedFactEntity> facts) {
        return valueOf(facts, OwnershipQuestionParser.SHARES_FACT_KEY)
                + "|"
                + valueOf(facts, OwnershipQuestionParser.RATIO_FACT_KEY);
    }

    private static String valueOf(List<ExtractedFactEntity> facts, String factKey) {
        return facts.stream()
                .filter(fact -> factKey.equals(fact.getFactKey()))
                .map(fact -> {
                    if (fact.getNormalizedValue() != null && !fact.getNormalizedValue().isBlank()) {
                        return fact.getNormalizedValue();
                    }
                    return fact.getRawValue() == null ? "" : fact.getRawValue();
                })
                .collect(Collectors.joining(","));
    }

    private static String firstRaw(List<ExtractedFactEntity> facts, String factKey) {
        return facts.stream()
                .filter(fact -> factKey.equals(fact.getFactKey()))
                .map(ExtractedFactEntity::getRawValue)
                .filter(value -> value != null && !value.isBlank())
                .findFirst()
                .orElse(null);
    }

    private static String compact(String value) {
        return value == null ? null : value.replaceAll("\\s+", "");
    }
}
