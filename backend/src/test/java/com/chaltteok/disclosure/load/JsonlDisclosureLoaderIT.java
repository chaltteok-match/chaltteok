package com.chaltteok.disclosure.load;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.chaltteok.disclosure.model.enums.ExtractionStatus;
import com.chaltteok.disclosure.persistence.entity.DisclosureDocumentEntity;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.chaltteok.disclosure.persistence.repository.DisclosureDocumentRepository;
import com.chaltteok.disclosure.persistence.repository.ChangedFieldRepository;
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
    private ChangedFieldRepository changedFieldRepository;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private EvidenceChunkRepository evidenceChunkRepository;

    @Autowired
    private ExtractedFactRepository extractedFactRepository;

    @TempDir
    Path tempDir;

    private List<String> sampleLines;

    @BeforeEach
    void setUp() throws IOException {
        clearAll();
        try (InputStream stream = new ClassPathResource(SAMPLE).getInputStream()) {
            sampleLines = new String(stream.readAllBytes(), StandardCharsets.UTF_8)
                    .lines()
                    .filter(line -> !line.isBlank())
                    .toList();
        }
    }

    /**
     * 외래키 역순으로 지운다.
     * {@code deleteAllInBatch()}는 cascade를 타지 않으므로 자식 테이블을 직접 먼저 비워야 한다.
     * changed_field → disclosure_relation → extracted_fact → evidence_chunk → disclosure_document 순.
     * cascade에 맡기면 orphanRemoval이 chunk를 먼저 끊어 fact.chunk_id가 null이 된다.
     */
    private void clearAll() {
        changedFieldRepository.deleteAllInBatch();
        relationRepository.deleteAllInBatch();
        extractedFactRepository.deleteAllInBatch();
        evidenceChunkRepository.deleteAllInBatch();
        documentRepository.deleteAllInBatch();
    }

    private Path write(List<String> lines) throws IOException {
        Path path = Files.createTempFile(tempDir, "load", ".jsonl");
        Files.write(path, lines, StandardCharsets.UTF_8);
        return path;
    }

    @Test
    void savesDocumentsChunksAndFactsFromPreprocessOutput() throws IOException {
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
    void storedValuesMatchPreprocessOutput() throws IOException {
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
    void reloadingSameFileKeepsCountsUnchanged() throws IOException {
        Path path = write(sampleLines);
        loader.load(path);
        LoadSummary second = loader.load(path);

        assertThat(second.hasFailure()).isFalse();
        assertThat(documentRepository.count()).isEqualTo(2);
        assertThat(evidenceChunkRepository.count()).isEqualTo(24);
        assertThat(extractedFactRepository.count()).isEqualTo(72);
    }

    @Test
    void reportsExtractionStatusCountsInSummary() throws IOException {
        // 전량 적재 후 값이 빈 원인을 원인별로 세려면 요약에 상태가 남아야 한다.
        LoadSummary summary = loader.load(write(sampleLines));

        assertThat(summary.extractionStatusCounts().get(ExtractionStatus.OK)).isEqualTo(72);
        assertThat(summary.describe()).contains("추출 상태: ok 72");
    }

    @Test
    void countsRepeatedDocIdOnceInSummary() throws IOException {
        // 같은 doc_id가 두 줄이면 저장은 upsert로 1건이다. 요약도 1건으로 보고해야 한다.
        LoadSummary summary = loader.load(write(List.of(sampleLines.get(0), sampleLines.get(0))));

        assertThat(summary.totalRecords()).isEqualTo(2);
        assertThat(summary.succeeded()).isEqualTo(2);
        assertThat(summary.documents()).isEqualTo(1);
        assertThat(summary.documents()).isEqualTo((int) documentRepository.count());
        assertThat(summary.evidenceChunks()).isEqualTo((int) evidenceChunkRepository.count());
        assertThat(summary.extractedFacts()).isEqualTo((int) extractedFactRepository.count());
    }

    @Test
    void reportsLineNumberAndDocIdWhenOneLineFails() throws IOException {
        String broken = sampleLines.get(0).replace("\"schema_version\":\"0.2\"", "\"schema_version\":\"\"");
        LoadSummary summary = loader.load(write(List.of(broken, sampleLines.get(1))));

        assertThat(summary.totalRecords()).isEqualTo(2);
        assertThat(summary.succeeded()).isEqualTo(1);
        assertThat(summary.failed()).isEqualTo(1);
        assertThat(summary.failures().get(0).lineNumber()).isEqualTo(1);
        assertThat(summary.failures().get(0).docId()).isEqualTo("periodic_20250311001085");
        assertThat(documentRepository.existsById("periodic_20240312000736")).isTrue();
    }

    @Test
    void reportsFailureForFieldOutsideContract() throws IOException {
        String extra = sampleLines.get(0).replaceFirst("\\{", "{\"unexpected_field\":1,");
        LoadSummary summary = loader.load(write(List.of(extra)));

        assertThat(summary.failed()).isEqualTo(1);
        assertThat(summary.failures().get(0).reason()).contains("unexpected_field");
        assertThat(documentRepository.count()).isZero();
    }

    @Test
    void skipsBlankLines() throws IOException {
        LoadSummary summary = loader.load(write(List.of(sampleLines.get(0), "", "   ", sampleLines.get(1))));

        assertThat(summary.totalRecords()).isEqualTo(2);
        assertThat(summary.succeeded()).isEqualTo(2);
    }

    @Test
    void loadsAndClearsRelationWithChangedFields() throws IOException {
        // 관계는 원본 문서가 먼저 저장되어 있어야 하므로 2023년 보고서를 앞 줄에 둔다.
        String original = "periodic_20240312000736";
        Path path = write(List.of(sampleLines.get(1), withRelation(sampleLines.get(0), original)));

        LoadSummary summary = loader.load(path);

        assertThat(summary.hasFailure()).isFalse();
        assertThat(summary.relations()).isEqualTo(1);
        assertThat(relationRepository.count()).isEqualTo(1);
        assertThat(changedFieldRepository.count()).isEqualTo(1);

        // 관계 자식 테이블까지 정리되어야 다음 테스트가 외래키 위반 없이 시작된다.
        clearAll();
        assertThat(changedFieldRepository.count()).isZero();
        assertThat(relationRepository.count()).isZero();
        assertThat(documentRepository.count()).isZero();
    }

    @Test
    void reportsFailureWhenRelationPrecedesItsOriginalDocument() throws IOException {
        // README에 적은 줄 순서 제약을 고정한다. 원본이 뒷줄이면 정정 문서 줄이 실패한다.
        String original = "periodic_20240312000736";
        Path path = write(List.of(withRelation(sampleLines.get(0), original), sampleLines.get(1)));

        LoadSummary summary = loader.load(path);

        assertThat(summary.failed()).isEqualTo(1);
        assertThat(summary.failures().get(0).lineNumber()).isEqualTo(1);
        assertThat(summary.failures().get(0).docId()).isEqualTo("periodic_20250311001085");
        assertThat(summary.failures().get(0).reason()).contains("original_doc_id");
        // 나머지 줄은 저장되므로, 순서를 고쳐 다시 적재하면 복구된다.
        assertThat(documentRepository.existsById(original)).isTrue();

        LoadSummary retry = loader.load(
                write(List.of(sampleLines.get(1), withRelation(sampleLines.get(0), original))));
        assertThat(retry.hasFailure()).isFalse();
        assertThat(relationRepository.count()).isEqualTo(1);
    }

    /** 전처리 결과 한 줄에 정정 관계와 변경 필드를 붙인다. */
    private String withRelation(String documentLine, String originalDocId) throws IOException {
        ObjectNode root = (ObjectNode) objectMapper.readTree(documentLine);
        String docId = root.path("document").path("doc_id").asText();
        String chunkId = root.path("evidence_chunks").get(0).path("chunk_id").asText();

        ObjectNode changedField = objectMapper.createObjectNode();
        changedField.put("fact_key", "revenue");
        changedField.put("before_value", "258935494000000");
        changedField.put("after_value", "300870903000000");
        changedField.put("unit", "KRW");

        ObjectNode relation = objectMapper.createObjectNode();
        relation.put("relation_id", originalDocId + ":" + docId + ":correction");
        relation.put("event_key", "periodic-correction-test");
        relation.put("original_doc_id", originalDocId);
        relation.put("related_doc_id", docId);
        relation.put("relation_type", "correction");
        relation.putNull("previous_status");
        relation.putNull("current_status");
        relation.set("changed_fields", objectMapper.createArrayNode().add(changedField));
        relation.set("evidence_chunk_ids", objectMapper.createArrayNode().add(chunkId));

        root.set("relations", objectMapper.createArrayNode().add(relation));
        return objectMapper.writeValueAsString(root);
    }

    @Test
    void failsWhenFileMissing() {
        assertThatThrownBy(() -> loader.load(tempDir.resolve("없는파일.jsonl")))
                .isInstanceOf(JsonlLoadException.class)
                .hasMessageContaining("없습니다");
    }
}
