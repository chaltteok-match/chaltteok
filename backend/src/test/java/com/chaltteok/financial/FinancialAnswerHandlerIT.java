package com.chaltteok.financial;

import static org.assertj.core.api.Assertions.assertThat;

import com.chaltteok.answer.AnswerHandlerResult;
import com.chaltteok.answer.RetrievedEvidence;
import com.chaltteok.disclosure.model.DisclosureDocument;
import com.chaltteok.disclosure.model.EvidenceChunk;
import com.chaltteok.disclosure.model.ExtractedFact;
import com.chaltteok.disclosure.model.PreprocessResult;
import com.chaltteok.disclosure.model.enums.ContentType;
import com.chaltteok.disclosure.model.enums.DocGroup;
import com.chaltteok.disclosure.model.enums.ManifestFileFormat;
import com.chaltteok.disclosure.model.enums.ExtractionStatus;
import com.chaltteok.disclosure.persistence.entity.ExtractedFactEntity;
import com.chaltteok.disclosure.persistence.repository.ExtractedFactRepository;
import com.chaltteok.disclosure.model.enums.PeriodBasis;
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
 * 저장된 정기공시에서 근거를 찾아 조립하는 전체 경로를 검증한다.
 * 수치는 삼성전자 2024 사업보고서 실제 값이다(docs/architecture/periodic-parsing.md §10).
 */
@SpringBootTest(properties = {
        "spring.autoconfigure.exclude=",
        "spring.profiles.active=persistence-it"
})
@ActiveProfiles("persistence-it")
@Testcontainers
@Transactional
class FinancialAnswerHandlerIT {

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
    private FinancialAnswerHandler handler;

    @Autowired
    private DisclosurePersistenceService persistenceService;

    @Autowired
    private ExtractedFactRepository factRepository;

    @BeforeEach
    void setUp() {
        persistenceService.save(samsungAnnual2024());
        persistenceService.save(samsungQuarter2024());
        persistenceService.save(samsungElectroMechanics2024());
        persistenceService.save(unitUnknownDocument());
        persistenceService.save(quarterOnlyCompany2023());
    }

    private AnswerHandlerResult answer(String question) {
        return handler.handle("Q-TEST", question);
    }

    @Test
    void returnsEvidenceAndProcessSummaryForSingleLookup() {
        AnswerHandlerResult result = answer("삼성전자의 2024년 연결기준 매출액은 얼마인가?");

        assertThat(result.isAnswerable()).isTrue();
        assertThat(result.evidences()).hasSize(1);
        assertThat(result.evidences().get(0).companyName()).isEqualTo("삼성전자");
        assertThat(result.evidences().get(0).reportName()).isEqualTo("사업보고서 (2024.12)");
        assertThat(result.evidences().get(0).reportDate()).isEqualTo(LocalDate.of(2025, 3, 11));
        assertThat(result.evidences().get(0).evidenceText()).contains("300,870,903");
        assertThat(result.processSummary())
                .contains("삼성전자·2024년·매출액·연결기준 식별")
                .contains("사업보고서 (2024.12)")
                .contains("300조 8,709억원");
    }

    @Test
    void returnsUsedValuesAndFormulaForComparison() {
        AnswerHandlerResult result =
                answer("삼성전자의 2024년 연결기준 매출액은 2023년 대비 얼마나 늘었는가?");

        assertThat(result.isAnswerable()).isTrue();
        assertThat(result.processSummary())
                .contains("2024년 300조 8,709억원")
                .contains("2023년 258조 9,354억원")
                .contains("증감률=(300870903000000-258935494000000)/258935494000000×100=16.20%");
    }

