package com.chaltteok.answer;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
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
    void answerReturnsTemporaryResponse() throws Exception {
        mockMvc.perform(get("/answer").param("question", "삼성전자 매출액은?"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.answer").exists())
                .andExpect(jsonPath("$.status").value("skeleton"))
                .andExpect(jsonPath("$.evidences").isArray())
                .andExpect(jsonPath("$.evidences[0].disclosureName").value("백엔드 스켈레톤"));
    }

    @Test
    void answerWorksWithoutQuestionParam() throws Exception {
        mockMvc.perform(get("/answer"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("skeleton"));
    }
}
