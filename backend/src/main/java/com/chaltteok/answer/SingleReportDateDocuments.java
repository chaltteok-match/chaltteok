package com.chaltteok.answer;

import com.chaltteok.disclosure.persistence.entity.DisclosureDocumentEntity;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 날짜 없는 단건 조회에서 「한 사건으로 볼 수 있는지」를 본다.
 *
 * <p>연도가 있으면 그 해 접수일만 남긴 뒤 본다.
 *
 * <p>원본과 정정본을 나눠 센다. <b>원본이 하루, 정정본이 하루</b>면 한 사건이고 정정본이
 * 원본을 대신한다. 정정본은 원본 다음 날 접수되는 일이 흔해, 접수일 수만 세면 한 쌍뿐인
 * 기업도 여러 공시로 보게 된다. 원본이 여러 날이거나 정정본이 여러 날이면 어느 사건을
 * 묻는지 알 수 없어 고르지 않는다. 정정본이 원본보다 앞서면 다른 사건의 것이므로 잇지 않는다.
 */
public final class SingleReportDateDocuments {

    private SingleReportDateDocuments() {
    }

    /**
     * 연도가 있으면 그 해 접수일만 남긴다. 연도가 없으면 받은 목록을 그대로 둔다.
     *
     * <p>정정본이 해를 넘겨 접수되면 접수일만 보고 자를 때 원본과 갈라진다. 갈라 놓으면
     * {@code 2024년}으로 물었을 때 <b>정정 전 값</b>을 답하게 된다. 실제로 그런 공시가 있다
     * (LG씨엔에스 유상증자: 원본 2024-12-05, 정정 2025-01-17).
     *
     * <p>그래서 그 해 원본이 <b>하나뿐이고</b> 그 뒤에 다른 원본이 없을 때만, 해를 넘긴
     * 정정본을 함께 남긴다. 그 사이에 다른 원본이 있으면 어느 사건의 정정인지 알 수 없어
     * 잇지 않는다.
     */
    public static List<DisclosureDocumentEntity> inYear(
            List<DisclosureDocumentEntity> documents,
            Integer year
    ) {
        if (year == null) {
            return List.copyOf(documents);
        }
        List<DisclosureDocumentEntity> kept = documents.stream()
                .filter(document -> document.getRceptDt() != null
                        && document.getRceptDt().getYear() == year)
                .toList();
        List<DisclosureDocumentEntity> originals = kept.stream()
                .filter(document -> !document.isCorrection())
                .toList();
        if (originals.size() != 1) {
            return kept;
        }
        LocalDate filedOn = originals.getFirst().getRceptDt();
        List<DisclosureDocumentEntity> later = documents.stream()
                .filter(document -> document.getRceptDt() != null
                        && document.getRceptDt().isAfter(filedOn)
                        && document.getRceptDt().getYear() != year)
                .toList();
        List<DisclosureDocumentEntity> corrections =
                later.stream().filter(DisclosureDocumentEntity::isCorrection).toList();
        if (corrections.isEmpty()) {
            return kept;
        }
        LocalDate lastCorrection = corrections.stream()
                .map(DisclosureDocumentEntity::getRceptDt)
                .max(LocalDate::compareTo)
                .orElseThrow();
        boolean anotherEventInBetween = later.stream()
                .anyMatch(document -> !document.isCorrection()
                        && !document.getRceptDt().isAfter(lastCorrection));
        if (anotherEventInBetween) {
            return kept;
        }
        List<DisclosureDocumentEntity> joined = new ArrayList<>(kept);
        joined.addAll(corrections);
        return List.copyOf(joined);
    }

    /**
     * 한 사건으로 볼 수 있으면 그 문서들을, 아니면 빈 목록을 돌려준다.
     *
     * <p>정정본은 원본 다음 날 접수되는 일이 흔하다. 접수일이 다르다고 다른 사건으로 세면
     * 원본·정정본 한 쌍뿐인 기업에도 「공시일을 특정하지 못했습니다」가 나간다.
     *
     * <p>그래서 원본과 정정본을 나눠 센다. <b>원본이 하루, 정정본이 하루</b>면 한 사건이다.
     * 원본이 여러 날이면 정정본이 하나여도 어느 사건을 묻는지 알 수 없으므로 고르지 않는다.
     * 한 사건으로 판단되면 정정본이 원본을 대신한다.
     */
    public static List<DisclosureDocumentEntity> uniqueDay(List<DisclosureDocumentEntity> documents) {
        List<DisclosureDocumentEntity> dated = documents.stream()
                .filter(document -> document.getRceptDt() != null)
                .toList();
        List<DisclosureDocumentEntity> corrections = dated.stream()
                .filter(DisclosureDocumentEntity::isCorrection)
                .toList();
        List<DisclosureDocumentEntity> originals = dated.stream()
                .filter(document -> !document.isCorrection())
                .toList();
        if (distinctDays(originals) > 1 || distinctDays(corrections) > 1) {
            return List.of();
        }
        if (corrections.isEmpty()) {
            return originals.isEmpty() ? List.of() : List.copyOf(originals);
        }
        // 정정본은 원본보다 뒤에 온다. 앞선 정정본은 다른 사건의 것이므로 잇지 않는다.
        if (!originals.isEmpty()
                && corrections.getFirst().getRceptDt().isBefore(originals.getFirst().getRceptDt())) {
            return List.of();
        }
        return List.copyOf(corrections);
    }

    private static int distinctDays(List<DisclosureDocumentEntity> documents) {
        Map<LocalDate, List<DisclosureDocumentEntity>> byDate = new LinkedHashMap<>();
        for (DisclosureDocumentEntity document : documents) {
            byDate.computeIfAbsent(document.getRceptDt(), key -> new ArrayList<>()).add(document);
        }
        return byDate.size();
    }
}
