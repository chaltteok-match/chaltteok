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
    void matchesItemKeywordsIgnoringWhitespace() {
        assertThat(OwnershipQuestionParser.parse(
                "SK하이닉스가 2023년 6월 7일 공시한 대량보유상황보고서의 보유 목적은?",
                CORPS).factKey())
                .isEqualTo(OwnershipQuestionParser.PURPOSE_FACT_KEY);
        assertThat(OwnershipQuestionParser.parse("삼성전자 보유목적은?", CORPS).factKey())
                .isEqualTo(OwnershipQuestionParser.PURPOSE_FACT_KEY);
        assertThat(OwnershipQuestionParser.parse("삼성전자 보유 비율은?", CORPS).factKey())
                .isEqualTo(OwnershipQuestionParser.RATIO_FACT_KEY);
        assertThat(OwnershipQuestionParser.parse("삼성전자 보유 주식 수는?", CORPS).factKey())
                .isEqualTo(OwnershipQuestionParser.SHARES_FACT_KEY);
        assertThat(OwnershipQuestionParser.parse("삼성전자 보고 구분은?", CORPS).factKey())
                .isEqualTo(OwnershipQuestionParser.REPORT_TYPE_FACT_KEY);
    }

    @Test
    void treatsPlainHoldingQuestionAsOverview() {
        OwnershipQuestion parsed = OwnershipQuestionParser.parse(
                "삼성전자가 2025년 1월 15일 공시한 대량보유상황보고서는?",
                CORPS);

        assertThat(parsed.factKey()).isNull();
        assertThat(parsed.itemLabel()).isEqualTo("대량보유 현황");
        assertThat(parsed.history()).isFalse();
    }

    @Test
    void treatsProcessWordingAsHistory() {
        assertThat(OwnershipQuestionParser.parse(
                "삼성전자 대량보유상황보고서 지분율은 어떻게 바뀌었나?", CORPS).history())
                .isTrue();
        assertThat(OwnershipQuestionParser.parse("삼성전자 지분공시 변경된 내역은?", CORPS).history())
                .isTrue();
        assertThat(OwnershipQuestionParser.parse("삼성전자 대량보유 해소 보고는?", CORPS).history())
                .isTrue();
        assertThat(OwnershipQuestionParser.parse(
                "삼성전자가 2025년 1월 15일 정정한 대량보유상황보고서의 지분율은?", CORPS).history())
                .isFalse();
    }

    @Test
    void prefersLongerCompanyName() {
        assertThat(OwnershipQuestionParser.parse("삼성전기 지분공시", CORPS).corpName())
                .isEqualTo("삼성전기");
    }
}
