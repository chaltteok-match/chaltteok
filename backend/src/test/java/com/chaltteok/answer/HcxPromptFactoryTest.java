package com.chaltteok.answer;

import static org.assertj.core.api.Assertions.assertThat;

import com.chaltteok.hcx.HcxPrompt;
import org.junit.jupiter.api.Test;

class HcxPromptFactoryTest {

    private static final String QUESTION =
            "한화에어로스페이스가 2025년 4월 8일 공시한 유상증자결정의 타법인 증권취득 목적 자금은 얼마인가?";
    private static final String CONTEXT = """
            [근거 1]
            공시명: 주요사항보고서(유상증자결정)
            공시일: 2025-04-08
            기업명: 한화에어로스페이스
            근거: 4. 자금조달의 목적 > 타법인 증권취득자금 (원): 1,600,000,000,000원 (정규화 1600000000000 KRW)
            """;
    private static final String PROCESS =
            "기업명=한화에어로스페이스, 공시유형=major, 공시명키워드=유상증자 검색"
                    + " → 문서 major_20250408000012 선택"
                    + " → fundraising_purpose_securities_acquisition(ANC_ACQ_AMT)=1,600,000,000,000원 추출";

    private final HcxPromptFactory factory = new HcxPromptFactory();

    @Test
    void instructsModelNotToTreatPurposeAmountAsTotalProceeds() {
        HcxPrompt prompt = factory.create(QUESTION, CONTEXT, PROCESS);

        assertThat(prompt.systemPrompt())
                .contains("타법인 증권취득자금")
                .contains("총 조달금액이 아닙니다")
                .contains("목적별 금액을 총 조달금액처럼 답하지 마세요")
                .contains("목적별 금액을 더한 값도")
                .contains("총 조달금액이라고 쓰지 마세요")
                .contains("자기주식 취득예정 수량·금액과 유상증자 신주·발행가를 혼동하지 마세요")
                .contains("예정발행가라고 밝히세요");
        assertThat(prompt.userPrompt())
                .contains(QUESTION)
                .contains(PROCESS)
                .contains("타법인 증권취득자금")
                .contains("1,600,000,000,000")
                .contains("특정 자금조달 목적 금액이면 그 목적을 답변에 명시")
                .contains("총 조달금액이라고 쓰지 마세요")
                .contains("그 값이 자금 사용 목적 합계임을 괄호로 덧붙이세요")
                .contains("예정 자금 조달금액(자금 사용 목적 합계)");
    }
}
