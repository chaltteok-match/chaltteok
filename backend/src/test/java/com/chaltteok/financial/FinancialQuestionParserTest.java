package com.chaltteok.financial;

import com.chaltteok.answer.CorpNameResolver;

import static org.assertj.core.api.Assertions.assertThat;

import com.chaltteok.disclosure.model.enums.Scope;
import java.util.List;
import org.junit.jupiter.api.Test;

class FinancialQuestionParserTest {

    private static final CorpNameResolver CORPS =
            CorpNameResolver.ofNames(List.of(
                    "삼성전자", "삼성전기", "NAVER", "한화에어로스페이스", "SK하이닉스"));

    private FinancialQuestion parse(String question) {
        return FinancialQuestionParser.parse(question, CORPS);
    }

    @Test
    void readsCompanyYearItemAndScopeFromSingleLookup() {
        FinancialQuestion parsed = parse("삼성전자의 2024년 연결기준 매출액은 얼마인가?");

        assertThat(parsed.corpName()).isEqualTo("삼성전자");
        assertThat(parsed.targetYear()).isEqualTo(2024);
        assertThat(parsed.factKey()).isEqualTo("revenue");
        assertThat(parsed.scope()).isEqualTo(Scope.CONSOLIDATED);
        assertThat(parsed.scopeExplicit()).isTrue();
        assertThat(parsed.isComparison()).isFalse();
    }

    @Test
    void usesEarlierYearAsBaseWhenTwoYearsGiven() {
        FinancialQuestion parsed =
                parse("삼성전자의 2024년 연결기준 매출액은 2023년 대비 얼마나 늘었는가?");

        assertThat(parsed.targetYear()).isEqualTo(2024);
        assertThat(parsed.baseYear()).isEqualTo(2023);
        assertThat(parsed.isComparison()).isTrue();
    }

    @Test
    void usesPreviousYearAsBaseWhenOnlyOneYearGiven() {
        FinancialQuestion parsed = parse("삼성전자의 2024년 매출액은 전년 대비 증가했는가?");

        assertThat(parsed.targetYear()).isEqualTo(2024);
        assertThat(parsed.baseYear()).isEqualTo(2023);
    }

    @Test
    void readsSeparateScopeWhenStated() {
        assertThat(parse("삼성전자의 2024년 별도기준 매출액").scope()).isEqualTo(Scope.SEPARATE);
        assertThat(parse("삼성전자의 2024년 개별기준 매출액").scope()).isEqualTo(Scope.SEPARATE);
    }

    @Test
    void defaultsToConsolidatedAndMarksScopeNotExplicit() {
        FinancialQuestion parsed = parse("삼성전자의 2024년 매출액은 얼마인가?");

        assertThat(parsed.scope()).isEqualTo(Scope.CONSOLIDATED);
        assertThat(parsed.scopeExplicit()).isFalse();
    }

    @Test
    void prefersLongerCompanyName() {
        assertThat(parse("삼성전기의 2024년 매출액").corpName()).isEqualTo("삼성전기");
    }

    @Test
    void prefersLongerItemExpression() {
        assertThat(parse("삼성전자의 2024년 자산총계").factKey()).isEqualTo("total_assets");
        assertThat(parse("삼성전자의 2024년 당기순이익").factKey()).isEqualTo("net_income");
        assertThat(parse("삼성전자의 2024년 영업이익").factKey()).isEqualTo("operating_profit");
        assertThat(parse("NAVER의 2024년 영업수익").factKey()).isEqualTo("revenue");
    }

    @Test
    void readsShorterEverydayItemNames() {
        assertThat(parse("삼성전자의 2024년 매출은?").factKey()).isEqualTo("revenue");
        assertThat(parse("삼성전자의 2024년 매출 규모는?").factKey()).isEqualTo("revenue");
        assertThat(parse("삼성전자의 2024년 순이익은?").factKey()).isEqualTo("net_income");
        assertThat(parse("삼성전자의 2024년 자기자본은?").factKey()).isEqualTo("total_equity");
        assertThat(parse("삼성전자의 2024년 영업손익은?").factKey()).isEqualTo("operating_profit");
        assertThat(parse("삼성전자의 2024년 자산 규모는?").factKey()).isEqualTo("total_assets");
        assertThat(parse("삼성전자의 2024년 자산규모는?").factKey()).isEqualTo("total_assets");
        assertThat(parse("삼성전자의 2024년 부채 규모는?").factKey()).isEqualTo("total_liabilities");
    }

    @Test
    void stillRefusesTermsThatAreNeitherAnItemNorARatioWeCompute() {
        // 요약재무정보에 없고 계산할 수도 없다. 조각으로 잡히면 다른 값을 답하게 된다.
        assertThat(parse("삼성전자의 2024년 매출총이익은?").factKey()).isNull();
        assertThat(parse("삼성전자의 2024년 매출원가는?").factKey()).isNull();
        assertThat(parse("삼성전자의 2024년 매출액증가율은?").factKey()).isNull();
        assertThat(parse("삼성전자의 2024년 총자산회전율은?").factKey()).isNull();
        assertThat(parse("삼성전자의 2024년 주당순이익은?").factKey()).isNull();
        // 항목마다 만들어지는 표기. 목록에 적어 두지 않아도 꼬리로 걸러진다.
        assertThat(parse("삼성전자의 2024년 당기순이익증가율은?").factKey()).isNull();
        assertThat(parse("삼성전자의 2024년 영업수익증가율은?").factKey()).isNull();
        assertThat(parse("삼성전자의 2024년 자기자본증가율은?").factKey()).isNull();
    }