    @Test
    void comparesTwoCompaniesInTheSameYear() {
        AnswerHandlerResult result =
                answer("삼성전자와 삼성전기의 2024년 연결기준 매출액을 비교하면?");

        assertThat(result.isAnswerable()).isTrue();
        // 두 기업 모두 근거가 있어야 한다. 한쪽만 주면 모델이 나머지를 지어낸다.
        assertThat(result.evidences()).hasSize(2);
        assertThat(result.evidences()).extracting(RetrievedEvidence::companyName)
                .containsExactly("삼성전자", "삼성전기");
        assertThat(result.processSummary())
                .contains("삼성전자와 삼성전기·2024년·매출액·연결기준 비교 식별")
                .contains("삼성전자 300조 8,709억원")
                .contains("삼성전기 10조 9,335억원")
                .contains("삼성전자가 삼성전기보다 289조 9,373억원 큼");
    }

    @Test
    void comparesARatioBetweenTwoCompanies() {
        AnswerHandlerResult result =
                answer("삼성전자와 삼성전기의 2024년 연결기준 영업이익률을 비교하면?");

        assertThat(result.isAnswerable()).isTrue();
        // 비율마다 분자·분모 두 건씩, 두 기업이면 네 건이다.
        // 비율마다 분자·분모 두 건씩, 두 기업이면 네 건이다.
        assertThat(result.evidences()).hasSize(4);
        assertThat(result.processSummary())
                .contains("삼성전자와 삼성전기·2024년·영업이익률·연결기준 비교 식별")
                .contains("삼성전자 영업이익률=32725961000000/300870903000000×100=10.88%")
                .contains("삼성전기 영업이익률=610000000000/10933516000000×100=5.58%")
                // 비율 차이는 %p다. 금액 차이처럼 적으면 다른 뜻이 된다.
                .contains("삼성전자가 삼성전기보다 영업이익률 5.30%p 높음");
    }

    @Test
    void refusesARatioComparisonWhenOneCompanyCannotBeComputed() {
        AnswerHandlerResult result =
                answer("삼성전자와 한미반도체의 2024년 연결기준 영업이익률을 비교하면?");

        assertThat(result.isAnswerable()).isFalse();
        assertThat(result.unavailableReason())
                .contains("한미반도체")
                .contains("비교할 수 없습니다");
    }

    @Test
    void keepsTheOrderTheQuestionUses() {
        AnswerHandlerResult result =
                answer("삼성전기와 삼성전자의 2024년 연결기준 매출액을 비교하면?");

        assertThat(result.evidences()).extracting(RetrievedEvidence::companyName)
                .containsExactly("삼성전기", "삼성전자");
        // 큰 쪽은 순서와 무관하게 값으로 가린다.
        assertThat(result.processSummary()).contains("삼성전자가 삼성전기보다");
    }

    @Test
    void refusesComparisonWhenOneCompanyHasNoValue() {
        AnswerHandlerResult result =
                answer("삼성전자와 한미반도체의 2024년 연결기준 매출액을 비교하면?");

        assertThat(result.isAnswerable()).isFalse();
        assertThat(result.unavailableReason())
                .contains("한미반도체는")
                .contains("한쪽 수치만으로는 비교할 수 없습니다");
        // 찾은 쪽 근거는 남기되 답변 가능으로 내보내지 않는다.
        assertThat(result.evidences()).hasSize(1);
        assertThat(result.evidences().get(0).companyName()).isEqualTo("삼성전자");
    }

    @Test
    void refusesToRankTheWholeCorpus() {
        // 70개사를 훑어 순위를 매기면 적재되지 않은 기업이 빠진 채 답하게 된다.
        AnswerHandlerResult result = answer("2024년 매출액이 가장 큰 기업은?");

        assertThat(result.isAnswerable()).isFalse();
        assertThat(result.unavailableReason()).contains("기업 전체를 견주어 순위를 답할 수 없습니다");
        assertThat(result.evidences()).isEmpty();
        // 기업 이름이 없어도 이 Handler가 맡아야 위 사유가 사용자에게 나간다.
        assertThat(handler.supports("2024년 매출액이 가장 큰 기업은?")).isTrue();
    }

    @Test
    void refusesWhenTheCounterpartIsNotInTheCorpus() {
        AnswerHandlerResult result = answer("삼성전자와 애플의 2024년 매출액을 비교하면?");

        assertThat(result.isAnswerable()).isFalse();
        assertThat(result.unavailableReason()).contains("비교할 기업을 제공된 공시 데이터에서 찾지 못했습니다");
        assertThat(result.evidences()).isEmpty();
    }

