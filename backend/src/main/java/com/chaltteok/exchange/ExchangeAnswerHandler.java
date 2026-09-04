package com.chaltteok.exchange;

import com.chaltteok.answer.AnswerHandler;
import com.chaltteok.answer.CorpNameResolver;
import com.chaltteok.answer.DisclosureTypeMarkers;
import com.chaltteok.disclosure.persistence.repository.CorpIdentityRepository;
import com.chaltteok.answer.AnswerHandlerResult;
import com.chaltteok.answer.KoreanAmountFormatter;
import com.chaltteok.answer.KoreanParticle;
import com.chaltteok.answer.RetrievedEvidence;
import com.chaltteok.disclosure.model.enums.DocGroup;
import com.chaltteok.disclosure.model.enums.ExtractionStatus;
import com.chaltteok.disclosure.persistence.entity.DisclosureDocumentEntity;
import com.chaltteok.disclosure.persistence.entity.DisclosureRelationEntity;
import com.chaltteok.disclosure.persistence.entity.ExtractedFactEntity;
import com.chaltteok.disclosure.persistence.repository.DisclosureDocumentRepository;
import com.chaltteok.disclosure.persistence.repository.DisclosureRelationRepository;
import com.chaltteok.disclosure.persistence.repository.ExtractedFactRepository;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * 거래소공시 계약·투자 수치 조회와 최댓값 계산을 담당한다.
 *
 * <p>거래소공시는 사건 공시다. 문서 한 건이 계약 한 건이라 기준연도가 없고, 한 기업이 같은
 * 서식을 수백 건 낸다(대우건설 104건). 그래서 정기공시처럼 문서를 한 건으로 좁힐 수 없고,
 * 질문의 공시일·연도로 후보를 줄인 뒤 남은 문서를 훑는다.
 *
 * <p>대표 질문: 삼성전자가 2025년 7월 28일 공시한 단일판매ㆍ공급계약의 계약금액은 얼마인가?
 *
 * <p>후보가 여러 건이고 값이 서로 다르면 하나를 고르지 않고 답변 불가로 둔다. 어느 계약을
 * 묻는지 모르는 채 최신 건을 답하면 틀린 계약의 금액을 확신 있게 말하게 된다.
 *
 * <p>투자판단관련주요경영사항 300건은 자유 서술이라 사실이 없다. 근거 chunk로만 적재되므로
 * 이 Handler가 다루지 않는다.
 */
@Component
@Order(ExchangeAnswerHandler.HANDLER_ORDER)
@ConditionalOnProperty(name = "chaltteok.disclosure.persistence.enabled", havingValue = "true")
public class ExchangeAnswerHandler implements AnswerHandler {

    /**
     * 정기공시 Handler보다 먼저 본다.
     *
     * <p>"공급계약금액이 최근 매출액 대비 몇 %인가" 같은 질문은 '매출액'을 품고 있어
     * 정기공시 Handler도 처리하겠다고 답한다. 등록 순서에 답이 좌우되지 않도록,
     * 서식을 특정하는 Handler를 앞에 둔다.
     */
    static final int HANDLER_ORDER = 10;

    /** 서식 갈래 → report_nm 앞부분. 전처리와 같은 기준으로 서식을 가린다. */
    private static final Map<ExchangeTopic, String> FORM_NAMES = Map.of(
            ExchangeTopic.SUPPLY_CONTRACT, "단일판매ㆍ공급계약체결",
            ExchangeTopic.SUPPLY_TERMINATION, "단일판매ㆍ공급계약해지",
            ExchangeTopic.FACILITY_INVESTMENT, "신규시설투자등");

    private static final String CORRECTION_PREFIX = "[기재정정]";

