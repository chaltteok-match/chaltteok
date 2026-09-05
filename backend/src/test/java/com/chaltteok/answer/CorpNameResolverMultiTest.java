package com.chaltteok.answer;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;

/** 질문에 나온 기업을 모두 푸는 경로. 단일 해석은 {@link CorpNameResolverTest}가 본다. */
class CorpNameResolverMultiTest {

    private static final CorpNameResolver RESOLVER = CorpNameResolver.of(List.of(
            new CorpIdentity("삼성전자", "삼성전자", "005930", "00126380"),
            new CorpIdentity("SK하이닉스", "SK하이닉스", "000660", "00164779"),
            new CorpIdentity("현대자동차", "현대차", "005380", "00164742"),
            new CorpIdentity("LG이노텍", "LG이노텍", "011070", "00105873")));

    @Test
    void readsEveryCompanyInAppearanceOrder() {
        assertThat(RESOLVER.resolveAll("삼성전자와 SK하이닉스의 2024년 매출액을 비교하면?"))
                .containsExactly("삼성전자", "SK하이닉스");
        assertThat(RESOLVER.resolveAll("SK하이닉스와 삼성전자 중 어느 쪽이 큰가?"))
                .containsExactly("SK하이닉스", "삼성전자");
        assertThat(RESOLVER.resolveAll("삼성전자, SK하이닉스, 현대차의 자산총계"))
                .containsExactly("삼성전자", "SK하이닉스", "현대자동차");
    }

    @Test
    void readsAliasesAndStockCodes() {
        assertThat(RESOLVER.resolveAll("현대차와 에스케이하이닉스의 영업이익"))
                .containsExactly("현대자동차", "SK하이닉스");
        assertThat(RESOLVER.resolveAll("005930과 000660의 매출액"))
                .containsExactly("삼성전자", "SK하이닉스");
    }

    @Test
    void keepsOneEntryWhenTheSameCompanyAppearsTwice() {
        assertThat(RESOLVER.resolveAll("삼성전자 2024년 매출액은 삼성전자 2023년보다 컸나?"))
                .containsExactly("삼성전자");
    }

    @Test
    void prefersTheLongerNameWhenNamesOverlap() {
        // `LG이노텍`을 잡은 자리에서 `LG`를 다시 잡으면 없는 기업이 하나 더 생긴다.
        assertThat(RESOLVER.resolveAll("LG이노텍의 2024년 매출액")).containsExactly("LG이노텍");
    }

    @Test
    void isEmptyWhenNoCompanyIsNamed() {
        assertThat(RESOLVER.resolveAll("2024년 매출액이 가장 큰 기업은?")).isEmpty();
        assertThat(RESOLVER.resolveAll(null)).isEmpty();
    }
}
