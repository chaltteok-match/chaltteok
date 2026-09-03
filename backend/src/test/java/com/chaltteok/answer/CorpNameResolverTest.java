package com.chaltteok.answer;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * 적재된 코퍼스에 실제로 있는 표기로 검증한다.
 * 사명·상장 표기·종목코드는 {@code disclosure_document}에서 그대로 가져온 값이다.
 */
class CorpNameResolverTest {

    private static final CorpNameResolver RESOLVER = CorpNameResolver.of(List.of(
            new CorpIdentity("삼성전자", "삼성전자", "005930", "00126380"),
            new CorpIdentity("삼성전기", "삼성전기", "009150", "00126186"),
            new CorpIdentity("현대자동차", "현대차", "005380", "00164742"),
            new CorpIdentity("현대건설", "현대건설", "000720", "00164478"),
            new CorpIdentity("SK하이닉스", "SK하이닉스", "000660", "00164779"),
            new CorpIdentity("POSCO홀딩스", "POSCO홀딩스", "005490", "00155319"),
            new CorpIdentity("기아", "기아", "000270", "00106641"),
            new CorpIdentity("LG에너지솔루션", "LG에너지솔루션", "373220", "01515323"),
            new CorpIdentity("NC", "엔씨소프트", "036570", "00261443"),
            new CorpIdentity("케이티", "KT", "030200", "00126257"),
            new CorpIdentity("엘에스일렉트릭", "LS ELECTRIC", "010120", "00110875"),
            new CorpIdentity("삼성화재해상보험", "삼성화재", "000810", "00126362"),
            new CorpIdentity("우리금융지주", "우리금융지주", "316140", "01515326")));

    @ParameterizedTest
    @CsvSource({
            "삼성전자의 2024년 매출액은?, 삼성전자",
            "005930의 2024년 매출액은?, 삼성전자",
            "삼성전자(005930) 매출액은?, 삼성전자",
    })
    void 공식_사명과_종목코드로_찾는다(String question, String expected) {
        assertThat(RESOLVER.resolve(question)).isEqualTo(expected);
    }

    @ParameterizedTest
    @CsvSource({
            "현대차 2024년 영업이익은?, 현대자동차",
            "삼성화재 자산총계는?, 삼성화재해상보험",
            "엔씨소프트 매출액은?, NC",
            "KT 매출액은?, 케이티",
    })
    void 상장_표기로_물어도_공식_사명을_돌려준다(String question, String expected) {
        assertThat(RESOLVER.resolve(question)).isEqualTo(expected);
    }

    @ParameterizedTest
    @CsvSource({
            "에스케이하이닉스 자산총계는?, SK하이닉스",
            "엘지에너지솔루션 매출액은?, LG에너지솔루션",
            "포스코 매출액은?, POSCO홀딩스",
            "엘에스일렉트릭 매출액은?, 엘에스일렉트릭",
            "LS일렉트릭 매출액은?, 엘에스일렉트릭",
    })
    void 한글_음차와_영문_약자를_같은_것으로_본다(String question, String expected) {
        assertThat(RESOLVER.resolve(question)).isEqualTo(expected);
    }

    @ParameterizedTest
    @CsvSource({
            "포스코 매출액은?, POSCO홀딩스",
            "우리금융 자산총계는?, 우리금융지주",
    })
    void 지주_접미어를_빼고_물어도_찾는다(String question, String expected) {
        assertThat(RESOLVER.resolve(question)).isEqualTo(expected);
    }

    @Test
    void 영문_앞머리를_뺀_표기도_찾는다() {
        assertThat(RESOLVER.resolve("하이닉스 자산총계는?")).isEqualTo("SK하이닉스");
    }

    @Test
    void 사명이_질문_표기_안에_들어_있어도_찾는다() {
        assertThat(RESOLVER.resolve("기아자동차 영업이익은?")).isEqualTo("기아");
    }

    @ParameterizedTest
    @ValueSource(strings = {"현대 매출액은?", "현대의 2024년 영업이익은?"})
    void 여러_기업에_걸리는_표기는_고르지_않는다(String question) {
        assertThat(RESOLVER.resolve(question)).isNull();
    }

    @Test
    void 긴_표기가_짧은_표기를_이긴다() {
        assertThat(RESOLVER.resolve("삼성전기 매출액은?")).isEqualTo("삼성전기");
        assertThat(RESOLVER.resolve("삼성전자 매출액은?")).isEqualTo("삼성전자");
    }

    @Test
    void 긴_숫자_안에_들어_있는_6자리는_종목코드로_읽지_않는다() {
        // 접수번호 20250408000012 안에 '005380' 같은 값이 우연히 들어갈 수 있다.
        assertThat(RESOLVER.resolve("접수번호 20050538000012 공시의 금액은?")).isNull();
    }

    @Test
    void 기업을_찾지_못하면_null이다() {
        assertThat(RESOLVER.resolve("작년 코스피 지수는?")).isNull();
        assertThat(RESOLVER.resolve(null)).isNull();
        assertThat(RESOLVER.resolve("")).isNull();
    }

    @Test
    void 사명만_주면_사명_표기만_푼다() {
        CorpNameResolver names = CorpNameResolver.ofNames(List.of("삼성전자", "대우건설"));
        assertThat(names.resolve("삼성전자 매출액은?")).isEqualTo("삼성전자");
        assertThat(names.resolve("005930 매출액은?")).isNull();
    }
}