    /**
     * 어느 계약·투자인지 밝히는 항목.
     *
     * <p>공시일 없이 최신 한 건으로 답할 때, 값만 내면 어느 계약인지 알 수 없다.
     * 공시일과 함께 이 항목을 근거에 남긴다.
     */
    private static final Map<ExchangeTopic, String> SUBJECT_FACT_KEYS = Map.of(
            ExchangeTopic.SUPPLY_CONTRACT, "contract_counterparty",
            ExchangeTopic.SUPPLY_TERMINATION, "termination_counterparty",
            ExchangeTopic.FACILITY_INVESTMENT, "investment_target");

    /** 정정본이 가리키는 원본의 접수일. 이 값이 그 계약이 처음 공시된 날이다. */
    private static final String TARGET_FILING_DATE_KEY = "correction_target_filing_date";

    private final DisclosureDocumentRepository documentRepository;
    private final ExtractedFactRepository factRepository;
    private final DisclosureRelationRepository relationRepository;
    private final CorpIdentityRepository corpIdentityRepository;

    public ExchangeAnswerHandler(
            DisclosureDocumentRepository documentRepository,
            ExtractedFactRepository factRepository,
            DisclosureRelationRepository relationRepository,
            CorpIdentityRepository corpIdentityRepository
    ) {
        this.documentRepository = documentRepository;
        this.factRepository = factRepository;
        this.relationRepository = relationRepository;
        this.corpIdentityRepository = corpIdentityRepository;
    }

    @Override
    @Transactional(readOnly = true)
    public boolean supports(String question) {
        // 다른 유형을 못 박은 질문에서 물러난다. 주제 키워드에 `시설투자`·`투자금액`이 있어서
        // `유상증자로 조달한 자금 중 시설투자에 쓰는 금액`을 순위만으로 가져가고 있었다.
        if (DisclosureTypeMarkers.mentionsAnyOf(question,
                DisclosureTypeMarkers.FUNDRAISING,
                DisclosureTypeMarkers.TREASURY,
                DisclosureTypeMarkers.OWNERSHIP)) {
            return false;
        }
        ExchangeQuestion parsed = parse(question);
        return parsed.corpName() != null && parsed.topic() != null;
    }

    @Override
    @Transactional(readOnly = true)
    public AnswerHandlerResult handle(String questionId, String question) {
        ExchangeQuestion parsed = parse(question);
        if (!parsed.isIdentified()) {
            return AnswerHandlerResult.unavailable(
                    "질문에서 기업·공시 종류·항목을 식별하는 중", missingPartsOf(parsed));
        }

        String formName = FORM_NAMES.get(parsed.topic());
        String summaryPrefix = "%s·%s·%s 식별".formatted(
                parsed.corpName(), formName, parsed.itemLabel());

        List<DisclosureDocumentEntity> documents = selectDocuments(parsed, formName);
        if (!parsed.history()) {
            // 이력추적은 바뀐 과정을 묻는 것이라 정정된 문서도 함께 봐야 한다.
            documents = withoutSuperseded(documents);
        }
        if (documents.isEmpty()) {
            return AnswerHandlerResult.unavailable(
                    summaryPrefix + " → 조건에 맞는 거래소공시를 찾지 못함",
                    "제공된 공시 데이터에 %s의 %s%s가 없습니다".formatted(
                            parsed.corpName(), periodText(parsed), formName));
        }

        List<ExtractedFactEntity> facts = factRepository.findForAnswerByDocIds(
                documents.stream().map(DisclosureDocumentEntity::getDocId).toList(),
                parsed.factKey());
        String searchSummary = summaryPrefix + " → 공시 %d건에서 '%s' 검색".formatted(
                documents.size(), parsed.itemLabel());

        List<ExtractedFactEntity> usable = facts.stream()
                .filter(fact -> fact.getNormalizedValue() != null
                        || fact.getExtractionStatus() == ExtractionStatus.OK)
                .toList();
        if (usable.isEmpty()) {
            return AnswerHandlerResult.unavailable(
                    searchSummary + " → 해당 항목의 값 없음",
                    "찾은 %s 공시에 %s가 기재되어 있지 않습니다".formatted(formName, parsed.itemLabel()));
        }

        if (parsed.history()) {
            return history(parsed, usable, searchSummary);
        }
        return parsed.largest()
                ? largest(parsed, usable, searchSummary)
                : single(parsed, usable, searchSummary);
    }

