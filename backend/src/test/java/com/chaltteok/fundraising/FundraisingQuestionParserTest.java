package com.chaltteok.fundraising;

import com.chaltteok.answer.CorpNameResolver;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;

class FundraisingQuestionParserTest {

    private static final CorpNameResolver CORPS =
            CorpNameResolver.ofNames(List.of("한화에어로스페이스", "삼성SDI", "삼성전기"));

    @Test
    void readsSecuritiesAcquisitionPurpose() {
        FundraisingQuestion parsed = FundraisingQuestionParser.parse(
                "한화에어로스페이스가 2025년 4월 8일 공시한 유상증자결정의 타법인 증권취득 목적 자금은 얼마인가?",
                CORPS);

        assertThat(parsed.corpName()).isEqualTo("한화에어로스페이스");
        assertThat(parsed.isTotal()).isFalse();
        assertThat(parsed.factKey()).isEqualTo(FundraisingAnswerHandler.SECURITIES_ACQUISITION_FACT_KEY);
        assertThat(parsed.itemLabel()).contains("타법인");
    }

    @Test
    void readsFacilityPurpose() {
        FundraisingQuestion parsed = FundraisingQuestionParser.parse(
                "한화에어로스페이스가 2025년 4월 8일 공시한 유상증자결정의 시설자금은 얼마인가?",
                CORPS);

        assertThat(parsed.factKey()).isEqualTo(FundraisingAnswerHandler.FACILITY_FACT_KEY);
        assertThat(parsed.isTotal()).isFalse();
    }

    @Test
    void treatsScheduledProceedsAsTotal() {
        FundraisingQuestion parsed = FundraisingQuestionParser.parse(
                "한화에어로스페이스가 2025년 4월 8일 공시한 유상증자결정의 예정 자금 조달금액은 얼마인가?",
                CORPS);

        assertThat(parsed.isTotal()).isTrue();
        assertThat(parsed.factKey()).isNull();
        assertThat(parsed.itemLabel()).isEqualTo("자금 사용 목적 합계");
    }

    @Test
    void prefersTotalWhenWordsSitBetweenPurposeAndMarker() {
        FundraisingQuestion included = FundraisingQuestionParser.parse(
                "한화에어로스페이스 유상증자의 시설자금을 포함한 총 조달금액은?", CORPS);
        assertThat(included.isTotal()).isTrue();
        assertThat(included.excludedFactKeys()).isEmpty();

        FundraisingQuestion excluded = FundraisingQuestionParser.parse(
                "한화에어로스페이스 유상증자의 타법인 증권취득자금을 제외한 총액은?", CORPS);
        assertThat(excluded.isTotal()).isTrue();
        assertThat(excluded.excludedFactKeys())
                .containsExactly(FundraisingAnswerHandler.SECURITIES_ACQUISITION_FACT_KEY);
        assertThat(excluded.itemLabel()).contains("제외");
    }

    @Test
    void excludesOnlyPurposesAttachedToExcludeWhenIncludeIsAlsoPresent() {
        FundraisingQuestion parsed = FundraisingQuestionParser.parse(
                "한화에어로스페이스 유상증자의 시설자금을 포함하고 타법인 증권취득을 제외한 총액은?",
                CORPS);
        assertThat(parsed.isTotal()).isTrue();
        assertThat(parsed.excludedFactKeys())
                .containsExactly(FundraisingAnswerHandler.SECURITIES_ACQUISITION_FACT_KEY);
        assertThat(parsed.itemLabel()).contains("타법인").contains("제외");
        assertThat(parsed.itemLabel()).doesNotContain("시설자금");
    }

    @Test
    void excludesPurposeBeforeIncludeWhenWordingIsReversed() {
        FundraisingQuestion parsed = FundraisingQuestionParser.parse(
                "한화에어로스페이스 유상증자의 타법인 증권취득을 빼고 시설자금을 포함한 총액은?",
                CORPS);
        assertThat(parsed.excludedFactKeys())
                .containsExactly(FundraisingAnswerHandler.SECURITIES_ACQUISITION_FACT_KEY);
    }

