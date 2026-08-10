package com.chaltteok.answer;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.Test;

class RetrievedContextFormatterTest {

    @Test
    void formatsMultipleEvidences() {
        String context = RetrievedContextFormatter.format(List.of(
                new RetrievedEvidence(
                        "2025년 사업보고서",
                        LocalDate.of(2026, 3, 20),
                        "예시전자",
                        "매출액 12,345억원"
                ),
                new RetrievedEvidence(
                        "주요사항보고서",
                        LocalDate.of(2025, 8, 10),
                        "예시전자",
                        "시설투자 1,000억원"
                )
        ));

        assertThat(context).isEqualTo("""
                [근거 1]
                공시명: 2025년 사업보고서
                공시일: 2026-03-20
                기업명: 예시전자
                근거: 매출액 12,345억원

                [근거 2]
                공시명: 주요사항보고서
                공시일: 2025-08-10
                기업명: 예시전자
                근거: 시설투자 1,000억원\
                """);
    }

    @Test
    void returnsEmptyStringWhenNoEvidence() {
        assertThat(RetrievedContextFormatter.format(List.of())).isEmpty();
    }
}
