package com.chaltteok.exchange;

import static org.assertj.core.api.Assertions.assertThat;

import com.chaltteok.answer.AnswerHandlerResult;
import com.chaltteok.answer.RetrievedEvidence;
import com.chaltteok.disclosure.model.DisclosureDocument;
import com.chaltteok.disclosure.model.DisclosureRelation;
import com.chaltteok.disclosure.model.EvidenceChunk;
import com.chaltteok.disclosure.model.ExtractedFact;
import com.chaltteok.disclosure.model.PreprocessResult;
import com.chaltteok.disclosure.model.enums.ContentType;
import com.chaltteok.disclosure.model.enums.DocGroup;
import com.chaltteok.disclosure.model.enums.ExtractionStatus;
import com.chaltteok.disclosure.model.enums.ManifestFileFormat;
import com.chaltteok.disclosure.model.enums.PeriodBasis;
import com.chaltteok.disclosure.model.enums.RelationType;
import com.chaltteok.disclosure.model.enums.Scope;
import com.chaltteok.disclosure.model.enums.SourceFormat;
import com.chaltteok.disclosure.model.enums.ValueType;
import com.chaltteok.disclosure.persistence.DisclosurePersistenceService;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.annotation.Transactional;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * 저장된 거래소공시에서 근거를 찾아 조립하는 전체 경로를 검증한다.
 * 수치는 코퍼스 실제 값이다(work/EXCHANGE_SPEC.md).
 */
@SpringBootTest(properties = {
        "spring.autoconfigure.exclude=",
        "spring.profiles.active=persistence-it"
})
@ActiveProfiles("persistence-it")
@Testcontainers
@Transactional
class ExchangeAnswerHandlerIT {

    @SuppressWarnings("resource") // @Testcontainers extension manages this container's lifecycle.
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
    private ExchangeAnswerHandler handler;

    @Autowired
    private DisclosurePersistenceService persistenceService;

    @BeforeEach
    void setUp() {
        // 삼성전자 20250728800035 — 원본, 22.7조. 검증 기준값.
        persistenceService.save(samsungContract("20250728800035", LocalDate.of(2025, 7, 28), false,
                "단일판매ㆍ공급계약체결", "22,764,764,160,000", "22764764160000"));
        // 같은 계약의 정정본. 금액은 같고, 7월 28일 공시를 고친 것이다.
        persistenceService.save(samsungCorrection("20250731800028", LocalDate.of(2025, 7, 31),
                "22,764,764,160,000", "22764764160000", LocalDate.of(2025, 7, 28),
                "exchange_20250728800035"));

        // 대우건설 2023-03-10 — 리비아 계약 원본.
        persistenceService.save(daewooContract("20230310800034", LocalDate.of(2023, 3, 10), false,
                "단일판매ㆍ공급계약체결", "1,046,363,500,000", "1046363500000", "리비아 전력청"));
        // 같은 날 접수된 별개 계약. 아래 정정본과 아무 관계가 없다.
        persistenceService.save(daewooContract("20230630800613", LocalDate.of(2023, 6, 30), false,
                "단일판매ㆍ공급계약체결", "400,119,500,000", "400119500000", "국내 발주처"));
        // 2023-03-10 계약의 정정본. 접수일은 6월 30일이지만 고친 대상은 3월 10일 공시다.
        persistenceService.save(daewooCorrection("20230630800615", LocalDate.of(2023, 6, 30),
                "1,046,363,500,000", "1046363500000", LocalDate.of(2023, 3, 10),
                "exchange_20230310800034"));
        // 다른 해의 더 큰 계약. 최댓값 질문이 연도를 지키는지 본다.
        persistenceService.save(daewooContract("20240628800100", LocalDate.of(2024, 6, 28), false,
                "단일판매ㆍ공급계약체결", "1,046,363,500,000", "1046363500000", "사우디 발주처"));
        persistenceService.save(daewooContract("20240115800200", LocalDate.of(2024, 1, 15), false,
                "단일판매ㆍ공급계약체결", "1,006,253,000,000", "1006253000000", "베트남 발주처"));

        // 같은 날 접수된 서로 다른 계약 2건. 날짜로 좁혀도 갈리는 경우다.
        persistenceService.save(daewooContract("20240228800300", LocalDate.of(2024, 2, 28), false,
                "단일판매ㆍ공급계약체결", "500,000,000,000", "500000000000", "국내 발주처 A"));
        persistenceService.save(daewooContract("20240228800400", LocalDate.of(2024, 2, 28), false,
                "단일판매ㆍ공급계약체결", "700,000,000,000", "700000000000", "국내 발주처 B"));

        // 서로 다른 두 계약과, 같은 날 함께 접수된 그 둘의 정정본.
        // 접수일로 되짚어도 원공시일이 갈리는 경우다.
        persistenceService.save(daewooContract("20220301800100", LocalDate.of(2022, 3, 1), false,
                "단일판매ㆍ공급계약체결", "300,000,000,000", "300000000000", "폴란드 발주처"));
        persistenceService.save(daewooContract("20220401800100", LocalDate.of(2022, 4, 1), false,
                "단일판매ㆍ공급계약체결", "200,000,000,000", "200000000000", "체코 발주처"));
        persistenceService.save(daewooCorrection("20221201800100", LocalDate.of(2022, 12, 1),
                "310,000,000,000", "310000000000", LocalDate.of(2022, 3, 1),
                "exchange_20220301800100"));
        persistenceService.save(daewooCorrection("20221201800200", LocalDate.of(2022, 12, 1),
                "210,000,000,000", "210000000000", LocalDate.of(2022, 4, 1),
                "exchange_20220401800100"));

        // HMM 20230214 — 신규시설투자등.
        persistenceService.save(hmmInvestment());
    }