    /**
     * 값이 언제 얼마에서 얼마로 바뀌었는지 답한다.
     *
     * <p>정정 사슬은 전처리가 만든 관계를 따라간다. 사건 키는 사슬의 맨 처음 원본이라,
     * 정정의 정정도 한 사건으로 묶인다. 현대건설 20230626800002는 6조 5,544억으로
     * 시작해 14번 정정되며 3조 900억이 됐다.
     */
    private AnswerHandlerResult history(
            ExchangeQuestion parsed, List<ExtractedFactEntity> facts, String searchSummary) {
        List<String> docIds = facts.stream()
                .map(fact -> fact.getDocument().getDocId())
                .distinct()
                .toList();
        List<DisclosureRelationEntity> relations = relationRepository.findByDocIds(docIds);
        if (relations.isEmpty()) {
            return AnswerHandlerResult.unavailable(
                    partialEvidences(facts),
                    searchSummary + " → 정정 이력 없음",
                    "찾은 %s 공시에 정정 이력이 없습니다".formatted(FORM_NAMES.get(parsed.topic())));
        }

        // 후보에 걸린 사건 중 정정이 가장 많은 사슬을 고른다.
        String eventKey = relations.stream()
                .collect(Collectors.groupingBy(
                        DisclosureRelationEntity::getEventKey, Collectors.counting()))
                .entrySet().stream()
                .max(Map.Entry.comparingByValue())
                .orElseThrow()
                .getKey();
        List<DisclosureRelationEntity> chain = relationRepository.findChainByEventKey(eventKey);
        if (chain.isEmpty()) {
            return AnswerHandlerResult.unavailable(
                    partialEvidences(facts),
                    searchSummary + " → 정정 사슬을 읽지 못함",
                    "정정 이력을 확인할 수 없습니다");
        }

        return chainAnswer(parsed, chain, searchSummary);
    }

    private AnswerHandlerResult chainAnswer(
            ExchangeQuestion parsed,
            List<DisclosureRelationEntity> chain,
            String searchSummary) {
        DisclosureDocumentEntity root = chain.get(0).getOriginalDocument();
        List<String> chainIds = new ArrayList<>();
        chainIds.add(root.getDocId());
        chain.forEach(relation -> chainIds.add(relation.getRelatedDocument().getDocId()));

        Map<String, ExtractedFactEntity> byDoc = new LinkedHashMap<>();
        for (ExtractedFactEntity fact
                : factRepository.findForAnswerByDocIds(chainIds, parsed.factKey())) {
            byDoc.put(fact.getDocument().getDocId(), fact);
        }

        List<RetrievedEvidence> evidences = new ArrayList<>();
        StringBuilder steps = new StringBuilder();
        for (String docId : chainIds) {
            ExtractedFactEntity fact = byDoc.get(docId);
            if (fact == null) {
                continue;
            }
            if (!steps.isEmpty()) {
                steps.append(" → ");
            }
            steps.append("%s %s".formatted(fact.getDocument().getRceptDt(), display(fact)));
            RetrievedEvidence evidence = evidenceOf(fact);
            if (!evidences.contains(evidence)) {
                evidences.add(evidence);
            }
        }
        if (evidences.isEmpty()) {
            return AnswerHandlerResult.unavailable(
                    searchSummary + " → 사슬에서 해당 항목의 값을 찾지 못함",
                    "정정 이력은 있으나 %s%s 비교할 수 없습니다".formatted(
                            parsed.itemLabel(), KoreanParticle.object(parsed.itemLabel())));
        }

        String summary = searchSummary
                + " → '%s'(%s) 최초 공시부터 정정 %d회: %s".formatted(
                        root.getReportNm(), root.getRceptDt(), chain.size(), steps);
        return AnswerHandlerResult.of(evidences, summary);
    }

