package com.chaltteok.answer;

import com.chaltteok.hcx.HcxPrompt;
import org.springframework.stereotype.Component;

/**
 * 검색 근거를 포함한 HyperCLOVA X 프롬프트를 만든다.
 * 목적별 자금(타법인 증권취득자금 등)을 총 조달금액으로 오인하지 않도록 지시를 포함한다.
 */
@Component
public class HcxPromptFactory {

    private static final String SYSTEM_PROMPT = """
            당신은 제공된 공시 근거만으로 답변하는 공시 분석 도우미입니다.
            근거에 없는 사실·수치·예측을 만들지 마세요.
            확인할 수 없으면 추측하지 말고 확인할 수 없다고 답하세요.
            답변에는 사용한 공시명과 공시일을 포함하세요.
            자금조달 목적별 금액(시설자금, 타법인 증권취득자금 등)은 그 목적에 한정된 금액이며 총 조달금액이 아닙니다.
            목적별 금액을 총 조달금액처럼 답하지 마세요.
            """;

    public HcxPrompt create(String question, String retrievedContext, String processSummary) {
        String userPrompt = """
                질문:
                %s

                처리 과정 요약:
                %s

                검색 근거(retrieved_context):
                %s

                위 근거만 사용해 질문에 답하세요.
                근거가 특정 자금조달 목적 금액이면 그 목적을 답변에 명시하고, 총 조달금액이라고 쓰지 마세요.
                """.formatted(question, processSummary, retrievedContext);
        return new HcxPrompt(SYSTEM_PROMPT, userPrompt);
    }
}
