package com.chaltteok.answer;

import java.util.List;

/**
 * Handler 근거 목록을 평가 API {@code retrieved_context} 문자열로 조립한다.
 * docs/api-contract.md §4.3.
 */
public final class RetrievedContextFormatter {

    private RetrievedContextFormatter() {
    }

    public static String format(List<RetrievedEvidence> evidences) {
        if (evidences == null || evidences.isEmpty()) {
            return "";
        }

        StringBuilder builder = new StringBuilder();
        for (int i = 0; i < evidences.size(); i++) {
            if (i > 0) {
                builder.append("\n\n");
            }
            RetrievedEvidence evidence = evidences.get(i);
            builder.append("[근거 ").append(i + 1).append("]\n")
                    .append("공시명: ").append(evidence.reportName()).append('\n')
                    .append("공시일: ").append(evidence.reportDate()).append('\n')
                    .append("기업명: ").append(evidence.companyName()).append('\n')
                    .append("근거: ").append(evidence.evidenceText());
        }
        return builder.toString();
    }
}