    private AnswerHandlerResult answer(String question) {
        return handler.handle("Q-TEST", question);
    }

    @Test
    void answersTheSameForIsoAndDottedDisclosureDates() {
        // 한글만 읽던 때에는 ISO 표기에서 날짜를 못 읽고 그 기업의 다른 공시를 답했다.
        // 답변 불가가 아니라 틀린 금액이 나갔다.
        AnswerHandlerResult korean =
                answer("삼성전자가 2025년 7월 28일 공시한 공급계약의 계약금액은 얼마인가?");

        for (String written : new String[]{"2025-07-28", "2025.07.28"}) {
            AnswerHandlerResult result =
                    answer("삼성전자가 " + written + " 공시한 공급계약의 계약금액은 얼마인가?");

            assertThat(result.isAnswerable()).isTrue();
            assertThat(result.evidences()).isEqualTo(korean.evidences());
            assertThat(result.processSummary()).isEqualTo(korean.processSummary());
        }
    }

    @Test
    void answersContractAmountForTheGivenDisclosureDate() {
        AnswerHandlerResult result =
                answer("삼성전자가 2025년 7월 28일 공시한 공급계약의 계약금액은 얼마인가?");

        assertThat(result.isAnswerable()).isTrue();
        assertThat(result.evidences()).hasSize(1);
        assertThat(result.evidences().get(0).companyName()).isEqualTo("삼성전자");
        // 7월 28일 계약을 물으면 그 계약을 고친 7월 31일 정정본이 원본을 대체한다.
        assertThat(result.evidences().get(0).reportDate()).isEqualTo(LocalDate.of(2025, 7, 31));
        assertThat(result.evidences().get(0).evidenceText()).contains("22,764,764,160,000");
        assertThat(result.processSummary())
                .contains("삼성전자·단일판매ㆍ공급계약체결·계약금액 식별")
                .contains("22조 7,647억원");
    }

