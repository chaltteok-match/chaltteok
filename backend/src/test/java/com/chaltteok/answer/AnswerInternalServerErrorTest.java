package com.chaltteok.answer;

import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest
@AutoConfigureMockMvc
class AnswerInternalServerErrorTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private AnswerService answerService;

    @Test
    void returns500ForUnexpectedException() throws Exception {
        when(answerService.answer(anyString(), anyString()))
                .thenThrow(new RuntimeException("unexpected failure"));

        mockMvc.perform(get("/answer")
                        .param("question_id", "Q-500")
                        .param("question", "질문"))
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.question_id").value("Q-500"))
                .andExpect(jsonPath("$.error_code").value("INTERNAL_SERVER_ERROR"))
                .andExpect(jsonPath("$.message").value("서버 내부 오류가 발생했습니다."))
                .andExpect(jsonPath("$.retryable").value(false));
    }
}
