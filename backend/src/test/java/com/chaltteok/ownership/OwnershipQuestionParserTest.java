package com.chaltteok.ownership;

import static org.assertj.core.api.Assertions.assertThat;

import com.chaltteok.answer.CorpNameResolver;
import java.util.List;
import org.junit.jupiter.api.Test;

class OwnershipQuestionParserTest {

    private static final CorpNameResolver CORPS =
            CorpNameResolver.ofNames(List.of("삼성전자", "삼성전기"));

    @Test
    void readsRatioItem() {
        OwnershipQuestion parsed = OwnershipQuestionParser.parse(
                "삼성전자가 2025년 1월 15일 공시한 대량보유상황보고서의 지분율은 얼마인가?",
                CORPS);

        assertThat(parsed.corpName()).isEqualTo("삼성전자");
        assertThat(parsed.factKey()).isEqualTo(OwnershipQuestionParser.RATIO_FACT_KEY);
        assertThat(parsed.itemLabel()).isEqualTo("지분율");
    }

    @Test
    void treatsPlainHoldingQuestionAsOverview() {
        OwnershipQuestion parsed = OwnershipQuestionParser.parse(
                "삼성전자가 2025년 1월 15일 공시한 대량보유상황보고서는?",
                CORPS);

        assertThat(parsed.factKey()).isNull();
        assertThat(parsed.itemLabel()).isEqualTo("대량보유 현황");
    }

    @Test
    void prefersLongerCompanyName() {
        assertThat(OwnershipQuestionParser.parse("삼성전기 지분공시", CORPS).corpName())
                .isEqualTo("삼성전기");
    }
}
