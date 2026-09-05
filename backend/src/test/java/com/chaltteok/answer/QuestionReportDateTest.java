package com.chaltteok.answer;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDate;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class QuestionReportDateTest {

    private static final LocalDate APRIL_8 = LocalDate.of(2025, 4, 8);

    @ParameterizedTest
    @ValueSource(strings = {
            "한화에어로스페이스가 2025년 4월 8일 공시한 유상증자",
            "한화에어로스페이스가 2025-04-08 공시한 유상증자",
            "한화에어로스페이스가 2025.04.08 공시한 유상증자"
    })
    void readsKoreanIsoAndDottedAsTheSameDay(String question) {
        assertThat(QuestionReportDate.parse(question)).isEqualTo(APRIL_8);
    }

    @Test
    void ignoresYearMonthWithoutDay() {
        assertThat(QuestionReportDate.parse("한화에어로스페이스가 2025년 4월 공시한 유상증자"))
                .isNull();
    }

    @Test
    void ignoresInvalidCalendarDay() {
        assertThat(QuestionReportDate.parse("2025년 2월 30일 공시")).isNull();
        assertThat(QuestionReportDate.parse("2025-02-30 공시")).isNull();
    }

    @Test
    void returnsNullWhenQuestionHasNoDate() {
        assertThat(QuestionReportDate.parse("한화에어로스페이스 유상증자 시설자금은?")).isNull();
        assertThat(QuestionReportDate.parse(null)).isNull();
    }

    @Test
    void mentionsDateWhenYearMonthOrNumericDateAppears() {
        assertThat(QuestionReportDate.mentionsDate("한화에어로스페이스가 2025년 4월 공시한 유상증자"))
                .isTrue();
        assertThat(QuestionReportDate.mentionsDate("한화에어로스페이스 유상증자 시설자금은?"))
                .isFalse();
        assertThat(QuestionReportDate.mentionsDate("NAVER의 2099년 자기주식 취득 예정 금액은?"))
                .isFalse();
    }

    @Test
    void readsSingleYearWhenDayIsOmitted() {
        assertThat(QuestionReportDate.parseYear("NAVER의 2023년 자기주식 취득 금액은?"))
                .isEqualTo(2023);
        assertThat(QuestionReportDate.parseYear("레인보우로보틱스의 2099년 유상증자 발행가는?"))
                .isEqualTo(2099);
        assertThat(QuestionReportDate.parseYear("NAVER의 자기주식 취득 예정 금액은?"))
                .isNull();
        assertThat(QuestionReportDate.parseYear("2023년과 2024년 유상증자"))
                .isNull();
        assertThat(QuestionReportDate.parseYear(null)).isNull();
    }
}
