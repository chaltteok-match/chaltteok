package com.chaltteok.ownership;

import static org.assertj.core.api.Assertions.assertThat;

import com.chaltteok.disclosure.model.enums.DocGroup;
import com.chaltteok.disclosure.persistence.entity.DisclosureDocumentEntity;
import com.chaltteok.disclosure.persistence.entity.ExtractedFactEntity;
import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.Test;

class OwnershipHoldingHistoryTest {

    @Test
    void classifiesReportTypes() {
        assertThat(OwnershipHoldingHistory.classify("신규")).isEqualTo(
                OwnershipHoldingHistory.ReportKind.INITIAL);
        assertThat(OwnershipHoldingHistory.classify("변경보고")).isEqualTo(
                OwnershipHoldingHistory.ReportKind.CHANGE);
        assertThat(OwnershipHoldingHistory.classify("보고의무 해소")).isEqualTo(
                OwnershipHoldingHistory.ReportKind.CLEAR);
        assertThat(OwnershipHoldingHistory.classify("기타")).isEqualTo(
                OwnershipHoldingHistory.ReportKind.UNKNOWN);
    }

    @Test
    void splitsNewFilingsIntoSeparateEvents() {
        OwnershipHoldingHistory.Step first = step(
                "holding_1", LocalDate.of(2024, 1, 1), "국민연금공단", "신규", "1", "1");
        OwnershipHoldingHistory.Step change = step(
                "holding_2", LocalDate.of(2024, 6, 1), "국민연금공단", "변경", "2", "2");
        OwnershipHoldingHistory.Step secondNew = step(
                "holding_3", LocalDate.of(2025, 1, 1), "국민연금공단", "신규", "3", "3");

        List<List<OwnershipHoldingHistory.Step>> chains =
                OwnershipHoldingHistory.eventChains(List.of(first, change, secondNew));

        assertThat(chains).hasSize(2);
        assertThat(chains.getFirst()).containsExactly(first, change);
        assertThat(chains.getLast()).containsExactly(secondNew);
    }

    @Test
    void doesNotAttachUnknownReportTypeToPreviousEvent() {
        OwnershipHoldingHistory.Step initial = step(
                "holding_1", LocalDate.of(2024, 1, 1), "국민연금공단", "신규", "1", "1");
        OwnershipHoldingHistory.Step unknown = step(
                "holding_2", LocalDate.of(2024, 6, 1), "국민연금공단", "기타", "2", "2");

        List<List<OwnershipHoldingHistory.Step>> chains =
                OwnershipHoldingHistory.eventChains(List.of(initial, unknown));

        assertThat(chains).hasSize(2);
    }

    private static OwnershipHoldingHistory.Step step(
            String docId,
            LocalDate date,
            String holder,
            String reportType,
            String shares,
            String ratio
    ) {
        DisclosureDocumentEntity document = new DisclosureDocumentEntity();
        document.setDocId(docId);
        document.setRceptNo(docId);
        document.setRceptDt(date);
        document.setDocGroup(DocGroup.HOLDING);
        document.setCorrection(false);
        List<ExtractedFactEntity> facts = List.of(
                fact(document, OwnershipQuestionParser.HOLDER_FACT_KEY, holder),
                fact(document, OwnershipQuestionParser.REPORT_TYPE_FACT_KEY, reportType),
                fact(document, OwnershipQuestionParser.SHARES_FACT_KEY, shares),
                fact(document, OwnershipQuestionParser.RATIO_FACT_KEY, ratio)
        );
        return OwnershipHoldingHistory.stepOf(document, facts);
    }

    private static ExtractedFactEntity fact(
            DisclosureDocumentEntity document,
            String factKey,
            String rawValue
    ) {
        ExtractedFactEntity fact = new ExtractedFactEntity();
        fact.setFactId(document.getDocId() + ":" + factKey);
        fact.setDocument(document);
        fact.setFactKey(factKey);
        fact.setLabel(factKey);
        fact.setRawValue(rawValue);
        fact.setNormalizedValue(rawValue);
        return fact;
    }
}
