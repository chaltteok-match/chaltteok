package com.chaltteok.disclosure.persistence;

import static org.assertj.core.api.Assertions.assertThat;

import com.chaltteok.disclosure.load.JsonlDisclosureLoader;
import com.chaltteok.disclosure.load.LoadSummary;
import com.chaltteok.disclosure.model.DisclosureDocument;
import com.chaltteok.disclosure.model.EvidenceChunk;
import com.chaltteok.disclosure.model.PreprocessResult;
import com.chaltteok.disclosure.model.enums.ContentType;
import com.chaltteok.disclosure.model.enums.DocGroup;
import com.chaltteok.disclosure.model.enums.ManifestFileFormat;
import com.chaltteok.disclosure.model.enums.SourceFormat;
import com.chaltteok.disclosure.persistence.entity.DisclosureDocumentEntity;
import com.chaltteok.disclosure.persistence.repository.DisclosureDocumentRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.io.ClassPathResource;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.annotation.Transactional;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * 기업명·공시 유형·공시명 키워드로 저장된 문서를 걸러내는지 확인한다.
 */
@SpringBootTest(properties = {
        "spring.autoconfigure.exclude=",
        "spring.profiles.active=persistence-it"
})
@ActiveProfiles("persistence-it")
@Testcontainers
@Transactional
class DisclosureDocumentSearchIT {

    private static final String TARGET_DOC_ID = "major_20250408000012";
    private static final String FUNDRAISING_FIXTURE = "load/major-fundraising-20250408000012.jsonl";
    private static final String TREASURY_STOCK_FIXTURE = "fixtures/major-treasury-stock-20250115000001.json";

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
    private JsonlDisclosureLoader loader;

    @Autowired
    private DisclosurePersistenceService persistenceService;

    @Autowired
    private DisclosureDocumentRepository documentRepository;

    @Autowired
    private ObjectMapper objectMapper;

    @TempDir
    Path tempDir;

    @BeforeEach
    void saveMixedDocuments() throws Exception {
        LoadSummary summary = loader.load(copyClasspath(FUNDRAISING_FIXTURE));
        assertThat(summary.hasFailure()).isFalse();
        persistenceService.save(readFixture(TREASURY_STOCK_FIXTURE));
        persistenceService.save(otherCompanyFundraising());
        persistenceService.save(sameCompanyLaterFundraising());
        persistenceService.save(sameCompanyPeriodicReport());
    }

    @Test
    void findsHanwhaMajorPaidInCapitalIncreaseAmongMixedDocuments() {
        List<DisclosureDocumentEntity> found = documentRepository
                .findByCorpNameAndDocGroupAndDocSubtype(
                        "한화에어로스페이스",
                        DocGroup.MAJOR,
                        "유상증자결정"
                );

        assertThat(found).extracting(DisclosureDocumentEntity::getDocId)
                .containsExactlyInAnyOrder(TARGET_DOC_ID, "major_20250627000349");
        assertThat(found.getFirst().getReportNm()).isEqualTo("주요사항보고서(유상증자결정)");
        assertThat(found.getFirst().getCorpName()).isEqualTo("한화에어로스페이스");
        assertThat(found.getFirst().getDocGroup()).isEqualTo(DocGroup.MAJOR);
    }

    @Test
    void doesNotFindDocumentWhenCorpNameDiffers() {
        assertThat(documentRepository.findByCorpNameAndDocGroupAndDocSubtype(
                "삼성SDI",
                DocGroup.MAJOR,
                "유상증자결정"
        )).extracting(DisclosureDocumentEntity::getDocId)
                .doesNotContain(TARGET_DOC_ID)
                .containsExactly("major_20250301000001");

        assertThat(documentRepository.findByCorpNameAndDocGroupAndDocSubtype(
                "OCI홀딩스",
                DocGroup.MAJOR,
                "유상증자결정"
        )).isEmpty();
    }

    @Test
    void doesNotFindDocumentWhenDocGroupDiffers() {
        // doc_subtype은 doc_group 안에서만 의미가 있다.
        // 공시명에 '유상증자'가 들어간 사업보고서가 섞여 있어도 서식명으로는 걸리지 않는다.
        assertThat(documentRepository.findByCorpNameAndDocGroupAndDocSubtype(
                "한화에어로스페이스",
                DocGroup.PERIODIC,
                "유상증자결정"
        )).isEmpty();

        assertThat(documentRepository.findByCorpNameAndDocGroupAndDocSubtype(
                "한화에어로스페이스",
                DocGroup.PERIODIC,
                "annual"
        )).extracting(DisclosureDocumentEntity::getDocId)
                .doesNotContain(TARGET_DOC_ID)
                .containsExactly("periodic_20250311000012");
    }

