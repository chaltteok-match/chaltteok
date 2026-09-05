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
}
