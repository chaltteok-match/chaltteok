package com.chaltteok.financial;

import static org.assertj.core.api.Assertions.assertThat;

import com.chaltteok.answer.HcxPromptFactory;
import com.chaltteok.hcx.HcxClient;
import com.chaltteok.hcx.HcxPrompt;
import com.chaltteok.hcx.HcxResult;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

/**
 * 정기공시 근거를 넣은 실제 CLOVA Studio 호출. 비용이 나므로 {@code HCX_LIVE_TEST=true}일 때만 실행한다.
 *
 * <p>확인하려는 것은 연결 여부가 아니라 <b>단위와 연결·별도 기준이 답변에 반영되는지</b>다.
 * 원문 단위가 백만원이라 모델이 이를 놓치면 300,870,903원처럼 100만 배 틀린 금액을 낸다.
 */
@SpringBootTest(properties = "hcx.enabled=true")
@EnabledIfEnvironmentVariable(named = "HCX_LIVE_TEST", matches = "true")
class FinancialHcxLiveApiTest {

    private static final String QUESTION = "삼성전자의 2024년 연결기준 매출액은 얼마인가?";
    private static final String RETRIEVED_CONTEXT = """
            [근거 1]
            공시명: 사업보고서 (2024.12)
            공시일: 2025-03-11
            기업명: 삼성전자
            근거: [가. 요약연결재무정보] 매출액 — 제56기: 300,870,903 / 제55기: 258,935,494 (단위: 백만원)
            """;
    private static final String PROCESS_SUMMARY =
            "삼성전자·2024년·매출액·연결기준 식별"
                    + " → '사업보고서 (2024.12)'(2025-03-11) 검색"
                    + " → 매출액 300조 8,709억원(300,870,903백만원) 확인"
                    + " → 근거와 답변 수치 일치 검증";

    @Autowired
    private HcxClient hcxClient;

    @Autowired
    private HcxPromptFactory hcxPromptFactory;

    @Test
    void answersRevenueWithTheUnitAndScopeFromEvidence() {
        HcxPrompt prompt = hcxPromptFactory.create(QUESTION, RETRIEVED_CONTEXT, PROCESS_SUMMARY);
        HcxResult result = hcxClient.generate(prompt);

        assertThat(result.content()).isNotBlank();
        assertThat(result.content())
                .containsAnyOf("300조", "300,870,903", "300870903", "300.87조");
        // 단위를 놓치면 백만원을 원으로 읽어 100만 배 작은 금액을 낸다.
        assertThat(result.content()).doesNotContain("300,870,903원");
        assertThat(result.content()).contains("연결");
    }
}