    @Test
    void usesSeparateValuesWhenQuestionSaysSeparate() {
        AnswerHandlerResult result = answer("삼성전자의 2024년 별도기준 매출액은 얼마인가?");

        assertThat(result.isAnswerable()).isTrue();
        assertThat(result.processSummary()).contains("별도기준 식별").contains("209조 522억원");
        assertThat(result.evidences().get(0).evidenceText()).contains("209,052,241");
    }

    @Test
    void statesUsedScopeWhenQuestionOmitsIt() {
        AnswerHandlerResult result = answer("삼성전자의 2024년 매출액은 얼마인가?");

        assertThat(result.isAnswerable()).isTrue();
        assertThat(result.processSummary())
                .contains("300조 8,709억원")
                .contains("질문에 연결·별도 기준이 없어 연결기준을 사용")
                .contains("참고: 별도기준 2024년 209조 522억원");
        // 참고로 제시한 수치에도 근거가 있어야 한다.
        assertThat(result.evidences()).hasSize(2);
        assertThat(result.evidences().get(1).evidenceText()).contains("209,052,241");
    }

    @Test
    void addsCounterpartComparisonWhenScopeOmitted() {
        AnswerHandlerResult result = answer("삼성전자의 2024년 매출액은 2023년 대비 얼마나 늘었는가?");

        assertThat(result.isAnswerable()).isTrue();
        assertThat(result.processSummary())
                .contains("증감률=(300870903000000-258935494000000)/258935494000000×100=16.20%")
                .contains("참고: 별도기준 2024년 209조 522억원, 2023년 170조 3,740억원")
                // 증감률은 사용한 기준 것만 낸다. 두 벌로 내면 어느 쪽이 답인지 흐려진다.
                .doesNotContain("22.70%");
        assertThat(result.evidences()).hasSize(2);
    }

    @Test
    void omitsCounterpartWhenScopeIsExplicit() {
        AnswerHandlerResult result = answer("삼성전자의 2024년 연결기준 매출액은 얼마인가?");

        assertThat(result.processSummary()).doesNotContain("참고:");
        assertThat(result.evidences()).hasSize(1);
    }

    @Test
    void omitsCounterpartWhenOtherScopeHasNoValue() {
        // 삼성전기 문서에는 별도 수치가 없다. 참고 수치를 만들어 내지 않는다.
        AnswerHandlerResult result = answer("삼성전기의 2024년 매출액은 얼마인가?");

        assertThat(result.isAnswerable()).isTrue();
        assertThat(result.processSummary())
                .contains("질문에 연결·별도 기준이 없어 연결기준을 사용")
                .doesNotContain("참고:");
        assertThat(result.evidences()).hasSize(1);
    }

    @Test
    void filtersByCompanyWhenOtherDocumentsExist() {
        AnswerHandlerResult result = answer("삼성전기의 2024년 연결기준 매출액은 얼마인가?");

        assertThat(result.evidences().get(0).companyName()).isEqualTo("삼성전기");
        assertThat(result.processSummary()).contains("10조 9,335억원").doesNotContain("300조");
    }

    @Test
    void prefersAnnualReportOverQuarterlyForYearlyFigure() {
        AnswerHandlerResult result = answer("삼성전자의 2024년 연결기준 매출액은 얼마인가?");

        assertThat(result.evidences().get(0).reportName()).isEqualTo("사업보고서 (2024.12)");
    }

    @Test
    void returnsUnavailableForYearMissingFromData() {
        AnswerHandlerResult result = answer("삼성전자의 2019년 연결기준 매출액은 얼마인가?");

        assertThat(result.isAnswerable()).isFalse();
        assertThat(result.evidences()).isEmpty();
        assertThat(result.unavailableReason()).contains("2019년 정기공시가 없습니다");
    }

