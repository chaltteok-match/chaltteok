package com.chaltteok.answer;

import static org.assertj.core.api.Assertions.assertThat;

import com.chaltteok.disclosure.model.enums.DocGroup;
import com.chaltteok.disclosure.persistence.entity.DisclosureDocumentEntity;
import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.Test;

class SingleReportDateDocumentsTest {

    @Test
    void keepsSameDayCorrectionOnly() {
        DisclosureDocumentEntity original = document("a", LocalDate.of(2025, 4, 8), false);
        DisclosureDocumentEntity correction = document("b", LocalDate.of(2025, 4, 8), true);

        List<DisclosureDocumentEntity> unique =
                SingleReportDateDocuments.uniqueDay(List.of(original, correction));

        assertThat(unique).containsExactly(correction);
    }

    @Test
    void returnsEmptyWhenDatesDiffer() {
        DisclosureDocumentEntity first = document("a", LocalDate.of(2025, 4, 8), false);
        DisclosureDocumentEntity second = document("b", LocalDate.of(2025, 4, 18), false);

        assertThat(SingleReportDateDocuments.uniqueDay(List.of(first, second))).isEmpty();
    }

    @Test
    void keepsOnlyDocumentsInTheAskedYear() {
        DisclosureDocumentEntity in2024 = document("a", LocalDate.of(2024, 9, 30), false);
        DisclosureDocumentEntity in2025 = document("b", LocalDate.of(2025, 8, 14), false);

        assertThat(SingleReportDateDocuments.inYear(List.of(in2024, in2025), 2024))
                .containsExactly(in2024);
        assertThat(SingleReportDateDocuments.inYear(List.of(in2024, in2025), 2099))
                .isEmpty();
        assertThat(SingleReportDateDocuments.inYear(List.of(in2024, in2025), null))
                .containsExactly(in2024, in2025);
    }

    private static DisclosureDocumentEntity document(String docId, LocalDate date, boolean correction) {
        DisclosureDocumentEntity entity = new DisclosureDocumentEntity();
        entity.setDocId(docId);
        entity.setRceptDt(date);
        entity.setDocGroup(DocGroup.MAJOR);
        entity.setCorrection(correction);
        return entity;
    }
}
