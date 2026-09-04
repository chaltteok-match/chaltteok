package com.chaltteok.ownership;

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
class OwnershipAnswerIT {

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
    void loadHoldingDocument() throws Exception {
        Path path = tempDir.resolve("holding.jsonl");
        try (InputStream in = new ClassPathResource("load/holding-samsung-20250115000001.jsonl").getInputStream()) {
            Files.copy(in, path);
        }
        LoadSummary summary = loader.load(path);
        assertThat(summary.hasFailure()).isFalse();
        assertThat(summary.succeeded()).isEqualTo(1);
    }

    @Test
    void answersRatioQuestion() throws Exception {
        when(hcxClient.generate(any(HcxPrompt.class)))
                .thenReturn(new HcxResult("지분율은 8.25%입니다.", "HCX-005", 10, 20, 30));

        mockMvc.perform(get("/answer")
                        .param("question_id", "Q-OWN-001")
                        .param("question", "삼성전자가 2025년 1월 15일 공시한 대량보유상황보고서의 지분율은 얼마인가?"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.retrieved_context").value(containsString("8.25")))
                .andExpect(jsonPath("$.think_trace").value(containsString("ownership_ratio")))
                .andExpect(jsonPath("$.answer").value(containsString("8.25")));
    }
}