    @Test
    void correctionBelongsToTheDateItCorrectsNotTheDateItWasFiled() {
        // 2023-06-30에는 새 계약(4,001억)과 2023-03-10 계약의 정정본(1조 463억)이 함께 있다.
        // 접수일만 보면 서로 다른 계약이 한 질문의 후보로 섞인다.
        AnswerHandlerResult result =
                answer("대우건설이 2023년 6월 30일 공시한 공급계약의 계약금액은 얼마인가?");

        assertThat(result.isAnswerable()).isTrue();
        assertThat(result.processSummary()).contains("4,001억원").doesNotContain("1조 463억원");
        // 계약금액과 계약상대를 함께 근거로 남긴다.
        assertThat(result.evidences()).hasSize(2);
        assertThat(result.evidences().get(0).reportName()).doesNotStartWith("[기재정정]");
    }

    @Test
    void correctedFilingIsAnsweredWithTheCorrectedValue() {
        // 2023-03-10 계약을 물으면, 그 계약을 고친 정정본이 원본을 대체한다.
        AnswerHandlerResult result =
                answer("대우건설이 2023년 3월 10일 공시한 공급계약의 계약금액은 얼마인가?");

        assertThat(result.isAnswerable()).isTrue();
        assertThat(result.evidences()).hasSize(1);
        assertThat(result.evidences().get(0).reportName()).startsWith("[기재정정]");
        assertThat(result.evidences().get(0).reportDate()).isEqualTo(LocalDate.of(2023, 6, 30));
    }

    @Test
    void correctionIsReachableByItsOwnFilingDate() {
        // 2025-07-31에 올라온 것은 7월 28일 계약의 정정본이다. 정정본을 원공시일로 옮겨
        // 찾기 때문에, 접수일로 부르는 길이 없으면 그날 공시가 있는데도 답변 불가가 나간다.
        AnswerHandlerResult result =
                answer("삼성전자가 2025년 7월 31일 공시한 공급계약의 계약금액은 얼마인가?");

        assertThat(result.isAnswerable()).isTrue();
        assertThat(result.evidences()).hasSize(1);
        assertThat(result.evidences().get(0).evidenceText()).contains("22,764,764,160,000");
    }

    @Test
    void filingDateAndContractDateGiveTheSameAnswer() {
        AnswerHandlerResult byContractDate =
                answer("삼성전자가 2025년 7월 28일 공시한 공급계약의 계약금액은 얼마인가?");
        AnswerHandlerResult byFilingDate =
                answer("삼성전자가 2025년 7월 31일 공시한 공급계약의 계약금액은 얼마인가?");

        assertThat(byFilingDate.evidences())
                .extracting(RetrievedEvidence::evidenceText)
                .isEqualTo(byContractDate.evidences().stream()
                        .map(RetrievedEvidence::evidenceText)
                        .toList());
    }

    @Test
    void refusesWhenCorrectionsFiledTogetherBelongToDifferentContracts() {
        // 2022-12-01에는 3월 1일 계약과 4월 1일 계약의 정정본이 함께 접수됐다.
        // 접수일로 되짚어도 어느 계약을 묻는지 갈리므로 고르지 않는다.
        AnswerHandlerResult result =
                answer("대우건설이 2022년 12월 1일 공시한 공급계약의 계약금액은 얼마인가?");

        assertThat(result.isAnswerable()).isFalse();
        assertThat(result.unavailableReason()).contains("2022-12-01");
    }

    @Test
    void tracksHowTheAmountChangedThroughCorrections() {
        AnswerHandlerResult result =
                answer("대우건설의 2023년 3월 10일 공급계약 금액은 어떻게 정정됐는가?");

        assertThat(result.isAnswerable()).isTrue();
        assertThat(result.processSummary())
                .contains("정정 1회")
                .contains("2023-03-10")
                .contains("2023-06-30");
        // 최초 공시와 정정본이 모두 근거로 남아야 변경 과정을 확인할 수 있다.
        assertThat(result.evidences()).hasSize(2);
    }

