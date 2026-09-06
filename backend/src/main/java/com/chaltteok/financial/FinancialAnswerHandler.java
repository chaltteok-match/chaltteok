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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.regex.Pattern;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * 정기공시 재무 수치 조회와 연도 비교·계산을 담당한다.
 *
 * <p>요약재무정보 표 하나에 3개 기간이 들어 있으므로 대부분 문서 한 건으로 끝난다.
 * 다만 네 해 이상 떨어진 비교는 한 표에 담기지 않아 보고서 두 건에서 값을 가져온다.
 * 근거 조립까지만 하고 최종 문장은 HCX가 만든다.
 */
@Component
@Order(30)
@ConditionalOnProperty(name = "chaltteok.disclosure.persistence.enabled", havingValue = "true")
public class FinancialAnswerHandler implements AnswerHandler {

    private static final int SCALE = 2;
    /** 연도를 적지 않고 최신을 묻는 말. */
    private static final Pattern LATEST = Pattern.compile("최근|최신|가장 ?최근|직전|마지막");

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
        if (parsed.factKey() == null) {
            return false;
        }
        // 기업 이름이 없어도 `매출액이 가장 큰 기업은?`은 정기공시 항목을 묻는 질문이다.
        // 나서지 않으면 왜 답할 수 없는지 대신 기본 문구가 나간다.
        return parsed.corpName() != null || parsed.wantsCorpRanking();
    }

    @Override
    @Transactional(readOnly = true)
    public AnswerHandlerResult handle(String questionId, String question) {
        FinancialQuestion initial = parse(question);
        // 비율 비교는 금액 비교 경로로 보내면 안 된다. 기업 간 비교는 분자 금액을 견주고,
        // 연도 대비는 대상 연도 비율만 답한다. 둘 다 물어본 것과 다른 답이다.
        if (initial.isRatio() && initial.isCorpComparison()) {
            return ratioCorpComparison(initial);
        }
        if (initial.wantsCorpRanking()) {
            // 근거를 댈 수 있는 것은 질문에 이름이 나온 기업뿐이다. 70개사를 훑어 순위를 매기면
            // 적재되지 않은 기업이 빠진 채 「가장 크다」고 답하게 된다.
            return AnswerHandlerResult.unavailable(
                    "질문이 코퍼스 전체 비교를 요구함 → 이름이 나온 기업만으로는 답할 수 없음",
                    "제공된 공시 데이터로는 기업 전체를 견주어 순위를 답할 수 없습니다."
                            + " 비교할 기업을 두 곳 이상 지정해 주세요");
        }
        if (initial.lostCounterpart()) {
            return AnswerHandlerResult.unavailable(
                    "비교 상대 기업을 코퍼스에서 찾지 못함 → 한 기업 수치로 비교하지 않음",
                    "%s 외에 비교할 기업을 제공된 공시 데이터에서 찾지 못했습니다"
                            .formatted(initial.corpName()));
        }
        FinancialQuestion parsed = latestYearIfAsked(initial, question);
        if (!parsed.isAnswerable()) {
            return AnswerHandlerResult.unavailable(
                    "질문에서 기업·연도·항목을 식별하는 중",
                    missingPartsOf(parsed));
        }
        if (parsed.isCorpComparison()) {
            return corpComparison(parsed);
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

        if (parsed.isRatio()) {
            return parsed.isComparison()
                    ? ratioYearComparison(parsed, document, targetFact, searchSummary)
                    : ratio(parsed, document, targetFact, searchSummary);
        }
        return parsed.isComparison()
                ? comparison(parsed, document, facts, targetFact, searchSummary)
                : single(parsed, document, targetFact, searchSummary);
    }

    /**
     * 표에 없는 비율을 두 값으로 계산한다.
     *
     * <p>분자는 이미 찾아 둔 수치다. 분모는 같은 문서·같은 기준·같은 해에서 찾는다.
     * 문서를 섞지 않으므로 단위와 연결·별도 기준이 어긋나지 않는다.
     * 분모가 없거나 0이면 계산하지 않고 무엇이 없는지 밝힌다.
     */
    private AnswerHandlerResult ratio(
            FinancialQuestion parsed,
            DisclosureDocumentEntity document,
            ExtractedFactEntity numerator,
            String searchSummary
    ) {
        FinancialRatio ratio = parsed.ratio();
        Optional<ExtractedFactEntity> found = usableFactOfYear(
                factRepository.findForAnswer(
                        document.getDocId(), ratio.denominatorKey(), parsed.scope()),
                parsed.targetYear());
        if (found.isEmpty()) {
            return AnswerHandlerResult.unavailable(
                    List.of(evidenceOf(document, numerator)),
                    searchSummary + " → 분모(%s) 수치 없음".formatted(ratio.denominatorLabel()),
                    "'%s'에서 %d년 %s%s 찾지 못해 %s%s 계산할 수 없습니다".formatted(
                            document.getReportNm(), parsed.targetYear(),
                            ratio.denominatorLabel(),
                            KoreanParticle.object(ratio.denominatorLabel()),
                            ratio.label(), KoreanParticle.object(ratio.label())));
        }
        ExtractedFactEntity denominator = found.get();

        BigDecimal top = new BigDecimal(numerator.getNormalizedValue());
        BigDecimal bottom = new BigDecimal(denominator.getNormalizedValue());
        if (bottom.signum() == 0) {
            return AnswerHandlerResult.unavailable(
                    List.of(evidenceOf(document, numerator), evidenceOf(document, denominator)),
                    searchSummary + " → 분모(%s)가 0".formatted(ratio.denominatorLabel()),
                    "%d년 %s%s 0이라 %s%s 계산할 수 없습니다".formatted(
                            parsed.targetYear(), ratio.denominatorLabel(),
                            KoreanParticle.subject(ratio.denominatorLabel()),
                            ratio.label(), KoreanParticle.object(ratio.label())));
        }
        BigDecimal percent = top.multiply(BigDecimal.valueOf(100))
                .divide(bottom, SCALE, RoundingMode.HALF_UP);

        StringBuilder summary = new StringBuilder(searchSummary)
                .append(" → %s %s, %s %s 확인".formatted(
                        ratio.numeratorLabel(), KoreanAmountFormatter.format(numerator.getNormalizedValue()),
                        ratio.denominatorLabel(), KoreanAmountFormatter.format(denominator.getNormalizedValue())))
                .append(" → %s=%s/%s×100=%s%%".formatted(
                        ratio.label(), top.toPlainString(), bottom.toPlainString(),
                        percent.toPlainString()));
        appendScopeNote(summary, parsed);

        List<RetrievedEvidence> evidences = new ArrayList<>();
        addEvidence(evidences, document, numerator);
        addEvidence(evidences, document, denominator);
        return AnswerHandlerResult.of(evidences, summary.toString());
    }

    /**
     * 질문에 나온 기업끼리 같은 연도·항목·기준으로 견준다.
     *
     * <p>한 기업이라도 수치를 못 찾으면 답변 불가다. 찾은 쪽 수치만 근거로 내보내면 모델이
     * 나머지를 지어내 비교 답을 만든다. 어느 기업이 왜 빠졌는지 밝힌다.
     */
    private AnswerHandlerResult corpComparison(FinancialQuestion parsed) {
        List<RetrievedEvidence> evidences = new ArrayList<>();
        Map<String, BigDecimal> values = new LinkedHashMap<>();
        List<String> missing = new ArrayList<>();
        StringBuilder summary = new StringBuilder("%s·%d년·%s·%s기준 비교 식별".formatted(
                joinCorpNames(parsed.corpNames()), parsed.targetYear(),
                parsed.itemLabel(), scopeText(parsed.scope())));

        for (String corpName : parsed.corpNames()) {
            Optional<DisclosureDocumentEntity> found = selectDocument(parsed, corpName);
            if (found.isEmpty()) {
                missing.add("%s%s %d년 정기공시가 없습니다".formatted(
                        corpName, KoreanParticle.topic(corpName), parsed.targetYear()));
                continue;
            }
            DisclosureDocumentEntity document = found.get();
            List<ExtractedFactEntity> facts = factRepository.findForAnswer(
                    document.getDocId(), parsed.factKey(), parsed.scope());
            Optional<ExtractedFactEntity> fact = usableFactOfYear(facts, parsed.targetYear());
            if (fact.isEmpty()) {
                missing.add("%s%s '%s'에서 %d년 %s기준 %s%s 찾지 못했습니다".formatted(
                        corpName, KoreanParticle.topic(corpName), document.getReportNm(),
                        parsed.targetYear(), scopeText(parsed.scope()), parsed.itemLabel(),
                        KoreanParticle.object(parsed.itemLabel())));
                continue;
            }
            ExtractedFactEntity matched = fact.get();
            values.put(corpName, new BigDecimal(matched.getNormalizedValue()));
            summary.append(" → %s %s".formatted(
                    corpName, KoreanAmountFormatter.format(matched.getNormalizedValue())));
            addEvidence(evidences, document, matched);
        }

        if (!missing.isEmpty()) {
            return AnswerHandlerResult.unavailable(
                    evidences,
                    summary + " → 수치를 못 찾은 기업이 있어 비교하지 않음",
                    String.join(". ", missing) + ". 한쪽 수치만으로는 비교할 수 없습니다");
        }

        appendCorpGap(summary, values);
        appendScopeNote(summary, parsed);
        return AnswerHandlerResult.of(evidences, summary.toString());
    }

    /** {@code 삼성전자와 SK하이닉스}처럼 받침에 맞춰 잇는다. */
    private static String joinCorpNames(List<String> corpNames) {
        StringBuilder joined = new StringBuilder(corpNames.getFirst());
        for (int index = 1; index < corpNames.size(); index++) {
            joined.append(KoreanParticle.and(corpNames.get(index - 1)))
                    .append(' ')
                    .append(corpNames.get(index));
        }
        return joined.toString();
    }

    /** 가장 큰 기업과 차이를 남긴다. 답변 문장은 HCX가 쓰므로 계산 근거만 적는다. */
    private static void appendCorpGap(StringBuilder summary, Map<String, BigDecimal> values) {
        Map.Entry<String, BigDecimal> largest = null;
        Map.Entry<String, BigDecimal> smallest = null;
        for (Map.Entry<String, BigDecimal> entry : values.entrySet()) {
            if (largest == null || entry.getValue().compareTo(largest.getValue()) > 0) {
                largest = entry;
            }
            if (smallest == null || entry.getValue().compareTo(smallest.getValue()) < 0) {
                smallest = entry;
            }
        }
        if (largest == null || largest.getKey().equals(smallest.getKey())) {
            summary.append(" → 값이 모두 같아 큰 쪽을 가리지 않음");
            return;
        }
        BigDecimal gap = largest.getValue().subtract(smallest.getValue());
        summary.append(" → %s가 %s보다 %s 큼(%s-%s)".formatted(
                largest.getKey(), smallest.getKey(),
                KoreanAmountFormatter.format(gap.toPlainString()),
                largest.getValue().toPlainString(), smallest.getValue().toPlainString()));
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
        DisclosureDocumentEntity baseDocument = document;
        Optional<ExtractedFactEntity> base = usableFactOfYear(facts, parsed.baseYear());
        if (base.isEmpty()) {
            // 요약재무정보 표는 세 기간뿐이라 네 해 이상 떨어진 연도는 같은 표에 들어가지 못한다.
            // 대상 연도 문서에 없으면 기준 연도를 담은 다른 보고서에서 찾는다.
            Optional<DisclosureDocumentEntity> other =
                    reportContainingYear(parsed, parsed.corpName(), parsed.baseYear());
            if (other.isPresent()) {
                baseDocument = other.get();
                base = usableFactOfYear(
                        factRepository.findForAnswer(
                                baseDocument.getDocId(), parsed.factKey(), parsed.scope()),
                        parsed.baseYear());
            }
        }
        if (base.isEmpty()) {
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
        if (baseDocument != document) {
            summary.append("(%d년은 '%s'(%s)에서 확인)".formatted(
                    parsed.baseYear(), baseDocument.getReportNm(), baseDocument.getRceptDt()));
        }

        changeRate(current, previous).ifPresentOrElse(
                rate -> summary.append(" → 증감액=%s, 증감률=(%s-%s)/%s×100=%s%%".formatted(
                        KoreanAmountFormatter.format(difference.toPlainString()),
                        current.toPlainString(), previous.toPlainString(),
                        previous.toPlainString(), rate.toPlainString())),
                () -> summary.append(" → 기준 연도 값이 0이라 증감률을 계산하지 않음"));
        appendScopeNote(summary, parsed);

        List<RetrievedEvidence> evidences = new ArrayList<>();
        addEvidence(evidences, document, targetFact);
        addEvidence(evidences, baseDocument, baseFact);
        appendCounterpart(summary, evidences, parsed, document, baseDocument);
        return AnswerHandlerResult.of(evidences, summary.toString());
    }

    /** 한 기업·한 해의 비율. 분자·분모와 그 출처를 함께 들고 다닌다. */
    private record RatioValue(
            DisclosureDocumentEntity document,
            ExtractedFactEntity numerator,
            ExtractedFactEntity denominator,
            BigDecimal percent
    ) { }

    /**
     * 한 기업·한 해의 비율을 계산한다. 분자·분모 중 하나라도 없거나 분모가 0이면 비어 있다.
     *
     * <p>분자와 분모를 같은 문서·같은 기준에서 찾는다. 문서를 섞지 않으므로 단위와
     * 연결·별도가 어긋나지 않는다.
     */
    private Optional<RatioValue> ratioAt(FinancialQuestion parsed, String corpName, Integer year) {
        FinancialRatio ratio = parsed.ratio();
        Optional<DisclosureDocumentEntity> found = selectDocument(parsed, corpName, year);
        if (found.isEmpty()) {
            return Optional.empty();
        }
        DisclosureDocumentEntity document = found.get();
        Optional<ExtractedFactEntity> numerator = usableFactOfYear(
                factRepository.findForAnswer(
                        document.getDocId(), ratio.numeratorKey(), parsed.scope()),
                year);
        Optional<ExtractedFactEntity> denominator = usableFactOfYear(
                factRepository.findForAnswer(
                        document.getDocId(), ratio.denominatorKey(), parsed.scope()),
                year);
        if (numerator.isEmpty() || denominator.isEmpty()) {
            return Optional.empty();
        }
        BigDecimal bottom = new BigDecimal(denominator.get().getNormalizedValue());
        if (bottom.signum() == 0) {
            return Optional.empty();
        }
        BigDecimal top = new BigDecimal(numerator.get().getNormalizedValue());
        return Optional.of(new RatioValue(document, numerator.get(), denominator.get(),
                top.multiply(BigDecimal.valueOf(100)).divide(bottom, SCALE, RoundingMode.HALF_UP)));
    }

    /**
     * 같은 해 두 기업의 비율을 견준다.
     *
     * <p>한 기업이라도 계산할 수 없으면 답변 불가다. 계산된 쪽만 내보내면 모델이 나머지를
     * 지어낼 여지가 생긴다.
     */
    private AnswerHandlerResult ratioCorpComparison(FinancialQuestion parsed) {
        FinancialRatio ratio = parsed.ratio();
        if (parsed.targetYear() == null) {
            return AnswerHandlerResult.unavailable(
                    "비율 비교 질문으로 분류", missingPartsOf(parsed));
        }
        String searchSummary = "%s·%d년·%s·%s기준 비교 식별".formatted(
                joinCorpNames(parsed.corpNames()), parsed.targetYear(),
                ratio.label(), scopeText(parsed.scope()));

        Map<String, RatioValue> values = new LinkedHashMap<>();
        for (String corpName : parsed.corpNames()) {
            Optional<RatioValue> value = ratioAt(parsed, corpName, parsed.targetYear());
            if (value.isEmpty()) {
                return AnswerHandlerResult.unavailable(
                        searchSummary + " → %s 계산 불가".formatted(corpName),
                        "%s의 %d년 %s%s 계산할 자료를 찾지 못해 비교할 수 없습니다".formatted(
                                corpName, parsed.targetYear(), ratio.label(),
                                KoreanParticle.object(ratio.label())));
            }
            values.put(corpName, value.get());
        }

        StringBuilder summary = new StringBuilder(searchSummary);
        List<RetrievedEvidence> evidences = new ArrayList<>();
        for (Map.Entry<String, RatioValue> entry : values.entrySet()) {
            RatioValue value = entry.getValue();
            summary.append(" → %s %s=%s/%s×100=%s%%".formatted(
                    entry.getKey(), ratio.label(),
                    new BigDecimal(value.numerator().getNormalizedValue()).toPlainString(),
                    new BigDecimal(value.denominator().getNormalizedValue()).toPlainString(),
                    value.percent().toPlainString()));
            addEvidence(evidences, value.document(), value.numerator());
            addEvidence(evidences, value.document(), value.denominator());
        }
        appendRatioGap(summary, values, ratio);
        appendScopeNote(summary, parsed);
        return AnswerHandlerResult.of(evidences, summary.toString());
    }

    /** 가장 높은 곳과 가장 낮은 곳의 차이를 %p로 밝힌다. */
    private static void appendRatioGap(
            StringBuilder summary, Map<String, RatioValue> values, FinancialRatio ratio) {
        Map.Entry<String, RatioValue> largest = null;
        Map.Entry<String, RatioValue> smallest = null;
        for (Map.Entry<String, RatioValue> entry : values.entrySet()) {
            if (largest == null || entry.getValue().percent().compareTo(largest.getValue().percent()) > 0) {
                largest = entry;
            }
            if (smallest == null || entry.getValue().percent().compareTo(smallest.getValue().percent()) < 0) {
                smallest = entry;
            }
        }
        if (largest == null || largest.getKey().equals(smallest.getKey())) {
            return;
        }
        summary.append(" → %s가 %s보다 %s %s%%p 높음".formatted(
                largest.getKey(), smallest.getKey(), ratio.label(),
                largest.getValue().percent().subtract(smallest.getValue().percent()).toPlainString()));
    }

    /**
     * 한 기업의 두 해 비율을 견준다. 비율의 증감은 %p로 밝힌다.
     *
     * <p>대상 연도 비율은 이미 고른 문서에서 계산한 것을 쓴다. 기준 연도는 그 해를 담은
     * 보고서를 따로 찾는다. 표가 세 기간뿐이라 문서가 달라질 수 있다.
     */
    private AnswerHandlerResult ratioYearComparison(
            FinancialQuestion parsed,
            DisclosureDocumentEntity document,
            ExtractedFactEntity numerator,
            String searchSummary
    ) {
        FinancialRatio ratio = parsed.ratio();
        AnswerHandlerResult target = ratio(parsed, document, numerator, searchSummary);
        if (!target.isAnswerable()) {
            return target;
        }
        Optional<RatioValue> base = ratioAt(parsed, parsed.corpName(), parsed.baseYear());
        if (base.isEmpty()) {
            return AnswerHandlerResult.unavailable(
                    target.evidences(),
                    target.processSummary() + " → 비교 기준 연도(%d년) 계산 불가".formatted(parsed.baseYear()),
                    "%d년 %s%s 확인했지만 %d년 %s%s 계산할 자료를 찾지 못했습니다".formatted(
                            parsed.targetYear(), ratio.label(),
                            KoreanParticle.object(ratio.label()),
                            parsed.baseYear(), ratio.label(),
                            KoreanParticle.object(ratio.label())));
        }
        RatioValue baseValue = base.get();
        Optional<RatioValue> targetValue = ratioAt(parsed, parsed.corpName(), parsed.targetYear());
        if (targetValue.isEmpty()) {
            return target;
        }
        BigDecimal difference = targetValue.get().percent().subtract(baseValue.percent());

        StringBuilder summary = new StringBuilder(target.processSummary())
                .append(" → %d년 %s=%s/%s×100=%s%%".formatted(
                        parsed.baseYear(), ratio.label(),
                        new BigDecimal(baseValue.numerator().getNormalizedValue()).toPlainString(),
                        new BigDecimal(baseValue.denominator().getNormalizedValue()).toPlainString(),
                        baseValue.percent().toPlainString()));
        if (baseValue.document() != document) {
            summary.append("(%d년은 '%s'(%s)에서 확인)".formatted(
                    parsed.baseYear(), baseValue.document().getReportNm(),
                    baseValue.document().getRceptDt()));
        }
        summary.append(" → 증감=%s%%p(%s-%s)".formatted(
                difference.toPlainString(),
                targetValue.get().percent().toPlainString(),
                baseValue.percent().toPlainString()));

        List<RetrievedEvidence> evidences = new ArrayList<>(target.evidences());
        addEvidence(evidences, baseValue.document(), baseValue.numerator());
        addEvidence(evidences, baseValue.document(), baseValue.denominator());
        return AnswerHandlerResult.of(evidences, summary.toString());
    }

    private Optional<DisclosureDocumentEntity> selectDocument(FinancialQuestion parsed) {
        return selectDocument(parsed, parsed.corpName());
    }

    private Optional<DisclosureDocumentEntity> selectDocument(
            FinancialQuestion parsed, String corpName) {
        return selectDocument(parsed, corpName, parsed.targetYear());
    }

    private Optional<DisclosureDocumentEntity> selectDocument(
            FinancialQuestion parsed, String corpName, Integer year) {
        List<DisclosureDocumentEntity> documents =
                documentRepository.findByCorpNameAndDocGroupAndBaseYearOrderByRceptDtDescRceptNoDesc(
                        corpName, DocGroup.PERIODIC, year);
        if (!documents.isEmpty()) {
            // 연간 수치를 묻는 질문이므로 사업보고서를 우선하고, 같은 조건이면 최신 접수분을 쓴다.
            return documents.stream()
                    .min(Comparator.comparingInt(
                            document -> "annual".equals(document.getDocSubtype()) ? 0 : 1));
        }
        return reportContainingYear(parsed, corpName, year);
    }

    /**
     * 그 해가 기준연도인 보고서가 없을 때, 그 해 수치를 담은 보고서를 찾는다.
     *
     * <p>요약재무정보 표는 한 번에 세 기간을 담는다. 코퍼스 접수가 2023-01-01부터라
     * {@code base_year}는 2023년부터지만, 2023년 사업보고서에 2022·2021년 값이 함께 실려 있다.
     * 기준연도만 보면 값이 있는데도 「정기공시가 없습니다」가 나간다.
     *
     * <p>질문 연도에 가까운 보고서를 먼저 본다. 뒤에 나온 보고서일수록 그 해 수치가 다시
     * 작성됐을 수 있어, 그 해를 담은 보고서 중 가장 이른 것이 원래 공시된 값에 가깝다.
     */
    private Optional<DisclosureDocumentEntity> reportContainingYear(
            FinancialQuestion parsed, String corpName) {
        return reportContainingYear(parsed, corpName, parsed.targetYear());
    }

    private Optional<DisclosureDocumentEntity> reportContainingYear(
            FinancialQuestion parsed, String corpName, Integer year) {
        if (year == null) {
            return Optional.empty();
        }
        return documentRepository
                .findByCorpNameAndDocGroupOrderByRceptDtDescRceptNoDesc(corpName, DocGroup.PERIODIC)
                .stream()
                .sorted(Comparator
                        .comparingInt((DisclosureDocumentEntity document) ->
                                document.getBaseYear() == null ? Integer.MAX_VALUE : document.getBaseYear())
                        .thenComparingInt(document ->
                                "annual".equals(document.getDocSubtype()) ? 0 : 1))
                .filter(document -> containsYear(document, parsed, year))
                .findFirst();
    }

    /** 그 보고서에 물어본 해의 연간 수치가 실제로 있는지. 조회는 후보마다 한 번씩만 한다. */
    private boolean containsYear(
            DisclosureDocumentEntity document, FinancialQuestion parsed, Integer year) {
        return usableFactOfYear(
                factRepository.findForAnswer(document.getDocId(), parsed.factKey(), parsed.scope()),
                year).isPresent();
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
        appendCounterpart(summary, evidences, parsed, document, document);
    }

    private void appendCounterpart(
            StringBuilder summary,
            List<RetrievedEvidence> evidences,
            FinancialQuestion parsed,
            DisclosureDocumentEntity document,
            DisclosureDocumentEntity baseDocument
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
        // 기준 연도 수치가 다른 보고서에서 나왔으면 참고 기준도 그 보고서에서 찾는다.
        List<ExtractedFactEntity> baseFacts = baseDocument == document
                ? facts
                : factRepository.findForAnswer(baseDocument.getDocId(), parsed.factKey(), counterpart);
        Optional<ExtractedFactEntity> counterpartBase = usableFactOfYear(baseFacts, parsed.baseYear());
        if (counterpartBase.isEmpty()) {
            summary.append(", %d년 수치가 없어 %s기준 증감은 계산하지 않음".formatted(parsed.baseYear(), scopeName));
            return;
        }
        ExtractedFactEntity baseFact = counterpartBase.get();
        summary.append(", %d년 %s".formatted(
                parsed.baseYear(), KoreanAmountFormatter.format(baseFact.getNormalizedValue())));
        addEvidence(evidences, baseDocument, baseFact);
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

    /**
     * {@code 최근 매출액은?}처럼 연도를 적지 않고 최신을 묻는 질문의 연도를 정한다.
     *
     * <p>그 기업의 가장 최근 사업보고서 기준연도로 본다. 연도를 적은 질문은 그대로 둔다.
     * 기업을 못 찾았으면 정할 근거가 없어 그대로 둔다.
     */
    private FinancialQuestion latestYearIfAsked(FinancialQuestion parsed, String question) {
        if (parsed.targetYear() != null || parsed.corpName() == null
                || !LATEST.matcher(question).find()) {
            return parsed;
        }
        return documentRepository
                .findByCorpNameAndDocGroupOrderByRceptDtDescRceptNoDesc(
                        parsed.corpName(), DocGroup.PERIODIC)
                .stream()
                .filter(document -> "annual".equals(document.getDocSubtype()))
                .map(DisclosureDocumentEntity::getBaseYear)
                .filter(Objects::nonNull)
                .max(Comparator.naturalOrder())
                .map(parsed::withTargetYear)
                .orElse(parsed);
    }

    private FinancialQuestion parse(String question) {
        return FinancialQuestionParser.parse(
                question, CorpNameResolver.of(corpIdentityRepository.findByDocGroup(DocGroup.PERIODIC)));
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
