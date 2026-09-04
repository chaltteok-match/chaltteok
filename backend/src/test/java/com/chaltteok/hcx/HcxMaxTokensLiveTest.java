package com.chaltteok.hcx;

import static org.assertj.core.api.Assertions.assertThat;

import com.chaltteok.answer.AnswerHandler;
import com.chaltteok.answer.AnswerHandlerResult;
import com.chaltteok.answer.HcxPromptFactory;
import com.chaltteok.answer.RetrievedContextFormatter;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

/**
 * `HCX_MAX_TOKENS` 기본값을 정하기 위해 실제 출력 길이를 잰다.
 *
 * <p>CLOVA Studio TPM은 실제 소비가 아니라 {@code input token + maxTokens}로 계산된다.
 * 즉 답이 200토큰이어도 {@code maxTokens=1024}면 1024를 쓴 것으로 센다. 낮출수록 429에
 * 덜 닿지만 너무 낮추면 답이 잘리므로, 바닥이 어딘지는 실제로 재야 안다.
 *
 * <p>적재된 로컬 DB로 진짜 근거를 만들어 부른다. 질문 수만큼 호출하므로
 * {@code HCX_LIVE_TEST=true}일 때만 돈다.
 */
@SpringBootTest(properties = {
        "spring.autoconfigure.exclude=",
        "chaltteok.disclosure.persistence.enabled=true",
        "hcx.enabled=true",
        // 길이를 재는 것이 목적이므로 상한 때문에 잘리지 않게 넉넉히 준다.
        "hcx.max-tokens=2048"
})
@ActiveProfiles("local")
@EnabledIfEnvironmentVariable(named = "HCX_LIVE_TEST", matches = "true")
class HcxMaxTokensLiveTest {

    /**
     * 유형과 근거 건수를 고루 섞는다. 정정 이력이 근거가 가장 많다.
     * 맨 아래 둘은 코퍼스에서 사슬이 가장 긴 계약이다(현대건설 14회 정정, 근거 15건).
     * 답이 가장 길어지는 경우라 상한을 정할 때 이쪽을 봐야 한다.
     */
    private static final List<String> QUESTIONS = List.of(
            "삼성전자의 2024년 연결 매출액은?",
            "삼성전자의 2024년 별도 영업이익은?",
            "하나금융지주의 2024년 연결 자산총계는?",
            "현대제철의 2024년 별도 매출액은?",
            "삼성전자가 2025년 7월 31일 공시한 공급계약의 계약금액은?",
            "대우건설이 2023년 3월 10일 공시한 공급계약의 계약금액은?",
            "한화에어로스페이스가 2025년 4월 8일 결정한 유상증자의 예정 자금 조달금액은?",
            "한화에어로스페이스가 2025년 4월 8일 결정한 유상증자에서 타법인 증권취득자금을 제외한 총액은?",
            "한화에어로스페이스의 2025년 3월 20일 유상증자 예정 자금 조달금액은 어떻게 바뀌었나요?",
            "삼성SDI의 유상증자 예정 자금 조달금액이 변경된 내역은?",
            "LIG디펜스앤에어로스페이스가 2023년 4월 14일 공시한 공급계약의 계약금액은 어떻게 정정됐는가?",
            "현대건설이 2023년 6월 26일 공시한 공급계약의 계약금액은 어떻게 정정됐는가?");

    @Autowired
    private List<AnswerHandler> handlers;
    @Autowired
    private HcxPromptFactory promptFactory;
    @Autowired
    private HcxClient hcxClient;

    @Test
    void measuresCompletionTokens() {
        List<Integer> completions = new ArrayList<>();
        int maxPrompt = 0;

        System.out.println("\n=== HCX 출력 길이 측정 ===");
        System.out.printf("%-56s %8s %8s %6s%n", "질문", "입력", "출력", "글자");

        for (String question : QUESTIONS) {
            AnswerHandler handler = handlers.stream()
                    .filter(h -> h.supports(question))
                    .findFirst()
                    .orElse(null);
            if (handler == null) {
                System.out.printf("%-56s %8s%n", trim(question), "핸들러 없음");
                continue;
            }
            AnswerHandlerResult handled = handler.handle("measure", question);
            if (!handled.isAnswerable()) {
                System.out.printf("%-56s %8s%n", trim(question), "답변 불가");
                continue;
            }
            HcxPrompt prompt = promptFactory.create(
                    question,
                    RetrievedContextFormatter.format(handled.evidences()),
                    handled.processSummary());
            HcxResult result = hcxClient.generate(prompt);

            Integer in = result.promptTokens();
            Integer out = result.completionTokens();
            if (out != null) {
                completions.add(out);
            }
            if (in != null) {
                maxPrompt = Math.max(maxPrompt, in);
            }
            System.out.printf("%-56s %8s %8s %6d%n",
                    trim(question), in, out, result.content().length());
        }

        assertThat(completions).as("측정된 호출이 있어야 한다").isNotEmpty();
        int max = completions.stream().mapToInt(Integer::intValue).max().orElseThrow();
        int sum = completions.stream().mapToInt(Integer::intValue).sum();
        System.out.println("---");
        System.out.printf("출력 토큰  최대 %d / 평균 %d / 건수 %d%n", max, sum / completions.size(), completions.size());
        System.out.printf("입력 토큰  최대 %d%n", maxPrompt);
        System.out.printf("→ maxTokens 후보(최대의 2배) %d, 그때 건당 TPM %d%n",
                max * 2, maxPrompt + max * 2);
        System.out.println("=== 끝 ===\n");
    }

    private static String trim(String question) {
        return question.length() > 54 ? question.substring(0, 53) + "…" : question;
    }
}
