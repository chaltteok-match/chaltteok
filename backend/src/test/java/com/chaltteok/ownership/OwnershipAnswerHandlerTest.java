package com.chaltteok.ownership;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.chaltteok.answer.AnswerHandlerResult;
import com.chaltteok.answer.CorpIdentity;
import com.chaltteok.disclosure.model.enums.DocGroup;
import com.chaltteok.disclosure.persistence.entity.DisclosureDocumentEntity;
import com.chaltteok.disclosure.persistence.entity.ExtractedFactEntity;
import com.chaltteok.disclosure.persistence.repository.CorpIdentityRepository;
import com.chaltteok.disclosure.persistence.repository.DisclosureDocumentRepository;
import com.chaltteok.disclosure.persistence.repository.ExtractedFactRepository;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class OwnershipAnswerHandlerTest {

    private static final String QUESTION =
            "삼성전자가 2025년 1월 15일 공시한 대량보유상황보고서의 지분율은 얼마인가?";
    private static final LocalDate REPORT_DATE = LocalDate.of(2025, 1, 15);

    @Mock
    private DisclosureDocumentRepository documentRepository;

    @Mock
    private ExtractedFactRepository factRepository;

    @Mock
    private CorpIdentityRepository corpIdentityRepository;

    private OwnershipAnswerHandler handler;

    @BeforeEach
    void setUp() {
        handler = new OwnershipAnswerHandler(
                documentRepository, factRepository, corpIdentityRepository);
        when(corpIdentityRepository.findByDocGroupAndDocSubtype(DocGroup.HOLDING, "대량보유상황보고서"))
                .thenReturn(List.of(
                        new CorpIdentity("삼성전자", "삼성전자", "005930", "00126380")));
    }

    @Test
    void supportsHoldingQuestionsForLoadedCompany() {
        assertThat(handler.supports(QUESTION)).isTrue();
        assertThat(handler.supports("삼성전자 지분공시 보고자는?")).isTrue();
        assertThat(handler.supports("005930이 공시한 대량보유상황보고서 지분율은?")).isTrue();
        assertThat(handler.supports("한화에어로스페이스 유상증자 지분율은?")).isFalse();
        assertThat(handler.supports("예시전자 대량보유상황보고서 지분율은?")).isFalse();
    }

    @Test
    void answersRatioFromSameDateDocument() {
        DisclosureDocumentEntity document = document("holding_20250115000001", false);
        when(documentRepository.findByCorpNameAndDocGroupAndDocSubtypeAndRceptDt(
                eq("삼성전자"), eq(DocGroup.HOLDING), eq("대량보유상황보고서"), eq(REPORT_DATE)))
                .thenReturn(List.of(document));
        when(factRepository.findByDocumentDocId("holding_20250115000001"))
                .thenReturn(List.of(
                        fact(document, "ownership_holder", "대표보고자", "국민연금공단", null, null),
                        fact(document, "ownership_ratio", "보유비율 (%)", "8.25", "8.25", "%")
                ));

        AnswerHandlerResult result = handler.handle("Q-OWN", QUESTION);

        assertThat(result.isAnswerable()).isTrue();
        assertThat(result.evidences()).hasSize(1);
        assertThat(result.evidences().getFirst().evidenceText()).contains("8.25");
        assertThat(result.processSummary()).contains("ownership_ratio=8.25");
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "삼성전자가 2025-01-15 공시한 대량보유상황보고서의 지분율은 얼마인가?",
            "삼성전자가 2025.01.15 공시한 대량보유상황보고서의 지분율은 얼마인가?"
    })
    void looksUpSameDocumentWhenReportDateIsIsoOrDotted(String question) {
        DisclosureDocumentEntity document = document("holding_20250115000001", false);
        when(documentRepository.findByCorpNameAndDocGroupAndDocSubtypeAndRceptDt(
                eq("삼성전자"), eq(DocGroup.HOLDING), eq("대량보유상황보고서"), eq(REPORT_DATE)))
                .thenReturn(List.of(document));
        when(factRepository.findByDocumentDocId("holding_20250115000001"))
                .thenReturn(List.of(
                        fact(document, "ownership_ratio", "보유비율 (%)", "8.25", "8.25", "%")
                ));

        AnswerHandlerResult result = handler.handle("Q-OWN", question);

        assertThat(result.isAnswerable()).isTrue();
        assertThat(result.evidences().getFirst().evidenceText()).contains("8.25");
        assertThat(result.processSummary()).contains("공시일=2025-01-15");
    }

    @Test
    void requiresReportDateWhenSeveralFilingsExist() {
        DisclosureDocumentEntity first = document("holding_20250115000001", false);
        DisclosureDocumentEntity second = document("holding_20250310000001", LocalDate.of(2025, 3, 10), false);
        when(documentRepository.findByCorpNameAndDocGroupAndDocSubtype(
                "삼성전자", DocGroup.HOLDING, "대량보유상황보고서"))
                .thenReturn(List.of(first, second));

        AnswerHandlerResult result = handler.handle("Q-OWN", "삼성전자 대량보유상황보고서의 지분율은?");
        assertThat(result.isAnswerable()).isFalse();
        assertThat(result.unavailableReason()).contains("공시일");
        verify(documentRepository, never()).findByCorpNameAndDocGroupAndDocSubtypeAndRceptDt(
                any(), any(), any(), any());
    }

    @Test
    void answersWhenOnlyOneFilingExistsWithoutReportDate() {
        DisclosureDocumentEntity document = document("holding_20250115000001", false);
        when(documentRepository.findByCorpNameAndDocGroupAndDocSubtype(
                "삼성전자", DocGroup.HOLDING, "대량보유상황보고서"))
                .thenReturn(List.of(document));
        when(factRepository.findByDocumentDocId("holding_20250115000001"))
                .thenReturn(List.of(
                        fact(document, "ownership_ratio", "보유비율 (%)", "8.25", "8.25", "%")
                ));

        AnswerHandlerResult result = handler.handle("Q-OWN", "삼성전자 대량보유상황보고서의 지분율은?");

        assertThat(result.isAnswerable()).isTrue();
        assertThat(result.processSummary()).contains("공시일 없이 단건");
        assertThat(result.evidences().getFirst().evidenceText()).contains("8.25");
    }

    @Test
    void prefersCorrectionOnSameDate() {
        DisclosureDocumentEntity original = document("holding_20250115000001", false);
        DisclosureDocumentEntity correction = document("holding_20250115000002", true);
        when(documentRepository.findByCorpNameAndDocGroupAndDocSubtypeAndRceptDt(
                any(), any(), any(), any()))
                .thenReturn(List.of(original, correction));
        when(factRepository.findByDocumentDocId("holding_20250115000002"))
                .thenReturn(List.of(
                        fact(correction, "ownership_ratio", "보유비율 (%)", "9.00", "9", "%")));

        AnswerHandlerResult result = handler.handle("Q-OWN", QUESTION);

        assertThat(result.isAnswerable()).isTrue();
        assertThat(result.evidences().getFirst().evidenceText()).contains("9.00");
        assertThat(result.processSummary()).contains("holding_20250115000002");
    }

    @Test
    void datedCorrectionWordingUsesSingleDocument() {
        DisclosureDocumentEntity original = document("holding_20250115000001", REPORT_DATE, false);
        DisclosureDocumentEntity correction = document("holding_20250115000002", REPORT_DATE, true);
        when(documentRepository.findByCorpNameAndDocGroupAndDocSubtypeAndRceptDt(
                any(), any(), any(), any()))
                .thenReturn(List.of(original, correction));
        when(factRepository.findByDocumentDocId("holding_20250115000002"))
                .thenReturn(List.of(
                        fact(correction, "ownership_ratio", "보유비율 (%)", "9.00", "9", "%")));

        AnswerHandlerResult result = handler.handle(
                "Q-OWN",
                "삼성전자가 2025년 1월 15일 정정한 대량보유상황보고서의 지분율은?");

        assertThat(result.isAnswerable()).isTrue();
        assertThat(result.evidences()).hasSize(1);
        assertThat(result.processSummary())
                .contains("문서 holding_20250115000002 선택")
                .doesNotContain("보유 이력");
        verify(documentRepository, never()).findByCorpNameAndDocGroupAndDocSubtype(
                anyString(), any(), anyString());
    }

    @Test
    void tracesNewChangeClearForOneHolder() {
        LocalDate initialDate = LocalDate.of(2024, 12, 1);
        LocalDate changeDate = REPORT_DATE;
        LocalDate clearDate = LocalDate.of(2025, 3, 10);
        DisclosureDocumentEntity initial = document("holding_20241201000001", initialDate, false);
        DisclosureDocumentEntity change = document("holding_20250115000001", changeDate, false);
        DisclosureDocumentEntity clear = document("holding_20250310000001", clearDate, false);
        when(documentRepository.findByCorpNameAndDocGroupAndDocSubtype(
                "삼성전자", DocGroup.HOLDING, "대량보유상황보고서"))
                .thenReturn(List.of(initial, change, clear));
        when(factRepository.findByDocumentDocId("holding_20241201000001"))
                .thenReturn(holdingFacts(initial, "국민연금공단", "신규", "40000000", "6.00"));
        when(factRepository.findByDocumentDocId("holding_20250115000001"))
                .thenReturn(holdingFacts(change, "국민연금공단", "변경", "50000000", "8.25"));
        when(factRepository.findByDocumentDocId("holding_20250310000001"))
                .thenReturn(holdingFacts(clear, "국민연금공단", "해소", "0", "0"));

        AnswerHandlerResult result = handler.handle(
                "Q-OWN",
                "삼성전자 대량보유상황보고서 지분율은 어떻게 바뀌었나?");

        assertThat(result.isAnswerable()).isTrue();
        assertThat(result.evidences()).hasSize(3);
        assertThat(result.processSummary())
                .contains("보유 이력")
                .contains("2024-12-01 신규")
                .contains("2025-01-15 변경")
                .contains("2025-03-10 해소")
                .contains("6.00")
                .contains("8.25");
    }

    @Test
    void dateLessHistoryIsUnavailableWhenMultipleEventsExist() {
        DisclosureDocumentEntity first = document("holding_20241201000001", LocalDate.of(2024, 12, 1), false);
        DisclosureDocumentEntity second = document("holding_20250601000001", LocalDate.of(2025, 6, 1), false);
        when(documentRepository.findByCorpNameAndDocGroupAndDocSubtype(
                "삼성전자", DocGroup.HOLDING, "대량보유상황보고서"))
                .thenReturn(List.of(first, second));
        when(factRepository.findByDocumentDocId("holding_20241201000001"))
                .thenReturn(holdingFacts(first, "국민연금공단", "신규", "40000000", "6.00"));
        when(factRepository.findByDocumentDocId("holding_20250601000001"))
                .thenReturn(holdingFacts(second, "국민연금공단", "신규", "10000000", "2.00"));

        AnswerHandlerResult result = handler.handle(
                "Q-OWN",
                "삼성전자 지분공시 지분율은 어떻게 바뀌었나?");

        assertThat(result.isAnswerable()).isFalse();
        assertThat(result.unavailableReason()).contains("여러 보고 사건");
    }

    @Test
    void datedHistoryPicksTheEventContainingThatDay() {
        DisclosureDocumentEntity firstNew = document("holding_20241201000001", LocalDate.of(2024, 12, 1), false);
        DisclosureDocumentEntity firstChange = document("holding_20250115000001", REPORT_DATE, false);
        DisclosureDocumentEntity secondNew = document("holding_20250601000001", LocalDate.of(2025, 6, 1), false);
        when(documentRepository.findByCorpNameAndDocGroupAndDocSubtype(
                "삼성전자", DocGroup.HOLDING, "대량보유상황보고서"))
                .thenReturn(List.of(firstNew, firstChange, secondNew));
        when(factRepository.findByDocumentDocId("holding_20241201000001"))
                .thenReturn(holdingFacts(firstNew, "국민연금공단", "신규", "40000000", "6.00"));
        when(factRepository.findByDocumentDocId("holding_20250115000001"))
                .thenReturn(holdingFacts(firstChange, "국민연금공단", "변경", "50000000", "8.25"));
        when(factRepository.findByDocumentDocId("holding_20250601000001"))
                .thenReturn(holdingFacts(secondNew, "국민연금공단", "신규", "10000000", "2.00"));

        AnswerHandlerResult result = handler.handle(
                "Q-OWN",
                "삼성전자가 2025년 1월 15일 공시한 대량보유상황보고서 지분율은 어떻게 바뀌었나?");

        assertThat(result.isAnswerable()).isTrue();
        assertThat(result.processSummary())
                .contains("2024-12-01 신규")
                .contains("2025-01-15 변경")
                .doesNotContain("2025-06-01");
    }

    @Test
    void doesNotJoinDifferentHoldersOnTheSameDate() {
        DisclosureDocumentEntity pension = document("holding_20250115000001", REPORT_DATE, false);
        DisclosureDocumentEntity other = document("holding_20250115000002", REPORT_DATE, false);
        when(documentRepository.findByCorpNameAndDocGroupAndDocSubtype(
                "삼성전자", DocGroup.HOLDING, "대량보유상황보고서"))
                .thenReturn(List.of(pension, other));
        when(factRepository.findByDocumentDocId("holding_20250115000001"))
                .thenReturn(holdingFacts(pension, "국민연금공단", "변경", "50000000", "8.25"));
        when(factRepository.findByDocumentDocId("holding_20250115000002"))
                .thenReturn(holdingFacts(other, "블랙록", "변경", "20000000", "3.10"));

        AnswerHandlerResult result = handler.handle(
                "Q-OWN",
                "삼성전자가 2025년 1월 15일 공시한 대량보유상황보고서 지분율은 어떻게 바뀌었나?");

        assertThat(result.isAnswerable()).isFalse();
        assertThat(result.unavailableReason()).contains("한 사건으로 특정하지 못했습니다");
    }

    @Test
    void historyKeepsFirstAndLastWhenQuantityDidNotChange() {
        DisclosureDocumentEntity original = document("holding_20241201000001", LocalDate.of(2024, 12, 1), false);
        DisclosureDocumentEntity correction = document("holding_20250115000002", REPORT_DATE, true);
        when(documentRepository.findByCorpNameAndDocGroupAndDocSubtype(
                "삼성전자", DocGroup.HOLDING, "대량보유상황보고서"))
                .thenReturn(List.of(original, correction));
        when(factRepository.findByDocumentDocId("holding_20241201000001"))
                .thenReturn(holdingFacts(original, "국민연금공단", "신규", "50000000", "8.25"));
        when(factRepository.findByDocumentDocId("holding_20250115000002"))
                .thenReturn(holdingFacts(correction, "국민연금공단", "변경", "50000000", "8.25"));

        AnswerHandlerResult result = handler.handle(
                "Q-OWN",
                "삼성전자 대량보유상황보고서 지분율은 변경된 내역이 있나?");

        assertThat(result.isAnswerable()).isTrue();
        assertThat(result.evidences()).hasSize(2);
        assertThat(result.processSummary())
                .contains("2024-12-01 신규")
                .contains("2025-01-15 정정");
    }

    @Test
    void answersPurposeOnASingleFilingDate() {
        DisclosureDocumentEntity document = document("holding_20250115000001", false);
        when(documentRepository.findByCorpNameAndDocGroupAndDocSubtypeAndRceptDt(
                eq("삼성전자"), eq(DocGroup.HOLDING), eq("대량보유상황보고서"), eq(REPORT_DATE)))
                .thenReturn(List.of(document));
        when(factRepository.findByDocumentDocId("holding_20250115000001"))
                .thenReturn(List.of(
                        fact(document, "ownership_holder", "대표보고자", "국민연금공단", null, null),
                        fact(document, "ownership_purpose", "보유목적", "단순투자", null, null)
                ));

        AnswerHandlerResult result = handler.handle(
                "Q-OWN",
                "삼성전자가 2025년 1월 15일 공시한 대량보유상황보고서의 보유 목적은?");

        assertThat(result.isAnswerable()).isTrue();
        assertThat(result.evidences()).hasSize(1);
        assertThat(result.evidences().getFirst().evidenceText()).contains("단순투자");
    }

    @Test
    void purposeHistoryKeepsPurposeChangeWhenQuantityDidNotChange() {
        DisclosureDocumentEntity original = document("holding_20241201000001", LocalDate.of(2024, 12, 1), false);
        DisclosureDocumentEntity changed = document("holding_20250115000001", REPORT_DATE, false);
        when(documentRepository.findByCorpNameAndDocGroupAndDocSubtype(
                "삼성전자", DocGroup.HOLDING, "대량보유상황보고서"))
                .thenReturn(List.of(original, changed));
        when(factRepository.findByDocumentDocId("holding_20241201000001"))
                .thenReturn(holdingFacts(original, "국민연금공단", "신규", "50000000", "8.25", "단순투자"));
        when(factRepository.findByDocumentDocId("holding_20250115000001"))
                .thenReturn(holdingFacts(changed, "국민연금공단", "변경", "50000000", "8.25", "경영권 영향"));

        AnswerHandlerResult result = handler.handle(
                "Q-OWN",
                "삼성전자 대량보유상황보고서 보유목적은 어떻게 바뀌었나?");

        assertThat(result.isAnswerable()).isTrue();
        assertThat(result.evidences()).hasSize(2);
        assertThat(result.evidences().getFirst().evidenceText()).contains("단순투자");
        assertThat(result.evidences().getLast().evidenceText()).contains("경영권 영향");
        assertThat(result.processSummary()).contains("단순투자").contains("경영권영향");
    }

    @Test
    void quantityHistoryStillFoldsPurposeOnlyChange() {
        DisclosureDocumentEntity first = document("holding_20241201000001", LocalDate.of(2024, 12, 1), false);
        DisclosureDocumentEntity middle = document("holding_20250101000001", LocalDate.of(2025, 1, 1), false);
        DisclosureDocumentEntity last = document("holding_20250115000001", REPORT_DATE, false);
        when(documentRepository.findByCorpNameAndDocGroupAndDocSubtype(
                "삼성전자", DocGroup.HOLDING, "대량보유상황보고서"))
                .thenReturn(List.of(first, middle, last));
        when(factRepository.findByDocumentDocId("holding_20241201000001"))
                .thenReturn(holdingFacts(first, "국민연금공단", "신규", "50000000", "8.25", "단순투자"));
        when(factRepository.findByDocumentDocId("holding_20250101000001"))
                .thenReturn(holdingFacts(middle, "국민연금공단", "변경", "50000000", "8.25", "경영권 영향"));
        when(factRepository.findByDocumentDocId("holding_20250115000001"))
                .thenReturn(holdingFacts(last, "국민연금공단", "변경", "50000000", "8.25", "경영권 영향"));

        AnswerHandlerResult result = handler.handle(
                "Q-OWN",
                "삼성전자 대량보유상황보고서 지분율은 어떻게 바뀌었나?");

        assertThat(result.isAnswerable()).isTrue();
        assertThat(result.evidences()).hasSize(2);
        assertThat(result.processSummary())
                .contains("2024-12-01 신규")
                .contains("2025-01-15 변경")
                .doesNotContain("2025-01-01");
    }

    private static DisclosureDocumentEntity document(String docId, boolean correction) {
        return document(docId, REPORT_DATE, correction);
    }

    private static DisclosureDocumentEntity document(String docId, LocalDate date, boolean correction) {
        DisclosureDocumentEntity entity = new DisclosureDocumentEntity();
        entity.setDocId(docId);
        entity.setCorpName("삼성전자");
        entity.setReportNm("주식등의 대량보유상황보고서");
        entity.setRceptNo(docId.substring("holding_".length()));
        entity.setRceptDt(date);
        entity.setDocGroup(DocGroup.HOLDING);
        entity.setDocSubtype("대량보유상황보고서");
        entity.setCorrection(correction);
        return entity;
    }

    private static List<ExtractedFactEntity> holdingFacts(
            DisclosureDocumentEntity document,
            String holder,
            String reportType,
            String shares,
            String ratio
    ) {
        return holdingFacts(document, holder, reportType, shares, ratio, null);
    }

    private static List<ExtractedFactEntity> holdingFacts(
            DisclosureDocumentEntity document,
            String holder,
            String reportType,
            String shares,
            String ratio,
            String purpose
    ) {
        List<ExtractedFactEntity> facts = new ArrayList<>(List.of(
                fact(document, "ownership_holder", "대표보고자", holder, null, null),
                fact(document, "ownership_report_type", "보고구분", reportType, null, null),
                fact(document, "ownership_shares", "보유주식등의 수 (주)", shares, shares, "share"),
                fact(document, "ownership_ratio", "보유비율 (%)", ratio, ratio, "%")
        ));
        if (purpose != null) {
            facts.add(fact(document, "ownership_purpose", "보유목적", purpose, null, null));
        }
        return facts;
    }

    private static ExtractedFactEntity fact(
            DisclosureDocumentEntity document,
            String factKey,
            String label,
            String rawValue,
            String normalizedValue,
            String normalizedUnit
    ) {
        ExtractedFactEntity fact = new ExtractedFactEntity();
        fact.setFactId(document.getDocId() + ":fact:" + factKey);
        fact.setDocument(document);
        fact.setFactKey(factKey);
        fact.setLabel(label);
        fact.setRawValue(rawValue);
        fact.setNormalizedValue(normalizedValue);
        fact.setNormalizedUnit(normalizedUnit);
        return fact;
    }
}
