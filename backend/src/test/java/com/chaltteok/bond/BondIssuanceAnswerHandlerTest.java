package com.chaltteok.bond;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
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
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class BondIssuanceAnswerHandlerTest {

    private static final String QUESTION =
            "에코프로비엠이 2023년 6월 30일 공시한 전환사채의 권면총액은 얼마인가?";
    private static final LocalDate REPORT_DATE = LocalDate.of(2023, 6, 30);

    @Mock
    private DisclosureDocumentRepository documentRepository;

    @Mock
    private ExtractedFactRepository factRepository;

    @Mock
    private CorpIdentityRepository corpIdentityRepository;

    private BondIssuanceAnswerHandler handler;

    @BeforeEach
    void setUp() {
        handler = new BondIssuanceAnswerHandler(
                documentRepository, factRepository, corpIdentityRepository);
        when(corpIdentityRepository.findByDocGroupAndDocSubtype(
                DocGroup.MAJOR, "전환사채권발행결정"))
                .thenReturn(List.of(
                        new CorpIdentity("에코프로비엠", "에코프로비엠", "247540", "01160363")));
        when(corpIdentityRepository.findByDocGroupAndDocSubtype(
                DocGroup.MAJOR, "교환사채권발행결정"))
                .thenReturn(List.of(
                        new CorpIdentity("SK하이닉스", "SK하이닉스", "000660", "00164742")));
    }

    @Test
    void supportsConvertibleAndExchangeableOnly() {
        assertThat(handler.supports(QUESTION)).isTrue();
        assertThat(handler.supports("SK하이닉스 교환사채 권면총액은?")).isTrue();
        assertThat(handler.supports("247540이 공시한 전환사채 권면총액은?")).isTrue();
        assertThat(handler.supports("에코프로비엠 유상증자 신주 수는?")).isFalse();
        assertThat(handler.supports("삼성전자 자기주식취득결정 취득예정금액은?")).isFalse();
        assertThat(handler.supports("삼성전자 신규시설투자 투자금액은?")).isFalse();
        assertThat(handler.supports("에코프로비엠 신주인수권부사채 권면총액은?")).isFalse();
        assertThat(handler.supports("예시전자 전환사채 권면총액은?")).isFalse();
    }

    @Test
    void answersFaceAmount() {
        DisclosureDocumentEntity document = document();
        when(documentRepository.findByCorpNameAndDocGroupAndDocSubtypeAndRceptDt(
                eq("에코프로비엠"),
                eq(DocGroup.MAJOR),
                eq("전환사채권발행결정"),
                eq(REPORT_DATE)))
                .thenReturn(List.of(document));
        when(factRepository.findByDocumentDocId("major_20230630000403"))
                .thenReturn(List.of(
                        fact(document, BondQuestionParser.FACE_AMOUNT_FACT_KEY, "권면총액",
                                "440,000,000,000", "440000000000", "KRW", "원"),
                        fact(document, BondQuestionParser.EXERCISE_PRICE_FACT_KEY, "전환가액",
                                "275,000", "275000", "KRW", "원")
                ));

        AnswerHandlerResult result = handler.handle("Q-CB", QUESTION);

        assertThat(result.isAnswerable()).isTrue();
        assertThat(result.evidences()).hasSize(1);
        assertThat(result.evidences().getFirst().evidenceText()).contains("440,000,000,000");
        assertThat(result.processSummary()).contains("bond_face_amount");
        assertThat(result.processSummary()).doesNotContain("bond_exercise_price");
    }

    @Test
    void answersExchangeableWhenAsked() {
        DisclosureDocumentEntity document = document(
                "major_20230403003950",
                "SK하이닉스",
                "교환사채권발행결정",
                false);
        when(documentRepository.findByCorpNameAndDocGroupAndDocSubtypeAndRceptDt(
                eq("SK하이닉스"),
                eq(DocGroup.MAJOR),
                eq("교환사채권발행결정"),
                eq(LocalDate.of(2023, 4, 3))))
                .thenReturn(List.of(document));
        when(factRepository.findByDocumentDocId("major_20230403003950"))
                .thenReturn(List.of(
                        fact(document, BondQuestionParser.EXERCISE_PRICE_FACT_KEY, "교환가액",
                                "111,180", "111180", "KRW", "원")
                ));

        AnswerHandlerResult result = handler.handle(
                "Q-EB",
                "SK하이닉스가 2023년 4월 3일 공시한 교환사채의 교환가액은?");

        assertThat(result.isAnswerable()).isTrue();
        assertThat(result.evidences().getFirst().evidenceText()).contains("111,180");
    }

    @Test
    void requiresReportDate() {
        AnswerHandlerResult result = handler.handle("Q-CB", "에코프로비엠 전환사채의 권면총액은?");
        assertThat(result.isAnswerable()).isFalse();
        assertThat(result.unavailableReason()).contains("공시일");
    }

    @Test
    void prefersCorrectionOnSameDate() {
        DisclosureDocumentEntity original = document("major_20230630000403", "에코프로비엠",
                "전환사채권발행결정", false);
        DisclosureDocumentEntity correction = document("major_20230630000404", "에코프로비엠",
                "전환사채권발행결정", true);
        when(documentRepository.findByCorpNameAndDocGroupAndDocSubtypeAndRceptDt(
                any(), any(), any(), any()))
                .thenReturn(List.of(original, correction));
        when(factRepository.findByDocumentDocId("major_20230630000404"))
                .thenReturn(List.of(
                        fact(correction, BondQuestionParser.FACE_AMOUNT_FACT_KEY, "권면총액",
                                "450,000,000,000", "450000000000", "KRW", "원")));

        AnswerHandlerResult result = handler.handle("Q-CB", QUESTION);

        assertThat(result.isAnswerable()).isTrue();
        assertThat(result.evidences().getFirst().evidenceText()).contains("450,000,000,000");
        assertThat(result.processSummary()).contains("major_20230630000404");
    }

    private static DisclosureDocumentEntity document() {
        return document("major_20230630000403", "에코프로비엠", "전환사채권발행결정", false);
    }

    private static DisclosureDocumentEntity document(
            String docId,
            String corpName,
            String subtype,
            boolean correction
    ) {
        DisclosureDocumentEntity entity = new DisclosureDocumentEntity();
        entity.setDocId(docId);
        entity.setCorpName(corpName);
        entity.setReportNm("주요사항보고서(" + subtype + ")");
        entity.setRceptNo(docId.substring("major_".length()));
        entity.setRceptDt(docId.contains("20230403")
                ? LocalDate.of(2023, 4, 3)
                : REPORT_DATE);
        entity.setDocGroup(DocGroup.MAJOR);
        entity.setDocSubtype(subtype);
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
