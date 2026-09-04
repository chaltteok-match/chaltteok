package com.chaltteok.bond;

import static org.assertj.core.api.Assertions.assertThat;

import com.chaltteok.answer.CorpNameResolver;
import java.util.List;
import org.junit.jupiter.api.Test;

class BondQuestionParserTest {

    private static final CorpNameResolver CORPS =
            CorpNameResolver.ofNames(List.of("에코프로비엠", "SK하이닉스"));

    @Test
    void readsConvertibleFaceAmount() {
        BondQuestion parsed = BondQuestionParser.parse(
                "에코프로비엠이 2023년 6월 30일 공시한 전환사채의 권면총액은 얼마인가?",
                CORPS);

        assertThat(parsed.corpName()).isEqualTo("에코프로비엠");
        assertThat(parsed.form()).isEqualTo(BondForm.CONVERTIBLE);
        assertThat(parsed.factKey()).isEqualTo(BondQuestionParser.FACE_AMOUNT_FACT_KEY);
        assertThat(parsed.itemLabel()).isEqualTo("권면총액");
    }

    @Test
    void readsExchangeableExercisePrice() {
        BondQuestion parsed = BondQuestionParser.parse(
                "SK하이닉스가 2023년 4월 3일 공시한 교환사채의 교환가액은?",
                CORPS);

        assertThat(parsed.form()).isEqualTo(BondForm.EXCHANGEABLE);
        assertThat(parsed.factKey()).isEqualTo(BondQuestionParser.EXERCISE_PRICE_FACT_KEY);
    }

    @Test
    void treatsPlainBondQuestionAsOverview() {
        BondQuestion parsed = BondQuestionParser.parse(
                "에코프로비엠이 2023년 6월 30일 공시한 전환사채는?",
                CORPS);

        assertThat(parsed.factKey()).isNull();
        assertThat(parsed.itemLabel()).isEqualTo("사채 발행");
    }

    @Test
    void prefersExchangeableWhenBothWordsCouldAppear() {
        assertThat(BondQuestionParser.formOf("교환사채 전환가액")).isEqualTo(BondForm.EXCHANGEABLE);
        assertThat(BondQuestionParser.formOf("전환 사채 권면총액")).isEqualTo(BondForm.CONVERTIBLE);
    }
}
