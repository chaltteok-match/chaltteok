package com.chaltteok.financial;

import com.chaltteok.answer.AnswerHandler;
import com.chaltteok.answer.CorpNameResolver;
import com.chaltteok.disclosure.persistence.repository.CorpIdentityRepository;
import com.chaltteok.answer.AnswerHandlerResult;
import com.chaltteok.answer.KoreanAmountFormatter;
import com.chaltteok.answer.KoreanParticle;
import com.chaltteok.answer.RetrievedEvidence;
import com.chaltteok.disclosure.model.enums.DocGroup;
import com.chaltteok.disclosure.model.enums.ExtractionStatus;
import com.chaltteok.disclosure.model.enums.Scope;
import com.chaltteok.disclosure.persistence.entity.DisclosureDocumentEntity;
import com.chaltteok.disclosure.persistence.entity.ExtractedFactEntity;
import com.chaltteok.disclosure.persistence.repository.DisclosureDocumentRepository;
import com.chaltteok.disclosure.persistence.repository.ExtractedFactRepository;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * 정기공시 재무 수치 조회와 연도 비교·계산을 담당한다.
 *
 * <p>요약재무정보 표 하나에 3개 기간이 들어 있으므로, 단일 조회와 전년 대비 비교를
 * 모두 문서 한 건으로 처리한다. 근거 조립까지만 하고 최종 문장은 HCX가 만든다.
 */
@Component
@Order(30)
@ConditionalOnProperty(name = "chaltteok.disclosure.persistence.enabled", havingValue = "true")
public class FinancialAnswerHandler implements AnswerHandler {

    private static final int SCALE = 2;

    private final DisclosureDocumentRepository documentRepository;
    private final ExtractedFactRepository factRepository;
    private final CorpIdentityRepository corpIdentityRepository;

    public FinancialAnswerHandler(
            DisclosureDocumentRepository documentRepository,
            ExtractedFactRepository factRepository,
            CorpIdentityRepository corpIdentityRepository
    ) {
        this.documentRepository = documentRepository;
        this.factRepository = factRepository;
        this.corpIdentityRepository = corpIdentityRepository;
    }

    @Override
    @Transactional(readOnly = true)
    public boolean supports(String question) {
        FinancialQuestion parsed = parse(question);
        return parsed.corpName() != null && parsed.factKey() != null;
    }

    @Override
    @Transactional(readOnly = true)
    public AnswerHandlerResult handle(String questionId, String question) {
        FinancialQuestion parsed = parse(question);
        if (!parsed.isAnswerable()) {
            return AnswerHandlerResult.unavailable(
                    "질문에서 기업·연도·항목을 식별하는 중",
                    missingPartsOf(parsed));
        }

        String summaryPrefix = "%s·%d년·%s·%s기준 식별".formatted(
                parsed.corpName(), parsed.targetYear(), parsed.itemLabel(), scopeText(parsed.scope()));

        Optional<DisclosureDocumentEntity> found = selectDocument(parsed);
        if (found.isEmpty()) {
            return AnswerHandlerResult.unavailable(
                    summaryPrefix + " → 해당 기업·연도의 정기공시를 찾지 못함",
                    "제공된 공시 데이터에 %s의 %d년 정기공시가 없습니다"
                            .formatted(parsed.corpName(), parsed.targetYear()));
        }
        DisclosureDocumentEntity document = found.get();

        List<ExtractedFactEntity> facts =
                factRepository.findForAnswer(document.getDocId(), parsed.factKey(), parsed.scope());
        String searchSummary = summaryPrefix + " → '%s'(%s) 검색".formatted(
                document.getReportNm(), document.getRceptDt());

        Optional<ExtractedFactEntity> target = factOfYear(facts, parsed.targetYear());
        if (target.isEmpty()) {
            boolean onlyPartialPeriod = facts.stream().anyMatch(
                    fact -> parsed.targetYear().equals(yearOf(fact)));
            String reason = onlyPartialPeriod
                    ? "'%s'에는 %d년 %s가 연간 수치가 아니라 일부 기간 누적으로만 있어 연간 값으로 답할 수 없습니다"
                            .formatted(document.getReportNm(), parsed.targetYear(), parsed.itemLabel())
                    : "'%s'에서 %d년 %s기준 %s%s 찾지 못했습니다".formatted(
                            document.getReportNm(), parsed.targetYear(),
                            scopeText(parsed.scope()), parsed.itemLabel(),
                            KoreanParticle.object(parsed.itemLabel()));
            return AnswerHandlerResult.unavailable(
                    searchSummary + (onlyPartialPeriod
                            ? " → 연간 수치 없음(일부 기간 누적만 존재)"
                            : " → 해당 항목·기준의 수치 없음"),
                    reason);
        }
        ExtractedFactEntity targetFact = target.get();

        if (targetFact.getNormalizedValue() == null) {
            // 값이 비는 이유는 하나가 아니다. 전처리가 남긴 상태를 그대로 전한다.
            // 이유를 단정하면 파서 결함이 정보 한계로 위장된다(docs/data-model.md §9).
            return AnswerHandlerResult.unavailable(
                    List.of(evidenceOf(document, targetFact)),
                    searchSummary + " → " + unavailableTrace(targetFact.getExtractionStatus()),
                    unavailableReason(targetFact.getExtractionStatus()));
        }

        return parsed.isComparison()
                ? comparison(parsed, document, facts, targetFact, searchSummary)
                : single(parsed, document, targetFact, searchSummary);
    }

