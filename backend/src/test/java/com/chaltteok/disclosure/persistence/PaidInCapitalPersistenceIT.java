package com.chaltteok.disclosure.persistence;

import static org.assertj.core.api.Assertions.assertThat;

import com.chaltteok.disclosure.load.JsonlDisclosureLoader;
import com.chaltteok.disclosure.load.LoadSummary;
import com.chaltteok.disclosure.model.DisclosureDocument;
import com.chaltteok.disclosure.model.EvidenceChunk;
import com.chaltteok.disclosure.model.ExtractedFact;
import com.chaltteok.disclosure.model.PreprocessResult;
import com.chaltteok.disclosure.model.enums.DocGroup;
import com.chaltteok.disclosure.persistence.entity.DisclosureDocumentEntity;
import com.chaltteok.disclosure.persistence.entity.EvidenceChunkEntity;
import com.chaltteok.disclosure.persistence.entity.ExtractedFactEntity;
import com.chaltteok.disclosure.persistence.repository.DisclosureDocumentRepository;
import com.chaltteok.disclosure.persistence.repository.DisclosureRelationRepository;
import com.chaltteok.disclosure.persistence.repository.EvidenceChunkRepository;
import com.chaltteok.disclosure.persistence.repository.ExtractedFactRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;
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
 * 유상증자결정 파서 JSONL을 {@link JsonlDisclosureLoader}로 적재한 뒤
 * Repository 재조회 값이 원본과 같은지 확인한다.
 */
@SpringBootTest(properties = {
        "spring.autoconfigure.exclude=",
        "spring.profiles.active=persistence-it"
})
@ActiveProfiles("persistence-it")
@Testcontainers
@Transactional
class PaidInCapitalPersistenceIT {

    private static final String DOC_ID = "major_20250408000012";
    private static final String FIXTURE = "load/major-fundraising-20250408000012.jsonl";

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
    private DisclosureDocumentRepository documentRepository;

    @Autowired
    private EvidenceChunkRepository chunkRepository;

    @Autowired
    private ExtractedFactRepository factRepository;

    @Autowired
    private DisclosureRelationRepository relationRepository;

    @Autowired
    private ObjectMapper objectMapper;

    @TempDir
    Path tempDir;

    @Test
    void savesParserJsonlThroughLoaderAndReloadsMatchingOriginal() throws Exception {
        PreprocessResult original = objectMapper.readValue(
                new ClassPathResource(FIXTURE).getInputStream(),
                PreprocessResult.class
        );

        LoadSummary summary = loader.load(copyFixture());
        assertThat(summary.hasFailure()).isFalse();
        assertThat(summary.succeeded()).isEqualTo(1);
        assertThat(summary.documents()).isEqualTo(1);

        DisclosureDocumentEntity document = documentRepository.findById(DOC_ID).orElseThrow();
        assertDocumentMatches(document, original.document(), original);

        List<EvidenceChunkEntity> chunks =
                chunkRepository.findByDocumentDocIdOrderBySequenceAsc(DOC_ID);
        assertChunksMatch(chunks, original.evidenceChunks());

        List<ExtractedFactEntity> facts = factRepository.findByDocumentDocId(DOC_ID);
        assertFactsMatch(facts, original.extractedFacts());

        ExtractedFactEntity expectedAmount = factRepository.findByFactKey(
                        "fundraising_purpose_securities_acquisition")
                .stream()
                .filter(fact -> DOC_ID.equals(fact.getDocument().getDocId()))
                .findFirst()
                .orElseThrow();
        assertThat(expectedAmount.getRawValue()).isEqualTo("1,600,000,000,000");
        assertThat(expectedAmount.getNormalizedValue()).isEqualTo("1600000000000");
        assertThat(expectedAmount.getNormalizedUnit()).isEqualTo("KRW");

        assertThat(relationRepository.findByOriginalDocumentDocIdOrRelatedDocumentDocId(DOC_ID, DOC_ID))
                .isEmpty();
        assertThat(original.relations()).isEmpty();
        assertThat(documentRepository.count()).isEqualTo(1);
    }

    private Path copyFixture() throws IOException {
        Path path = tempDir.resolve("major-fundraising-20250408000012.jsonl");
        try (InputStream in = new ClassPathResource(FIXTURE).getInputStream()) {
            Files.copy(in, path);
        }
        return path;
    }

