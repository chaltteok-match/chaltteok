package com.chaltteok.fundraising;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.chaltteok.answer.AnswerHandlerResult;
import com.chaltteok.answer.RetrievedContextFormatter;
import com.chaltteok.answer.RetrievedEvidence;
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
class FundraisingAnswerHandlerTest {

    private static final String QUESTION =
            "한화에어로스페이스가 2025년 4월 8일 공시한 유상증자결정의 타법인 증권취득 목적 자금은 얼마인가?";
    private static final String LEGACY_TOTAL_PROCEEDS_QUESTION =
            "한화에어로스페이스가 2025년 4월 8일 공시한 유상증자결정의 예정 자금 조달금액은 얼마인가?";

    @Mock
    private DisclosureDocumentRepository documentRepository;

    @Mock
    private ExtractedFactRepository factRepository;

    private FundraisingAnswerHandler handler;

    @BeforeEach
    void setUp() {
        handler = new FundraisingAnswerHandler(documentRepository, factRepository);
    }

    @Test
    void supportsRepresentativeFundraisingQuestion() {
        assertThat(handler.supports(QUESTION)).isTrue();
        assertThat(handler.supports(LEGACY_TOTAL_PROCEEDS_QUESTION)).isTrue();
    }

    @Test
    void doesNotSupportUnrelatedOrIncompleteQuestions() {
        assertThat(handler.supports("예시전자의 연결 매출액은?")).isFalse();
        assertThat(handler.supports("삼성SDI의 유상증자결정 조달금액은?")).isFalse();
        assertThat(handler.supports("한화에어로스페이스 자기주식 취득 규모는?")).isFalse();
        assertThat(handler.supports(null)).isFalse();
    }

    @Test
    void assemblesEvidenceFromSearchResultAndAncAcqAmtFact() {
        DisclosureDocumentEntity document = hanwhaFundraisingDocument();
        when(documentRepository.findByCorpNameAndDocGroupAndReportNmContaining(
                "한화에어로스페이스",
                DocGroup.MAJOR,
                "유상증자"
        )).thenReturn(List.of(document));
        when(factRepository.findByDocumentDocId("major_20250408000012"))
                .thenReturn(List.of(parValueFact(document), expectedAmountFact(document)));

        AnswerHandlerResult result = handler.handle("Q-FUND", QUESTION);

        assertThat(result.isAnswerable()).isTrue();
        assertThat(result.evidences()).hasSize(1);
        RetrievedEvidence evidence = result.evidences().getFirst();
        assertThat(evidence.reportName()).isEqualTo("주요사항보고서(유상증자결정)");
        assertThat(evidence.reportDate()).isEqualTo(LocalDate.of(2025, 4, 8));
        assertThat(evidence.companyName()).isEqualTo("한화에어로스페이스");
        assertThat(evidence.evidenceText())
                .contains("타법인 증권취득자금")
                .contains("1,600,000,000,000")
                .contains("1600000000000")
                .contains("KRW");

        String retrievedContext = RetrievedContextFormatter.format(result.evidences());
        assertThat(retrievedContext)
                .contains("공시명: 주요사항보고서(유상증자결정)")
                .contains("공시일: 2025-04-08")
                .contains("기업명: 한화에어로스페이스")
                .contains("1,600,000,000,000");

        assertThat(result.processSummary())
                .contains("기업명=한화에어로스페이스")
                .contains("공시유형=major")
                .contains("유상증자")
                .contains("major_20250408000012")
                .contains("fundraising_purpose_securities_acquisition(ANC_ACQ_AMT)")
                .contains("1,600,000,000,000원");
    }

    @Test
    void returnsUnavailableWhenSearchFindsNoDocument() {
        when(documentRepository.findByCorpNameAndDocGroupAndReportNmContaining(
                "한화에어로스페이스",
                DocGroup.MAJOR,
                "유상증자"
        )).thenReturn(List.of());

        AnswerHandlerResult result = handler.handle("Q-FUND", QUESTION);

        assertThat(result.isAnswerable()).isFalse();
        assertThat(result.evidences()).isEmpty();
        assertThat(result.unavailableReason()).contains("유상증자 공시를 찾지 못했습니다");
        verify(factRepository, never()).findByDocumentDocId(anyString());
    }

    @Test
    void returnsUnavailableWhenDocumentHasNoAncAcqAmtFact() {
        DisclosureDocumentEntity document = hanwhaFundraisingDocument();
        when(documentRepository.findByCorpNameAndDocGroupAndReportNmContaining(
                any(),
                any(),
                any()
        )).thenReturn(List.of(document));
        when(factRepository.findByDocumentDocId("major_20250408000012"))
                .thenReturn(List.of(parValueFact(document)));

        AnswerHandlerResult result = handler.handle("Q-FUND", QUESTION);

        assertThat(result.isAnswerable()).isFalse();
        assertThat(result.evidences()).isEmpty();
        assertThat(result.unavailableReason()).contains("ANC_ACQ_AMT");
    }

    private static DisclosureDocumentEntity hanwhaFundraisingDocument() {
        DisclosureDocumentEntity document = new DisclosureDocumentEntity();
        document.setDocId("major_20250408000012");
        document.setCorpName("한화에어로스페이스");
        document.setReportNm("주요사항보고서(유상증자결정)");
        document.setRceptDt(LocalDate.of(2025, 4, 8));
        document.setDocGroup(DocGroup.MAJOR);
        return document;
    }

    private static ExtractedFactEntity expectedAmountFact(DisclosureDocumentEntity document) {
        ExtractedFactEntity fact = new ExtractedFactEntity();
        fact.setFactId("major_20250408000012:fact:0003");
        fact.setDocument(document);
        fact.setFactKey(FundraisingAnswerHandler.SECURITIES_ACQUISITION_FACT_KEY);
        fact.setLabel("4. 자금조달의 목적 > 타법인 증권취득자금 (원)");
        fact.setRawValue("1,600,000,000,000");
        fact.setRawUnit("원");
        fact.setNormalizedValue("1600000000000");
        fact.setNormalizedUnit("KRW");
        return fact;
    }

    private static ExtractedFactEntity parValueFact(DisclosureDocumentEntity document) {
        ExtractedFactEntity fact = new ExtractedFactEntity();
        fact.setFactId("major_20250408000012:fact:0001");
        fact.setDocument(document);
        fact.setFactKey("fundraising_par_value");
        fact.setLabel("2. 1주당 액면가액 (원)");
        fact.setRawValue("5,000");
        fact.setRawUnit("원");
        fact.setNormalizedValue("5000");
        fact.setNormalizedUnit("KRW");
        return fact;
    }
}
