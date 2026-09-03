package com.chaltteok.fundraising;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
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
    private static final String QUESTION_WITHOUT_DATE =
            "한화에어로스페이스의 유상증자결정의 타법인 증권취득 목적 자금은 얼마인가?";
    private static final LocalDate REPORT_DATE = LocalDate.of(2025, 4, 8);
    private static final String QUESTION_APRIL_18 =
            "한화에어로스페이스가 2025년 4월 18일 공시한 유상증자결정의 타법인 증권취득 목적 자금은 얼마인가?";
    private static final LocalDate APRIL_18 = LocalDate.of(2025, 4, 18);

    @Mock
    private DisclosureDocumentRepository documentRepository;

    @Mock
    private ExtractedFactRepository factRepository;

    private FundraisingAnswerHandler handler;

    @BeforeEach
    void setUp() {
        handler = new FundraisingAnswerHandler(documentRepository, factRepository);
        when(documentRepository.findDistinctCorpNamesByDocGroupAndDocSubtype(
                DocGroup.MAJOR, "유상증자결정"))
                .thenReturn(List.of("한화에어로스페이스", "삼성SDI"));
    }

    @Test
    void supportsRepresentativeFundraisingQuestion() {
        assertThat(handler.supports(QUESTION)).isTrue();
        assertThat(handler.supports(LEGACY_TOTAL_PROCEEDS_QUESTION)).isTrue();
        assertThat(handler.supports(QUESTION_WITHOUT_DATE)).isTrue();
    }

    @Test
    void doesNotSupportUnrelatedOrIncompleteQuestions() {
        assertThat(handler.supports("예시전자의 연결 매출액은?")).isFalse();
        assertThat(handler.supports("카카오의 유상증자결정 조달금액은?")).isFalse();
        assertThat(handler.supports("한화에어로스페이스 자기주식 취득 규모는?")).isFalse();
        assertThat(handler.supports(null)).isFalse();
    }

    @Test
    void supportsLoadedCompanyOtherThanHanwha() {
        assertThat(handler.supports("삼성SDI의 유상증자결정 조달금액은?")).isTrue();
    }

    @Test
    void assemblesEvidenceFromSameDateDocumentAndAncAcqAmtFact() {
        DisclosureDocumentEntity document = hanwhaFundraisingDocument(
                "major_20250408000012",
                "20250408000012",
                REPORT_DATE,
                true
        );
        when(documentRepository.findByCorpNameAndDocGroupAndDocSubtypeAndRceptDt(
                "한화에어로스페이스",
                DocGroup.MAJOR,
                "유상증자결정",
                REPORT_DATE
        )).thenReturn(List.of(document));
        when(factRepository.findByDocumentDocId("major_20250408000012"))
                .thenReturn(List.of(parValueFact(document), amountFact(document, "1,600,000,000,000", "1600000000000")));

        AnswerHandlerResult result = handler.handle("Q-FUND", QUESTION);

        assertThat(result.isAnswerable()).isTrue();
        assertThat(result.evidences()).hasSize(1);
        RetrievedEvidence evidence = result.evidences().getFirst();
        assertThat(evidence.reportName()).isEqualTo("주요사항보고서(유상증자결정)");
        assertThat(evidence.reportDate()).isEqualTo(REPORT_DATE);
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
                .contains("공시일=2025-04-08")
                .contains("major_20250408000012")
                .contains("fundraising_purpose_securities_acquisition(ANC_ACQ_AMT)")
                .contains("1,600,000,000,000원")
                .doesNotContain("2,218,764,800,000");
    }

    @Test
    void doesNotPickLatestDocumentWhenQuestionSpecifiesEarlierDate() {
        DisclosureDocumentEntity aprilEighth = hanwhaFundraisingDocument(
                "major_20250408000012",
                "20250408000012",
                REPORT_DATE,
                true
        );
        when(documentRepository.findByCorpNameAndDocGroupAndDocSubtypeAndRceptDt(
                eq("한화에어로스페이스"),
                eq(DocGroup.MAJOR),
                eq("유상증자결정"),
                eq(REPORT_DATE)
        )).thenReturn(List.of(aprilEighth));
        when(factRepository.findByDocumentDocId("major_20250408000012"))
                .thenReturn(List.of(amountFact(aprilEighth, "1,600,000,000,000", "1600000000000")));

        AnswerHandlerResult result = handler.handle("Q-FUND", QUESTION);

        assertThat(result.isAnswerable()).isTrue();
        assertThat(result.evidences().getFirst().evidenceText()).contains("1,600,000,000,000");
        verify(documentRepository).findByCorpNameAndDocGroupAndDocSubtypeAndRceptDt(
                "한화에어로스페이스",
                DocGroup.MAJOR,
                "유상증자결정",
                REPORT_DATE
        );
        // 공시일로 좁히지 않은 조회는 쓰지 않는다. 같은 서식이 여러 날 제출되기 때문이다.
        verify(documentRepository, never()).findByCorpNameAndDocGroupAndDocSubtype(
                any(),
                any(),
                any()
        );
    }

    @Test
    void returnsUnavailableWhenQuestionOmitsReportDate() {
        AnswerHandlerResult result = handler.handle("Q-FUND", QUESTION_WITHOUT_DATE);

        assertThat(result.isAnswerable()).isFalse();
        assertThat(result.unavailableReason()).contains("공시일");
        verify(documentRepository, never()).findByCorpNameAndDocGroupAndDocSubtypeAndRceptDt(
                any(),
                any(),
                any(),
                any()
        );
    }

    @Test
    void returnsUnavailableWhenSearchFindsNoDocument() {
        when(documentRepository.findByCorpNameAndDocGroupAndDocSubtypeAndRceptDt(
                "한화에어로스페이스",
                DocGroup.MAJOR,
                "유상증자결정",
                REPORT_DATE
        )).thenReturn(List.of());

        AnswerHandlerResult result = handler.handle("Q-FUND", QUESTION);

        assertThat(result.isAnswerable()).isFalse();
        assertThat(result.evidences()).isEmpty();
        assertThat(result.unavailableReason()).contains("2025-04-08");
        verify(factRepository, never()).findByDocumentDocId(anyString());
    }

    @Test
    void returnsUnavailableWhenDocumentHasNoAncAcqAmtFact() {
        DisclosureDocumentEntity document = hanwhaFundraisingDocument(
                "major_20250408000012",
                "20250408000012",
                REPORT_DATE,
                true
        );
        when(documentRepository.findByCorpNameAndDocGroupAndDocSubtypeAndRceptDt(
                any(),
                any(),
                any(),
                any()
        )).thenReturn(List.of(document));
        when(factRepository.findByDocumentDocId("major_20250408000012"))
                .thenReturn(List.of(parValueFact(document)));

        AnswerHandlerResult result = handler.handle("Q-FUND", QUESTION);

        assertThat(result.isAnswerable()).isFalse();
        assertThat(result.evidences()).isEmpty();
        assertThat(result.unavailableReason()).contains("타법인");
    }

    @Test
    void picksOneDocumentWhenSameDateHasSameAmount() {
        DisclosureDocumentEntity first = hanwhaFundraisingDocument(
                "major_20250408000012",
                "20250408000012",
                REPORT_DATE,
                true
        );
        DisclosureDocumentEntity laterSameDay = hanwhaFundraisingDocument(
                "major_20250408002848",
                "20250408002848",
                REPORT_DATE,
                true
        );
        when(documentRepository.findByCorpNameAndDocGroupAndDocSubtypeAndRceptDt(
                any(),
                any(),
                any(),
                any()
        )).thenReturn(List.of(laterSameDay, first));
        when(factRepository.findByDocumentDocId("major_20250408000012"))
                .thenReturn(List.of(amountFact(first, "1,600,000,000,000", "1600000000000")));
        when(factRepository.findByDocumentDocId("major_20250408002848"))
                .thenReturn(List.of(amountFact(laterSameDay, "1,600,000,000,000", "1600000000000")));

        AnswerHandlerResult result = handler.handle("Q-FUND", QUESTION);

        assertThat(result.isAnswerable()).isTrue();
        assertThat(result.evidences()).hasSize(1);
        assertThat(result.processSummary())
                .contains("major_20250408000012")
                .doesNotContain("major_20250408002848")
                .doesNotContain("1,600,000,000,000원, 1,600,000,000,000원");
    }

    @Test
    void returnsUnavailableWhenSameDateHasConflictingAmounts() {
        DisclosureDocumentEntity first = hanwhaFundraisingDocument(
                "major_20250408000012",
                "20250408000012",
                REPORT_DATE,
                true
        );
        DisclosureDocumentEntity conflicting = hanwhaFundraisingDocument(
                "major_20250408002848",
                "20250408002848",
                REPORT_DATE,
                true
        );
        when(documentRepository.findByCorpNameAndDocGroupAndDocSubtypeAndRceptDt(
                any(),
                any(),
                any(),
                any()
        )).thenReturn(List.of(first, conflicting));
        when(factRepository.findByDocumentDocId("major_20250408000012"))
                .thenReturn(List.of(amountFact(first, "1,600,000,000,000", "1600000000000")));
        when(factRepository.findByDocumentDocId("major_20250408002848"))
                .thenReturn(List.of(amountFact(conflicting, "2,400,000,000,000", "2400000000000")));

        AnswerHandlerResult result = handler.handle("Q-FUND", QUESTION);

        assertThat(result.isAnswerable()).isFalse();
        assertThat(result.unavailableReason()).contains("서로 다른");
        assertThat(result.evidences()).isEmpty();
    }

    @Test
    void prefersCorrectionsOverOriginalWhenSameDayAmountsDiffer() {
        DisclosureDocumentEntity original = hanwhaFundraisingDocument(
                "major_20250418000538",
                "20250418000538",
                APRIL_18,
                false
        );
        DisclosureDocumentEntity firstCorrection = hanwhaFundraisingDocument(
                "major_20250418000593",
                "20250418000593",
                APRIL_18,
                true
        );
        DisclosureDocumentEntity secondCorrection = hanwhaFundraisingDocument(
                "major_20250418000639",
                "20250418000639",
                APRIL_18,
                true
        );
        when(documentRepository.findByCorpNameAndDocGroupAndDocSubtypeAndRceptDt(
                "한화에어로스페이스",
                DocGroup.MAJOR,
                "유상증자결정",
                APRIL_18
        )).thenReturn(List.of(original, firstCorrection, secondCorrection));
        when(factRepository.findByDocumentDocId("major_20250418000538"))
                .thenReturn(List.of(amountFact(original, "800,000,000,000", "800000000000")));
        when(factRepository.findByDocumentDocId("major_20250418000593"))
                .thenReturn(List.of(amountFact(firstCorrection, "1,600,000,000,000", "1600000000000")));
        when(factRepository.findByDocumentDocId("major_20250418000639"))
                .thenReturn(List.of(amountFact(secondCorrection, "1,600,000,000,000", "1600000000000")));

        AnswerHandlerResult result = handler.handle("Q-FUND", QUESTION_APRIL_18);

        assertThat(result.isAnswerable()).isTrue();
        assertThat(result.evidences().getFirst().evidenceText()).contains("1,600,000,000,000");
        assertThat(result.processSummary())
                .contains("major_20250418000593")
                .doesNotContain("major_20250418000538")
                .doesNotContain("800,000,000,000");
    }

    @Test
    void prefersCorrectionAsEvidenceWhenSameDayAmountsMatch() {
        DisclosureDocumentEntity original = hanwhaFundraisingDocument(
                "major_20250408000010",
                "20250408000010",
                REPORT_DATE,
                false
        );
        DisclosureDocumentEntity correction = hanwhaFundraisingDocument(
                "major_20250408000012",
                "20250408000012",
                REPORT_DATE,
                true
        );
        when(documentRepository.findByCorpNameAndDocGroupAndDocSubtypeAndRceptDt(
                any(),
                any(),
                any(),
                any()
        )).thenReturn(List.of(original, correction));
        when(factRepository.findByDocumentDocId("major_20250408000010"))
                .thenReturn(List.of(amountFact(original, "1,600,000,000,000", "1600000000000")));
        when(factRepository.findByDocumentDocId("major_20250408000012"))
                .thenReturn(List.of(amountFact(correction, "1,600,000,000,000", "1600000000000")));

        AnswerHandlerResult result = handler.handle("Q-FUND", QUESTION);

        assertThat(result.isAnswerable()).isTrue();
        assertThat(result.processSummary())
                .contains("major_20250408000012")
                .doesNotContain("major_20250408000010");
    }

    @Test
    void answersFacilityPurposeFromSameDocument() {
        DisclosureDocumentEntity document = hanwhaFundraisingDocument(
                "major_20250408000012",
                "20250408000012",
                REPORT_DATE,
                true
        );
        when(documentRepository.findByCorpNameAndDocGroupAndDocSubtypeAndRceptDt(
                any(), any(), any(), any()
        )).thenReturn(List.of(document));
        when(factRepository.findByDocumentDocId("major_20250408000012"))
                .thenReturn(List.of(
                        purposeFact(document, FundraisingAnswerHandler.FACILITY_FACT_KEY,
                                "시설자금 (원)", "700,020,800,000", "700020800000"),
                        amountFact(document, "1,600,000,000,000", "1600000000000")
                ));

        AnswerHandlerResult result = handler.handle(
                "Q-FUND",
                "한화에어로스페이스가 2025년 4월 8일 공시한 유상증자결정의 시설자금은 얼마인가?"
        );

        assertThat(result.isAnswerable()).isTrue();
        assertThat(result.evidences()).hasSize(1);
        assertThat(result.evidences().getFirst().evidenceText()).contains("700,020,800,000");
        assertThat(result.processSummary())
                .contains("fundraising_purpose_facility")
                .contains("700,020,800,000원")
                .doesNotContain("1,600,000,000,000");
    }

    @Test
    void sumsPurposeAmountsForTotalProceedsQuestion() {
        DisclosureDocumentEntity document = hanwhaFundraisingDocument(
                "major_20250408000012",
                "20250408000012",
                REPORT_DATE,
                true
        );
        when(documentRepository.findByCorpNameAndDocGroupAndDocSubtypeAndRceptDt(
                any(), any(), any(), any()
        )).thenReturn(List.of(document));
        when(factRepository.findByDocumentDocId("major_20250408000012"))
                .thenReturn(List.of(
                        purposeFact(document, FundraisingAnswerHandler.FACILITY_FACT_KEY,
                                "시설자금 (원)", "700,020,800,000", "700020800000"),
                        amountFact(document, "1,600,000,000,000", "1600000000000")
                ));

        AnswerHandlerResult result = handler.handle("Q-FUND", LEGACY_TOTAL_PROCEEDS_QUESTION);

        assertThat(result.isAnswerable()).isTrue();
        assertThat(result.evidences()).hasSize(2);
        assertThat(result.processSummary())
                .contains("자금 사용 목적 합계")
                .contains("700,020,800,000원")
                .contains("1,600,000,000,000원")
                .contains("2300020800000원")
                .contains("2조 3,000억원");
        assertThat(result.evidences())
                .extracting(RetrievedEvidence::evidenceText)
                .anyMatch(text -> text.contains("시설자금"))
                .anyMatch(text -> text.contains("타법인 증권취득자금"));
    }

    @Test
    void answersSecuritiesAcquisitionForOtherLoadedCompany() {
        DisclosureDocumentEntity document = fundraisingDocument(
                "major_20240101000001",
                "20240101000001",
                LocalDate.of(2024, 1, 1),
                true,
                "삼성SDI"
        );
        when(documentRepository.findByCorpNameAndDocGroupAndDocSubtypeAndRceptDt(
                "삼성SDI",
                DocGroup.MAJOR,
                "유상증자결정",
                LocalDate.of(2024, 1, 1)
        )).thenReturn(List.of(document));
        when(factRepository.findByDocumentDocId("major_20240101000001"))
                .thenReturn(List.of(amountFact(document, "100,000,000,000", "100000000000")));

        AnswerHandlerResult result = handler.handle(
                "Q-FUND",
                "삼성SDI가 2024년 1월 1일 공시한 유상증자결정의 타법인 증권취득 목적 자금은 얼마인가?"
        );

        assertThat(result.isAnswerable()).isTrue();
        assertThat(result.evidences().getFirst().companyName()).isEqualTo("삼성SDI");
        assertThat(result.evidences().getFirst().evidenceText()).contains("100,000,000,000");
    }

    private static DisclosureDocumentEntity hanwhaFundraisingDocument(
            String docId,
            String rceptNo,
            LocalDate rceptDt,
            boolean correction
    ) {
        return fundraisingDocument(docId, rceptNo, rceptDt, correction, "한화에어로스페이스");
    }

    private static DisclosureDocumentEntity fundraisingDocument(
            String docId,
            String rceptNo,
            LocalDate rceptDt,
            boolean correction,
            String corpName
    ) {
        DisclosureDocumentEntity document = new DisclosureDocumentEntity();
        document.setDocId(docId);
        document.setCorpName(corpName);
        document.setReportNm("주요사항보고서(유상증자결정)");
        document.setRceptNo(rceptNo);
        document.setRceptDt(rceptDt);
        document.setDocGroup(DocGroup.MAJOR);
        document.setCorrection(correction);
        return document;
    }

    private static ExtractedFactEntity amountFact(
            DisclosureDocumentEntity document,
            String rawValue,
            String normalizedValue
    ) {
        return purposeFact(
                document,
                FundraisingAnswerHandler.SECURITIES_ACQUISITION_FACT_KEY,
                "4. 자금조달의 목적 > 타법인 증권취득자금 (원)",
                rawValue,
                normalizedValue
        );
    }

    private static ExtractedFactEntity purposeFact(
            DisclosureDocumentEntity document,
            String factKey,
            String label,
            String rawValue,
            String normalizedValue
    ) {
        ExtractedFactEntity fact = new ExtractedFactEntity();
        fact.setFactId(document.getDocId() + ":fact:" + factKey);
        fact.setDocument(document);
        fact.setFactKey(factKey);
        fact.setLabel(label);
        fact.setRawValue(rawValue);
        fact.setRawUnit("원");
        fact.setNormalizedValue(normalizedValue);
        fact.setNormalizedUnit("KRW");
        return fact;
    }

    private static ExtractedFactEntity parValueFact(DisclosureDocumentEntity document) {
        ExtractedFactEntity fact = new ExtractedFactEntity();
        fact.setFactId(document.getDocId() + ":fact:0001");
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
