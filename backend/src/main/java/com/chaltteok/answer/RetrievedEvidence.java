package com.chaltteok.answer;

import java.time.LocalDate;
import java.util.Objects;

/**
 * 답변에 사용할 공시 근거 한 건. docs/api-contract.md §4.3.
 */
public record RetrievedEvidence(
        String reportName,
        LocalDate reportDate,
        String companyName,
        String evidenceText
) {
    public RetrievedEvidence {
        Objects.requireNonNull(reportName, "reportName");
        Objects.requireNonNull(reportDate, "reportDate");
        Objects.requireNonNull(companyName, "companyName");
        Objects.requireNonNull(evidenceText, "evidenceText");
    }
}