    /**
     * 후보 문서를 고른다.
     *
     * <p>공시일이 있으면 그 날짜로 좁히고, 같은 날 정정본이 있으면 원본은 대체된 것으로 본다.
     * 연도만 있으면 그 해 전체를 둔다. 둘 다 없으면 기업의 전체 공시를 훑는다.
     */
    private List<DisclosureDocumentEntity> selectDocuments(
            ExchangeQuestion parsed, String formName) {
        // 공시일을 지정해도 그 날짜만 읽지 않는다. 그 계약의 정정본은 나중에 접수되므로
        // 접수일로 좁히면 정정된 값을 놓친다.
        List<DisclosureDocumentEntity> documents =
                documentRepository.findByCorpNameAndDocGroupOrderByRceptDtDescRceptNoDesc(
                        parsed.corpName(), DocGroup.EXCHANGE);

        List<DisclosureDocumentEntity> matched = documents.stream()
                .filter(document -> matchesForm(document, formName))
                .filter(document -> parsed.year() == null
                        || document.getRceptDt().getYear() == parsed.year())
                .toList();
        if (parsed.reportDate() == null) {
            return matched;
        }

        List<DisclosureDocumentEntity> onDate = onlyFiledOn(matched, parsed.reportDate());
        return onDate.isEmpty()
                ? onlyFiledOn(matched, originalFilingDateOf(matched, parsed.reportDate()))
                : onDate;
    }

    /**
     * 접수일로 부르는 길. 그 날 접수된 문서가 고친 계약의 원공시일을 돌려준다.
     *
     * <p>정정본은 {@link #onlyFiledOn}이 원공시일로 옮겨 찾으므로, 자기 접수일로는 어떤
     * 질문도 닿지 않는다. 삼성전자 {@code 20250731800028}은 2025-07-31에 접수됐지만
     * 7월 28일 계약의 정정본이라 「7월 31일 공시」로 물으면 아무것도 나오지 않았다.
     *
     * <p>접수일 후보를 그대로 쓰지 않고 원공시일로 되돌리는 이유는, 그 정정본이 나중에 또
     * 정정됐을 때 낡은 값을 답하지 않기 위해서다. 원공시일로 기존 경로를 다시 타면 그 계약의
     * 최신 정정본이 그대로 뽑힌다.
     *
     * <p>한 날짜에 서로 다른 계약의 정정본이 섞이면 원공시일이 갈린다. 그때는 고르지 않고
     * {@code null}을 돌려 답변 불가로 둔다.
     */
    private LocalDate originalFilingDateOf(
            List<DisclosureDocumentEntity> documents, LocalDate reportDate) {
        List<DisclosureDocumentEntity> filedOnDate = documents.stream()
                .filter(document -> reportDate.equals(document.getRceptDt()))
                .toList();
        if (filedOnDate.isEmpty()) {
            return null;
        }
        Map<String, LocalDate> targetDates = targetFilingDates(filedOnDate);
        Set<LocalDate> originals = filedOnDate.stream()
                .map(document -> targetDates.getOrDefault(document.getDocId(), document.getRceptDt()))
                .collect(Collectors.toSet());
        return originals.size() == 1 ? originals.iterator().next() : null;
    }

    /**
     * 그날 공시된 계약만 남긴다.
     *
     * <p>정정본은 그날 새로 맺은 계약이 아니라 **앞선 공시를 고친 것**이다. 대우건설
     * 2023-06-30에는 새 계약(4,001억)과 2023-03-10 계약의 정정본(1조 463억)이 함께 있다.
     * 접수일만 보면 서로 다른 계약이 한 질문의 후보로 섞인다.
     */
    private List<DisclosureDocumentEntity> onlyFiledOn(
            List<DisclosureDocumentEntity> documents, LocalDate reportDate) {
        if (documents.isEmpty() || reportDate == null) {
            return List.of();
        }
        Map<String, LocalDate> targetDates = targetFilingDates(documents);
        return documents.stream()
                .filter(document -> reportDate.equals(
                        targetDates.getOrDefault(document.getDocId(), document.getRceptDt())))
                .toList();
    }

