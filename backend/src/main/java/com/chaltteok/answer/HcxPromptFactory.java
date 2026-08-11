package com.chaltteok.answer;

import com.chaltteok.hcx.HcxPrompt;
import org.springframework.stereotype.Component;

/**
 * 검색 근거를 포함한 HyperCLOVA X 프롬프트를 만든다.
 */
@Component
public class HcxPromptFactory {

    private static final String SYSTEM_PROMPT = """
            당신은 제공된 공시 근거만으로 답변하는 공시 분석 도우미입니다.
            근거에 없는 사실·수치·예측을 만들지 마세요.
            확인할 수 없으면 추측하지 말고 확인할 수 없다고 답하세요.
            답변에는 사용한 공시명과 공시일을 포함하세요.
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
                """.formatted(question, processSummary, retrievedContext);
        return new HcxPrompt(SYSTEM_PROMPT, userPrompt);
    }
}