    @Test
    void doesNotFindTreasuryStockDocumentWithoutKeyword() {
        assertThat(documentRepository.findById("major_20250115000001")).isPresent();
        assertThat(documentRepository.findByCorpNameAndDocGroupAndDocSubtype(
                "한화에어로스페이스",
                DocGroup.MAJOR,
                "유상증자결정"
        )).extracting(DisclosureDocumentEntity::getDocId)
                .doesNotContain("major_20250115000001")
                .containsExactlyInAnyOrder(TARGET_DOC_ID, "major_20250627000349");
    }

    @Test
    void findsOnlyDocumentsOnAskedReceiptDate() {
        assertThat(documentRepository.findByCorpNameAndDocGroupAndDocSubtypeAndRceptDt(
                "한화에어로스페이스",
                DocGroup.MAJOR,
                "유상증자결정",
                LocalDate.of(2025, 4, 8)
        )).extracting(DisclosureDocumentEntity::getDocId)
                .containsExactly(TARGET_DOC_ID);

        assertThat(documentRepository.findByCorpNameAndDocGroupAndDocSubtypeAndRceptDt(
                "한화에어로스페이스",
                DocGroup.MAJOR,
                "유상증자결정",
                LocalDate.of(2025, 6, 27)
        )).extracting(DisclosureDocumentEntity::getDocId)
                .containsExactly("major_20250627000349");
    }

    private Path copyClasspath(String location) throws IOException {
        Path path = tempDir.resolve(Path.of(location).getFileName().toString());
        try (InputStream in = new ClassPathResource(location).getInputStream()) {
            Files.copy(in, path);
        }
        return path;
    }

    private PreprocessResult readFixture(String path) throws Exception {
        return objectMapper.readValue(
                new ClassPathResource(path).getInputStream(),
                PreprocessResult.class
        );
    }

    private static PreprocessResult otherCompanyFundraising() {
        return stubResult(
                "major_20250301000001",
                "삼성SDI",
                "006400",
                "00126380",
                DocGroup.MAJOR,
                "유상증자결정",
                "주요사항보고서(유상증자결정)",
                "20250301000001",
                LocalDate.of(2025, 3, 1),
                null,
                null,
                "raw/major/삼성SDI/20250301000001"
        );
    }

    private static PreprocessResult sameCompanyLaterFundraising() {
        return stubResult(
                "major_20250627000349",
                "한화에어로스페이스",
                "012450",
                "00126566",
                DocGroup.MAJOR,
                "유상증자결정",
                "주요사항보고서(유상증자결정)",
                "20250627000349",
                LocalDate.of(2025, 6, 27),
                null,
                null,
                "raw/major/한화에어로스페이스/20250627000349"
        );
    }

    private static PreprocessResult sameCompanyPeriodicReport() {
        return stubResult(
                "periodic_20250311000012",
                "한화에어로스페이스",
                "012450",
                "00126566",
                DocGroup.PERIODIC,
                "annual",
                "사업보고서 (유상증자 관련)",
                "20250311000012",
                LocalDate.of(2025, 3, 11),
                2024,
                12,
                "raw/periodic/한화에어로스페이스/20250311000012_annual_2024_12"
        );
    }

    private static PreprocessResult stubResult(
            String docId,
            String corpName,
            String stockCode,
            String corpCode,
            DocGroup docGroup,
            String docSubtype,
            String reportNm,
            String rceptNo,
            LocalDate rceptDt,
            Integer baseYear,
            Integer baseMonth,
            String sourcePath
    ) {
        return new PreprocessResult(
                "0.2",
                "0.1.0",
                new DisclosureDocument(
                        docId,
                        corpCode,
                        corpName,
                        corpName,
                        stockCode,
                        "제조업",
                        "기계·장비",
                        docGroup,
                        docSubtype,
                        reportNm,
                        rceptNo,
                        rceptDt,
                        corpName,
                        false,
                        baseYear,
                        baseMonth,
                        sourcePath,
                        ManifestFileFormat.XML,
                        1
                ),
                List.of(new EvidenceChunk(
                        docId + ":chunk:0000",
                        docId,
                        0,
                        ContentType.PARAGRAPH,
                        reportNm,
                        sourcePath + "/" + rceptNo + ".xml",
                        SourceFormat.DART_XML,
                        null,
                        reportNm,
                        null,
                        null,
                        null
                )),
                List.of(),
                List.of()
        );
    }
}