    @Test
    void refusesUncomputableRatiosWrittenWithASpace() {
        // 표현을 찾을 때 공백을 지우므로 띄어 쓴 표기도 같게 본다.
        assertThat(parse("삼성전자의 2024년 매출 증가율은?").factKey()).isNull();
        assertThat(parse("삼성전자의 2024년 매출액 증가율은?").factKey()).isNull();
        assertThat(parse("삼성전자의 2024년 순이익 증가율은?").factKey()).isNull();
        assertThat(parse("삼성전자의 2024년 총자산 회전율은?").factKey()).isNull();
        assertThat(parse("삼성전자의 2024년 영업이익 증가율은?").factKey()).isNull();
    }

    @Test
    void readsRatiosAsRatiosNotAsTheirNumerator() {
        assertThat(parse("삼성전자의 2024년 영업이익률은?").ratio())
                .isEqualTo(FinancialRatio.OPERATING_MARGIN);
        assertThat(parse("삼성전자의 2024년 순이익률은?").ratio())
                .isEqualTo(FinancialRatio.NET_MARGIN);
        assertThat(parse("삼성전자의 2024년 당기순이익률은?").ratio())
                .isEqualTo(FinancialRatio.NET_MARGIN);
        assertThat(parse("삼성전자의 2024년 부채비율은?").ratio())
                .isEqualTo(FinancialRatio.DEBT_RATIO);
        assertThat(parse("삼성전자의 2024년 자기자본비율은?").ratio())
                .isEqualTo(FinancialRatio.EQUITY_RATIO);
        // 띄어 쓴 비율도 같게 본다.
        assertThat(parse("삼성전자의 2024년 자기자본 비율은?").ratio())
                .isEqualTo(FinancialRatio.EQUITY_RATIO);
        assertThat(parse("삼성전자의 2024년 ROE는?").ratio())
                .isEqualTo(FinancialRatio.RETURN_ON_EQUITY);
        assertThat(parse("삼성전자의 2024년 자기자본이익률은?").ratio())
                .isEqualTo(FinancialRatio.RETURN_ON_EQUITY);
        assertThat(parse("삼성전자의 2024년 ROA는?").ratio())
                .isEqualTo(FinancialRatio.RETURN_ON_ASSETS);
        assertThat(parse("삼성전자의 2024년 총자산순이익률은?").ratio())
                .isEqualTo(FinancialRatio.RETURN_ON_ASSETS);
    }

    @Test
    void doesNotTreatAPlainItemAsARatio() {
        assertThat(parse("삼성전자의 2024년 영업이익은?").ratio()).isNull();
        assertThat(parse("삼성전자의 2024년 순이익은?").ratio()).isNull();
        assertThat(parse("삼성전자의 2024년 자기자본은?").ratio()).isNull();
        assertThat(parse("삼성전자의 2024년 부채총계는?").ratio()).isNull();
    }

    @Test
    void readsAYearWrittenWithoutTheKoreanSuffix() {
        assertThat(parse("삼성전자 매출액 2024").targetYear()).isEqualTo(2024);
        assertThat(parse("삼성전자 2024 순이익").targetYear()).isEqualTo(2024);
        // `년`이 붙은 표기가 하나라도 있으면 그쪽만 본다.
        assertThat(parse("삼성전자의 2024년 매출액 300,870,903").targetYear()).isEqualTo(2024);
    }

    @Test
    void doesNotReadDigitsInsideAmountsOrCodesAsAYear() {
        assertThat(parse("삼성전자 매출액 1,2024,000").targetYear()).isNull();
        assertThat(parse("삼성전자 매출액 20241").targetYear()).isNull();
        assertThat(parse("삼성전자 매출액 12024").targetYear()).isNull();
    }

    @Test
    void leavesUnidentifiedPartsNull() {
        FinancialQuestion parsed = parse("작년에 실적이 좋았던 기업은?");

        assertThat(parsed.corpName()).isNull();
        assertThat(parsed.targetYear()).isNull();
        assertThat(parsed.factKey()).isNull();
        assertThat(parsed.isAnswerable()).isFalse();
    }

