package com.chaltteok.fundraising;

import com.chaltteok.answer.AnswerHandler;
import com.chaltteok.answer.CorpNameResolver;
import com.chaltteok.answer.DisclosureTypeMarkers;
import com.chaltteok.disclosure.persistence.repository.CorpIdentityRepository;
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
 *
 * <p>{@code 제외한} 합계는 그 목적만 빼고 더한다. 포함과 제외가 한 문장에 있어도
 * 제외에 붙은 목적만 뺀다. 정정 이력은 바뀐 과정을 물을 때만
 * 타고, 같은 증자 건의 원본·정정본을 날짜 순으로 모아 금액이 바뀐 지점만 남긴다.
 * {@code 정정한 유상증자}처럼 공시 한 건을 가리키면 이력으로 풀지 않는다.
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
    static final String NEW_SHARES_FACT_KEY = "fundraising_common_new_shares";
    static final String PAR_VALUE_FACT_KEY = "fundraising_par_value";
    static final String ISSUE_PRICE_FACT_KEY = "fundraising_common_issue_price";
    static final String PLANNED_ISSUE_PRICE_FACT_KEY = "fundraising_common_planned_issue_price";
    static final Set<String> ISSUANCE_FACT_KEYS = Set.of(
            NEW_SHARES_FACT_KEY,
            PAR_VALUE_FACT_KEY,
            ISSUE_PRICE_FACT_KEY,
            PLANNED_ISSUE_PRICE_FACT_KEY
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
    private final CorpIdentityRepository corpIdentityRepository;

    public FundraisingAnswerHandler(
            DisclosureDocumentRepository documentRepository,
            ExtractedFactRepository factRepository,
            CorpIdentityRepository corpIdentityRepository
    ) {
        this.documentRepository = documentRepository;
        this.factRepository = factRepository;
        this.corpIdentityRepository = corpIdentityRepository;
    }

    @Override
    public boolean supports(String question) {
        if (question == null || !question.contains(REPORT_KEYWORD)) {
            return false;
        }
        // `유상증자 참여 이후 대량보유 지분율`은 지분공시 질문이다. 순위가 앞선다고 가져가지 않는다.
        if (DisclosureTypeMarkers.mentions(question, DisclosureTypeMarkers.OWNERSHIP)) {
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
        // 공시일이 있어도 '변경된 내역'·'어떻게 바뀌었나'면 이력이다. '정정한 유상증자'만으로는 단건이다.
        if (parsed.history()) {
            return handleHistory(parsed, reportDate);
        }

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

        List<Candidate> candidates = candidatesOf(documents, parsed);
        if (candidates.isEmpty()) {
            return AnswerHandlerResult.unavailable(
                    searchSummary + " → 문서 " + documents.size() + "건",
                    emptyAmountReason(parsed)
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

    /**
     * 같은 증자 건의 원본·정정본을 시간순으로 모아 금액이 바뀐 과정을 근거로 남긴다.
     *
     * <p>전처리에 정정 사슬이 없어, 원본(비정정)이 다른 날에 다시 나오면 새 건으로 본다.
     * 공시일이 있으면 그 날이 들어 있는 건만 쓰고, 없으면 건이 하나일 때만 답한다.
     * 같은 날 정정본이 있으면 원본은 빼고, 금액이 그대로인 단계는 접는다.
     */
    private AnswerHandlerResult handleHistory(FundraisingQuestion parsed, LocalDate reportDate) {
        String searchSummary = "기업명=" + parsed.corpName()
                + ", 공시유형=major, 서식=" + DOC_SUBTYPE + " 정정 이력 검색";
        List<DisclosureDocumentEntity> all =
                documentRepository.findByCorpNameAndDocGroupAndDocSubtype(
                        parsed.corpName(), DocGroup.MAJOR, DOC_SUBTYPE);
        if (all.isEmpty()) {
            return AnswerHandlerResult.unavailable(searchSummary, "해당 기업의 유상증자 공시를 찾지 못했습니다");
        }
        List<DisclosureDocumentEntity> sorted = all.stream()
                .sorted(Comparator
                        .comparing(DisclosureDocumentEntity::getRceptDt, Comparator.nullsLast(LocalDate::compareTo))
                        .thenComparing(DisclosureDocumentEntity::getRceptNo, Comparator.nullsLast(String::compareTo)))
                .toList();
        List<List<DisclosureDocumentEntity>> chains = offeringChains(sorted);
        List<DisclosureDocumentEntity> chain = selectChain(chains, reportDate);
        if (chain == null) {
            return AnswerHandlerResult.unavailable(
                    searchSummary + " → 유상증자 " + chains.size() + "건",
                    reportDate == null
                            ? "여러 차례 유상증자가 있어 공시일을 특정하지 못했습니다"
                            : reportDate + " 공시가 들어 있는 유상증자 정정 이력을 찾지 못했습니다"
            );
        }
        if (chain.size() < 2) {
            return AnswerHandlerResult.unavailable(
                    searchSummary + " → 정정 이력 없음",
                    "찾은 유상증자 공시에 정정 이력이 없습니다"
            );
        }

        List<Candidate> alongChain = new ArrayList<>();
        for (DisclosureDocumentEntity document : chain) {
            List<Candidate> one = candidatesOf(List.of(document), parsed);
            if (!one.isEmpty()) {
                alongChain.add(one.getFirst());
            }
        }
        List<Candidate> steps = collapseHistory(alongChain);
        List<RetrievedEvidence> evidences = new ArrayList<>();
        List<String> labels = new ArrayList<>();
        for (Candidate candidate : steps) {
            DisclosureDocumentEntity document = candidate.document();
            evidences.addAll(candidate.facts().stream()
                    .map(fact -> toEvidence(document, fact))
                    .toList());
            labels.add(document.getRceptDt()
                    + (document.isCorrection() ? " 정정" : " 원본")
                    + " " + candidate.amountKey() + "원");
        }
        if (evidences.isEmpty()) {
            return AnswerHandlerResult.unavailable(searchSummary, emptyAmountReason(parsed));
        }
        if (steps.size() < 2) {
            return AnswerHandlerResult.unavailable(
                    searchSummary + " → 정정 이력 없음",
                    "찾은 유상증자 공시에 정정 이력이 없습니다"
            );
        }
        return AnswerHandlerResult.of(
                evidences,
                searchSummary + " → " + parsed.itemLabel() + " " + String.join(" → ", labels)
        );
    }

    /**
     * 같은 날 정정본이 있으면 원본은 빼고, 금액이 그대로인 단계는 접는다.
     * 값이 끝까지 같으면 처음과 마지막만 남겨 정정은 있었으나 금액은 같았음을 보인다.
     */
    static List<Candidate> collapseHistory(List<Candidate> alongChain) {
        List<Candidate> sameDay = new ArrayList<>();
        int index = 0;
        while (index < alongChain.size()) {
            LocalDate date = alongChain.get(index).document().getRceptDt();
            List<Candidate> day = new ArrayList<>();
            while (index < alongChain.size()
                    && Objects.equals(alongChain.get(index).document().getRceptDt(), date)) {
                day.add(alongChain.get(index));
                index++;
            }
            sameDay.addAll(preferCorrections(day));
        }
        List<Candidate> changed = new ArrayList<>();
        String previous = null;
        for (Candidate candidate : sameDay) {
            if (candidate.amountKey().equals(previous)) {
                continue;
            }
            previous = candidate.amountKey();
            changed.add(candidate);
        }
        if (changed.size() >= 2 || sameDay.size() < 2) {
            return changed;
        }
        return List.of(sameDay.getFirst(), sameDay.getLast());
    }

    private List<Candidate> candidatesOf(
            List<DisclosureDocumentEntity> documents,
            FundraisingQuestion parsed
    ) {
        List<Candidate> candidates = new ArrayList<>();
        for (DisclosureDocumentEntity document : documents) {
            List<ExtractedFactEntity> facts = factRepository.findByDocumentDocId(document.getDocId()).stream()
                    .filter(fact -> fact.getNormalizedValue() != null && !fact.getNormalizedValue().isBlank())
                    .toList();
            List<ExtractedFactEntity> matched = relevantFacts(facts, parsed);
            if (!matched.isEmpty()) {
                candidates.add(new Candidate(document, matched));
            }
        }
        return candidates;
    }

    private static List<ExtractedFactEntity> relevantFacts(
            List<ExtractedFactEntity> facts,
            FundraisingQuestion parsed
    ) {
        if (parsed.factKey() != null && ISSUANCE_FACT_KEYS.contains(parsed.factKey())) {
            return issuanceFacts(facts, parsed.factKey());
        }
        List<ExtractedFactEntity> purposes = facts.stream()
                .filter(fact -> PURPOSE_FACT_KEYS.contains(fact.getFactKey()))
                .toList();
        if (!parsed.isTotal()) {
            return purposes.stream()
                    .filter(fact -> parsed.factKey().equals(fact.getFactKey()))
                    .toList();
        }
        if (!parsed.excludesPurposes()) {
            return purposes;
        }
        return purposes.stream()
                .filter(fact -> !parsed.excludedFactKeys().contains(fact.getFactKey()))
                .toList();
    }

    /**
     * 확정발행가가 비어 있고 예정발행가만 있는 문서가 있다. 「발행가」는 확정을 먼저 보고
     * 없으면 예정값을 쓴다.
     */
    private static List<ExtractedFactEntity> issuanceFacts(
            List<ExtractedFactEntity> facts,
            String factKey
    ) {
        if (ISSUE_PRICE_FACT_KEY.equals(factKey)) {
            List<ExtractedFactEntity> confirmed = facts.stream()
                    .filter(fact -> ISSUE_PRICE_FACT_KEY.equals(fact.getFactKey()))
                    .toList();
            if (!confirmed.isEmpty()) {
                return confirmed;
            }
            return facts.stream()
                    .filter(fact -> PLANNED_ISSUE_PRICE_FACT_KEY.equals(fact.getFactKey()))
                    .toList();
        }
        return facts.stream()
                .filter(fact -> factKey.equals(fact.getFactKey()))
                .toList();
    }

    private static String emptyAmountReason(FundraisingQuestion parsed) {
        if (parsed.excludesPurposes()) {
            return "검색된 공시에서 제외하고 남을 목적별 자금 금액을 찾지 못했습니다";
        }
        if (parsed.factKey() != null && ISSUANCE_FACT_KEYS.contains(parsed.factKey())) {
            return "검색된 공시에서 " + parsed.itemLabel() + " 값을 찾지 못했습니다";
        }
        return parsed.isTotal()
                ? "검색된 공시에서 합산할 목적별 자금 금액을 찾지 못했습니다"
                : "검색된 공시에서 " + parsed.itemLabel() + " 금액을 찾지 못했습니다";
    }

    /**
     * 비정정이 다른 날에 나오면 새 증자 건으로 본다. 같은 날 원본·정정과
     * 이후 날짜의 정정본은 한 건으로 묶는다.
     */
    static List<List<DisclosureDocumentEntity>> offeringChains(List<DisclosureDocumentEntity> sorted) {
        List<List<DisclosureDocumentEntity>> chains = new ArrayList<>();
        List<DisclosureDocumentEntity> current = new ArrayList<>();
        for (DisclosureDocumentEntity document : sorted) {
            if (current.isEmpty()) {
                current.add(document);
                continue;
            }
            DisclosureDocumentEntity last = current.getLast();
            boolean sameDay = Objects.equals(document.getRceptDt(), last.getRceptDt());
            if (sameDay || document.isCorrection()) {
                current.add(document);
            } else {
                chains.add(current);
                current = new ArrayList<>();
                current.add(document);
            }
        }
        if (!current.isEmpty()) {
            chains.add(current);
        }
        return chains;
    }

    private static List<DisclosureDocumentEntity> selectChain(
            List<List<DisclosureDocumentEntity>> chains,
            LocalDate reportDate
    ) {
        if (reportDate != null) {
            return chains.stream()
                    .filter(chain -> chain.stream().anyMatch(doc -> reportDate.equals(doc.getRceptDt())))
                    .findFirst()
                    .orElse(null);
        }
        return chains.size() == 1 ? chains.getFirst() : null;
    }

    private FundraisingQuestion parse(String question) {
        return FundraisingQuestionParser.parse(
                question,
                CorpNameResolver.of(corpIdentityRepository.findByDocGroupAndDocSubtype(
                        DocGroup.MAJOR, DOC_SUBTYPE)));
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
                String key = fact.getFactKey();
                if (SECURITIES_ACQUISITION_FACT_KEY.equals(parsed.factKey())) {
                    key = parsed.factKey() + "(ANC_ACQ_AMT)";
                }
                String formatted = formatValue(fact);
                return "문서 " + docId + " 선택 → " + key + "=" + formatted + " 추출";
            }
            String parts = facts.stream()
                    .map(fact -> fact.getFactKey() + "=" + rawAmount(fact))
                    .collect(Collectors.joining(" + "));
            String total = sum().toPlainString();
            return "문서 " + docId + " 선택 → " + parsed.itemLabel() + " " + parts + " = " + total
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
            return formatValue(fact);
        }

        private static String formatValue(ExtractedFactEntity fact) {
            String unit = fact.getRawUnit() == null ? "" : fact.getRawUnit();
            String raw = fact.getRawValue() + unit;
            if ("KRW".equals(fact.getNormalizedUnit())
                    && PURPOSE_FACT_KEYS.contains(fact.getFactKey())
                    && fact.getNormalizedValue() != null) {
                return raw;
            }
            return raw;
        }
    }
}