    private AnswerHandlerResult single(
            FinancialQuestion parsed,
            DisclosureDocumentEntity document,
            ExtractedFactEntity fact,
            String searchSummary
    ) {
        StringBuilder summary = new StringBuilder(searchSummary)
                .append(" → %s %s(%s) 확인".formatted(
                        fact.getLabel(),
                        KoreanAmountFormatter.format(fact.getNormalizedValue()),
                        fact.getRawValue() + (fact.getRawUnit() == null ? "" : fact.getRawUnit())))
                .append(" → 근거와 답변 수치 일치 검증");
        appendScopeNote(summary, parsed);

        List<RetrievedEvidence> evidences = new ArrayList<>();
        addEvidence(evidences, document, fact);
        appendCounterpart(summary, evidences, parsed, document);
        return AnswerHandlerResult.of(evidences, summary.toString());
    }

    private AnswerHandlerResult comparison(
            FinancialQuestion parsed,
            DisclosureDocumentEntity document,
            List<ExtractedFactEntity> facts,
            ExtractedFactEntity targetFact,
            String searchSummary
    ) {
        Optional<ExtractedFactEntity> base = factOfYear(facts, parsed.baseYear());
        if (base.isEmpty() || base.get().getNormalizedValue() == null) {
            return AnswerHandlerResult.unavailable(
                    List.of(evidenceOf(document, targetFact)),
                    searchSummary + " → 비교 기준 연도(%d년) 수치 없음".formatted(parsed.baseYear()),
                    "%d년 수치는 확인했지만 비교 기준인 %d년 수치를 찾지 못해 증감을 계산할 수 없습니다"
                            .formatted(parsed.targetYear(), parsed.baseYear()));
        }
        ExtractedFactEntity baseFact = base.get();

        BigDecimal current = new BigDecimal(targetFact.getNormalizedValue());
        BigDecimal previous = new BigDecimal(baseFact.getNormalizedValue());
        BigDecimal difference = current.subtract(previous);

        StringBuilder summary = new StringBuilder(searchSummary)
                .append(" → %d년 %s, %d년 %s 확인".formatted(
                        parsed.targetYear(), KoreanAmountFormatter.format(targetFact.getNormalizedValue()),
                        parsed.baseYear(), KoreanAmountFormatter.format(baseFact.getNormalizedValue())));

        changeRate(current, previous).ifPresentOrElse(
                rate -> summary.append(" → 증감액=%s, 증감률=(%s-%s)/%s×100=%s%%".formatted(
                        KoreanAmountFormatter.format(difference.toPlainString()),
                        current.toPlainString(), previous.toPlainString(),
                        previous.toPlainString(), rate.toPlainString())),
                () -> summary.append(" → 기준 연도 값이 0이라 증감률을 계산하지 않음"));
        appendScopeNote(summary, parsed);

        List<RetrievedEvidence> evidences = new ArrayList<>();
        addEvidence(evidences, document, targetFact);
        addEvidence(evidences, document, baseFact);
        appendCounterpart(summary, evidences, parsed, document);
        return AnswerHandlerResult.of(evidences, summary.toString());
    }

