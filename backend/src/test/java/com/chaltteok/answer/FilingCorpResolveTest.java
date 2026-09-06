package com.chaltteok.answer;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

/**
 * 질문에 기업이 둘일 때 그 공시를 낸 쪽을 고르는지 본다.
 *
 * <p>표기가 긴 쪽을 고르는 기본 규칙으로는 상대 기업이 이긴다. 하이브 지분을 미래에셋증권이
 * 보유한 공시를 물으면 미래에셋증권을 잡아, 답할 수 있는 질문이 답변 불가로 나갔다.
 */
class FilingCorpResolveTest {

    private static final CorpNameResolver RESOLVER =
            CorpNameResolver.ofNames(List.of("하이브", "미래에셋증권", "기아", "현대자동차", "삼성전자"));

    private static final String QUESTION = "미래에셋증권이 보유한 하이브 지분을 2024년 5월 3일 공시한 지분율은?";

    @Test
    void picksTheCompanyThatFiledTheDisclosure() {
        // 기본 규칙은 표기가 긴 미래에셋증권을 고른다.
        assertThat(RESOLVER.resolve(QUESTION)).isEqualTo("미래에셋증권");

        Set<String> filedThatDay = Set.of("하이브");
        assertThat(RESOLVER.resolve(QUESTION, filedThatDay::contains)).isEqualTo("하이브");
    }

    @Test
    void keepsTheDefaultWhenBothFiled() {
        // 둘 다 그날 공시했으면 어느 쪽을 묻는지 알 수 없다. 찍지 않는다.
        Set<String> filedThatDay = Set.of("하이브", "미래에셋증권");

        assertThat(RESOLVER.resolve(QUESTION, filedThatDay::contains)).isEqualTo("미래에셋증권");
    }

    @Test
    void keepsTheDefaultWhenNeitherFiled() {
        assertThat(RESOLVER.resolve(QUESTION, corp -> false)).isEqualTo("미래에셋증권");
    }

    @Test
    void doesNothingWhenOnlyOneCompanyIsNamed() {
        String single = "기아가 2024년 5월 3일 공시한 대량보유 지분율은?";

        assertThat(RESOLVER.resolve(single, corp -> false)).isEqualTo("기아");
        assertThat(RESOLVER.resolve(single, corp -> true)).isEqualTo("기아");
    }

    @Test
    void ordersCandidatesAsTheyAppearInTheQuestion() {
        // 앞에 나온 쪽부터 본다. 조건에 맞는 쪽이 하나면 그것을 고른다.
        String question = "현대자동차가 보유한 기아 지분을 2024년 5월 3일 공시한 지분율은?";

        assertThat(RESOLVER.resolve(question, Set.of("기아")::contains)).isEqualTo("기아");
        assertThat(RESOLVER.resolve(question, Set.of("현대자동차")::contains)).isEqualTo("현대자동차");
    }
}
