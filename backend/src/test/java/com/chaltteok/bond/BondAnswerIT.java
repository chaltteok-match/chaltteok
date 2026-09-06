package com.chaltteok.bond;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.chaltteok.disclosure.load.JsonlDisclosureLoader;
import com.chaltteok.disclosure.load.LoadSummary;
import com.chaltteok.hcx.HcxClient;
import com.chaltteok.hcx.HcxPrompt;
import com.chaltteok.hcx.HcxResult;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.io.ClassPathResource;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

@SpringBootTest(properties = {
        "spring.autoconfigure.exclude=",
        "spring.profiles.active=persistence-it"
})
@ActiveProfiles("persistence-it")
@AutoConfigureMockMvc
@Testcontainers
@Transactional
class BondAnswerIT {

    @SuppressWarnings("resource")
    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16")
            .withDatabaseName("chaltteok")
            .withUsername("chaltteok")
            .withPassword("test");

    @DynamicPropertySource
    static void datasourceProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
    }

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JsonlDisclosureLoader loader;

    @MockitoBean
    private HcxClient hcxClient;

    @TempDir
    Path tempDir;

    @BeforeEach
    void loadBondDocument() throws Exception {
        Path path = tempDir.resolve("bond.jsonl");
        try (InputStream in = new ClassPathResource("load/major-cb-20230630000403.jsonl").getInputStream()) {
            Files.copy(in, path);
        }
        LoadSummary summary = loader.load(path);
        assertThat(summary.hasFailure()).isFalse();
        assertThat(summary.succeeded()).isEqualTo(1);
    }

    @Test
    void answersConvertibleFaceAmountQuestion() throws Exception {
        when(hcxClient.generate(any(HcxPrompt.class)))
                .thenReturn(new HcxResult("권면총액은 4,400억원입니다.", "HCX-005", 10, 20, 30));

        mockMvc.perform(get("/answer")
                        .param("question_id", "Q-CB-001")
                        .param("question", "에코프로비엠이 2023년 6월 30일 공시한 전환사채의 권면총액은 얼마인가?"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.retrieved_context").value(containsString("440,000,000,000")))
                .andExpect(jsonPath("$.think_trace").value(containsString("bond_face_amount")))
                .andExpect(jsonPath("$.answer").value(containsString("4,400억")));
    }

    @Test
    void answersTheSameQuestionWrittenWithAnIsoDate() throws Exception {
        when(hcxClient.generate(any(HcxPrompt.class)))
                .thenReturn(new HcxResult("권면총액은 4,400억원입니다.", "HCX-005", 10, 20, 30));

        mockMvc.perform(get("/answer")
                        .param("question_id", "Q-CB-002")
                        .param("question", "에코프로비엠이 2023-06-30 공시한 전환사채의 권면총액은 얼마인가?"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.retrieved_context").value(containsString("440,000,000,000")))
                .andExpect(jsonPath("$.think_trace").value(containsString("공시일=2023-06-30")))
                .andExpect(jsonPath("$.answer").value(containsString("4,400억")));
    }

    @Test
    void answersWithoutADateWhenTheCompanyFiledOnce() throws Exception {
        when(hcxClient.generate(any(HcxPrompt.class)))
                .thenReturn(new HcxResult("권면총액은 4,400억원입니다.", "HCX-005", 10, 20, 30));

        mockMvc.perform(get("/answer")
                        .param("question_id", "Q-CB-003")
                        .param("question", "에코프로비엠의 전환사채 권면총액은 얼마인가?"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.retrieved_context").value(containsString("440,000,000,000")))
                .andExpect(jsonPath("$.think_trace").value(containsString("공시일 없이 단건 검색")))
                .andExpect(jsonPath("$.answer").value(containsString("4,400억")));
    }
}
