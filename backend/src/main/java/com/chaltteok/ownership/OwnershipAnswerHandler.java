package com.chaltteok.ownership;

import com.chaltteok.answer.AnswerHandler;
import com.chaltteok.answer.AnswerHandlerResult;
import com.chaltteok.answer.CorpNameResolver;
import com.chaltteok.answer.QuestionReportDate;
import com.chaltteok.answer.RetrievedEvidence;
import com.chaltteok.answer.SingleReportDateDocuments;
import com.chaltteok.disclosure.model.enums.DocGroup;
import com.chaltteok.disclosure.persistence.entity.DisclosureDocumentEntity;
import com.chaltteok.disclosure.persistence.entity.ExtractedFactEntity;
import com.chaltteok.disclosure.persistence.repository.CorpIdentityRepository;
import com.chaltteok.disclosure.persistence.repository.DisclosureDocumentRepository;
import com.chaltteok.disclosure.persistence.repository.ExtractedFactRepository;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

/**
 * 대량보유상황보고서 질문의 검색·근거 조립.
 *
 * <p>대표 질문: 삼성전자가 2025년 1월 15일 공시한 대량보유상황보고서의 지분율은 얼마인가?
 *
 * <p>공시일(한글·ISO·점 표기)만 있으면 그날 문서를 특정하고, 같은 날 정정본이 있으면 정정본만 남긴다.
 * {@code 어떻게 바뀌었나}처럼 과정을 물으면 같은 보고자의 신규·변동·변경을 시간순으로 잇는다.
 * 보유목적을 물으면 목적 문장이 바뀐 단계를 남긴다.
 */
@Component
@Order(OwnershipAnswerHandler.HANDLER_ORDER)
@ConditionalOnProperty(name = "chaltteok.disclosure.persistence.enabled", havingValue = "true")
public class OwnershipAnswerHandler implements AnswerHandler {

    static final int HANDLER_ORDER = 25;
    static final String DOC_SUBTYPE = "대량보유상황보고서";
    private static final List<String> DEFAULT_FACT_KEYS = List.of(
            OwnershipQuestionParser.HOLDER_FACT_KEY,
            OwnershipQuestionParser.SHARES_FACT_KEY,
            OwnershipQuestionParser.RATIO_FACT_KEY
    );

    private final DisclosureDocumentRepository documentRepository;
    private final ExtractedFactRepository factRepository;
    private final CorpIdentityRepository corpIdentityRepository;

    public OwnershipAnswerHandler(
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
        if (question == null || !looksLikeHolding(question)) {
            return false;
        }
        return parse(question).corpName() != null;
    }

    @Override
    public AnswerHandlerResult handle(String questionId, String question) {
        OwnershipQuestion parsed = parse(question);
        if (parsed.corpName() == null) {
            return AnswerHandlerResult.unavailable(
                    "지분공시 질문으로 분류",
                    "질문에서 검색할 기업명을 확정하지 못했습니다"
            );
        }
        LocalDate reportDate = QuestionReportDate.parse(question);
        if (parsed.history()) {
            return handleHistory(parsed, reportDate);
        }

        if (reportDate == null) {
            return AnswerHandlerResult.unavailable(
                    "기업명=" + parsed.corpName() + ", 공시유형=holding, 서식=" + DOC_SUBTYPE + " 검색",
                    "질문에서 공시일을 읽지 못해 대량보유 공시를 특정하지 못했습니다"
            );
        }

        String searchSummary = "기업명=" + parsed.corpName()
                + ", 공시유형=holding, 서식=" + DOC_SUBTYPE;
        List<DisclosureDocumentEntity> documents;
        if (reportDate != null) {
            searchSummary += ", 공시일=" + reportDate + " 검색";
            documents = documentRepository.findByCorpNameAndDocGroupAndDocSubtypeAndRceptDt(
                    parsed.corpName(),
                    DocGroup.HOLDING,
                    DOC_SUBTYPE,
                    reportDate
            );
            if (documents.isEmpty()) {
                documents = documentRepository.findByCorpNameAndDocGroupAndRceptDtOrderByRceptNoDesc(
                        parsed.corpName(), DocGroup.HOLDING, reportDate);
            }
            if (documents.isEmpty()) {
                return AnswerHandlerResult.unavailable(
                        searchSummary,
                        "해당 기업의 " + reportDate + " 대량보유 공시를 찾지 못했습니다"
                );
            }
        } else if (QuestionReportDate.mentionsDate(question)) {
            return AnswerHandlerResult.unavailable(
                    searchSummary + " 검색",
                    "질문에서 공시일을 읽지 못해 대량보유 공시를 특정하지 못했습니다"
            );
        } else {
            searchSummary += ", 공시일 없이 단건 검색";
            List<DisclosureDocumentEntity> all =
                    documentRepository.findByCorpNameAndDocGroupAndDocSubtype(
                            parsed.corpName(), DocGroup.HOLDING, DOC_SUBTYPE);
            if (all.isEmpty()) {
                return AnswerHandlerResult.unavailable(
                        searchSummary,
                        "해당 기업의 대량보유 공시를 찾지 못했습니다"
                );
            }
            documents = SingleReportDateDocuments.uniqueDay(all);
            if (documents.isEmpty()) {
                return AnswerHandlerResult.unavailable(
                        searchSummary + " → 공시일 " + all.size() + "건",
                        "여러 차례 대량보유 공시가 있어 공시일을 특정하지 못했습니다"
                );
            }
        }
        List<Candidate> candidates = candidatesOf(preferCorrections(documents), parsed);
        if (candidates.isEmpty()) {
            return AnswerHandlerResult.unavailable(
                    searchSummary + " → 문서 " + documents.size() + "건",
                    "검색된 공시에서 " + parsed.itemLabel() + " 값을 찾지 못했습니다"
            );
        }
        Set<String> distinct = candidates.stream()
                .map(Candidate::valueKey)
                .collect(Collectors.toCollection(LinkedHashSet::new));
        if (distinct.size() > 1) {
            return AnswerHandlerResult.unavailable(
                    searchSummary + " → 문서 " + candidates.size() + "건",
                    "같은 공시일에 " + parsed.itemLabel() + " 값이 서로 다른 문서가 있어 한 건으로 특정하지 못했습니다"
            );
        }
        Candidate selected = candidates.stream()
                .min(Comparator.comparing(
                        candidate -> candidate.document().getRceptNo(),
                        Comparator.nullsLast(String::compareTo)))
                .orElseThrow();
        List<RetrievedEvidence> evidences = selected.facts().stream()
                .map(fact -> toEvidence(selected.document(), fact))
                .toList();
        return AnswerHandlerResult.of(evidences, searchSummary + " → " + selected.summary(parsed));
    }

