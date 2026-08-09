package com.chaltteok.answer;

import static org.hamcrest.Matchers.nullValue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest
@AutoConfigureMockMvc
class AnswerControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @Test
    void returnsDummySuccessResponse() throws Exception {
        mockMvc.perform(get("/answer")
                        .param("question_id", "Q-001")
                        .param("question", "삼성전자 매출액은?"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.question_id").value("Q-001"))
                .andExpect(jsonPath("$.question").value("삼성전자 매출액은?"))
                .andExpect(jsonPath("$.retrieved_context").isString())
                .andExpect(jsonPath("$.think_trace").isString())
                .andExpect(jsonPath("$.answer").isString())
                .andExpect(jsonPath("$.evidences").doesNotExist())
                .andExpect(jsonPath("$.status").doesNotExist());
    }

    @Test
    void returns400WhenQuestionIdMissing() throws Exception {
        mockMvc.perform(get("/answer").param("question", "질문"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.question_id").value(nullValue()))
                .andExpect(jsonPath("$.error_code").value("INVALID_REQUEST"))
                .andExpect(jsonPath("$.message").value("question_id는 필수이며 공백일 수 없습니다."))
                .andExpect(jsonPath("$.retryable").value(false));
    }

    @Test
    void returns400WhenQuestionIdBlank() throws Exception {
        mockMvc.perform(get("/answer")
                        .param("question_id", "   ")
                        .param("question", "질문"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.question_id").value(nullValue()))
                .andExpect(jsonPath("$.error_code").value("INVALID_REQUEST"))
                .andExpect(jsonPath("$.retryable").value(false));
    }

    @Test
    void returns400WhenQuestionMissing() throws Exception {
        mockMvc.perform(get("/answer").param("question_id", "Q-003"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.question_id").value("Q-003"))
                .andExpect(jsonPath("$.error_code").value("INVALID_REQUEST"))
                .andExpect(jsonPath("$.message").value("question은 필수이며 공백일 수 없습니다."))
                .andExpect(jsonPath("$.retryable").value(false));
    }

    @Test
    void returns400WhenQuestionBlank() throws Exception {
        mockMvc.perform(get("/answer")
                        .param("question_id", "Q-003")
                        .param("question", "  "))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.question_id").value("Q-003"))
                .andExpect(jsonPath("$.error_code").value("INVALID_REQUEST"))
                .andExpect(jsonPath("$.retryable").value(false));
    }

    @Test
    void returns405ForNonGetMethod() throws Exception {
        mockMvc.perform(post("/answer")
                        .param("question_id", "Q-001")
                        .param("question", "질문"))
                .andExpect(status().isMethodNotAllowed())
                .andExpect(jsonPath("$.question_id").value("Q-001"))
                .andExpect(jsonPath("$.error_code").value("METHOD_NOT_ALLOWED"))
                .andExpect(jsonPath("$.retryable").value(false));
    }

    @Test
    void returns405WithNullQuestionIdWhenMissing() throws Exception {
        mockMvc.perform(post("/answer").param("question", "질문"))
                .andExpect(status().isMethodNotAllowed())
                .andExpect(jsonPath("$.question_id").value(nullValue()))
                .andExpect(jsonPath("$.error_code").value("METHOD_NOT_ALLOWED"))
                .andExpect(jsonPath("$.retryable").value(false));
    }
}
