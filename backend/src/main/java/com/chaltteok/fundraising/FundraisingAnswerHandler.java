package com.chaltteok.fundraising;

import com.chaltteok.answer.AnswerHandler;
import com.chaltteok.answer.AnswerHandlerResult;
import com.chaltteok.answer.KoreanAmountFormatter;
import com.chaltteok.answer.RetrievedEvidence;
import com.chaltteok.disclosure.model.enums.DocGroup;
import com.chaltteok.disclosure.persistence.entity.DisclosureDocumentEntity;
import com.chaltteok.disclosure.persistence.entity.ExtractedFactEntity;
import com.chaltteok.disclosure.persistence.repository.DisclosureDocumentRepository;
import com.chaltteok.disclosure.persistence.repository.ExtractedFactRepository;
import java.math.BigDecimal;
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
import java.util.stream.Collectors;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

/**
 * 유상증자결정 질문의 검색·근거 조립.
 *
 * <p>대표 질문: 한화에어로스페이스가 2025년 4월 8일 공시한 유상증자결정의
 * 타법인 증권취득 목적 자금은 얼마인가?
 *
 * <p>기업명은 적재된 유상증자결정 문서에서 가져온다. 목적 6종은 질문이 가리키는
 * fact만 쓰고, 총액처럼 보이는 질문(예정 자금 조달금액 등)은 값이 있는 목적별 금액을
 * 같은 문서에서 합산해 {@code 자금 사용 목적 합계}로 남긴다. 발행제비용 등으로
 * 신주수×발행가액과 다를 수 있어 총 조달금액이라고 단정하지 않는다.
 *
 * <p>질문의 공시일({@code yyyy년 M월 d일})로 {@code rcept_dt}를 좁힌다.
 * 같은 날짜에 정정본이 있으면 원본은 대체된 것으로 보고 정정본만 남긴다.
 * 남은 문서끼리 금액이 서로 다르면 답변 불가로 둔다.
 */
@Component
@Order(20)
@ConditionalOnProperty(name = "chaltteok.disclosure.persistence.enabled", havingValue = "true")
public class FundraisingAnswerHandler implements AnswerHandler {

    static final String FACILITY_FACT_KEY = "fundraising_purpose_facility";
    static final String BUSINESS_ACQUISITION_FACT_KEY = "fundraising_purpose_business_acquisition";
    static final String OPERATING_FACT_KEY = "fundraising_purpose_operating";
    static final String DEBT_REPAYMENT_FACT_KEY = "fundraising_purpose_debt_repayment";
    static final String SECURITIES_ACQUISITION_FACT_KEY =
            "fundraising_purpose_securities_acquisition";
    static final String OTHER_FACT_KEY = "fundraising_purpose_other";
    static final Set<String> PURPOSE_FACT_KEYS = Set.of(
            FACILITY_FACT_KEY,
            BUSINESS_ACQUISITION_FACT_KEY,
            OPERATING_FACT_KEY,
            DEBT_REPAYMENT_FACT_KEY,
            SECURITIES_ACQUISITION_FACT_KEY,
            OTHER_FACT_KEY
    );