    /** 정정본마다 그 계약이 처음 공시된 날. 정정본이 아니면 값이 없다. */
    private Map<String, LocalDate> targetFilingDates(List<DisclosureDocumentEntity> documents) {
        List<String> correctionIds = documents.stream()
                .filter(DisclosureDocumentEntity::isCorrection)
                .map(DisclosureDocumentEntity::getDocId)
                .toList();
        if (correctionIds.isEmpty()) {
            return Map.of();
        }
        Map<String, LocalDate> dates = new LinkedHashMap<>();
        for (ExtractedFactEntity fact
                : factRepository.findForAnswerByDocIds(correctionIds, TARGET_FILING_DATE_KEY)) {
            if (fact.getNormalizedValue() != null) {
                dates.put(fact.getDocument().getDocId(), LocalDate.parse(fact.getNormalizedValue()));
            }
        }
        return dates;
    }

    /**
     * 뒤에 정정된 문서를 뺀다.
     *
     * <p>정정 관계는 전처리가 `정정관련 공시서류제출일`로 확정해 둔 것이라
     * 같은 날이라는 이유로 묶지 않는다.
     */
    private List<DisclosureDocumentEntity> withoutSuperseded(
            List<DisclosureDocumentEntity> documents) {
        if (documents.size() < 2) {
            return documents;
        }
        List<String> docIds = documents.stream()
                .map(DisclosureDocumentEntity::getDocId)
                .toList();
        Set<String> superseded = relationRepository.findByDocIds(docIds).stream()
                .filter(relation -> docIds.contains(relation.getRelatedDocument().getDocId()))
                .map(relation -> relation.getOriginalDocument().getDocId())
                .collect(Collectors.toSet());
        return superseded.isEmpty()
                ? documents
                : documents.stream()
                        .filter(document -> !superseded.contains(document.getDocId()))
                        .toList();
    }

    /**
     * report_nm이 이 서식인지 본다.
     *
     * <p>거래소공시는 {@code doc_subtype}이 비어 있어 {@code report_nm}으로 가린다.
     * 절반가량에 {@code [기재정정]} 접두가 붙고 괄호 부제가 따라붙어, 접두를 떼고 앞부분만 본다.
     */
    private static boolean matchesForm(DisclosureDocumentEntity document, String formName) {
        String reportNm = document.getReportNm();
        if (reportNm == null) {
            return false;
        }
        String base = reportNm.startsWith(CORRECTION_PREFIX)
                ? reportNm.substring(CORRECTION_PREFIX.length())
                : reportNm;
        return base.startsWith(formName);
    }

    /** 최댓값을 묻는 질문. 금액이 아닌 항목은 크기를 비교하지 않는다. */
    private AnswerHandlerResult largest(
            ExchangeQuestion parsed, List<ExtractedFactEntity> facts, String searchSummary) {
        List<ExtractedFactEntity> comparable = facts.stream()
                .filter(fact -> fact.getNormalizedValue() != null)
                .toList();
        if (comparable.isEmpty()) {
            return AnswerHandlerResult.unavailable(
                    searchSummary + " → 비교할 수 있는 수치 없음",
                    "%s를 크기로 비교할 수 있는 값이 없습니다".formatted(parsed.itemLabel()));
        }

        ExtractedFactEntity top = comparable.stream()
                .max(Comparator
                        .comparing((ExtractedFactEntity fact) -> new BigDecimal(fact.getNormalizedValue()))
                        // 정정본과 원본이 같은 금액이면 나중 공시를 근거로 든다.
                        .thenComparing(fact -> fact.getDocument().getRceptDt()))
                .orElseThrow();

        String summary = searchSummary + " → %d건 중 최대 %s(%s, %s) 확인".formatted(
                comparable.size(),
                display(top),
                top.getDocument().getRceptDt(),
                top.getDocument().getReportNm());
        return AnswerHandlerResult.of(List.of(evidenceOf(top)), summary);
    }

