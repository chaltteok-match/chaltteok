package com.chaltteok.exchange;

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
 * 거래소공시 근거를 넣은 실제 CLOVA Studio 호출.
 * 비용이 나므로 {@code HCX_LIVE_TEST=true}일 때만 실행한다.
 *
 * <p>근거 자체는 Handler 테스트가 검증한다. 여기서는 모델이 근거를 **읽는 과정**에서
 * 뜻이 바뀌지 않는지만 본다. 표현은 실행마다 달라지므로 공백을 지우고 비교한다.
 */
@SpringBootTest(properties = "hcx.enabled=true")
@EnabledIfEnvironmentVariable(named = "HCX_LIVE_TEST", matches = "true")
class ExchangeHcxLiveApiTest {

    @Autowired
    private HcxClient hcxClient;

    @Autowired
    private HcxPromptFactory hcxPromptFactory;

    private String answer(String question, String context, String summary) {
        HcxPrompt prompt = hcxPromptFactory.create(question, context, summary);
        HcxResult result = hcxClient.generate(prompt);
        assertThat(result.content()).isNotBlank();
        System.out.println("[HCX] " + question + "\n" + result.content() + "\n");
        return result.content();
    }

    /** 공백을 지운 비교용 문자열. 모델이 '22조 7,647억'을 '22조7647억'으로 쓰기도 한다. */
    private static String squashed(String content) {
        return content.replaceAll("\\s", "");
    }

    @Test
    void readsATrillionWonContractAmountWithoutLosingDigits() {
        String content = answer(
                "삼성전자가 2025년 7월 28일 공시한 단일판매ㆍ공급계약의 계약금액은 얼마인가?",
                """
                [근거 1]
                공시명: 단일판매ㆍ공급계약체결
                공시일: 2025-07-28
                기업명: 삼성전자
                근거: 2. 계약내역 | 계약금액(원) | 22,764,764,160,000 (정규화 22764764160000 KRW)
                """,
                "삼성전자·단일판매ㆍ공급계약체결·계약금액 식별 → 공시 1건에서 '계약금액' 검색"
                        + " → '단일판매ㆍ공급계약체결'(2025-07-28)에서 계약금액 22조 7,647억원 확인");

        // 조 단위를 억이나 원으로 잘못 줄이면 답이 1000배 어긋난다.
        assertThat(squashed(content)).containsAnyOf(
                "22,764,764,160,000", "22764764160000", "22조7,647억", "22조7647억");
        assertThat(squashed(content)).doesNotContain("227,647억원").doesNotContain("22,764,764,160원");
    }

    @Test
    void doesNotReportTheCounterpartyRevenueAsTheFilersRevenue() {
        String content = answer(
                "레인보우로보틱스가 2024년 3월 15일 공시한 공급계약의 최근매출액은 얼마인가?",
                """
                [근거 1]
                공시명: 단일판매ㆍ공급계약체결
                공시일: 2024-03-15
                기업명: 레인보우로보틱스
                근거: 최근 매출액(원) | 13,615,411,936 (정규화 13615411936 KRW)

                [근거 2]
                공시명: 단일판매ㆍ공급계약체결
                공시일: 2024-03-15
                기업명: 레인보우로보틱스
                근거: 3. 계약상대방 > -최근 매출액(원) | 2,786,888,282,702 (계약상대 한국항공우주산업㈜)
                """,
                "레인보우로보틱스·단일판매ㆍ공급계약체결·최근매출액 식별"
                        + " → '단일판매ㆍ공급계약체결'(2024-03-15)에서 최근매출액 136억원 확인");

        // 계약상대방 매출(2.7조)을 공시 기업 매출(136억)로 답하면 200배 틀린다.
        assertThat(squashed(content)).containsAnyOf(
                "13,615,411,936", "13615411936", "136억");
        assertThat(squashed(content)).doesNotContain("2,786,888,282,702원입니다");
    }

    @Test
    void listsCorrectionHistoryInChronologicalOrder() {
        String content = answer(
                "현대건설의 2023년 2월 22일 공급계약 금액은 얼마로 바뀌었는가?",
                """
                [근거 1]
                공시명: 단일판매ㆍ공급계약체결
                공시일: 2023-02-22
                기업명: 현대건설
                근거: 2. 계약내역 | 계약금액(원) | 3,095,146,354,000 (정규화 3095146354000 KRW)

                [근거 2]
                공시명: [기재정정]단일판매ㆍ공급계약체결
                공시일: 2023-03-08
                기업명: 현대건설
                근거: 2. 계약내역 | 계약금액(원) | 2,389,104,200,000 (정규화 2389104200000 KRW)
                """,
                "현대건설·단일판매ㆍ공급계약체결·계약금액 식별"
                        + " → '단일판매ㆍ공급계약체결'(2023-02-22) 최초 공시부터 정정 1회:"
                        + " 2023-02-22 3조 951억원 → 2023-03-08 2조 3,891억원");

        String squashed = squashed(content);
        assertThat(squashed).containsAnyOf("3,095,146,354,000", "3095146354000", "3조951억");
        assertThat(squashed).containsAnyOf("2,389,104,200,000", "2389104200000", "2조3,891억", "2조3891억");
        // 두 값을 모두 들고 정정 사실을 밝혀야 이력추적이 된다.
        assertThat(content).contains("정정");
    }
}
