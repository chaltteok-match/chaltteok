package com.chaltteok.ownership;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

import com.chaltteok.answer.AnswerHandlerResult;
import com.chaltteok.disclosure.model.enums.DocGroup;
import com.chaltteok.disclosure.persistence.entity.DisclosureDocumentEntity;
import com.chaltteok.disclosure.persistence.entity.ExtractedFactEntity;
import com.chaltteok.disclosure.persistence.repository.DisclosureDocumentRepository;
import com.chaltteok.disclosure.persistence.repository.ExtractedFactRepository;
import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
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

    private OwnershipAnswerHandler handler;

    @BeforeEach
    void setUp() {
        handler = new OwnershipAnswerHandler(documentRepository, factRepository);
        when(documentRepository.findDistinctCorpNamesByDocGroup(DocGroup.HOLDING))
                .thenReturn(List.of("삼성전자"));
    }

    @Test
    void supportsHoldingQuestionsForLoadedCompany() {
        assertThat(handler.supports(QUESTION)).isTrue();
        assertThat(handler.supports("삼성전자 지분공시 보고자는?")).isTrue();
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

    @Test
    void requiresReportDate() {
        AnswerHandlerResult result = handler.handle("Q-OWN", "삼성전자 대량보유상황보고서의 지분율은?");
        assertThat(result.isAnswerable()).isFalse();
        assertThat(result.unavailableReason()).contains("공시일");
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

    private static DisclosureDocumentEntity document(String docId, boolean correction) {
        DisclosureDocumentEntity entity = new DisclosureDocumentEntity();
        entity.setDocId(docId);
        entity.setCorpName("삼성전자");
        entity.setReportNm("주식등의 대량보유상황보고서");
        entity.setRceptNo(docId.substring("holding_".length()));
        entity.setRceptDt(REPORT_DATE);
        entity.setDocGroup(DocGroup.HOLDING);
        entity.setDocSubtype("대량보유상황보고서");
        entity.setCorrection(correction);
        return entity;
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
