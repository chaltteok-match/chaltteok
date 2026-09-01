package com.chaltteok.disclosure.load;

import com.chaltteok.disclosure.model.enums.ExtractionStatus;
import java.util.List;
import java.util.Map;

/**
 * 적재 1회의 결과 요약. docs/data-model.md §9가 요구하는 실행 요약이다.
 *
 * @param totalRecords 읽은 줄 수(빈 줄 제외)
 * @param succeeded 저장에 성공한 줄 수
 * @param documents 실제로 적재된 서로 다른 문서 수. 같은 {@code doc_id}가 여러 줄에 있으면 1건으로 센다
 * @param evidenceChunks 적재된 문서들의 근거 수
 * @param extractedFacts 적재된 문서들의 사실 수
 * @param relations 적재된 문서들이 선언한 관계 수
 * @param extractionStatusCounts 적재된 사실의 추출 상태별 건수. 전량 적재 후 원인별로 세기 위한 값
 * @param failures 실패한 줄 목록
 */
public record LoadSummary(
        int totalRecords,
        int succeeded,
        int documents,
        int evidenceChunks,
        int extractedFacts,
        int relations,
        Map<ExtractionStatus, Integer> extractionStatusCounts,
        List<LoadFailure> failures
) {

    public LoadSummary {
        extractionStatusCounts = extractionStatusCounts == null
                ? Map.of()
                : Map.copyOf(extractionStatusCounts);
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
        if (!extractionStatusCounts.isEmpty()) {
            // 값이 비는 원인을 원인별로 세어 둬야 전량 적재 후 QA에서 구분할 수 있다.
            text.append(" / 추출 상태:");
            for (ExtractionStatus status : ExtractionStatus.values()) {
                int count = extractionStatusCounts.getOrDefault(status, 0);
                if (count > 0) {
                    text.append(" %s %d".formatted(status.toJson(), count));
                }
            }
        }
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
