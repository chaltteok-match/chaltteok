package com.chaltteok.answer;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class KoreanAmountFormatterTest {

    @Test
    void formatsTrillionWithHundredMillion() {
        assertThat(KoreanAmountFormatter.format("300870903000000")).isEqualTo("300조 8,709억원");
    }

    @Test
    void formatsLargeAndSmallHundredMillionAmounts() {
        assertThat(KoreanAmountFormatter.format("656697600000000")).isEqualTo("656조 6,976억원");
        assertThat(KoreanAmountFormatter.format("100000000")).isEqualTo("1억원");
    }

    @Test
    void formatsAmountsBelowHundredMillionInWon() {
        assertThat(KoreanAmountFormatter.format("1234567")).isEqualTo("1,234,567원");
    }

    @Test
    void formatsNegativeAmount() {
        assertThat(KoreanAmountFormatter.format("-11526297000000")).isEqualTo("-11조 5,262억원");
    }

    @Test
    void formatsTrillionScaleAmount() {
        assertThat(KoreanAmountFormatter.format("6566976000000")).isEqualTo("6조 5,669억원");
    }
}