    @Test
    void doesNotMatchTermsThatContainAnItemButMeanSomethingElse() {
        // 주당순이익은 원 단위, 당기순이익은 조 단위다. 조각으로 잡히면 10억 배 틀린 답이 나간다.
        assertThat(parse("삼성전자의 2024년 주당순이익은 얼마인가?").factKey()).isNull();
        assertThat(parse("삼성전자의 2024년 기본주당순이익은 얼마인가?").factKey()).isNull();
        assertThat(parse("삼성전자의 2024년 매출총이익은 얼마인가?").factKey()).isNull();
        assertThat(parse("삼성전자의 2024년 매출원가는 얼마인가?").factKey()).isNull();
        // 계산할 수 없는 비율은 금액으로도 잡지 않는다.
        assertThat(parse("삼성전자의 2024년 총자산회전율은 얼마인가?").factKey()).isNull();

        // 계산할 수 있는 비율은 비율로 잡는다. 분자 금액을 비율인 척 답하지 않는다.
        FinancialQuestion margin = parse("삼성전자의 2024년 영업이익률은 얼마인가?");
        assertThat(margin.isRatio()).isTrue();
        assertThat(margin.itemLabel()).isEqualTo("영업이익률");
    }

    @Test
    void stillMatchesTheItemItselfNextToConfusableWording() {
        assertThat(parse("삼성전자의 2024년 매출액은 얼마인가?").factKey()).isEqualTo("revenue");
        assertThat(parse("삼성전자의 2024년 당기순이익은 얼마인가?").factKey()).isEqualTo("net_income");
        assertThat(parse("삼성전자의 2024년 영업이익은 얼마인가?").factKey()).isEqualTo("operating_profit");
        assertThat(parse("삼성전자의 2024년 총자산은 얼마인가?").factKey()).isEqualTo("total_assets");
    }

    @Test
    void doesNotMatchQuestionWithoutFinancialItem() {
        FinancialQuestion parsed = parse(
                "한화에어로스페이스가 2025년 4월 8일 공시한 유상증자결정의 타법인 증권취득 목적 자금은 얼마인가?");

        assertThat(parsed.corpName()).isEqualTo("한화에어로스페이스");
        assertThat(parsed.factKey()).isNull();
    }

    @Test
    void readsEveryCompanyForACrossCompanyComparison() {
        FinancialQuestion parsed = parse("삼성전자와 SK하이닉스의 2024년 연결 매출액을 비교하면?");

        assertThat(parsed.corpNames()).containsExactly("삼성전자", "SK하이닉스");
        assertThat(parsed.isCorpComparison()).isTrue();
        assertThat(parsed.corpName()).isEqualTo("삼성전자");
    }

    @Test
    void isNotACrossCompanyComparisonWithOneCompany() {
        FinancialQuestion parsed = parse("삼성전자의 2024년 연결 매출액은?");

        assertThat(parsed.corpNames()).containsExactly("삼성전자");
        assertThat(parsed.isCorpComparison()).isFalse();
        assertThat(parsed.wantsCorpRanking()).isFalse();
        assertThat(parsed.lostCounterpart()).isFalse();
    }

    @Test
    void marksQuestionsThatWouldNeedTheWholeCorpus() {
        // 답으로 기업을 요구하면서 기업 이름이 하나 이하다. 70개사를 다 훑어야 한다.
        assertThat(parse("2024년 매출액이 가장 큰 기업은?").wantsCorpRanking()).isTrue();
        assertThat(parse("2024년 매출액이 삼성전자보다 큰 기업은?").wantsCorpRanking()).isTrue();
    }

    @Test
    void doesNotMarkRankingWhenTwoCompaniesAreNamed() {
        // 이름이 둘이면 그 둘만 견주면 된다.
        FinancialQuestion parsed = parse("삼성전자와 SK하이닉스 중 2024년 매출액이 더 큰 기업은?");

        assertThat(parsed.wantsCorpRanking()).isFalse();
        assertThat(parsed.isCorpComparison()).isTrue();
    }

    @Test
    void marksComparisonsWhoseCounterpartIsNotInTheCorpus() {
        // 상대를 못 알아본 채 한 기업 수치로 비교 답을 만들면 안 된다.
        assertThat(parse("삼성전자와 애플의 2024년 매출액 비교").lostCounterpart()).isTrue();
    }

    @Test
    void doesNotMistakeAPeriodComparisonForALostCounterpart() {
        // `전년과`·`2023년과`는 기간을 잇는 말이다. 연도 간 비교 경로가 처리한다.
        assertThat(parse("삼성전자 2024년 매출액을 전년과 비교하면?").lostCounterpart()).isFalse();
        assertThat(parse("삼성전자 매출액을 2023년과 비교하면?").lostCounterpart()).isFalse();
    }

    @Test
    void doesNotMistakeScopeOrItemPairsForALostCounterpart() {
        // `와`·`과` 양옆이 기업이 아니라 우리가 아는 낱말이면 상대 기업을 잃은 것이 아니다.
        assertThat(parse("삼성전자 2024년 연결과 별도 매출액 차이는 얼마나 되나?")
                .lostCounterpart()).isFalse();
        assertThat(parse("삼성전자의 2024년 매출액을 연결기준과 별도기준으로 비교하면?")
                .lostCounterpart()).isFalse();
        assertThat(parse("삼성전자의 매출액과 영업이익을 비교하면?").lostCounterpart()).isFalse();
        assertThat(parse("삼성전자 매출액과 영업이익은 2023년 대비 얼마나 늘었나?")
                .lostCounterpart()).isFalse();
        assertThat(parse("삼성전자 별도와 연결 자산총계 증감을 알려줘").lostCounterpart()).isFalse();
    }
}
