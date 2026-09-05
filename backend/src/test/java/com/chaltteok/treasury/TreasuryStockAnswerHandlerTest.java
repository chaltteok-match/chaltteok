package com.chaltteok.treasury;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
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
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class TreasuryStockAnswerHandlerTest {

    private static final String QUESTION =
            "삼성전자가 2025년 3월 1일 공시한 자기주식취득결정의 취득예정금액은 얼마인가?";
    private static final LocalDate REPORT_DATE = LocalDate.of(2025, 3, 1);

    @Mock
    private DisclosureDocumentRepository documentRepository;

    @Mock
    private ExtractedFactRepository factRepository;

    @Mock
    private CorpIdentityRepository corpIdentityRepository;

    private TreasuryStockAnswerHandler handler;

    @BeforeEach
    void setUp() {
        handler = new TreasuryStockAnswerHandler(
                documentRepository, factRepository, corpIdentityRepository);
        when(corpIdentityRepository.findByDocGroupAndDocSubtype(DocGroup.MAJOR, "자기주식취득결정"))
                .thenReturn(List.of(
                        new CorpIdentity("삼성전자", "삼성전자", "005930", "00126380")));
    }

    @Test
    void supportsAcquisitionNotDisposalOrTrust() {
        assertThat(handler.supports(QUESTION)).isTrue();
        assertThat(handler.supports("005930이 공시한 자기주식취득결정 취득예정금액은?")).isTrue();
        assertThat(handler.supports("삼성전자 자기주식 처분 규모는?")).isFalse();
        assertThat(handler.supports("삼성전자 자기주식취득신탁계약 규모는?")).isFalse();
        assertThat(handler.supports("한화에어로스페이스 유상증자 신주 수는?")).isFalse();
        assertThat(handler.supports("예시전자 자기주식 취득 규모는?")).isFalse();
    }

    @Test
    void answersPlannedAmount() {
        DisclosureDocumentEntity document = document();
        when(documentRepository.findByCorpNameAndDocGroupAndDocSubtypeAndRceptDt(
                eq("삼성전자"), eq(DocGroup.MAJOR), eq("자기주식취득결정"), eq(REPORT_DATE)))
                .thenReturn(List.of(document));
        when(factRepository.findByDocumentDocId("major_20250301000001"))
                .thenReturn(List.of(
                        fact(document, TreasuryQuestionParser.SHARES_FACT_KEY, "취득예정주식",
                                "1,000,000", "1000000", "share", "주"),
                        fact(document, TreasuryQuestionParser.AMOUNT_FACT_KEY, "취득예정금액",
                                "50,000,000,000", "50000000000", "KRW", "원")
                ));

        AnswerHandlerResult result = handler.handle("Q-TS", QUESTION);

        assertThat(result.isAnswerable()).isTrue();
        assertThat(result.evidences()).hasSize(1);
        assertThat(result.evidences().getFirst().evidenceText()).contains("50,000,000,000");
        assertThat(result.processSummary()).contains("treasury_planned_common_amount");
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "삼성전자가 2025-03-01 공시한 자기주식취득결정의 취득예정금액은 얼마인가?",
            "삼성전자가 2025.03.01 공시한 자기주식취득결정의 취득예정금액은 얼마인가?"
    })
    void looksUpSameDocumentWhenReportDateIsIsoOrDotted(String question) {
        DisclosureDocumentEntity document = document();
        when(documentRepository.findByCorpNameAndDocGroupAndDocSubtypeAndRceptDt(
                eq("삼성전자"), eq(DocGroup.MAJOR), eq("자기주식취득결정"), eq(REPORT_DATE)))
                .thenReturn(List.of(document));
        when(factRepository.findByDocumentDocId("major_20250301000001"))
                .thenReturn(List.of(
                        fact(document, TreasuryQuestionParser.AMOUNT_FACT_KEY, "취득예정금액",
                                "50,000,000,000", "50000000000", "KRW", "원")
                ));

        AnswerHandlerResult result = handler.handle("Q-TS", question);

        assertThat(result.isAnswerable()).isTrue();
        assertThat(result.evidences().getFirst().evidenceText()).contains("50,000,000,000");
        assertThat(result.processSummary()).contains("공시일=2025-03-01");
    }

    @Test
    void answersSpacedPlannedAmountWithoutIncludingShareCount() {
        DisclosureDocumentEntity document = document();
        when(documentRepository.findByCorpNameAndDocGroupAndDocSubtypeAndRceptDt(
                eq("삼성전자"), eq(DocGroup.MAJOR), eq("자기주식취득결정"), eq(REPORT_DATE)))
                .thenReturn(List.of(document));
        when(factRepository.findByDocumentDocId("major_20250301000001"))
                .thenReturn(List.of(
                        fact(document, TreasuryQuestionParser.SHARES_FACT_KEY, "취득예정주식",
                                "1,000,000", "1000000", "share", "주"),
                        fact(document, TreasuryQuestionParser.AMOUNT_FACT_KEY, "취득예정금액",
                                "50,000,000,000", "50000000000", "KRW", "원")
                ));

        AnswerHandlerResult result = handler.handle(
                "Q-TS-SPACE",
                "삼성전자가 2025년 3월 1일 공시한 자기주식 취득 예정 금액은?");

        assertThat(result.isAnswerable()).isTrue();
        assertThat(result.evidences()).hasSize(1);
        assertThat(result.evidences().getFirst().evidenceText()).contains("50,000,000,000");
        assertThat(result.evidences().getFirst().evidenceText()).doesNotContain("1,000,000");
        assertThat(result.processSummary()).contains("treasury_planned_common_amount");
        assertThat(result.processSummary()).doesNotContain("treasury_planned_common_shares");
    }

    @Test
    void requiresReportDateWhenSeveralFilingsExist() {
        DisclosureDocumentEntity first = document("major_20250301000001", false);
        DisclosureDocumentEntity second = document("major_20250401000001", LocalDate.of(2025, 4, 1), false);
        when(documentRepository.findByCorpNameAndDocGroupAndDocSubtype(
                "삼성전자", DocGroup.MAJOR, "자기주식취득결정"))
                .thenReturn(List.of(first, second));

        AnswerHandlerResult result = handler.handle("Q-TS", "삼성전자 자기주식취득결정의 취득예정금액은?");
        assertThat(result.isAnswerable()).isFalse();
        assertThat(result.unavailableReason()).contains("공시일");
        verify(documentRepository, never()).findByCorpNameAndDocGroupAndDocSubtypeAndRceptDt(
                any(), any(), any(), any());
    }

    @Test
    void answersWhenOnlyOneFilingExistsWithoutReportDate() {
        DisclosureDocumentEntity document = document();
        when(documentRepository.findByCorpNameAndDocGroupAndDocSubtype(
                "삼성전자", DocGroup.MAJOR, "자기주식취득결정"))
                .thenReturn(List.of(document));
        when(factRepository.findByDocumentDocId("major_20250301000001"))
                .thenReturn(List.of(
                        fact(document, TreasuryQuestionParser.AMOUNT_FACT_KEY, "취득예정금액",
                                "50,000,000,000", "50000000000", "KRW", "원")
                ));

        AnswerHandlerResult result = handler.handle("Q-TS", "삼성전자 자기주식취득결정의 취득예정금액은?");

        assertThat(result.isAnswerable()).isTrue();
        assertThat(result.processSummary()).contains("공시일 없이 단건");
        assertThat(result.evidences().getFirst().evidenceText()).contains("50,000,000,000");
    }

    @Test
    void prefersCorrectionOnSameDate() {
        DisclosureDocumentEntity original = document("major_20250301000001", false);
        DisclosureDocumentEntity correction = document("major_20250301000002", true);
        when(documentRepository.findByCorpNameAndDocGroupAndDocSubtypeAndRceptDt(
                any(), any(), any(), any()))
                .thenReturn(List.of(original, correction));
        when(factRepository.findByDocumentDocId("major_20250301000002"))
                .thenReturn(List.of(
                        fact(correction, TreasuryQuestionParser.AMOUNT_FACT_KEY, "취득예정금액",
                                "60,000,000,000", "60000000000", "KRW", "원")));

        AnswerHandlerResult result = handler.handle("Q-TS", QUESTION);

        assertThat(result.isAnswerable()).isTrue();
        assertThat(result.evidences().getFirst().evidenceText()).contains("60,000,000,000");
        assertThat(result.processSummary()).contains("major_20250301000002");
    }

    private static DisclosureDocumentEntity document() {
        return document("major_20250301000001", false);
    }

    private static DisclosureDocumentEntity document(String docId, boolean correction) {
        return document(docId, REPORT_DATE, correction);
    }

    private static DisclosureDocumentEntity document(String docId, LocalDate date, boolean correction) {
        DisclosureDocumentEntity entity = new DisclosureDocumentEntity();
        entity.setDocId(docId);
        entity.setCorpName("삼성전자");
        entity.setReportNm("주요사항보고서(자기주식취득결정)");
        entity.setRceptNo(docId.substring("major_".length()));
        entity.setRceptDt(date);
        entity.setDocGroup(DocGroup.MAJOR);
        entity.setDocSubtype("자기주식취득결정");
        entity.setCorrection(correction);
        return entity;
    }

    private static ExtractedFactEntity fact(
            DisclosureDocumentEntity document,
            String factKey,
            String label,
            String rawValue,
            String normalizedValue,
            String normalizedUnit,
            String rawUnit
    ) {
        ExtractedFactEntity fact = new ExtractedFactEntity();
        fact.setFactId(document.getDocId() + ":fact:" + factKey);
        fact.setDocument(document);
        fact.setFactKey(factKey);
        fact.setLabel(label);
        fact.setRawValue(rawValue);
        fact.setRawUnit(rawUnit);
        fact.setNormalizedValue(normalizedValue);
        fact.setNormalizedUnit(normalizedUnit);
        return fact;
    }
}