    @Test
    void returnsUnavailableWhenQuestionPartsUnidentified() {
        AnswerHandlerResult result = answer("작년에 실적이 좋았던 기업은 어디인가?");

        assertThat(result.isAnswerable()).isFalse();
        assertThat(result.unavailableReason()).contains("식별하지 못했습니다");
    }

    @Test
    void keepsEvidenceOnlyWhenUnitIsUnknown() {
        AnswerHandlerResult result = answer("고려아연의 2023년 연결기준 자산총계는 얼마인가?");

        assertThat(result.isAnswerable()).isFalse();
        assertThat(result.evidences()).hasSize(1);
        assertThat(result.unavailableReason()).contains("단위 표기가 없어");
    }

    @Test
    void storesExtractionStatusWithEachFact() {
        // 값이 빈 이유를 데이터에 남겨야 파서 결함과 정보 한계를 구분할 수 있다.
        List<ExtractedFactEntity> facts =
                factRepository.findByDocumentDocId("periodic_20240311000892");

        assertThat(facts).isNotEmpty();
        assertThat(facts).allMatch(fact -> fact.getExtractionStatus() == ExtractionStatus.UNIT_MISSING);
        assertThat(factRepository.findByDocumentDocId("periodic_20250311001085"))
                .allMatch(fact -> fact.getExtractionStatus() == ExtractionStatus.OK);
    }

    @Test
    void skipsChangeCalculationWhenBaseYearValueMissing() {
        AnswerHandlerResult result =
                answer("삼성전기의 2024년 연결기준 매출액은 2023년 대비 얼마나 늘었는가?");

        assertThat(result.isAnswerable()).isFalse();
        assertThat(result.evidences()).hasSize(1);
        assertThat(result.unavailableReason()).contains("증감을 계산할 수 없습니다");
    }

    @Test
    void returnsUnavailableWhenOnlyPartialPeriodExistsForTheYear() {
        // 분기보고서의 3분기 누적을 연간 수치처럼 답하면 안 된다.
        AnswerHandlerResult result = answer("한미반도체의 2023년 연결기준 매출액은 얼마인가?");

        assertThat(result.isAnswerable()).isFalse();
        assertThat(result.unavailableReason()).contains("연간 수치가 아니라 일부 기간 누적");
        assertThat(result.processSummary()).contains("연간 수치 없음(일부 기간 누적만 존재)");
    }

    @Test
    void usesAnnualReportEvenWhenAQuarterlyReportSharesTheYear() {
        // 같은 연도에 둘 다 있으면 사업보고서의 연간 수치를 쓴다.
        AnswerHandlerResult result = answer("삼성전자의 2024년 연결기준 매출액은 얼마인가?");

        assertThat(result.isAnswerable()).isTrue();
        assertThat(result.processSummary()).contains("300조 8,709억원").doesNotContain("220,000,000");
    }

    @Test
    void supportsOnlyFinancialQuestions() {
        assertThat(handler.supports("삼성전자의 2024년 연결기준 매출액은 얼마인가?")).isTrue();
        assertThat(handler.supports(
                "한화에어로스페이스가 2025년 4월 8일 공시한 유상증자결정의 타법인 증권취득 목적 자금은 얼마인가?"))
                .isFalse();
        assertThat(handler.supports("오늘 날씨 어때?")).isFalse();
    }

    // --- 테스트 데이터 -----------------------------------------------------------

