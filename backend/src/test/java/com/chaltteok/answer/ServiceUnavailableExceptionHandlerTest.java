package com.chaltteok.answer;

import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.chaltteok.answer.web.ServiceUnavailableException;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest
@AutoConfigureMockMvc
class ServiceUnavailableExceptionHandlerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private AnswerService answerService;

    @Test
    void returns503ForServiceUnavailable() throws Exception {
        when(answerService.answer(anyString(), anyString()))
                .thenThrow(new ServiceUnavailableException(
                        "Q-503",
                        "일시적으로 서비스를 사용할 수 없습니다. 잠시 후 다시 시도해 주세요."));

        mockMvc.perform(get("/answer")
                        .param("question_id", "Q-503")
                        .param("question", "질문"))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.question_id").value("Q-503"))
                .andExpect(jsonPath("$.error_code").value("SERVICE_UNAVAILABLE"))
                .andExpect(jsonPath("$.message")
                        .value("일시적으로 서비스를 사용할 수 없습니다. 잠시 후 다시 시도해 주세요."))
                .andExpect(jsonPath("$.retryable").value(true));
    }
}