    private static void assertDocumentMatches(
            DisclosureDocumentEntity actual,
            DisclosureDocument expected,
            PreprocessResult original
    ) {
        assertThat(actual.getDocId()).isEqualTo(expected.docId());
        assertThat(actual.getSchemaVersion()).isEqualTo(original.schemaVersion());
        assertThat(actual.getParserVersion()).isEqualTo(original.parserVersion());
        assertThat(actual.getCorpCode()).isEqualTo("00126566");
        assertThat(actual.getCorpName()).isEqualTo("한화에어로스페이스");
        assertThat(actual.getListedName()).isEqualTo(expected.listedName());
        assertThat(actual.getStockCode()).isEqualTo(expected.stockCode());
        assertThat(actual.getIndustry()).isEqualTo(expected.industry());
        assertThat(actual.getSector()).isEqualTo(expected.sector());
        assertThat(actual.getDocGroup()).isEqualTo(DocGroup.MAJOR);
        assertThat(actual.getDocSubtype()).isEqualTo(expected.docSubtype());
        assertThat(actual.getReportNm()).isEqualTo("주요사항보고서(유상증자결정)");
        assertThat(actual.getRceptNo()).isEqualTo("20250408000012");
        assertThat(actual.getRceptDt()).isEqualTo(LocalDate.of(2025, 4, 8));
        assertThat(actual.getFlrNm()).isEqualTo(expected.flrNm());
        assertThat(actual.isCorrection()).isEqualTo(expected.isCorrection());
        assertThat(actual.getBaseYear()).isEqualTo(expected.baseYear());
        assertThat(actual.getBaseMonth()).isEqualTo(expected.baseMonth());
        assertThat(actual.getSourcePath()).isEqualTo(expected.sourcePath());
        assertThat(actual.getManifestFileFormat()).isEqualTo(expected.manifestFileFormat());
        assertThat(actual.getSourceFileCount()).isEqualTo(expected.sourceFileCount());
    }

    private static void assertChunksMatch(
            List<EvidenceChunkEntity> actual,
            List<EvidenceChunk> expected
    ) {
        assertThat(actual).hasSameSizeAs(expected);
        for (int i = 0; i < expected.size(); i++) {
            EvidenceChunkEntity entity = actual.get(i);
            EvidenceChunk chunk = expected.get(i);
            assertThat(entity.getChunkId()).isEqualTo(chunk.chunkId());
            assertThat(entity.getDocument().getDocId()).isEqualTo(chunk.docId());
            assertThat(entity.getSequence()).isEqualTo(chunk.sequence());
            assertThat(entity.getContentType()).isEqualTo(chunk.contentType());
            assertThat(entity.getText()).isEqualTo(chunk.text());
            assertThat(entity.getSourceFile()).isEqualTo(chunk.sourceFile());
            assertThat(entity.getSourceFormat()).isEqualTo(chunk.sourceFormat());
            assertThat(entity.getPageNumber()).isEqualTo(chunk.pageNumber());
            assertThat(entity.getSectionTitle()).isEqualTo(chunk.sectionTitle());
            assertThat(entity.getTableTitle()).isEqualTo(chunk.tableTitle());
            assertThat(entity.getRowLabel()).isEqualTo(chunk.rowLabel());
            assertThat(entity.getColumnLabel()).isEqualTo(chunk.columnLabel());
        }
    }

    private static void assertFactsMatch(
            List<ExtractedFactEntity> actual,
            List<ExtractedFact> expected
    ) {
        assertThat(actual).hasSameSizeAs(expected);
        Map<String, ExtractedFactEntity> byId = actual.stream()
                .collect(Collectors.toMap(ExtractedFactEntity::getFactId, Function.identity()));
        for (ExtractedFact fact : expected) {
            ExtractedFactEntity entity = byId.get(fact.factId());
            assertThat(entity).isNotNull();
            assertThat(entity.getDocument().getDocId()).isEqualTo(fact.docId());
            assertThat(entity.getEvidenceChunk().getChunkId()).isEqualTo(fact.chunkId());
            assertThat(entity.getFactKey()).isEqualTo(fact.factKey());
            assertThat(entity.getLabel()).isEqualTo(fact.label());
            assertThat(entity.getValueType()).isEqualTo(fact.valueType());
            assertThat(entity.getRawValue()).isEqualTo(fact.rawValue());
            assertThat(entity.getRawUnit()).isEqualTo(fact.rawUnit());
            assertThat(entity.getNormalizedValue()).isEqualTo(fact.normalizedValue());
            assertThat(entity.getNormalizedUnit()).isEqualTo(fact.normalizedUnit());
            assertThat(entity.getPeriodStart()).isEqualTo(fact.periodStart());
            assertThat(entity.getPeriodEnd()).isEqualTo(fact.periodEnd());
            assertThat(entity.getAsOfDate()).isEqualTo(fact.asOfDate());
            assertThat(entity.getPeriodBasis()).isEqualTo(fact.periodBasis());
            assertThat(entity.getScope()).isEqualTo(fact.scope());
        }
    }
}
