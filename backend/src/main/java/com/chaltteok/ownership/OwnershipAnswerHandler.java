package com.chaltteok.ownership;

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
import java.util.stream.Collectors;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

/**
 * 대량보유상황보고서 질문의 검색·근거 조립.
 *
 * <p>대표 질문: 삼성전자가 2025년 1월 15일 공시한 대량보유상황보고서의 지분율은 얼마인가?
 *
 * <p>이력 추적(신규·변경·해소 사슬)은 하지 않는다. 질문의 공시일로 문서를 특정하고,
 * 같은 날 정정본이 있으면 정정본만 남긴다.
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
    private static final Pattern KOREAN_REPORT_DATE = Pattern.compile(
            "(\\d{4})년\\s*(\\d{1,2})월\\s*(\\d{1,2})일"
    );

    private final DisclosureDocumentRepository documentRepository;
    private final ExtractedFactRepository factRepository;

    public OwnershipAnswerHandler(
            DisclosureDocumentRepository documentRepository,
            ExtractedFactRepository factRepository
    ) {
        this.documentRepository = documentRepository;
        this.factRepository = factRepository;
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
        LocalDate reportDate = resolveReportDate(question);
        if (reportDate == null) {
            return AnswerHandlerResult.unavailable(
                    "기업명=" + parsed.corpName() + ", 공시유형=holding, 서식=" + DOC_SUBTYPE + " 검색",
                    "질문에서 공시일(yyyy년 M월 d일)을 읽지 못해 대량보유 공시를 특정하지 못했습니다"
            );
        }
        String searchSummary = "기업명=" + parsed.corpName()
                + ", 공시유형=holding, 서식=" + DOC_SUBTYPE
                + ", 공시일=" + reportDate + " 검색";
        List<DisclosureDocumentEntity> documents =
                documentRepository.findByCorpNameAndDocGroupAndDocSubtypeAndRceptDt(
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
                documentRepository.findDistinctCorpNamesByDocGroup(DocGroup.HOLDING));
    }

    private static LocalDate resolveReportDate(String question) {
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
