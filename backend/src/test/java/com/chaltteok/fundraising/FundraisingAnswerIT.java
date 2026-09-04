package com.chaltteok.fundraising;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.allOf;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.chaltteok.disclosure.load.JsonlDisclosureLoader;
import com.chaltteok.disclosure.load.LoadSummary;
import com.chaltteok.hcx.HcxClient;
import com.chaltteok.hcx.HcxClientException;
import com.chaltteok.hcx.HcxErrorType;
import com.chaltteok.hcx.HcxPrompt;
import com.chaltteok.hcx.HcxResult;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.ArgumentCaptor;
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

/**
 * 대표 유상증자 질문을 GET /answer로 끝까지 확인한다.
 * 문서는 {@link JsonlDisclosureLoader}로 적재한다.
 * HCX는 CI에서 호출하지 않고 프롬프트 내용과 HTTP 매핑만 검증한다.
 * 실제 모델 호출은 {@link FundraisingHcxLiveApiTest}를 쓴다.
 */
@SpringBootTest(properties = {
        "spring.autoconfigure.exclude=",
        "spring.profiles.active=persistence-it"
})
@ActiveProfiles("persistence-it")
@AutoConfigureMockMvc
@Testcontainers
@Transactional
class FundraisingAnswerIT {

    private static final String QUESTION =
            "한화에어로스페이스가 2025년 4월 8일 공시한 유상증자결정의 타법인 증권취득 목적 자금은 얼마인가?";
    private static final String FIXTURE = "load/major-fundraising-20250408000012.jsonl";
    private static final String HCX_ANSWER =
            "한화에어로스페이스가 2025년 4월 8일 공시한 주요사항보고서(유상증자결정)에서 "
                    + "타법인 증권취득 목적 자금은 1.6조원입니다. 이 금액은 총 조달금액이 아닙니다.";

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
    void loadPaidInCapitalDocument() throws Exception {
        LoadSummary summary = loader.load(copyFixture());
        assertThat(summary.hasFailure()).isFalse();
        assertThat(summary.succeeded()).isEqualTo(1);
    }

    private Path copyFixture() throws IOException {
        Path path = tempDir.resolve("major-fundraising-20250408000012.jsonl");
        try (InputStream in = new ClassPathResource(FIXTURE).getInputStream()) {
            Files.copy(in, path);
        }
        return path;
    }

    @Test
    void answersRepresentativeQuestionWithSecuritiesAcquisitionContext() throws Exception {
        when(hcxClient.generate(any(HcxPrompt.class)))
                .thenReturn(new HcxResult(HCX_ANSWER, "HCX-005", 10, 20, 30));

        mockMvc.perform(get("/answer")
                        .param("question_id", "Q-FUND-001")
                        .param("question", QUESTION))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.question_id").value("Q-FUND-001"))
                .andExpect(jsonPath("$.question").value(QUESTION))
                .andExpect(jsonPath("$.retrieved_context").value(allOf(
                        containsString("공시명: 주요사항보고서(유상증자결정)"),
                        containsString("공시일: 2025-04-08"),
                        containsString("기업명: 한화에어로스페이스"),
                        containsString("타법인 증권취득자금"),
                        containsString("1,600,000,000,000")
                )))
                .andExpect(jsonPath("$.think_trace").value(allOf(
                        containsString("기업명=한화에어로스페이스"),
                        containsString("공시유형=major"),
                        containsString("유상증자"),
                        containsString("공시일=2025-04-08"),
                        containsString("검색"),
                        containsString("major_20250408000012"),
                        containsString("fundraising_purpose_securities_acquisition(ANC_ACQ_AMT)"),
                        containsString("1,600,000,000,000원"),
                        containsString("추출")
                )))
                .andExpect(jsonPath("$.answer").value(allOf(
                        containsString("1.6조원"),
                        containsString("타법인 증권취득")
                )));

        ArgumentCaptor<HcxPrompt> promptCaptor = ArgumentCaptor.forClass(HcxPrompt.class);
        verify(hcxClient).generate(promptCaptor.capture());
        HcxPrompt prompt = promptCaptor.getValue();
        assertThat(prompt.systemPrompt())
                .contains("총 조달금액이 아닙니다")
                .contains("목적별 금액을 총 조달금액처럼 답하지 마세요");
        assertThat(prompt.userPrompt())
                .contains(QUESTION)
                .contains("타법인 증권취득자금")
                .contains("1,600,000,000,000")
                .contains("총 조달금액이라고 쓰지 마세요");
    }

