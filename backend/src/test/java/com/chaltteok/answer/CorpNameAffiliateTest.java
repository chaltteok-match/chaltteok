package com.chaltteok.answer;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * 코퍼스 기업 이름을 앞에 단 다른 법인을 그 기업으로 답하지 않는지 본다.
 *
 * <p>부분 문자열로 걸려 카카오뱅크 질문에 카카오 수치가 나가고 있었다.
 */
class CorpNameAffiliateTest {

    private static final CorpNameResolver RESOLVER = CorpNameResolver.ofNames(
            List.of("카카오", "NAVER", "기아", "삼성전자", "삼성생명", "미래에셋증권", "신한지주"));

    @Test
    void doesNotAnswerAboutAnAffiliateWithTheParentsNumbers() {
        assertThat(RESOLVER.resolve("카카오뱅크의 2024년 영업이익은?")).isNull();
        assertThat(RESOLVER.resolve("카카오페이의 2024년 매출액은?")).isNull();
        assertThat(RESOLVER.resolve("네이버파이낸셜의 2024년 매출액은?")).isNull();
        assertThat(RESOLVER.resolve("미래에셋생명의 2024년 매출액은?")).isNull();
        assertThat(RESOLVER.resolve("신한카드의 2024년 매출액은?")).isNull();
    }

    @Test
    void keepsTheCompanyItself() {
        assertThat(RESOLVER.resolve("카카오의 2024년 영업이익은?")).isEqualTo("카카오");
        assertThat(RESOLVER.resolve("NAVER의 2024년 매출액은?")).isEqualTo("NAVER");
        assertThat(RESOLVER.resolve("삼성생명의 2024년 매출액은?")).isEqualTo("삼성생명");
        assertThat(RESOLVER.resolve("미래에셋증권의 2024년 매출액은?")).isEqualTo("미래에셋증권");
    }

    @Test
    void keepsFormerNamesOfTheSameCompany() {
        // 기아자동차는 기아의 옛 이름이다. 자회사를 만드는 말이 아니다.
        assertThat(RESOLVER.resolve("기아자동차의 2024년 매출액은?")).isEqualTo("기아");
    }

    @Test
    void appliesToEveryCompanyInTheQuestion() {
        assertThat(RESOLVER.resolveAll("삼성전자와 카카오뱅크의 2024년 매출액을 비교하면?"))
                .containsExactly("삼성전자");
        assertThat(RESOLVER.resolveAll("삼성전자와 카카오의 2024년 매출액을 비교하면?"))
                .containsExactly("삼성전자", "카카오");
    }
}