    @Test
    void answersWithTheLatestDisclosureWhenNoDateIsGiven() {
        // 공시일이 없으면 최신 한 건으로 답하되, 어느 계약인지 근거로 드러낸다.
        AnswerHandlerResult result = answer("대우건설의 공급계약 계약금액은 얼마인가?");

        assertThat(result.isAnswerable()).isTrue();
        assertThat(result.processSummary())
                .contains("최신 공시 사용")
                .contains("2024-06-28")
                .contains("사우디 발주처")
                .contains("1조 463억원");
        // 값과 함께 계약상대를 근거로 남겨야 어느 계약을 답한 것인지 알 수 있다.
        assertThat(result.evidences()).hasSize(2);
        assertThat(result.evidences()).anyMatch(evidence ->
                evidence.evidenceText().contains("사우디 발주처"));
    }

    @Test
    void refusesWhenTheGivenDateStillHasSeveralContracts() {
        // 질문이 이미 날짜로 좁혔는데도 갈리면 더 좁힐 정보를 물어야 한다.
        AnswerHandlerResult result =
                answer("대우건설이 2024년 2월 28일 공시한 공급계약의 계약금액은 얼마인가?");

        assertThat(result.isAnswerable()).isFalse();
        assertThat(result.unavailableReason())
                // 조사를 고정하면 '대우건설가'·'계약금액가'가 된다.
                .contains("대우건설이")
                .contains("단일판매ㆍ공급계약체결이")
                .contains("계약금액이 서로 달라")
                .contains("2024-02-28");
        // 특정하지 못해도 찾은 근거는 남긴다.
        assertThat(result.evidences()).isNotEmpty();
    }

    @Test
    void answersLargestContractWithinTheGivenYear() {
        AnswerHandlerResult result =
                answer("대우건설이 2024년에 체결한 공급계약 중 가장 큰 계약금액은 얼마인가?");

        assertThat(result.isAnswerable()).isTrue();
        assertThat(result.processSummary())
                .contains("최대 1조 463억원")
                .contains("2024-06-28");
        assertThat(result.evidences()).hasSize(1);
    }

    @Test
    void ratioIsNotFormattedAsAnAmount() {
        // 7.6을 '7원'으로 읽히게 하면 안 된다. 금액만 조·억으로 바꾼다.
        AnswerHandlerResult result =
                answer("삼성전자가 2025년 7월 28일 공시한 공급계약의 매출액대비 비율은?");

        assertThat(result.isAnswerable()).isTrue();
        assertThat(result.processSummary()).contains("7.6%").doesNotContain("7원");
    }

    @Test
    void recentRevenueMatchesThePeriodicDisclosure() {
        // 거래소공시 최근매출액은 정기공시 2024 연결 매출액과 같은 값이다.
        AnswerHandlerResult result =
                answer("삼성전자가 2025년 7월 28일 공시한 공급계약의 최근매출액은?");

        assertThat(result.isAnswerable()).isTrue();
        assertThat(result.processSummary()).contains("300조 8,709억원");
    }

    @Test
    void answersFacilityInvestmentAmount() {
        AnswerHandlerResult result = answer("HMM의 2023년 신규시설투자 금액은 얼마인가?");

        assertThat(result.isAnswerable()).isTrue();
        assertThat(result.processSummary()).contains("1조 4,128억원");
        assertThat(result.evidences().get(0).reportName()).isEqualTo("신규시설투자등");
    }

    @Test
    void doesNotAnswerContractQuestionsWithTerminationDisclosures() {
        // 해지 공시가 없는 기업에 해지를 물으면 체결 공시로 답하지 않는다.
        AnswerHandlerResult result = answer("삼성전자의 공급계약해지 금액은 얼마인가?");

        assertThat(result.isAnswerable()).isFalse();
        assertThat(result.unavailableReason()).contains("단일판매ㆍ공급계약해지");
    }

    @Test
    void declinesQuestionsAboutOtherDisclosureTypes() {
        assertThat(handler.supports("삼성전자의 2024년 연결기준 매출액은 얼마인가?")).isFalse();
        assertThat(handler.supports("삼성전자가 2025년 7월 28일 공시한 공급계약금액은?")).isTrue();
    }