    /** 한 건을 묻는 질문. 후보가 여러 건이면서 값이 다르면 고르지 않는다. */
    private AnswerHandlerResult single(
            ExchangeQuestion parsed, List<ExtractedFactEntity> facts, String searchSummary) {
        Set<String> distinctValues = facts.stream()
                .map(ExchangeAnswerHandler::comparableValue)
                .collect(Collectors.toCollection(LinkedHashSet::new));

        if (distinctValues.size() > 1) {
            return parsed.reportDate() == null
                    ? latest(parsed, facts, searchSummary)
                    : ambiguous(parsed, facts, searchSummary);
        }
        return answerWith(parsed, facts.get(0), facts.size(), searchSummary);
    }

    /**
     * 공시일을 지정하지 않은 질문은 최신 한 건으로 답한다.
     *
     * <p>어느 계약인지 밝히지 않고 값만 내면 틀린 계약의 금액을 확신 있게 말하게 되므로,
     * 공시일과 계약상대를 근거에 함께 남겨 어느 계약을 답한 것인지 드러낸다.
     */
    private AnswerHandlerResult latest(
            ExchangeQuestion parsed, List<ExtractedFactEntity> facts, String searchSummary) {
        ExtractedFactEntity newest = facts.stream()
                .max(Comparator
                        .comparing((ExtractedFactEntity fact) -> fact.getDocument().getRceptDt())
                        .thenComparing(fact -> fact.getDocument().getRceptNo()))
                .orElseThrow();
        return answerWith(parsed, newest, facts.size(), searchSummary);
    }

    /**
     * 공시일을 지정했는데도 값이 갈리면 답하지 않는다.
     *
     * <p>그날 서로 다른 계약이 여러 건 접수된 경우다. 질문이 이미 날짜로 좁혔는데도
     * 하나로 줄지 않으므로, 더 좁힐 정보를 물어야 한다.
     */
    private AnswerHandlerResult ambiguous(
            ExchangeQuestion parsed, List<ExtractedFactEntity> facts, String searchSummary) {
        String item = parsed.itemLabel();
        String formName = FORM_NAMES.get(parsed.topic());
        return AnswerHandlerResult.unavailable(
                partialEvidences(facts),
                searchSummary + " → %s 공시 %d건의 %s%s 서로 달라 한 건으로 좁히지 못함".formatted(
                        parsed.reportDate(), facts.size(), item, KoreanParticle.subject(item)),
                "%s%s %s에 공시한 %s%s 여러 건이고 %s%s 서로 달라 어느 계약인지 특정할 수 없습니다"
                        .formatted(parsed.corpName(), KoreanParticle.subject(parsed.corpName()),
                                parsed.reportDate(),
                                formName, KoreanParticle.subject(formName),
                                item, KoreanParticle.subject(item)));
    }