    private static PreprocessResult samsungAnnual2024() {
        Fixture fixture = new Fixture("periodic_20250311001085");
        fixture.document = document("periodic_20250311001085", "삼성전자", "00126380", "005930",
                "사업보고서 (2024.12)", "20250311001085", LocalDate.of(2025, 3, 11), "annual", 2024, 12);
        fixture.row("가. 요약연결재무정보", "매출액",
                "제56기: 300,870,903 / 제55기: 258,935,494 / 제54기: 302,231,360",
                "revenue", Scope.CONSOLIDATED,
                new String[]{"300,870,903", "258,935,494", "302,231,360"},
                new String[]{"300870903000000", "258935494000000", "302231360000000"},
                new int[]{2024, 2023, 2022});
        fixture.row("나. 요약별도재무정보", "매출액",
                "제56기: 209,052,241 / 제55기: 170,374,090 / 제54기: 211,867,483",
                "revenue", Scope.SEPARATE,
                new String[]{"209,052,241", "170,374,090", "211,867,483"},
                new String[]{"209052241000000", "170374090000000", "211867483000000"},
                new int[]{2024, 2023, 2022});
        fixture.row("가. 요약연결재무정보", "영업이익",
                "제56기: 32,725,961 / 제55기: 6,566,976 / 제54기: 43,376,630",
                "operating_profit", Scope.CONSOLIDATED,
                new String[]{"32,725,961", "6,566,976", "43,376,630"},
                new String[]{"32725961000000", "6566976000000", "43376630000000"},
                new int[]{2024, 2023, 2022});
        return fixture.build();
    }

    /** 같은 기준연도의 분기보고서. 사업보고서가 우선되는지 확인하기 위한 데이터다. */
    private static PreprocessResult samsungQuarter2024() {
        Fixture fixture = new Fixture("periodic_20241114002642");
        fixture.document = document("periodic_20241114002642", "삼성전자", "00126380", "005930",
                "분기보고서 (2024.09)", "20241114002642", LocalDate.of(2024, 11, 14), "quarter", 2024, 9);
        fixture.row("가. 요약연결재무정보", "매출액",
                "제56기 3분기: 220,000,000 / 제55기: 258,935,494 / 제54기: 302,231,360",
                "revenue", Scope.CONSOLIDATED,
                new String[]{"220,000,000", "258,935,494", "302,231,360"},
                new String[]{"220000000000000", "258935494000000", "302231360000000"},
                new int[]{2024, 2023, 2022}, 9);
        return fixture.build();
    }

    /** 비교 기준 연도 수치가 없는 문서. 다른 기업 필터링과 증감 계산 불가를 함께 확인한다. */
    private static PreprocessResult samsungElectroMechanics2024() {
        Fixture fixture = new Fixture("periodic_20250311001999");
        fixture.document = document("periodic_20250311001999", "삼성전기", "00126362", "009150",
                "사업보고서 (2024.12)", "20250311001999", LocalDate.of(2025, 3, 11), "annual", 2024, 12);
        fixture.row("가. 요약연결재무정보", "매출액", "제51기: 10,933,516",
                "revenue", Scope.CONSOLIDATED,
                new String[]{"10,933,516"}, new String[]{"10933516000000"}, new int[]{2024});
        // 비율 비교를 확인하려면 분모(매출액)만으로는 모자라다.
        fixture.row("가. 요약연결재무정보", "영업이익", "제51기: 610,000",
                "operating_profit", Scope.CONSOLIDATED,
                new String[]{"610,000"}, new String[]{"610000000000"}, new int[]{2024});
        return fixture.build();
    }

    /**
     * 그 연도에 분기보고서만 있는 기업. 당해 수치는 3분기 누적이라 연간 값이 아니다.
     * 실제 파서도 이 경우 period_end를 9월 30일로 남긴다.
     */
    private static PreprocessResult quarterOnlyCompany2023() {
        Fixture fixture = new Fixture("periodic_20231114000777");
        fixture.document = document("periodic_20231114000777", "한미반도체", "00164788", "042700",
                "분기보고서 (2023.09)", "20231114000777", LocalDate.of(2023, 11, 14), "quarter", 2023, 9);
        fixture.row("가. 요약연결재무정보", "매출액",
                "제44기 3분기: 121,000 / 제43기: 328,181 / 제42기: 366,246",
                "revenue", Scope.CONSOLIDATED,
                new String[]{"121,000", "328,181", "366,246"},
                new String[]{"121000000000", "328181000000", "366246000000"},
                new int[]{2023, 2022, 2021}, 9);
        return fixture.build();
    }

