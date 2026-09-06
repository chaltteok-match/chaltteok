package com.chaltteok.financial;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.allOf;
import static org.hamcrest.Matchers.containsString;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
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
 * 정기공시 대표 질문 2개를 {@code GET /answer}로 끝까지 확인한다.
 * 문서는 {@link JsonlDisclosureLoader}로 적재해 실제 전처리 출력 경로를 그대로 쓴다.
 * HCX는 CI에서 호출하지 않고 프롬프트 내용과 HTTP 매핑만 검증한다.
 * 실제 모델 호출은 {@code HcxLiveApiTest}를 쓴다.
 */
@SpringBootTest(properties = {
        "spring.autoconfigure.exclude=",
        "spring.profiles.active=persistence-it"
})
@ActiveProfiles("persistence-it")
@AutoConfigureMockMvc
@Testcontainers
@Transactional
class FinancialAnswerIT {

    private static final String FIXTURE = "load/periodic-sample.jsonl";
    private static final String SINGLE_QUESTION = "삼성전자의 2024년 연결기준 매출액은 얼마인가?";
    private static final String COMPARISON_QUESTION =
            "삼성전자의 2024년 연결기준 매출액은 2023년 대비 얼마나 늘었는가?";
    // 코퍼스가 담는 기간 안이지만 이 픽스처의 어느 보고서에도 없는 해. 범위 밖(2019년 등)은
    // Handler에 닿기 전에 걸리므로, Handler의 '값 없음' 경로를 보려면 범위 안이어야 한다.
    // 픽스처의 두 사업보고서는 2021~2024년 값을 담고 있어 그 해들은 쓸 수 없다.
    private static final String MISSING_QUESTION = "삼성전자의 2020년 연결기준 매출액은 얼마인가?";

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
    void loadPeriodicDocuments() throws Exception {
        LoadSummary summary = loader.load(copyFixture());
        assertThat(summary.hasFailure()).isFalse();
        assertThat(summary.documents()).isEqualTo(2);
    }

    private Path copyFixture() throws IOException {
        Path path = tempDir.resolve("periodic-sample.jsonl");
        try (InputStream in = new ClassPathResource(FIXTURE).getInputStream()) {
            Files.copy(in, path);
        }
        return path;
    }

    @Test
    void answersSingleLookupWithAmountAndUnit() throws Exception {
        when(hcxClient.generate(any(HcxPrompt.class))).thenReturn(new HcxResult(
                "삼성전자의 2024년 연결기준 매출액은 300조 8,709억원(300,870,903백만원)입니다.",
                "HCX-005", 10, 20, 30));

        mockMvc.perform(get("/answer")
                        .param("question_id", "Q-PERIODIC-001")
                        .param("question", SINGLE_QUESTION))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.question_id").value("Q-PERIODIC-001"))
                .andExpect(jsonPath("$.question").value(SINGLE_QUESTION))
                .andExpect(jsonPath("$.retrieved_context").value(allOf(
                        containsString("공시명: 사업보고서 (2024.12)"),
                        containsString("공시일: 2025-03-11"),
                        containsString("기업명: 삼성전자"),
                        containsString("300,870,903")
                )))
                .andExpect(jsonPath("$.think_trace").value(allOf(
                        containsString("삼성전자·2024년·매출액·연결기준 식별"),
                        containsString("300조 8,709억원"),
                        containsString("300,870,903백만원")
                )))
                .andExpect(jsonPath("$.answer").value(containsString("300조 8,709억원")));

        // 단위와 연결·별도 기준이 프롬프트까지 전달되어야 HCX가 금액을 오인하지 않는다.
        ArgumentCaptor<HcxPrompt> captor = ArgumentCaptor.forClass(HcxPrompt.class);
        verify(hcxClient).generate(captor.capture());
        assertThat(captor.getValue().userPrompt())
                .contains(SINGLE_QUESTION)
                .contains("백만원")
                .contains("연결기준")
                .contains("300,870,903");
    }

