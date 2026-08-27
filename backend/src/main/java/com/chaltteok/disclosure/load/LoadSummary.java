package com.chaltteok.disclosure.load;

import java.util.List;

/**
 * 적재 1회의 결과 요약. docs/data-model.md §9가 요구하는 실행 요약이다.
 */
public record LoadSummary(
        int totalRecords,
        int succeeded,
        int documents,
        int evidenceChunks,
        int extractedFacts,
        int relations,
        List<LoadFailure> failures
) {

    public LoadSummary {
        failures = failures == null ? List.of() : List.copyOf(failures);
    }

    public int failed() {
        return failures.size();
    }

    public boolean hasFailure() {
        return !failures.isEmpty();
    }

    /**
     * 부분 결과를 보존하더라도 완전한 적재 결과로 표시하지 않는다.
     */
    public String describe() {
        StringBuilder text = new StringBuilder()
                .append("적재 요약: 총 %d건, 성공 %d건, 실패 %d건"
                        .formatted(totalRecords, succeeded, failed()))
                .append(" / 문서 %d, 근거 %d, 사실 %d, 관계 %d"
                        .formatted(documents, evidenceChunks, extractedFacts, relations));
        for (LoadFailure failure : failures) {
            text.append(System.lineSeparator()).append("  실패 ").append(failure.describe());
        }
        return text.toString();
    }

    /**
     * 실패한 줄 하나. 어느 줄의 어느 문서인지 식별할 수 있어야 한다.
     */
    public record LoadFailure(int lineNumber, String docId, String reason) {

        public String describe() {
            return "line %d (doc_id=%s): %s".formatted(lineNumber, docId == null ? "-" : docId, reason);
        }
    }
}
