package com.chaltteok.answer;

import com.chaltteok.disclosure.persistence.entity.DisclosureDocumentEntity;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 날짜 없는 단건 조회에서 「공시일이 하루로 모이는지」를 본다.
 *
 * <p>같은 날 원본·정정은 정정본만 남긴다. 날이 둘 이상이면 고르지 않는다.
 */
public final class SingleReportDateDocuments {

    private SingleReportDateDocuments() {
    }

    public static List<DisclosureDocumentEntity> uniqueDay(List<DisclosureDocumentEntity> documents) {
        Map<LocalDate, List<DisclosureDocumentEntity>> byDate = new LinkedHashMap<>();
        for (DisclosureDocumentEntity document : documents) {
            LocalDate date = document.getRceptDt();
            if (date == null) {
                continue;
            }
            byDate.computeIfAbsent(date, key -> new ArrayList<>()).add(document);
        }
        if (byDate.size() != 1) {
            return List.of();
        }
        List<DisclosureDocumentEntity> sameDay = byDate.values().iterator().next();
        List<DisclosureDocumentEntity> corrections = sameDay.stream()
                .filter(DisclosureDocumentEntity::isCorrection)
                .toList();
        return corrections.isEmpty() ? List.copyOf(sameDay) : corrections;
    }
}