    @Test
    void treatsCorrectionWordingAsHistory() {
        FundraisingQuestion parsed = FundraisingQuestionParser.parse(
                "한화에어로스페이스가 2025년 4월 8일 공시한 유상증자 시설자금은 정정 후 어떻게 바뀌었나?",
                CORPS);
        assertThat(parsed.history()).isTrue();
        assertThat(parsed.factKey()).isEqualTo(FundraisingAnswerHandler.FACILITY_FACT_KEY);
    }

    @Test
    void treatsCorrectionAsSingleDocumentLabel() {
        assertThat(FundraisingQuestionParser.parse(
                "한화에어로스페이스가 2025년 4월 8일 정정한 유상증자의 시설자금은?", CORPS).history())
                .isFalse();
        assertThat(FundraisingQuestionParser.parse(
                "한화에어로스페이스가 2025년 4월 8일 정정공시한 유상증자의 예정 자금 조달금액은?", CORPS)
                .history())
                .isFalse();
        assertThat(FundraisingQuestionParser.parse(
                "한화에어로스페이스가 2025년 4월 8일 정정 전 시설자금은?", CORPS).history())
                .isFalse();
    }

    @Test
    void treatsChangeRecordWordingAsHistory() {
        FundraisingQuestion parsed = FundraisingQuestionParser.parse(
                "한화에어로스페이스가 2025년 3월 20일 공시한 유상증자 예정 자금 조달금액이 변경된 내역은?",
                CORPS);
        assertThat(parsed.history()).isTrue();
        assertThat(parsed.isTotal()).isTrue();
    }

    @Test
    void treatsMarkerRightAfterPurposeAsThatPurpose() {
        FundraisingQuestion facilityTotal = FundraisingQuestionParser.parse("시설자금 총액은?", CORPS);
        assertThat(facilityTotal.isTotal()).isFalse();
        assertThat(facilityTotal.factKey()).isEqualTo(FundraisingAnswerHandler.FACILITY_FACT_KEY);

        FundraisingQuestion facilitySum = FundraisingQuestionParser.parse("시설자금 합계는?", CORPS);
        assertThat(facilitySum.isTotal()).isFalse();
        assertThat(facilitySum.factKey()).isEqualTo(FundraisingAnswerHandler.FACILITY_FACT_KEY);
    }

    @Test
    void treatsPlainFundraisingAmountQuestionAsTotal() {
        FundraisingQuestion parsed = FundraisingQuestionParser.parse("유상증자 자금은 얼마인가?", CORPS);
        assertThat(parsed.isTotal()).isTrue();
    }

    @Test
    void prefersLongerCompanyName() {
        assertThat(FundraisingQuestionParser.parse("삼성전기의 유상증자결정", CORPS).corpName())
                .isEqualTo("삼성전기");
    }

    @Test
    void readsNewSharesParValueAndIssuePrice() {
        assertThat(FundraisingQuestionParser.parse(
                "한화에어로스페이스가 2025년 4월 8일 공시한 유상증자의 신주 수는?", CORPS).factKey())
                .isEqualTo(FundraisingAnswerHandler.NEW_SHARES_FACT_KEY);
        assertThat(FundraisingQuestionParser.parse(
                "한화에어로스페이스 유상증자 액면가액은?", CORPS).factKey())
                .isEqualTo(FundraisingAnswerHandler.PAR_VALUE_FACT_KEY);
        assertThat(FundraisingQuestionParser.parse(
                "한화에어로스페이스 유상증자 발행가액은?", CORPS).factKey())
                .isEqualTo(FundraisingAnswerHandler.ISSUE_PRICE_FACT_KEY);
        assertThat(FundraisingQuestionParser.parse(
                "한화에어로스페이스 유상증자 예정발행가는?", CORPS).factKey())
                .isEqualTo(FundraisingAnswerHandler.PLANNED_ISSUE_PRICE_FACT_KEY);
    }
}
