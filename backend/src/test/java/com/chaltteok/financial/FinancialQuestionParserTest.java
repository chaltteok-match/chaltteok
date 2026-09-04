package com.chaltteok.financial;

import com.chaltteok.answer.CorpNameResolver;

import static org.assertj.core.api.Assertions.assertThat;

import com.chaltteok.disclosure.model.enums.Scope;
import java.util.List;
import org.junit.jupiter.api.Test;

class FinancialQuestionParserTest {

    private static final CorpNameResolver CORPS =
            CorpNameResolver.ofNames(List.of("삼성전자", "삼성전기", "NAVER", "한화에어로스페이스"));

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
        // 비율은 금액이 아니다.
        assertThat(parse("삼성전자의 2024년 영업이익률은 얼마인가?").factKey()).isNull();
        assertThat(parse("삼성전자의 2024년 총자산회전율은 얼마인가?").factKey()).isNull();
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
}