    private static PreprocessResult samsungContract(
            String rceptNo, LocalDate rceptDt, boolean correction,
            String reportNm, String rawAmount, String normalizedAmount) {
        Fixture fixture = new Fixture("exchange_" + rceptNo);
        fixture.document = document("exchange_" + rceptNo, "삼성전자", "00126380", "005930",
                reportNm, rceptNo, rceptDt, correction);
        fixture.row("2. 계약내역", "계약금액(원)", "contract_amount",
                rawAmount, normalizedAmount, "원", "KRW", rceptDt);
        fixture.row(null, "최근매출액(원)", "contract_recent_revenue",
                "300,870,903,000,000", "300870903000000", "원", "KRW", rceptDt);
        fixture.row(null, "매출액대비(%)", "contract_revenue_ratio",
                "7.6", "7.6", "%", "%", rceptDt);
        return fixture.build();
    }

    /** 삼성전자 정정본. 실제 20250731800028처럼 고친 대상의 접수일을 함께 담는다. */
    private static PreprocessResult samsungCorrection(
            String rceptNo, LocalDate rceptDt, String rawAmount, String normalizedAmount,
            LocalDate targetFilingDate, String originalDocId) {
        String docId = "exchange_" + rceptNo;
        Fixture fixture = new Fixture(docId);
        fixture.document = document(docId, "삼성전자", "00126380", "005930",
                "[기재정정]단일판매ㆍ공급계약체결", rceptNo, rceptDt, true);
        fixture.row(null, "정정관련 공시서류제출일", "correction_target_filing_date",
                targetFilingDate.toString(), targetFilingDate.toString(), null, null, rceptDt,
                ValueType.DATE);
        fixture.row("2. 계약내역", "계약금액(원)", "contract_amount",
                rawAmount, normalizedAmount, "원", "KRW", rceptDt);
        // 정정본도 원본과 같은 항목을 모두 담는다. 실제 원문이 그렇다.
        fixture.row(null, "최근매출액(원)", "contract_recent_revenue",
                "300,870,903,000,000", "300870903000000", "원", "KRW", rceptDt);
        fixture.row(null, "매출액대비(%)", "contract_revenue_ratio",
                "7.6", "7.6", "%", "%", rceptDt);
        fixture.relations.add(new DisclosureRelation(
                "%s:%s:correction".formatted(originalDocId, docId),
                originalDocId, originalDocId, docId, RelationType.CORRECTION,
                null, null, List.of(), List.of(fixture.chunks.get(0).chunkId())));
        return fixture.build();
    }

    private static PreprocessResult daewooContract(
            String rceptNo, LocalDate rceptDt, boolean correction,
            String reportNm, String rawAmount, String normalizedAmount, String counterparty) {
        Fixture fixture = new Fixture("exchange_" + rceptNo);
        fixture.document = document("exchange_" + rceptNo, "대우건설", "00164839", "047040",
                reportNm, rceptNo, rceptDt, correction);
        fixture.row("2. 계약내역", "계약금액(원)", "contract_amount",
                rawAmount, normalizedAmount, "원", "KRW", rceptDt);
        if (counterparty != null) {
            fixture.row(null, "3. 계약상대", "contract_counterparty",
                    counterparty, null, null, null, rceptDt, ValueType.TEXT);
        }
        return fixture.build();
    }

