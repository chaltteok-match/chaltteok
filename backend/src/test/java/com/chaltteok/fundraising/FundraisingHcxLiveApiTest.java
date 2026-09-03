package com.chaltteok.fundraising;

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
 * 유상증자 근거를 넣은 실제 CLOVA Studio 호출. 비용이 나므로 HCX_LIVE_TEST=true일 때만 실행한다.
 */
@SpringBootTest(properties = "hcx.enabled=true")
@EnabledIfEnvironmentVariable(named = "HCX_LIVE_TEST", matches = "true")
class FundraisingHcxLiveApiTest {

    private static final String QUESTION =
            "한화에어로스페이스가 2025년 4월 8일 공시한 유상증자결정의 타법인 증권취득 목적 자금은 얼마인가?";
    private static final String RETRIEVED_CONTEXT = """
            [근거 1]
            공시명: 주요사항보고서(유상증자결정)
            공시일: 2025-04-08
            기업명: 한화에어로스페이스
            근거: 4. 자금조달의 목적 > 타법인 증권취득자금 (원): 1,600,000,000,000원 (정규화 1600000000000 KRW)
            """;
    private static final String PROCESS_SUMMARY =
            "기업명=한화에어로스페이스, 공시유형=major, 공시명키워드=유상증자 검색"
                    + " → 문서 major_20250408000012 선택"
                    + " → fundraising_purpose_securities_acquisition(ANC_ACQ_AMT)=1,600,000,000,000원 추출";

    @Autowired
    private HcxClient hcxClient;

    @Autowired
    private HcxPromptFactory hcxPromptFactory;

    @Test
    void answersSecuritiesAcquisitionAmountWithoutCallingItTotalProceeds() {
        HcxPrompt prompt = hcxPromptFactory.create(QUESTION, RETRIEVED_CONTEXT, PROCESS_SUMMARY);
        HcxResult result = hcxClient.generate(prompt);

        assertThat(result.content()).isNotBlank();
        // 모델은 '타법인'을 '타 법인'으로 띄어 쓰기도 한다. 뜻이 같으므로 공백을 지우고 본다.
        assertThat(result.content().replaceAll("\\s", ""))
                .contains("타법인")
                .containsAnyOf("1.6조", "1조6천", "1,600,000,000,000", "1600000000000");
        assertThat(result.content()).doesNotMatch("(?s).*총 조달금액은\\s*1.*");
    }

    @Test
    void answersPurposeSumWithoutCallingItTotalProceeds() {
        HcxPrompt prompt = hcxPromptFactory.create(
                "한화에어로스페이스가 2025년 4월 8일 공시한 유상증자결정의 예정 자금 조달금액은 얼마인가?",
                """
                [근거 1]
                공시명: 주요사항보고서(유상증자결정)
                공시일: 2025-04-08
                기업명: 한화에어로스페이스
                근거: 4. 자금조달의 목적 > 시설자금 (원): 700,020,800,000원 (정규화 700020800000 KRW)

                [근거 2]
                공시명: 주요사항보고서(유상증자결정)
                공시일: 2025-04-08
                기업명: 한화에어로스페이스
                근거: 4. 자금조달의 목적 > 타법인 증권취득자금 (원): 1,600,000,000,000원 (정규화 1600000000000 KRW)
                """,
                "기업명=한화에어로스페이스, 공시유형=major, 서식=유상증자결정, 공시일=2025-04-08 검색"
                        + " → 문서 major_20250408000012 선택"
                        + " → 자금 사용 목적 합계 fundraising_purpose_facility=700,020,800,000원"
                        + " + fundraising_purpose_securities_acquisition=1,600,000,000,000원"
                        + " = 2300020800000원 (2조 3,000억원)");
        HcxResult result = hcxClient.generate(prompt);

        assertThat(result.content()).isNotBlank();
        System.out.println("[HCX] 예정 자금 조달금액\n" + result.content() + "\n");
        String squashed = result.content().replaceAll("\\s", "");
        // 실제 요약은 '2300020800000원 (2조 3,000억원)'이다. 모델이 괄호 쪽을 따라 23,000억으로 쓰기도 한다.
        assertThat(squashed).containsAnyOf(
                "2.3조", "2조3천", "2조3,000억", "2조3000억", "23,000억", "23000억",
                "2,300,020,800,000", "2300020800000");
        assertThat(result.content()).contains("자금 사용 목적 합계");
    }
}