    @Test
    void answersComparisonWithValuesAndFormula() throws Exception {
        when(hcxClient.generate(any(HcxPrompt.class))).thenReturn(new HcxResult(
                "삼성전자의 2024년 연결기준 매출액은 2023년 대비 16.20% 증가했습니다.",
                "HCX-005", 10, 20, 30));

        mockMvc.perform(get("/answer")
                        .param("question_id", "Q-PERIODIC-002")
                        .param("question", COMPARISON_QUESTION))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.think_trace").value(allOf(
                        containsString("2024년 300조 8,709억원"),
                        containsString("2023년 258조 9,354억원"),
                        containsString(
                                "증감률=(300870903000000-258935494000000)/258935494000000×100=16.20%")
                )))
                .andExpect(jsonPath("$.answer").value(containsString("16.20%")));

        ArgumentCaptor<HcxPrompt> captor = ArgumentCaptor.forClass(HcxPrompt.class);
        verify(hcxClient).generate(captor.capture());
        assertThat(captor.getValue().userPrompt()).contains("16.20%").contains("연결기준");
    }

    @Test
    void answersAYearThatOnlyAppearsInsideAnotherYearsReport() throws Exception {
        // 요약재무정보 표는 세 기간을 담는다. 2023년 사업보고서에 2021년 값이 함께 있다.
        // 기준연도가 2021년인 보고서는 코퍼스에 없다(접수가 2023-01-01부터다).
        when(hcxClient.generate(any(HcxPrompt.class))).thenReturn(new HcxResult(
                "삼성전자의 2021년 연결기준 매출액은 279조 6,047억원입니다.",
                "HCX-005", 10, 20, 30));

        mockMvc.perform(get("/answer")
                        .param("question_id", "Q-PERIODIC-OLD")
                        .param("question", "삼성전자의 2021년 연결기준 매출액은 얼마인가?"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.retrieved_context").value(containsString("279,604,799")))
                // 어느 보고서에서 나온 값인지 드러나야 한다.
                .andExpect(jsonPath("$.think_trace").value(containsString("사업보고서 (2023.12)")))
                .andExpect(jsonPath("$.think_trace").value(containsString("2021년")));
    }

    @Test
    void answersTheLatestYearWhenTheQuestionOnlySaysRecent() throws Exception {
        // 픽스처의 가장 최근 사업보고서는 2024년치다.
        when(hcxClient.generate(any(HcxPrompt.class))).thenReturn(new HcxResult(
                "삼성전자의 최근 연결기준 매출액은 300조 8,709억원입니다.",
                "HCX-005", 10, 20, 30));

        mockMvc.perform(get("/answer")
                        .param("question_id", "Q-PERIODIC-LATEST")
                        .param("question", "삼성전자의 최근 연결기준 매출액은 얼마인가?"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.retrieved_context").value(containsString("300,870,903")))
                .andExpect(jsonPath("$.think_trace").value(containsString("2024년")));
    }

    @Test
    void answersAnEverydayItemNameWrittenWithoutAYearSuffix() throws Exception {
        when(hcxClient.generate(any(HcxPrompt.class))).thenReturn(new HcxResult(
                "삼성전자의 2024년 연결기준 매출액은 300조 8,709억원입니다.",
                "HCX-005", 10, 20, 30));

        mockMvc.perform(get("/answer")
                        .param("question_id", "Q-PERIODIC-SHORT")
                        .param("question", "삼성전자 연결 매출 2024"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.retrieved_context").value(containsString("300,870,903")))
                .andExpect(jsonPath("$.think_trace").value(containsString("2024년")));
    }

    @Test
    void computesARatioFromTwoValuesAndShowsBothAsEvidence() throws Exception {
        // 영업이익 32,725,961 / 매출액 300,870,903 = 10.88%
        when(hcxClient.generate(any(HcxPrompt.class))).thenReturn(new HcxResult(
                "삼성전자의 2024년 연결기준 영업이익률은 10.88%입니다.", "HCX-005", 10, 20, 30));

        mockMvc.perform(get("/answer")
                        .param("question_id", "Q-PERIODIC-RATIO")
                        .param("question", "삼성전자의 2024년 연결기준 영업이익률은?"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.think_trace").value(allOf(
                        containsString("영업이익 32조 7,259억원"),
                        containsString("매출액 300조 8,709억원"),
                        containsString(
                                "영업이익률=32725961000000/300870903000000×100=10.88%")
                )))
                // 분자와 분모의 근거가 모두 나가야 한다.
                .andExpect(jsonPath("$.retrieved_context").value(allOf(
                        containsString("32,725,961"),
                        containsString("300,870,903")
                )))
                .andExpect(jsonPath("$.answer").value(containsString("10.88%")));
    }

    @Test
    void refusesTheRatioWhenTheDenominatorIsMissing() throws Exception {
        // 이 픽스처에는 2022년 매출액이 있지만 2021년 영업이익은 없다.
        mockMvc.perform(get("/answer")
                        .param("question_id", "Q-PERIODIC-RATIO-2")
                        .param("question", "삼성전자의 2020년 연결기준 부채비율은?"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.answer").value("제공된 공시 데이터에서는 확인할 수 없습니다."));

        verify(hcxClient, never()).generate(any(HcxPrompt.class));
    }

    @Test
    void returnsUnavailableAnswerWithNormalSchemaForMissingYear() throws Exception {
        mockMvc.perform(get("/answer")
                        .param("question_id", "Q-PERIODIC-003")
                        .param("question", MISSING_QUESTION))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.question_id").value("Q-PERIODIC-003"))
                .andExpect(jsonPath("$.question").value(MISSING_QUESTION))
                .andExpect(jsonPath("$.retrieved_context").exists())
                .andExpect(jsonPath("$.think_trace").value(
                        containsString("2020년 정기공시가 없습니다")))
                .andExpect(jsonPath("$.answer").value("제공된 공시 데이터에서는 확인할 수 없습니다."));

        // 근거가 없으면 모델을 부르지 않는다. 없는 값을 만들 여지를 주지 않기 위해서다.
        verify(hcxClient, never()).generate(any(HcxPrompt.class));
    }

    @Test
    void returns503WhenHcxTimesOut() throws Exception {
        when(hcxClient.generate(any(HcxPrompt.class)))
                .thenThrow(new HcxClientException(HcxErrorType.TIMEOUT, "read timed out"));

        mockMvc.perform(get("/answer")
                        .param("question_id", "Q-PERIODIC-503-TIMEOUT")
                        .param("question", SINGLE_QUESTION))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.question_id").value("Q-PERIODIC-503-TIMEOUT"))
                .andExpect(jsonPath("$.error_code").value("SERVICE_UNAVAILABLE"))
                .andExpect(jsonPath("$.retryable").value(true));
    }

    @Test
    void returns503WhenHcxAuthenticationFails() throws Exception {
        when(hcxClient.generate(any(HcxPrompt.class)))
                .thenThrow(new HcxClientException(HcxErrorType.AUTHENTICATION, "unauthorized"));

        mockMvc.perform(get("/answer")
                        .param("question_id", "Q-PERIODIC-503-AUTH")
                        .param("question", SINGLE_QUESTION))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.question_id").value("Q-PERIODIC-503-AUTH"))
                .andExpect(jsonPath("$.error_code").value("SERVICE_UNAVAILABLE"))
                .andExpect(jsonPath("$.retryable").value(true));
    }
}
