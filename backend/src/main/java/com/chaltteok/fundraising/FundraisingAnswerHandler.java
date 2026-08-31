package com.chaltteok.fundraising;

import com.chaltteok.answer.AnswerHandler;
import com.chaltteok.answer.AnswerHandlerResult;
import com.chaltteok.answer.RetrievedEvidence;
import com.chaltteok.disclosure.model.enums.DocGroup;
import com.chaltteok.disclosure.persistence.entity.DisclosureDocumentEntity;
import com.chaltteok.disclosure.persistence.entity.ExtractedFactEntity;
import com.chaltteok.disclosure.persistence.repository.DisclosureDocumentRepository;
import com.chaltteok.disclosure.persistence.repository.ExtractedFactRepository;
import java.util.ArrayList;
import java.util.List;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * 유상증자결정 질문의 검색·근거 조립.
 * 기업명·유형을 질문에서 NLP로 추출하지 않고, 단순 키워드와 5번 Repository 검색을 사용한다.
 *
 * <p>대표 질문: 한화에어로스페이스가 2025년 4월 8일 공시한 유상증자결정의
 * 타법인 증권취득 목적 자금은 얼마인가?
 *
 * <p>{@code ANC_ACQ_AMT}/{@link #SECURITIES_ACQUISITION_FACT_KEY}는 총 조달금액이 아니라
 * 타법인 증권취득 목적 금액이다. {@code supports()}는 "유상증자"와 기업명만 보므로
 * "예정 자금 조달금액" 같은 총액 질문도 이 Handler로 들어올 수 있다. 그 경우에도
 * 1.6조원을 총액처럼 답하지 말고, 근거 문구에 타법인 증권취득 목적임을 유지한다.
 * 총액 합산은 별도 이슈 범위다. 근거: {@code docs/architecture/paid-in-capital-acodes.md}.
 */
@Component
@ConditionalOnProperty(name = "chaltteok.disclosure.persistence.enabled", havingValue = "true")
public class FundraisingAnswerHandler implements AnswerHandler {

    static final String SECURITIES_ACQUISITION_FACT_KEY =
            "fundraising_purpose_securities_acquisition";
    static final String REPORT_KEYWORD = "유상증자";
    static final List<String> KNOWN_CORP_NAMES = List.of("한화에어로스페이스");

    private final DisclosureDocumentRepository documentRepository;
    private final ExtractedFactRepository factRepository;

    public FundraisingAnswerHandler(
            DisclosureDocumentRepository documentRepository,
            ExtractedFactRepository factRepository
    ) {
        this.documentRepository = documentRepository;
        this.factRepository = factRepository;
    }

    @Override
    public boolean supports(String question) {
        if (question == null || !question.contains(REPORT_KEYWORD)) {
            return false;
        }
        return resolveCorpName(question) != null;
    }

    @Override
    public AnswerHandlerResult handle(String questionId, String question) {
        String corpName = resolveCorpName(question);
        if (corpName == null) {
            return AnswerHandlerResult.unavailable(
                    "유상증자 질문으로 분류",
                    "질문에서 검색할 기업명을 확정하지 못했습니다"
            );
        }

        String searchSummary = "기업명=" + corpName
                + ", 공시유형=major, 공시명키워드=" + REPORT_KEYWORD + " 검색";
        List<DisclosureDocumentEntity> documents =
                documentRepository.findByCorpNameAndDocGroupAndReportNmContaining(
                        corpName,
                        DocGroup.MAJOR,
                        REPORT_KEYWORD
                );
        if (documents.isEmpty()) {
            return AnswerHandlerResult.unavailable(
                    searchSummary,
                    "해당 기업의 유상증자 공시를 찾지 못했습니다"
            );
        }

        List<RetrievedEvidence> evidences = new ArrayList<>();
        List<String> selectedDocIds = new ArrayList<>();
        List<String> extractedValues = new ArrayList<>();
        for (DisclosureDocumentEntity document : documents) {
            List<ExtractedFactEntity> amountFacts = factRepository
                    .findByDocumentDocId(document.getDocId())
                    .stream()
                    .filter(fact -> SECURITIES_ACQUISITION_FACT_KEY.equals(fact.getFactKey()))
                    .toList();
            if (amountFacts.isEmpty()) {
                continue;
            }
            selectedDocIds.add(document.getDocId());
            for (ExtractedFactEntity fact : amountFacts) {
                evidences.add(toEvidence(document, fact));
                extractedValues.add(rawAmount(fact));
            }
        }

        if (evidences.isEmpty()) {
            return AnswerHandlerResult.unavailable(
                    searchSummary + " → 문서 " + documents.size() + "건",
                    "검색된 공시에서 fundraising_purpose_securities_acquisition(ANC_ACQ_AMT, 타법인 증권취득자금)를 찾지 못했습니다"
            );
        }

        String processSummary = searchSummary
                + " → 문서 " + String.join(", ", selectedDocIds) + " 선택"
                + " → fundraising_purpose_securities_acquisition(ANC_ACQ_AMT)="
                + String.join(", ", extractedValues) + " 추출";
        return AnswerHandlerResult.of(evidences, processSummary);
    }

    private static String resolveCorpName(String question) {
        if (question == null) {
            return null;
        }
        return KNOWN_CORP_NAMES.stream()
                .filter(question::contains)
                .findFirst()
                .orElse(null);
    }

    private static RetrievedEvidence toEvidence(
            DisclosureDocumentEntity document,
            ExtractedFactEntity fact
    ) {
        return new RetrievedEvidence(
                document.getReportNm(),
                document.getRceptDt(),
                document.getCorpName(),
                evidenceText(fact)
        );
    }

    private static String evidenceText(ExtractedFactEntity fact) {
        String unit = fact.getRawUnit() == null ? "" : fact.getRawUnit();
        StringBuilder text = new StringBuilder()
                .append(fact.getLabel())
                .append(": ")
                .append(fact.getRawValue())
                .append(unit);
        if (fact.getNormalizedValue() != null) {
            text.append(" (정규화 ").append(fact.getNormalizedValue());
            if (fact.getNormalizedUnit() != null) {
                text.append(' ').append(fact.getNormalizedUnit());
            }
            text.append(')');
        }
        return text.toString();
    }

    private static String rawAmount(ExtractedFactEntity fact) {
        String unit = fact.getRawUnit() == null ? "" : fact.getRawUnit();
        return fact.getRawValue() + unit;
    }
}