    /** 원문에 단위 표기가 없어 정규화하지 못한 문서(고려아연 사례). */
    private static PreprocessResult unitUnknownDocument() {
        Fixture fixture = new Fixture("periodic_20240311000892");
        fixture.document = document("periodic_20240311000892", "고려아연", "00102858", "010130",
                "사업보고서 (2023.12)", "20240311000892", LocalDate.of(2024, 3, 11), "annual", 2023, 12);
        fixture.row("1) 연결 요약재무정보", "자산총계", "제50기: 12,046,071,311,650",
                "total_assets", Scope.CONSOLIDATED,
                new String[]{"12,046,071,311,650"}, new String[]{null}, new int[]{2023});
        return fixture.build();
    }

    private static DisclosureDocument document(
            String docId, String corpName, String corpCode, String stockCode,
            String reportNm, String rceptNo, LocalDate rceptDt,
            String subtype, int baseYear, int baseMonth
    ) {
        return new DisclosureDocument(docId, corpCode, corpName, corpName, stockCode,
                "IT", "반도체·전자부품", DocGroup.PERIODIC, subtype, reportNm, rceptNo, rceptDt,
                corpName, false, baseYear, baseMonth,
                "raw/periodic/" + corpName + "/" + rceptNo, ManifestFileFormat.XML, 1);
    }

    /** 요약재무정보 표의 한 행을 chunk 하나 + 기간별 fact 여러 개로 만든다. */
    private static final class Fixture {

        private final String docId;
        private final List<EvidenceChunk> chunks = new ArrayList<>();
        private final List<ExtractedFact> facts = new ArrayList<>();
        private DisclosureDocument document;

        private Fixture(String docId) {
            this.docId = docId;
        }

        private void row(String tableTitle, String label, String text, String factKey, Scope scope,
                String[] rawValues, String[] normalizedValues, int[] years) {
            row(tableTitle, label, text, factKey, scope, rawValues, normalizedValues, years, 12);
        }

        /**
         * 실제 파서와 같은 기간을 만든다.
         * 분기·반기보고서는 당해 컬럼만 기준월까지 누적이고 직전 연도들은 연간이다.
         * 예: 2023년 1분기 보고서 → 2023-01-01~03-31, 2022-01-01~12-31, 2021-01-01~12-31
         */
        private void row(String tableTitle, String label, String text, String factKey, Scope scope,
                String[] rawValues, String[] normalizedValues, int[] years, int currentEndMonth) {
            int sequence = chunks.size();
            String chunkId = "%s:chunk:%04d".formatted(docId, sequence);
            chunks.add(new EvidenceChunk(chunkId, docId, sequence, ContentType.TABLE,
                    "[%s] %s — %s (단위: 백만원)".formatted(tableTitle, label, text),
                    "raw/periodic/sample/" + docId + ".xml", SourceFormat.DART_XML,
                    null, "1. 요약재무정보", tableTitle, label, null));

            boolean balance = factKey.startsWith("total_");
            int currentYear = years[0];
            for (int index = 0; index < years.length; index++) {
                int year = years[index];
                int endMonth = year == currentYear ? currentEndMonth : 12;
                LocalDate end = LocalDate.of(year, endMonth, 1)
                        .withDayOfMonth(LocalDate.of(year, endMonth, 1).lengthOfMonth());
                facts.add(new ExtractedFact(
                        "%s:fact:%04d".formatted(docId, facts.size()), docId, chunkId, factKey, label,
                        ValueType.NUMBER, rawValues[index],
                        normalizedValues[index] == null ? null : "백만원",
                        normalizedValues[index],
                        normalizedValues[index] == null ? null : "KRW",
                        balance ? null : LocalDate.of(year, 1, 1),
                        balance ? null : end,
                        balance ? end : null,
                        balance ? PeriodBasis.POINT_IN_TIME : PeriodBasis.CUMULATIVE,
                        scope,
                        // 단위를 확인하지 못한 값은 정규화값이 비고 상태가 남는다.
                        normalizedValues[index] == null
                                ? ExtractionStatus.UNIT_MISSING
                                : ExtractionStatus.OK));
            }
        }

        private PreprocessResult build() {
            return new PreprocessResult("0.1", "0.1.0", document, chunks, facts, List.of());
        }
    }
}