    private Optional<DisclosureDocumentEntity> selectDocument(FinancialQuestion parsed) {
        List<DisclosureDocumentEntity> documents =
                documentRepository.findByCorpNameAndDocGroupAndBaseYearOrderByRceptDtDescRceptNoDesc(
                        parsed.corpName(), DocGroup.PERIODIC, parsed.targetYear());
        if (documents.isEmpty()) {
            return Optional.empty();
        }
        // 연간 수치를 묻는 질문이므로 사업보고서를 우선하고, 같은 조건이면 최신 접수분을 쓴다.
        return documents.stream()
                .min(Comparator.comparingInt(
                        document -> "annual".equals(document.getDocSubtype()) ? 0 : 1));
    }

    private static Optional<ExtractedFactEntity> factOfYear(List<ExtractedFactEntity> facts, Integer year) {
        if (year == null) {
            return Optional.empty();
        }
        return facts.stream().filter(fact -> coversFullYear(fact, year)).findFirst();
    }

    /**
     * 연도를 묻는 질문에 쓸 수 있는 수치인지 본다.
     *
     * <p>분기·반기보고서의 요약재무정보에는 그 해 누적치와 직전 연도들의 연간치가 함께 있다.
     * 예를 들어 2023년 1분기 보고서에는 2023년 1~3월 매출과 2022년·2021년 연간 매출이 같이 실린다.
     * 연도만 비교하면 3개월치를 그 해 연간 수치로 답하게 되므로, 기간이 한 해를 다 덮는지 확인한다.
     * 재무상태 항목은 시점 값이라 기준일이 연말인지 본다.
     */
    private static boolean coversFullYear(ExtractedFactEntity fact, int year) {
        if (fact.getPeriodEnd() != null) {
            return LocalDate.of(year, 1, 1).equals(fact.getPeriodStart())
                    && LocalDate.of(year, 12, 31).equals(fact.getPeriodEnd());
        }
        return LocalDate.of(year, 12, 31).equals(fact.getAsOfDate());
    }

    private static Optional<ExtractedFactEntity> usableFactOfYear(
            List<ExtractedFactEntity> facts, Integer year) {
        return factOfYear(facts, year).filter(fact -> fact.getNormalizedValue() != null);
    }

    /** 기준 연도가 0이면 증감률을 정의할 수 없다. */
    private static Optional<BigDecimal> changeRate(BigDecimal current, BigDecimal previous) {
        if (previous.signum() == 0) {
            return Optional.empty();
        }
        return Optional.of(current.subtract(previous)
                .multiply(BigDecimal.valueOf(100))
                .divide(previous.abs(), SCALE, RoundingMode.HALF_UP));
    }

    /**
     * 질문이 기준을 밝히지 않았으면 반대 기준 수치도 함께 제시한다.
     * 답변에 나가는 수치는 근거가 있어야 하므로 참고 수치의 근거도 같이 넘긴다.
     * 참고로 제시하는 것은 값까지다. 증감률까지 두 벌로 내면 어느 쪽이 답인지 흐려진다.
     * 반대 기준 수치가 없으면 사용한 기준만 남기고 조용히 넘어간다.
     */
    private void appendCounterpart(
            StringBuilder summary,
            List<RetrievedEvidence> evidences,
            FinancialQuestion parsed,
            DisclosureDocumentEntity document
    ) {
        if (parsed.scopeExplicit()) {
            return;
        }
        Scope counterpart = counterpartScope(parsed.scope());
        List<ExtractedFactEntity> facts =
                factRepository.findForAnswer(document.getDocId(), parsed.factKey(), counterpart);

        Optional<ExtractedFactEntity> found = usableFactOfYear(facts, parsed.targetYear());
        if (found.isEmpty()) {
            return;
        }
        ExtractedFactEntity targetFact = found.get();
        String scopeName = scopeText(counterpart);

        summary.append(" → 참고: %s기준 %d년 %s".formatted(
                scopeName, parsed.targetYear(),
                KoreanAmountFormatter.format(targetFact.getNormalizedValue())));
        addEvidence(evidences, document, targetFact);

        if (!parsed.isComparison()) {
            return;
        }
        Optional<ExtractedFactEntity> counterpartBase = usableFactOfYear(facts, parsed.baseYear());
        if (counterpartBase.isEmpty()) {
            summary.append(", %d년 수치가 없어 %s기준 증감은 계산하지 않음".formatted(parsed.baseYear(), scopeName));
            return;
        }
        ExtractedFactEntity baseFact = counterpartBase.get();
        summary.append(", %d년 %s".formatted(
                parsed.baseYear(), KoreanAmountFormatter.format(baseFact.getNormalizedValue())));
        addEvidence(evidences, document, baseFact);
    }

