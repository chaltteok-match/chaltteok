package com.chaltteok.fundraising;

import com.chaltteok.answer.AnswerHandler;
import com.chaltteok.answer.AnswerHandlerResult;
import com.chaltteok.answer.RetrievedEvidence;
import com.chaltteok.disclosure.model.enums.DocGroup;
import com.chaltteok.disclosure.persistence.entity.DisclosureDocumentEntity;
import com.chaltteok.disclosure.persistence.entity.ExtractedFactEntity;
import com.chaltteok.disclosure.persistence.repository.DisclosureDocumentRepository;
import com.chaltteok.disclosure.persistence.repository.ExtractedFactRepository;
import java.time.DateTimeException;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * 유상증자결정 질문의 검색·근거 조립.
 * 기업명·유형을 질문에서 NLP로 추출하지 않고, 단순 키워드와 Repository 검색을 사용한다.
 *
 * <p>대표 질문: 한화에어로스페이스가 2025년 4월 8일 공시한 유상증자결정의
 * 타법인 증권취득 목적 자금은 얼마인가?
 *
 * <p>같은 기업의 유상증자결정이 여러 건이면 금액이 다를 수 있다. 질문의 공시일
 * ({@code yyyy년 M월 d일})로 {@code rcept_dt}를 좁힌다. 최신 1건을 고르지 않는다.
 * 같은 날짜에 금액이 서로 다르면 이어붙이지 않고 답변 불가로 둔다.
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
    private static final Pattern KOREAN_REPORT_DATE = Pattern.compile(
            "(\\d{4})년\\s*(\\d{1,2})월\\s*(\\d{1,2})일"
    );

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

        LocalDate reportDate = resolveReportDate(question);
        if (reportDate == null) {
            return AnswerHandlerResult.unavailable(
                    "기업명=" + corpName + ", 공시유형=major, 공시명키워드=" + REPORT_KEYWORD + " 검색",
                    "질문에서 공시일(yyyy년 M월 d일)을 읽지 못해 유상증자 공시를 특정하지 못했습니다"
            );
        }

        String searchSummary = "기업명=" + corpName
                + ", 공시유형=major, 공시명키워드=" + REPORT_KEYWORD
                + ", 공시일=" + reportDate + " 검색";
        List<DisclosureDocumentEntity> documents =
                documentRepository.findByCorpNameAndDocGroupAndReportNmContainingAndRceptDt(
                        corpName,
                        DocGroup.MAJOR,
                        REPORT_KEYWORD,
                        reportDate
                );
        if (documents.isEmpty()) {
            return AnswerHandlerResult.unavailable(
                    searchSummary,
                    "해당 기업의 " + reportDate + " 유상증자 공시를 찾지 못했습니다"
            );
        }

        List<Candidate> candidates = new ArrayList<>();
        for (DisclosureDocumentEntity document : documents) {
            factRepository.findByDocumentDocId(document.getDocId()).stream()
                    .filter(fact -> SECURITIES_ACQUISITION_FACT_KEY.equals(fact.getFactKey()))
                    .forEach(fact -> candidates.add(new Candidate(document, fact)));
        }

        if (candidates.isEmpty()) {
            return AnswerHandlerResult.unavailable(
                    searchSummary + " → 문서 " + documents.size() + "건",
                    "검색된 공시에서 fundraising_purpose_securities_acquisition(ANC_ACQ_AMT, 타법인 증권취득자금)를 찾지 못했습니다"
            );
        }

        Set<String> distinctAmounts = new LinkedHashSet<>();
        for (Candidate candidate : candidates) {
            distinctAmounts.add(amountKey(candidate.fact()));
        }
        if (distinctAmounts.size() > 1) {
            return AnswerHandlerResult.unavailable(
                    searchSummary + " → 문서 " + candidates.size() + "건",
                    "같은 공시일에 타법인 증권취득자금이 서로 다른 문서가 있어 한 건으로 특정하지 못했습니다"
            );
        }

        Candidate selected = candidates.stream()
                .min(documentPreference())
                .orElseThrow();
        String processSummary = searchSummary
                + " → 문서 " + selected.document().getDocId() + " 선택"
                + " → fundraising_purpose_securities_acquisition(ANC_ACQ_AMT)="
                + rawAmount(selected.fact()) + " 추출";
        return AnswerHandlerResult.of(List.of(toEvidence(selected.document(), selected.fact())), processSummary);
    }

    private static Comparator<Candidate> documentPreference() {
        return Comparator
                .comparing((Candidate candidate) -> candidate.document().isCorrection())
                .thenComparing(
                        candidate -> candidate.document().getRceptNo(),
                        Comparator.nullsLast(String::compareTo)
                );
    }

    private static LocalDate resolveReportDate(String question) {
        if (question == null) {
            return null;
        }
        Matcher matcher = KOREAN_REPORT_DATE.matcher(question);
        if (!matcher.find()) {
            return null;
        }
        try {
            return LocalDate.of(
                    Integer.parseInt(matcher.group(1)),
                    Integer.parseInt(matcher.group(2)),
                    Integer.parseInt(matcher.group(3))
            );
        } catch (DateTimeException | NumberFormatException ignored) {
            return null;
        }
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

    private static String amountKey(ExtractedFactEntity fact) {
        if (fact.getNormalizedValue() != null && !fact.getNormalizedValue().isBlank()) {
            return fact.getNormalizedValue();
        }
        return rawAmount(fact);
    }

    private record Candidate(DisclosureDocumentEntity document, ExtractedFactEntity fact) {
        Candidate {
            Objects.requireNonNull(document);
            Objects.requireNonNull(fact);
        }
    }
}
