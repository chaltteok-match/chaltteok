package com.chaltteok.treasury;

import static org.assertj.core.api.Assertions.assertThat;

import com.chaltteok.answer.CorpNameResolver;
import java.util.List;
import org.junit.jupiter.api.Test;

class TreasuryQuestionParserTest {

    private static final CorpNameResolver CORPS =
            CorpNameResolver.ofNames(List.of("삼성전자", "삼성전기"));

    @Test
    void readsPlannedAmount() {
        TreasuryQuestion parsed = TreasuryQuestionParser.parse(
                "삼성전자가 2025년 3월 1일 공시한 자기주식취득결정의 취득예정금액은 얼마인가?",
                CORPS);

        assertThat(parsed.corpName()).isEqualTo("삼성전자");
        assertThat(parsed.factKey()).isEqualTo(TreasuryQuestionParser.AMOUNT_FACT_KEY);
        assertThat(parsed.itemLabel()).isEqualTo("취득예정금액");
    }

    @Test
    void treatsPlainAcquisitionQuestionAsOverview() {
        TreasuryQuestion parsed = TreasuryQuestionParser.parse(
                "삼성전자가 2025년 3월 1일 공시한 자기주식취득결정은?",
                CORPS);

        assertThat(parsed.factKey()).isNull();
        assertThat(parsed.itemLabel()).isEqualTo("자기주식 취득예정");
    }

    @Test
    void prefersLongerCompanyName() {
        assertThat(TreasuryQuestionParser.parse("삼성전기 자기주식 취득", CORPS).corpName())
                .isEqualTo("삼성전기");
    }
}
