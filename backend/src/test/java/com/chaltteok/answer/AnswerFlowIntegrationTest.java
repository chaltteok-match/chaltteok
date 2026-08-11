package com.chaltteok.answer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.chaltteok.hcx.HcxClient;
import com.chaltteok.hcx.HcxPrompt;
import com.chaltteok.hcx.HcxResult;
import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest
@AutoConfigureMockMvc
@Import(AnswerFlowIntegrationTest.TestHandlerConfiguration.class)
class AnswerFlowIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private HcxClient hcxClient;

    @Test
    void connectsAnswerEndpointToHandlerAndHcxClient() throws Exception {
        when(hcxClient.generate(any(HcxPrompt.class)))
                .thenReturn(new HcxResult(
                        "예시전자의 연결 매출액은 12,345억원입니다.",
                        "HCX-005",
                        10,
                        20,
                        30
                ));

        mockMvc.perform(get("/answer")
                        .param("question_id", "Q-FLOW")
                        .param("question", "예시전자의 연결 매출액은?"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.question_id").value("Q-FLOW"))
                .andExpect(jsonPath("$.question").value("예시전자의 연결 매출액은?"))
                .andExpect(jsonPath("$.retrieved_context").value(containsString(
                        "연결 매출액은 12,345억원")))
                .andExpect(jsonPath("$.think_trace").value("기업 식별 → 정기공시 검색 → 매출액 확인"))
                .andExpect(jsonPath("$.answer").value("예시전자의 연결 매출액은 12,345억원입니다."));

        ArgumentCaptor<HcxPrompt> promptCaptor = ArgumentCaptor.forClass(HcxPrompt.class);
        verify(hcxClient).generate(promptCaptor.capture());
        assertThat(promptCaptor.getValue().userPrompt())
                .contains("예시전자의 연결 매출액은?")
                .contains("2025년 사업보고서")
                .contains("연결 매출액은 12,345억원");
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class TestHandlerConfiguration {

        @Bean
        AnswerHandler integrationTestAnswerHandler() {
            return new AnswerHandler() {
                @Override
                public boolean supports(String question) {
                    return question.contains("매출액");
                }

                @Override
                public AnswerHandlerResult handle(String questionId, String question) {
                    return AnswerHandlerResult.of(
                            List.of(new RetrievedEvidence(
                                    "2025년 사업보고서",
                                    LocalDate.of(2026, 3, 20),
                                    "예시전자",
                                    "연결 매출액은 12,345억원"
                            )),
                            "기업 식별 → 정기공시 검색 → 매출액 확인"
                    );
                }
            };
        }
    }
}