    /** 검색 키. 전처리가 공시명에서 뽑아 둔 서식명이다(docs/data-model.md §3). */
    static final String DOC_SUBTYPE = "유상증자결정";
    /** 질문이 유상증자를 묻는지 판단하는 키워드. 문서 검색에는 쓰지 않는다. */
    static final String REPORT_KEYWORD = "유상증자";
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
        return parse(question).corpName() != null;
    }

    @Override
    public AnswerHandlerResult handle(String questionId, String question) {
        FundraisingQuestion parsed = parse(question);
        if (parsed.corpName() == null) {
            return AnswerHandlerResult.unavailable(
                    "유상증자 질문으로 분류",
                    "질문에서 검색할 기업명을 확정하지 못했습니다"
            );
        }

        LocalDate reportDate = resolveReportDate(question);
        if (reportDate == null) {
            return AnswerHandlerResult.unavailable(
                    "기업명=" + parsed.corpName() + ", 공시유형=major, 서식=" + DOC_SUBTYPE + " 검색",
                    "질문에서 공시일(yyyy년 M월 d일)을 읽지 못해 유상증자 공시를 특정하지 못했습니다"
            );
        }

        String searchSummary = "기업명=" + parsed.corpName()
                + ", 공시유형=major, 서식=" + DOC_SUBTYPE
                + ", 공시일=" + reportDate + " 검색";
        List<DisclosureDocumentEntity> documents =
                documentRepository.findByCorpNameAndDocGroupAndDocSubtypeAndRceptDt(
                        parsed.corpName(),
                        DocGroup.MAJOR,
                        DOC_SUBTYPE,
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
            List<ExtractedFactEntity> purposes = factRepository.findByDocumentDocId(document.getDocId()).stream()
                    .filter(fact -> PURPOSE_FACT_KEYS.contains(fact.getFactKey()))
                    .filter(fact -> fact.getNormalizedValue() != null && !fact.getNormalizedValue().isBlank())
                    .toList();
            if (parsed.isTotal()) {
                if (!purposes.isEmpty()) {
                    candidates.add(new Candidate(document, purposes));
                }
            } else {
                List<ExtractedFactEntity> matched = purposes.stream()
                        .filter(fact -> parsed.factKey().equals(fact.getFactKey()))
                        .toList();
                if (!matched.isEmpty()) {
                    candidates.add(new Candidate(document, matched));
                }
            }
        }

        if (candidates.isEmpty()) {
            return AnswerHandlerResult.unavailable(
                    searchSummary + " → 문서 " + documents.size() + "건",
                    parsed.isTotal()
                            ? "검색된 공시에서 합산할 목적별 자금 금액을 찾지 못했습니다"
                            : "검색된 공시에서 " + parsed.itemLabel() + " 금액을 찾지 못했습니다"
            );
        }

        List<Candidate> preferred = preferCorrections(candidates);
        Set<String> distinctAmounts = new LinkedHashSet<>();
        for (Candidate candidate : preferred) {
            distinctAmounts.add(candidate.amountKey());
        }
        if (distinctAmounts.size() > 1) {
            return AnswerHandlerResult.unavailable(
                    searchSummary + " → 문서 " + preferred.size() + "건",
                    "같은 공시일에 " + parsed.itemLabel() + " 금액이 서로 다른 문서가 있어 한 건으로 특정하지 못했습니다"
            );
        }

        Candidate selected = preferred.stream()
                .min(documentPreference())
                .orElseThrow();
        List<RetrievedEvidence> evidences = selected.facts().stream()
                .map(fact -> toEvidence(selected.document(), fact))
                .toList();
        return AnswerHandlerResult.of(evidences, searchSummary + " → " + selected.summary(parsed));
    }

    private FundraisingQuestion parse(String question) {
        return FundraisingQuestionParser.parse(
                question,
                documentRepository.findDistinctCorpNamesByDocGroupAndDocSubtype(
                        DocGroup.MAJOR, DOC_SUBTYPE));
    }

    /**
     * 기재정정은 같은 날 원본을 대체한다. 정정본이 하나라도 있으면 원본은 후보에서 뺀다.
     */
    private static List<Candidate> preferCorrections(List<Candidate> candidates) {
        List<Candidate> corrections = candidates.stream()
                .filter(candidate -> candidate.document().isCorrection())
                .toList();
        return corrections.isEmpty() ? candidates : corrections;
    }

    private static Comparator<Candidate> documentPreference() {
        return Comparator.comparing(
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

    private record Candidate(DisclosureDocumentEntity document, List<ExtractedFactEntity> facts) {
        Candidate {
            Objects.requireNonNull(document);
            facts = List.copyOf(facts);
        }

        String amountKey() {
            return sum().toPlainString();
        }

        String summary(FundraisingQuestion parsed) {
            String docId = document.getDocId();
            if (!parsed.isTotal()) {
                ExtractedFactEntity fact = facts.getFirst();
                String key = parsed.factKey();
                if (SECURITIES_ACQUISITION_FACT_KEY.equals(key)) {
                    key = key + "(ANC_ACQ_AMT)";
                }
                return "문서 " + docId + " 선택 → " + key + "=" + rawAmount(fact) + " 추출";
            }
            String parts = facts.stream()
                    .map(fact -> fact.getFactKey() + "=" + rawAmount(fact))
                    .collect(Collectors.joining(" + "));
            String total = sum().toPlainString();
            return "문서 " + docId + " 선택 → 자금 사용 목적 합계 " + parts + " = " + total
                    + "원 (" + KoreanAmountFormatter.format(total) + ")";
        }

        private BigDecimal sum() {
            BigDecimal total = BigDecimal.ZERO;
            for (ExtractedFactEntity fact : facts) {
                total = total.add(new BigDecimal(fact.getNormalizedValue()));
            }
            return total;
        }

        private static String rawAmount(ExtractedFactEntity fact) {
            String unit = fact.getRawUnit() == null ? "" : fact.getRawUnit();
            return fact.getRawValue() + unit;
        }
    }
}
