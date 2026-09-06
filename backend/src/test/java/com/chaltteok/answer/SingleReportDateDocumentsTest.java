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
    void keepsTheCorrectionWhenItArrivedTheNextDay() {
        // 정정본이 원본 다음 날 접수되는 일이 흔하다. 다른 사건으로 세면 안 된다.
        DisclosureDocumentEntity original = document("a", LocalDate.of(2024, 4, 22), false);
        DisclosureDocumentEntity correction = document("b", LocalDate.of(2024, 4, 23), true);

        assertThat(SingleReportDateDocuments.uniqueDay(List.of(original, correction)))
                .containsExactly(correction);
    }

    @Test
    void returnsEmptyWhenSeveralOriginalsExistEvenIfOnlyOneWasCorrected() {
        // 원본이 여러 날이면 정정본이 하나여도 어느 사건을 묻는지 알 수 없다.
        DisclosureDocumentEntity first = document("a", LocalDate.of(2023, 5, 2), false);
        DisclosureDocumentEntity second = document("b", LocalDate.of(2024, 4, 22), false);
        DisclosureDocumentEntity correction = document("c", LocalDate.of(2024, 4, 23), true);

        assertThat(SingleReportDateDocuments.uniqueDay(List.of(first, second, correction)))
                .isEmpty();
    }

    @Test
    void returnsEmptyWhenCorrectionsThemselvesSpanSeveralDays() {
        DisclosureDocumentEntity original = document("a", LocalDate.of(2024, 4, 22), false);
        DisclosureDocumentEntity first = document("b", LocalDate.of(2024, 4, 23), true);
        DisclosureDocumentEntity second = document("c", LocalDate.of(2024, 7, 1), true);

        assertThat(SingleReportDateDocuments.uniqueDay(List.of(original, first, second)))
                .isEmpty();
    }

    @Test
    void keepsASingleOriginalWithNoCorrection() {
        DisclosureDocumentEntity original = document("a", LocalDate.of(2024, 4, 22), false);

        assertThat(SingleReportDateDocuments.uniqueDay(List.of(original)))
                .containsExactly(original);
    }

    @Test
    void returnsEmptyWhenTheCorrectionIsOlderThanTheOriginal() {
        // 정정본이 원본보다 앞설 수는 없다. 다른 사건의 정정본이다.
        DisclosureDocumentEntity correction = document("a", LocalDate.of(2025, 4, 8), true);
        DisclosureDocumentEntity original = document("b", LocalDate.of(2025, 4, 18), false);

        assertThat(SingleReportDateDocuments.uniqueDay(List.of(correction, original))).isEmpty();
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

    @Test
    void keepsACorrectionThatCrossedIntoTheNextYear() {
        // LG씨엔에스 유상증자: 원본 2024-12-05, 정정 2025-01-17.
        // 갈라 놓으면 2024년으로 물었을 때 정정 전 값을 답한다.
        DisclosureDocumentEntity original = document("a", LocalDate.of(2024, 12, 5), false);
        DisclosureDocumentEntity correction = document("b", LocalDate.of(2025, 1, 17), true);

        List<DisclosureDocumentEntity> scoped =
                SingleReportDateDocuments.inYear(List.of(original, correction), 2024);

        assertThat(scoped).containsExactly(original, correction);
        assertThat(SingleReportDateDocuments.uniqueDay(scoped)).containsExactly(correction);
    }

    @Test
    void doesNotJoinACorrectionWhenAnotherEventFiledInBetween() {
        // 이마트 자기주식: 2024-07-18 원본, 2025-01-20 원본과 그 정정.
        // 2025년 정정은 2025년 원본의 것이다. 2024년 원본에 이으면 다른 사건을 답한다.
        DisclosureDocumentEntity first = document("a", LocalDate.of(2024, 7, 18), false);
        DisclosureDocumentEntity second = document("b", LocalDate.of(2025, 1, 20), false);
        DisclosureDocumentEntity correction = document("c", LocalDate.of(2025, 1, 20), true);

        assertThat(SingleReportDateDocuments.inYear(List.of(first, second, correction), 2024))
                .containsExactly(first);
    }

    @Test
    void doesNotJoinACorrectionWhenTheYearHoldsSeveralOriginals() {
        DisclosureDocumentEntity first = document("a", LocalDate.of(2024, 3, 2), false);
        DisclosureDocumentEntity second = document("b", LocalDate.of(2024, 12, 5), false);
        DisclosureDocumentEntity correction = document("c", LocalDate.of(2025, 1, 17), true);

        assertThat(SingleReportDateDocuments.inYear(List.of(first, second, correction), 2024))
                .containsExactly(first, second);
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
