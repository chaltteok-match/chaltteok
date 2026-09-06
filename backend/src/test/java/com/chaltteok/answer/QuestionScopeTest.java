package com.chaltteok.answer;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class QuestionScopeTest {

    @ParameterizedTest
    @ValueSource(strings = {
            "삼성전자의 2027년 매출 전망은?",
            "삼성전자 주식 지금 사야 할까?",
            "삼성전자 목표주가는 얼마인가?",
            "삼성전자의 향후 실적을 예측해줘",
            "SK하이닉스 투자 의견을 알려줘",
            "삼성전자의 매출액 추정치는?",
            "지금 삼성전자 사는 게 괜찮을까?"})
    void refusesForecastAndInvestmentAdvice(String question) {
        assertThat(QuestionScope.check(question))
                .get()
                .extracting(QuestionScope.OutOfScope::kind)
                .isEqualTo(QuestionScope.Kind.FORECAST);
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "삼성전자의 2015년 매출액은?",
            "삼성전자의 2019년 연결 매출액은?",
            "삼성전자의 2030년 매출액은?",
            "삼성전자가 2027년에 공시한 공급계약 금액은?"})
    void refusesYearsOutsideTheCorpus(String question) {
        assertThat(QuestionScope.check(question))
                .get()
                .extracting(QuestionScope.OutOfScope::kind)
                .isEqualTo(QuestionScope.Kind.PERIOD);
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "삼성전자의 2024년 연결 매출액은?",
            // 요약재무정보 표가 세 기간을 담아 접수 연도보다 앞선 값이 있다.
            "삼성전자의 2021년 연결 매출액은?",
            "삼성전자의 2020년 매출액은?",
            "삼성전자가 2025년 7월 28일 공시한 공급계약의 계약금액은?",
            "삼성전자의 2024년 매출액은 2023년 대비 얼마나 늘었나?",
            "삼성전자의 자기주식 취득 예정 금액은?",
            "한화에어로스페이스의 유상증자 발행가는?"})
    void letsAnswerableQuestionsThrough(String question) {
        assertThat(QuestionScope.check(question)).isEmpty();
    }

    @ParameterizedTest
    @ValueSource(strings = {
            // 한쪽이라도 범위 안이면 막지 않는다. 그 해는 답할 수 있다.
            "삼성전자의 2019년과 2024년 매출액을 비교하면?",
            "삼성전자의 2024년과 2030년 매출액 차이는?"})
    void doesNotBlockWhenOneOfTheYearsIsAnswerable(String question) {
        assertThat(QuestionScope.check(question)).isEmpty();
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "  "})
    void ignoresEmptyQuestions(String question) {
        assertThat(QuestionScope.check(question)).isEmpty();
    }

    @org.junit.jupiter.api.Test
    void tellsWhatIsMissing() {
        QuestionScope.OutOfScope period = QuestionScope.check("삼성전자의 2015년 매출액은?").orElseThrow();
        assertThat(period.thinkTrace()).contains("2015").contains("2020~2026");
        assertThat(period.answerNote()).contains("2023-01-01").contains("2026-03-31");

        QuestionScope.OutOfScope forecast =
                QuestionScope.check("삼성전자 목표주가는?").orElseThrow();
        assertThat(forecast.answerNote()).contains("미래 예측").contains("투자 의견");
    }
}
