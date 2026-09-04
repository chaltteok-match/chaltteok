package com.chaltteok.exchange;

import com.chaltteok.answer.CorpNameResolver;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.Test;

class ExchangeQuestionParserTest {

    private static final CorpNameResolver CORP_NAMES =
            CorpNameResolver.ofNames(List.of("삼성전자", "대우건설", "HMM"));

    private static ExchangeQuestion parse(String question) {
        return ExchangeQuestionParser.parse(question, CORP_NAMES);
    }

    @Test
    void readsCompanyReportDateAndItem() {
        ExchangeQuestion parsed =
                parse("삼성전자가 2025년 7월 28일 공시한 공급계약의 계약금액은 얼마인가?");

        assertThat(parsed.corpName()).isEqualTo("삼성전자");
        assertThat(parsed.reportDate()).isEqualTo(LocalDate.of(2025, 7, 28));
        assertThat(parsed.topic()).isEqualTo(ExchangeTopic.SUPPLY_CONTRACT);
        assertThat(parsed.factKey()).isEqualTo("contract_amount");
        assertThat(parsed.largest()).isFalse();
    }

    @Test
    void terminationIsNotReadAsContractSigning() {
        // '계약해지'가 '계약금액'보다 먼저 걸려야 해지 공시를 체결 공시로 답하지 않는다.
        ExchangeQuestion parsed = parse("대우건설의 공급계약해지 금액은 얼마인가?");

        assertThat(parsed.topic()).isEqualTo(ExchangeTopic.SUPPLY_TERMINATION);
        assertThat(parsed.factKey()).isEqualTo("termination_amount");
    }

    @Test
    void recentRevenueIsNotReadAsRevenueRatio() {
        // 항목 표현이 여럿 걸리면 가장 긴 것을 고른다.
        assertThat(parse("삼성전자 공급계약의 최근매출액은?").factKey())
                .isEqualTo("contract_recent_revenue");
        assertThat(parse("삼성전자 공급계약의 매출액대비 비율은?").factKey())
                .isEqualTo("contract_revenue_ratio");
    }

    @Test
    void defaultsToTheFormsMainAmountWhenItemIsNotNamed() {
        assertThat(parse("HMM의 신규시설투자 규모는?").factKey()).isEqualTo("investment_amount");
        assertThat(parse("삼성전자가 수주한 계약은?").factKey()).isEqualTo("contract_amount");
    }

    @Test
    void detectsLargestQuestions() {
        assertThat(parse("대우건설이 2024년에 체결한 공급계약 중 가장 큰 계약금액은?").largest()).isTrue();
        assertThat(parse("대우건설의 최대 수주 금액은?").largest()).isTrue();
        assertThat(parse("대우건설의 2024년 공급계약 금액은?").largest()).isFalse();
    }

    @Test
    void readsYearOnlyWhenExactlyOneYearIsGiven() {
        assertThat(parse("대우건설이 2024년에 수주한 계약금액은?").year()).isEqualTo(2024);
        // 연도가 둘이면 어느 해를 묻는지 알 수 없으므로 만들지 않는다.
        assertThat(parse("대우건설이 2023년과 2024년에 수주한 계약금액은?").year()).isNull();
    }

    @Test
    void doesNotInventADateFromAnImpossibleOne() {
        assertThat(parse("삼성전자가 2025년 2월 30일 공시한 공급계약금액은?").reportDate()).isNull();
    }

    @Test
    void leavesTopicNullWhenQuestionIsNotAboutExchangeDisclosure() {
        ExchangeQuestion parsed = parse("삼성전자의 2024년 연결기준 매출액은 얼마인가?");

        assertThat(parsed.topic()).isNull();
        assertThat(parsed.isIdentified()).isFalse();
    }
}