    private static Scope counterpartScope(Scope scope) {
        return scope == Scope.SEPARATE ? Scope.CONSOLIDATED : Scope.SEPARATE;
    }

    /** 같은 근거 청크가 여러 수치에 걸릴 수 있으므로 중복을 넣지 않는다. */
    private void addEvidence(
            List<RetrievedEvidence> evidences,
            DisclosureDocumentEntity document,
            ExtractedFactEntity fact
    ) {
        RetrievedEvidence evidence = evidenceOf(document, fact);
        if (!evidences.contains(evidence)) {
            evidences.add(evidence);
        }
    }

    private static String unavailableTrace(ExtractionStatus status) {
        return status == ExtractionStatus.UNIT_MISSING
                ? "원문에 단위 표기가 없어 금액을 확정할 수 없음"
                : "전처리가 값을 정규화하지 못함(상태=" + status + ")";
    }

    private static String unavailableReason(ExtractionStatus status) {
        return status == ExtractionStatus.UNIT_MISSING
                ? "공시 원문에 단위 표기가 없어 금액을 확정할 수 없습니다"
                : "전처리가 이 수치를 정규화하지 못해 금액을 확정할 수 없습니다";
    }

    /** 사유를 구분하기 위한 값. 답변에 쓸 수치를 고르는 데는 쓰지 않는다. */
    private static Integer yearOf(ExtractedFactEntity fact) {
        LocalDate date = fact.getPeriodEnd() != null ? fact.getPeriodEnd() : fact.getAsOfDate();
        return date == null ? null : date.getYear();
    }

    private RetrievedEvidence evidenceOf(DisclosureDocumentEntity document, ExtractedFactEntity fact) {
        return new RetrievedEvidence(
                document.getReportNm(),
                document.getRceptDt(),
                document.getCorpName(),
                fact.getEvidenceChunk().getText());
    }

    /**
     * 질문이 기준을 밝히지 않으면 어떤 기준을 썼는지 남긴다.
     * 삼성전자 2024년 매출액은 연결과 별도가 91조 차이 나므로 기준을 밝히지 않으면 답이 달라진다.
     */
    private static void appendScopeNote(StringBuilder summary, FinancialQuestion parsed) {
        if (!parsed.scopeExplicit()) {
            summary.append(" (질문에 연결·별도 기준이 없어 %s기준을 사용)".formatted(scopeText(parsed.scope())));
        }
    }

    private static String scopeText(Scope scope) {
        return scope == Scope.SEPARATE ? "별도" : "연결";
    }

    private FinancialQuestion parse(String question) {
        return FinancialQuestionParser.parse(
                question, CorpNameResolver.of(corpIdentityRepository.findAllIdentities()));
    }

    private static String missingPartsOf(FinancialQuestion parsed) {
        List<String> missing = new ArrayList<>();
        if (parsed.corpName() == null) {
            missing.add("기업");
        }
        if (parsed.targetYear() == null) {
            missing.add("연도");
        }
        if (parsed.factKey() == null) {
            missing.add("재무 항목");
        }
        String parts = String.join("·", missing);
        return "질문에서 %s%s 식별하지 못했습니다".formatted(parts, KoreanParticle.object(parts));
    }
}
