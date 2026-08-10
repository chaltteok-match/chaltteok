package com.chaltteok.answer;

import java.util.List;
import java.util.Objects;

/**
 * Handler가 반환하는 검색·판단 결과.
 * 공시명·공시일·기업명·근거 문장·수치, 처리 과정 요약, 답변 불가 사유를 담는다.
 */
public record AnswerHandlerResult(
        List<RetrievedEvidence> evidences,
        String processSummary,
        String unavailableReason
) {
    public AnswerHandlerResult {
        evidences = evidences == null ? List.of() : List.copyOf(evidences);
        Objects.requireNonNull(processSummary, "processSummary");
    }

    public boolean hasEvidence() {
        return !evidences.isEmpty();
    }

    public static AnswerHandlerResult unavailable(String processSummary, String unavailableReason) {
        return new AnswerHandlerResult(List.of(), processSummary, unavailableReason);
    }

    public static AnswerHandlerResult of(List<RetrievedEvidence> evidences, String processSummary) {
        return new AnswerHandlerResult(evidences, processSummary, null);
    }
}