    @Test
    void answersTotalProceedsAsSumOfPurposeAmounts() throws Exception {
        when(hcxClient.generate(any(HcxPrompt.class))).thenReturn(new HcxResult(
                "한화에어로스페이스 2025년 4월 8일 유상증자의 예정 자금 조달금액은 2조 3,000억원입니다.",
                "HCX-005", 10, 20, 30));

        mockMvc.perform(get("/answer")
                        .param("question_id", "Q-FUND-TOTAL")
                        .param("question",
                                "한화에어로스페이스가 2025년 4월 8일 공시한 유상증자결정의 예정 자금 조달금액은 얼마인가?"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.think_trace").value(allOf(
                        containsString("자금 사용 목적 합계"),
                        containsString("700,020,800,000원"),
                        containsString("1,600,000,000,000원"),
                        containsString("2300020800000원")
                )))
                .andExpect(jsonPath("$.retrieved_context").value(allOf(
                        containsString("시설자금"),
                        containsString("타법인 증권취득자금")
                )))
                .andExpect(jsonPath("$.answer").value(containsString("2조 3,000억원")));
    }

    @Test
    void answersIncludeAndExcludeTogetherWithoutSubtractingIncludedPurpose() throws Exception {
        when(hcxClient.generate(any(HcxPrompt.class))).thenReturn(new HcxResult(
                "시설자금을 포함하고 타법인 증권취득을 제외한 총액은 7,000억원입니다.",
                "HCX-005", 10, 20, 30));

        mockMvc.perform(get("/answer")
                        .param("question_id", "Q-FUND-INCLUDE-EXCLUDE")
                        .param("question",
                                "한화에어로스페이스가 2025년 4월 8일 공시한 유상증자의 시설자금을 포함하고 타법인 증권취득을 제외한 총액은?"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.think_trace").value(allOf(
                        containsString("제외"),
                        containsString("700,020,800,000원")
                )))
                .andExpect(jsonPath("$.think_trace").value(not(containsString("1,600,000,000,000원"))))
                .andExpect(jsonPath("$.think_trace").value(not(containsString("자금 사용 목적 합계"))))
                .andExpect(jsonPath("$.retrieved_context").value(allOf(
                        containsString("시설자금"),
                        containsString("700,020,800,000")
                )))
                .andExpect(jsonPath("$.retrieved_context").value(not(containsString("1,600,000,000,000"))))
                .andExpect(jsonPath("$.answer").value(containsString("7,000억원")));
    }

    @Test
    void answersNewShareCountFromLoadedFacts() throws Exception {
        when(hcxClient.generate(any(HcxPrompt.class)))
                .thenReturn(new HcxResult("보통주식 신주는 4,267,200주입니다.", "HCX-005", 10, 20, 30));

        mockMvc.perform(get("/answer")
                        .param("question_id", "Q-FUND-SHARES")
                        .param("question",
                                "한화에어로스페이스가 2025년 4월 8일 공시한 유상증자의 신주 수는?"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.retrieved_context").value(containsString("4,267,200")))
                .andExpect(jsonPath("$.think_trace").value(containsString("fundraising_common_new_shares")));
    }

    @Test
    void returns503WhenHcxTimesOut() throws Exception {
        when(hcxClient.generate(any(HcxPrompt.class)))
                .thenThrow(new HcxClientException(HcxErrorType.TIMEOUT, "read timed out"));

        mockMvc.perform(get("/answer")
                        .param("question_id", "Q-FUND-503-TIMEOUT")
                        .param("question", QUESTION))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.question_id").value("Q-FUND-503-TIMEOUT"))
                .andExpect(jsonPath("$.error_code").value("SERVICE_UNAVAILABLE"))
                .andExpect(jsonPath("$.retryable").value(true));
    }

    @Test
    void returns503WhenHcxAuthenticationFails() throws Exception {
        when(hcxClient.generate(any(HcxPrompt.class)))
                .thenThrow(new HcxClientException(HcxErrorType.AUTHENTICATION, "unauthorized"));

        mockMvc.perform(get("/answer")
                        .param("question_id", "Q-FUND-503-AUTH")
                        .param("question", QUESTION))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.question_id").value("Q-FUND-503-AUTH"))
                .andExpect(jsonPath("$.error_code").value("SERVICE_UNAVAILABLE"))
                .andExpect(jsonPath("$.retryable").value(true));
    }
}