    /**
     * 같은 보고자의 신규·변동·변경을 시간순으로 모아 바뀐 과정을 근거로 남긴다.
     *
     * <p>공시일이 있으면 그 날이 들어 있는 사건만 쓰고, 없으면 사건이 하나일 때만 답한다.
     * 보고자가 다르거나 신규가 다시 나오면 새 사건이다. 추측해서 묶지 않는다.
     * 보유목적을 물으면 목적 문장이 바뀐 단계를 남기고, 지분율·주식 수는 수량 기준으로 접는다.
     */
    private AnswerHandlerResult handleHistory(OwnershipQuestion parsed, LocalDate reportDate) {
        String searchSummary = "기업명=" + parsed.corpName()
                + ", 공시유형=holding, 서식=" + DOC_SUBTYPE + " 보유 이력 검색";
        List<DisclosureDocumentEntity> all =
                documentRepository.findByCorpNameAndDocGroupAndDocSubtype(
                        parsed.corpName(), DocGroup.HOLDING, DOC_SUBTYPE);
        if (all.isEmpty()) {
            all = documentRepository.findByCorpNameAndDocGroupOrderByRceptDtDescRceptNoDesc(
                    parsed.corpName(), DocGroup.HOLDING);
        }
        if (all.isEmpty()) {
            return AnswerHandlerResult.unavailable(searchSummary, "해당 기업의 대량보유 공시를 찾지 못했습니다");
        }
        List<OwnershipHoldingHistory.Step> steps = new ArrayList<>();
        for (DisclosureDocumentEntity document : all) {
            List<ExtractedFactEntity> facts = factRepository.findByDocumentDocId(document.getDocId()).stream()
                    .filter(fact -> fact.getRawValue() != null && !fact.getRawValue().isBlank())
                    .toList();
            if (!facts.isEmpty()) {
                steps.add(OwnershipHoldingHistory.stepOf(document, facts));
            }
        }
        List<List<OwnershipHoldingHistory.Step>> chains = OwnershipHoldingHistory.eventChains(steps);
        if (chains.isEmpty()) {
            return AnswerHandlerResult.unavailable(
                    searchSummary,
                    "검색된 공시에서 보고자를 찾지 못해 사건을 잇지 못했습니다"
            );
        }
        List<OwnershipHoldingHistory.Step> chain = OwnershipHoldingHistory.selectChain(chains, reportDate);
        if (chain == null) {
            return AnswerHandlerResult.unavailable(
                    searchSummary + " → 보고 사건 " + chains.size() + "건",
                    reportDate == null
                            ? "여러 보고 사건이 있어 공시일을 특정하지 못했습니다"
                            : reportDate + " 공시가 들어 있는 대량보유 이력을 한 사건으로 특정하지 못했습니다"
            );
        }
        if (chain.size() < 2) {
            return AnswerHandlerResult.unavailable(
                    searchSummary + " → 변동·변경 이력 없음",
                    "찾은 대량보유 공시에 변동·변경 이력이 없습니다"
            );
        }

        List<OwnershipHoldingHistory.Step> alongChain = new ArrayList<>();
        for (OwnershipHoldingHistory.Step step : chain) {
            List<ExtractedFactEntity> matched = step.facts().stream()
                    .filter(fact -> wantedKeys(parsed).contains(fact.getFactKey()))
                    .toList();
            if (!matched.isEmpty()) {
                alongChain.add(new OwnershipHoldingHistory.Step(
                        step.document(), step.holder(), step.kind(), step.quantityKey(), matched));
            }
        }
        List<OwnershipHoldingHistory.Step> collapsed = OwnershipHoldingHistory.collapseHistory(
                alongChain, parsed.asksForPurposeHistory());
        List<RetrievedEvidence> evidences = new ArrayList<>();
        List<String> labels = new ArrayList<>();
        for (OwnershipHoldingHistory.Step step : collapsed) {
            DisclosureDocumentEntity document = step.document();
            evidences.addAll(step.facts().stream()
                    .map(fact -> toEvidence(document, fact))
                    .toList());
            String valueLabel = parsed.asksForPurposeHistory()
                    ? OwnershipHoldingHistory.purposeKey(step.facts())
                    : step.quantityKey();
            labels.add(document.getRceptDt()
                    + (document.isCorrection() ? " 정정" : " " + reportKindLabel(step.kind()))
                    + " " + valueLabel);
        }
        if (evidences.isEmpty()) {
            return AnswerHandlerResult.unavailable(
                    searchSummary,
                    "검색된 공시에서 " + parsed.itemLabel() + " 값을 찾지 못했습니다"
            );
        }
        if (collapsed.size() < 2) {
            return AnswerHandlerResult.unavailable(
                    searchSummary + " → 변동·변경 이력 없음",
                    "찾은 대량보유 공시에 변동·변경 이력이 없습니다"
            );
        }
        return AnswerHandlerResult.of(
                evidences,
                searchSummary + " → " + parsed.itemLabel() + " " + String.join(" → ", labels)
        );
    }