    /** 정정본. 고친 대상의 접수일과 원본과의 관계를 함께 만든다. */
    private static PreprocessResult daewooCorrection(
            String rceptNo, LocalDate rceptDt, String rawAmount, String normalizedAmount,
            LocalDate targetFilingDate, String originalDocId) {
        String docId = "exchange_" + rceptNo;
        Fixture fixture = new Fixture(docId);
        fixture.document = document(docId, "대우건설", "00164839", "047040",
                "[기재정정]단일판매ㆍ공급계약체결", rceptNo, rceptDt, true);
        fixture.row(null, "정정관련 공시서류제출일", "correction_target_filing_date",
                targetFilingDate.toString(), targetFilingDate.toString(), null, null, rceptDt,
                ValueType.DATE);
        fixture.row("2. 계약내역", "계약금액(원)", "contract_amount",
                rawAmount, normalizedAmount, "원", "KRW", rceptDt);
        fixture.relations.add(new DisclosureRelation(
                "%s:%s:correction".formatted(originalDocId, docId),
                originalDocId, originalDocId, docId, RelationType.CORRECTION,
                null, null, List.of(), List.of(fixture.chunks.get(0).chunkId())));
        return fixture.build();
    }

    private static PreprocessResult hmmInvestment() {
        LocalDate rceptDt = LocalDate.of(2023, 2, 14);
        Fixture fixture = new Fixture("exchange_20230214800100");
        fixture.document = document("exchange_20230214800100", "HMM", "00164645", "011200",
                "신규시설투자등", "20230214800100", rceptDt, false);
        fixture.row("2. 투자내역", "투자금액(원)", "investment_amount",
                "1,412,820,295,400", "1412820295400", "원", "KRW", rceptDt);
        fixture.row(null, "자기자본대비(%)", "investment_equity_ratio",
                "13.64", "13.64", "%", "%", rceptDt);
        return fixture.build();
    }

    private static DisclosureDocument document(
            String docId, String corpName, String corpCode, String stockCode,
            String reportNm, String rceptNo, LocalDate rceptDt, boolean correction) {
        return new DisclosureDocument(docId, corpCode, corpName, corpName, stockCode,
                "제조업", "전기·전자", DocGroup.EXCHANGE, null, reportNm, rceptNo, rceptDt,
                corpName, correction, null, null,
                "raw/exchange/" + corpName + "/" + rceptNo, ManifestFileFormat.XML, 1);
    }

    /** 거래소공시는 표 한 행이 chunk 하나 + fact 하나다. */
    private static final class Fixture {

        private final String docId;
        private final List<EvidenceChunk> chunks = new ArrayList<>();
        private final List<ExtractedFact> facts = new ArrayList<>();
        private final List<DisclosureRelation> relations = new ArrayList<>();
        private DisclosureDocument document;

        private Fixture(String docId) {
            this.docId = docId;
        }

        private void row(String section, String label, String factKey,
                String rawValue, String normalizedValue,
                String rawUnit, String normalizedUnit, LocalDate asOfDate) {
            row(section, label, factKey, rawValue, normalizedValue, rawUnit, normalizedUnit,
                    asOfDate, ValueType.NUMBER);
        }

        private void row(String section, String label, String factKey,
                String rawValue, String normalizedValue,
                String rawUnit, String normalizedUnit, LocalDate asOfDate, ValueType valueType) {
            int sequence = chunks.size();
            String chunkId = "%s:chunk:%04d".formatted(docId, sequence);
            String text = section == null
                    ? "%s | %s".formatted(label, rawValue)
                    : "%s | %s | %s".formatted(section, label, rawValue);
            chunks.add(new EvidenceChunk(chunkId, docId, sequence, ContentType.TABLE, text,
                    "raw/exchange/sample/" + docId + ".xml", SourceFormat.HTML,
                    null, section, section, label, null));
            facts.add(new ExtractedFact(
                    "%s:fact:%04d".formatted(docId, facts.size()), docId, chunkId, factKey, label,
                    valueType, rawValue, rawUnit, normalizedValue, normalizedUnit,
                    null, null, asOfDate,
                    // 거래소공시는 사건 공시다. 값은 접수 시점의 상태를 가리킨다.
                    PeriodBasis.POINT_IN_TIME, Scope.NOT_APPLICABLE, ExtractionStatus.OK));
        }

        private PreprocessResult build() {
            return new PreprocessResult("0.2", "0.2.0", document, chunks, facts, relations);
        }
    }
}
