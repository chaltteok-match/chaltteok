package com.chaltteok.answer;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class KoreanParticleTest {

    @Test
    void picksParticleByFinalConsonant() {
        assertThat(KoreanParticle.subject("계약금액")).isEqualTo("이");
        assertThat(KoreanParticle.subject("최근매출액")).isEqualTo("이");
        assertThat(KoreanParticle.subject("해지 주요사유")).isEqualTo("가");
        assertThat(KoreanParticle.object("매출액")).isEqualTo("을");
        assertThat(KoreanParticle.object("자기자본")).isEqualTo("을");
        assertThat(KoreanParticle.object("연도")).isEqualTo("를");
        assertThat(KoreanParticle.topic("계약상대")).isEqualTo("는");
        assertThat(KoreanParticle.topic("계약금액")).isEqualTo("은");
    }

    @Test
    void treatsNonHangulEndingsAsHavingAFinalConsonant() {
        // 받침을 알 수 없을 때는 '이'·'을'이 덜 어색하다.
        assertThat(KoreanParticle.subject("매출액대비(%)")).isEqualTo("이");
        assertThat(KoreanParticle.object("HMM")).isEqualTo("을");
        assertThat(KoreanParticle.subject("")).isEqualTo("이");
        assertThat(KoreanParticle.subject(null)).isEqualTo("이");
    }
}