    private static String reportKindLabel(OwnershipHoldingHistory.ReportKind kind) {
        return switch (kind) {
            case INITIAL -> "신규";
            case CHANGE -> "변경";
            case CLEAR -> "해소";
            case UNKNOWN -> "보고";
        };
    }

    static boolean looksLikeHolding(String question) {
        return question.contains("대량보유")
                || question.contains("대량 보유")
                || question.contains("지분공시")
                || question.contains("보유상황");
    }

    private List<Candidate> candidatesOf(
            List<DisclosureDocumentEntity> documents,
            OwnershipQuestion parsed
    ) {
        List<Candidate> candidates = new ArrayList<>();
        for (DisclosureDocumentEntity document : documents) {
            List<ExtractedFactEntity> matched = factRepository.findByDocumentDocId(document.getDocId()).stream()
                    .filter(fact -> wantedKeys(parsed).contains(fact.getFactKey()))
                    .filter(fact -> fact.getRawValue() != null && !fact.getRawValue().isBlank())
                    .toList();
            if (!matched.isEmpty()) {
                candidates.add(new Candidate(document, matched));
            }
        }
        return candidates;
    }

    private static List<String> wantedKeys(OwnershipQuestion parsed) {
        if (parsed.asksForSpecificItem()) {
            return List.of(parsed.factKey());
        }
        return DEFAULT_FACT_KEYS;
    }

    private static List<DisclosureDocumentEntity> preferCorrections(
            List<DisclosureDocumentEntity> documents
    ) {
        List<DisclosureDocumentEntity> corrections = documents.stream()
                .filter(DisclosureDocumentEntity::isCorrection)
                .toList();
        return corrections.isEmpty() ? documents : corrections;
    }

    private OwnershipQuestion parse(String question) {
        return OwnershipQuestionParser.parse(
                question,
                CorpNameResolver.of(corpIdentityRepository.findByDocGroupAndDocSubtype(
                        DocGroup.HOLDING, DOC_SUBTYPE)));
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

        String valueKey() {
            return facts.stream()
                    .map(fact -> fact.getFactKey() + "=" + fact.getRawValue())
                    .collect(Collectors.joining("|"));
        }

        String summary(OwnershipQuestion parsed) {
            String parts = facts.stream()
                    .map(fact -> fact.getFactKey() + "=" + fact.getRawValue())
                    .collect(Collectors.joining(", "));
            return "문서 " + document.getDocId() + " 선택 → " + parsed.itemLabel() + " " + parts + " 추출";
        }
    }
}