    /** 고른 한 건으로 답한다. 어느 계약인지 밝히는 항목이 있으면 근거에 함께 남긴다. */
    private AnswerHandlerResult answerWith(
            ExchangeQuestion parsed,
            ExtractedFactEntity fact,
            int candidateCount,
            String searchSummary) {
        DisclosureDocumentEntity document = fact.getDocument();
        List<RetrievedEvidence> evidences = new ArrayList<>();
        evidences.add(evidenceOf(fact));

        Optional<ExtractedFactEntity> subject = subjectFact(parsed, document.getDocId());
        subject.map(ExchangeAnswerHandler::evidenceOf)
                .filter(evidence -> !evidences.contains(evidence))
                .ifPresent(evidences::add);

        StringBuilder summary = new StringBuilder(searchSummary);
        if (candidateCount > 1) {
            summary.append(" → 후보 %d건 중 최신 공시 사용".formatted(candidateCount));
        }
        summary.append(" → '%s'(%s)".formatted(document.getReportNm(), document.getRceptDt()));
        subject.ifPresent(item ->
                summary.append(", %s %s".formatted(item.getLabel(), item.getRawValue())));
        summary.append("에서 %s %s 확인".formatted(parsed.itemLabel(), display(fact)));
        return AnswerHandlerResult.of(evidences, summary.toString());
    }

    /** 어느 계약인지 밝히는 항목. 서식에 없거나 값이 비면 비어 있다. */
    private Optional<ExtractedFactEntity> subjectFact(ExchangeQuestion parsed, String docId) {
        String subjectKey = SUBJECT_FACT_KEYS.get(parsed.topic());
        if (subjectKey == null || subjectKey.equals(parsed.factKey())) {
            return Optional.empty();
        }
        return factRepository.findForAnswerByDocIds(List.of(docId), subjectKey).stream().findFirst();
    }

    /** 정규화 값이 없는 텍스트 항목은 원문 값으로 비교한다. */
    private static String comparableValue(ExtractedFactEntity fact) {
        return fact.getNormalizedValue() != null ? fact.getNormalizedValue() : fact.getRawValue();
    }

    /**
     * 사람이 읽는 표기. 금액만 조·억으로 바꾸고 비율·텍스트는 원문 그대로 둔다.
     *
     * <p>7.6을 '7원'으로 읽히게 하지 않기 위해 단위를 보고 갈라야 한다.
     */
    private static String display(ExtractedFactEntity fact) {
        if (fact.getNormalizedValue() == null) {
            return fact.getRawValue();
        }
        if ("KRW".equals(fact.getNormalizedUnit())) {
            return KoreanAmountFormatter.format(fact.getNormalizedValue());
        }
        String unit = fact.getNormalizedUnit();
        return unit == null ? fact.getRawValue() : fact.getRawValue() + unit;
    }

    private static List<RetrievedEvidence> partialEvidences(List<ExtractedFactEntity> facts) {
        List<RetrievedEvidence> evidences = new ArrayList<>();
        for (ExtractedFactEntity fact : facts) {
            RetrievedEvidence evidence = evidenceOf(fact);
            if (!evidences.contains(evidence)) {
                evidences.add(evidence);
            }
        }
        return evidences;
    }

    private static RetrievedEvidence evidenceOf(ExtractedFactEntity fact) {
        DisclosureDocumentEntity document = fact.getDocument();
        return new RetrievedEvidence(
                document.getReportNm(),
                document.getRceptDt(),
                document.getCorpName(),
                fact.getEvidenceChunk().getText());
    }

    private static String periodText(ExchangeQuestion parsed) {
        if (parsed.reportDate() != null) {
            return parsed.reportDate() + " 공시 ";
        }
        return parsed.year() == null ? "" : parsed.year() + "년 ";
    }

    private ExchangeQuestion parse(String question) {
        return ExchangeQuestionParser.parse(
                question, CorpNameResolver.of(corpIdentityRepository.findByDocGroup(DocGroup.EXCHANGE)));
    }

    private static String missingPartsOf(ExchangeQuestion parsed) {
        List<String> missing = new ArrayList<>();
        if (parsed.corpName() == null) {
            missing.add("기업");
        }
        if (parsed.topic() == null) {
            missing.add("공시 종류");
        }
        if (parsed.factKey() == null) {
            missing.add("항목");
        }
        String parts = String.join("·", missing);
        return "질문에서 %s%s 식별하지 못했습니다".formatted(parts, KoreanParticle.object(parts));
    }
}
