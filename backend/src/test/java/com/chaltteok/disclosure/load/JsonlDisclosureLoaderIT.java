package com.chaltteok.disclosure.load;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.chaltteok.disclosure.persistence.entity.DisclosureDocumentEntity;
import com.chaltteok.disclosure.persistence.repository.DisclosureDocumentRepository;
import com.chaltteok.disclosure.persistence.repository.DisclosureRelationRepository;
import com.chaltteok.disclosure.persistence.repository.EvidenceChunkRepository;
import com.chaltteok.disclosure.persistence.repository.ExtractedFactRepository;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
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
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * Python 전처리가 실제로 만든 JSONL을 PostgreSQL에 적재하는 전체 경로를 검증한다.
 * 픽스처 {@code load/periodic-sample.jsonl}은 정기공시 파서 출력 원본이다.
 */
@SpringBootTest(properties = {
        "spring.autoconfigure.exclude=",
        "spring.profiles.active=persistence-it"
})
@ActiveProfiles("persistence-it")
@Testcontainers
class JsonlDisclosureLoaderIT {

    private static final String SAMPLE = "load/periodic-sample.jsonl";

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
    private DisclosureRelationRepository relationRepository;

    @Autowired
    private EvidenceChunkRepository evidenceChunkRepository;

    @Autowired
    private ExtractedFactRepository extractedFactRepository;

    @TempDir
    Path tempDir;

    private List<String> sampleLines;

    @BeforeEach
    void setUp() throws IOException {
        // fact가 chunk를, relation이 document를 참조하므로 외래키 역순으로 지운다.
        // cascade에 맡기면 orphanRemoval이 chunk를 먼저 끊어 fact.chunk_id가 null이 된다.
        relationRepository.deleteAllInBatch();
        extractedFactRepository.deleteAllInBatch();
        evidenceChunkRepository.deleteAllInBatch();
        documentRepository.deleteAllInBatch();
        try (InputStream stream = new ClassPathResource(SAMPLE).getInputStream()) {
            sampleLines = new String(stream.readAllBytes(), StandardCharsets.UTF_8)
                    .lines()
                    .filter(line -> !line.isBlank())
                    .toList();
        }
    }

    private Path write(List<String> lines) throws IOException {
        Path path = Files.createTempFile(tempDir, "load", ".jsonl");
        Files.write(path, lines, StandardCharsets.UTF_8);
        return path;
    }

    @Test
    void 전처리_출력을_읽어_문서와_근거와_사실을_저장한다() throws IOException {
        LoadSummary summary = loader.load(write(sampleLines));

        assertThat(summary.totalRecords()).isEqualTo(2);
        assertThat(summary.succeeded()).isEqualTo(2);
        assertThat(summary.hasFailure()).isFalse();
        assertThat(summary.evidenceChunks()).isEqualTo(24);
        assertThat(summary.extractedFacts()).isEqualTo(72);

        assertThat(documentRepository.count()).isEqualTo(2);
        assertThat(evidenceChunkRepository.count()).isEqualTo(24);
        assertThat(extractedFactRepository.count()).isEqualTo(72);
    }

    @Test
    void 저장한_값이_전처리_결과와_일치한다() throws IOException {
        loader.load(write(sampleLines));

        DisclosureDocumentEntity document =
                documentRepository.findById("periodic_20250311001085").orElseThrow();
        assertThat(document.getCorpName()).isEqualTo("삼성전자");
        assertThat(document.getReportNm()).isEqualTo("사업보고서 (2024.12)");

        String revenue = extractedFactRepository.findByDocumentDocId("periodic_20250311001085")
                .stream()
                .filter(fact -> "revenue".equals(fact.getFactKey()))
                .filter(fact -> fact.getScope().name().equals("CONSOLIDATED"))
                .filter(fact -> fact.getPeriodEnd() != null && fact.getPeriodEnd().getYear() == 2024)
                .findFirst()
                .orElseThrow()
                .getNormalizedValue();

        assertThat(revenue).isEqualTo("300870903000000");
    }

    @Test
    void 같은_파일을_두_번_적재해도_결과가_같다() throws IOException {
        Path path = write(sampleLines);
        loader.load(path);
        LoadSummary second = loader.load(path);

        assertThat(second.hasFailure()).isFalse();
        assertThat(documentRepository.count()).isEqualTo(2);
        assertThat(evidenceChunkRepository.count()).isEqualTo(24);
        assertThat(extractedFactRepository.count()).isEqualTo(72);
    }

    @Test
    void 한_줄이_실패해도_나머지를_적재하고_줄_번호와_doc_id를_보고한다() throws IOException {
        String broken = sampleLines.get(0).replace("\"schema_version\":\"0.1\"", "\"schema_version\":\"\"");
        LoadSummary summary = loader.load(write(List.of(broken, sampleLines.get(1))));

        assertThat(summary.totalRecords()).isEqualTo(2);
        assertThat(summary.succeeded()).isEqualTo(1);
        assertThat(summary.failed()).isEqualTo(1);
        assertThat(summary.failures().get(0).lineNumber()).isEqualTo(1);
        assertThat(summary.failures().get(0).docId()).isEqualTo("periodic_20250311001085");
        assertThat(documentRepository.existsById("periodic_20240312000736")).isTrue();
    }

    @Test
    void 계약에_없는_필드가_있으면_실패로_보고한다() throws IOException {
        String extra = sampleLines.get(0).replaceFirst("\\{", "{\"unexpected_field\":1,");
        LoadSummary summary = loader.load(write(List.of(extra)));

        assertThat(summary.failed()).isEqualTo(1);
        assertThat(summary.failures().get(0).reason()).contains("unexpected_field");
        assertThat(documentRepository.count()).isZero();
    }

    @Test
    void 빈_줄은_건너뛴다() throws IOException {
        LoadSummary summary = loader.load(write(List.of(sampleLines.get(0), "", "   ", sampleLines.get(1))));

        assertThat(summary.totalRecords()).isEqualTo(2);
        assertThat(summary.succeeded()).isEqualTo(2);
    }

    @Test
    void 파일이_없으면_적재를_시작하지_않는다() {
        assertThatThrownBy(() -> loader.load(tempDir.resolve("없는파일.jsonl")))
                .isInstanceOf(JsonlLoadException.class)
                .hasMessageContaining("없습니다");
    }
}
