package com.chaltteok.disclosure.persistence;

import static org.assertj.core.api.Assertions.assertThat;

import com.chaltteok.disclosure.model.ChangedField;
import com.chaltteok.disclosure.model.DisclosureDocument;
import com.chaltteok.disclosure.model.DisclosureRelation;
import com.chaltteok.disclosure.model.EvidenceChunk;
import com.chaltteok.disclosure.model.ExtractedFact;
import com.chaltteok.disclosure.model.PreprocessResult;
import com.chaltteok.disclosure.model.enums.ContentType;
import com.chaltteok.disclosure.model.enums.DocGroup;
import com.chaltteok.disclosure.model.enums.ManifestFileFormat;
import com.chaltteok.disclosure.model.enums.PeriodBasis;
import com.chaltteok.disclosure.model.enums.RelationType;
import com.chaltteok.disclosure.model.enums.Scope;
import com.chaltteok.disclosure.model.enums.SourceFormat;
import com.chaltteok.disclosure.model.enums.ValueType;
import com.chaltteok.disclosure.persistence.entity.DisclosureDocumentEntity;
import com.chaltteok.disclosure.persistence.entity.DisclosureRelationEntity;
import com.chaltteok.disclosure.persistence.entity.EvidenceChunkEntity;
import com.chaltteok.disclosure.persistence.entity.ExtractedFactEntity;
import com.chaltteok.disclosure.persistence.repository.DisclosureDocumentRepository;
import com.chaltteok.disclosure.persistence.repository.DisclosureRelationRepository;
import com.chaltteok.disclosure.persistence.repository.EvidenceChunkRepository;
import com.chaltteok.disclosure.persistence.repository.ExtractedFactRepository;
import java.time.LocalDate;
import java.util.List;
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

@SpringBootTest(properties = {
        "spring.autoconfigure.exclude=",
        "spring.profiles.active=persistence-it"
})
@ActiveProfiles("persistence-it")
@Testcontainers
@Transactional
class DisclosurePersistenceServiceIT {

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
    private DisclosurePersistenceService persistenceService;

    @Autowired
    private DisclosureDocumentRepository documentRepository;

    @Autowired
    private EvidenceChunkRepository chunkRepository;

    @Autowired
    private ExtractedFactRepository factRepository;

    @Autowired
    private DisclosureRelationRepository relationRepository;

    @Test
    void savesAndReloadsDocumentChunksFactsAndRelations() {
        PreprocessResult original = originalDocumentResult();
        persistenceService.save(original);

        PreprocessResult related = relatedDocumentResult();
        persistenceService.save(related);

        DisclosureDocumentEntity reloaded = persistenceService.findByDocId("exchange_20240201000002")
                .orElseThrow();

        assertThat(reloaded.getCorpCode()).isEqualTo("00000001");
        assertThat(reloaded.getCorpName()).isEqualTo("예시전자");
        assertThat(reloaded.getDocGroup()).isEqualTo(DocGroup.EXCHANGE);
        assertThat(reloaded.getRceptDt()).isEqualTo(LocalDate.of(2024, 2, 1));
        assertThat(reloaded.getSchemaVersion()).isEqualTo("0.1");
        assertThat(reloaded.getParserVersion()).isEqualTo("0.1.0");

        List<EvidenceChunkEntity> chunks =
                chunkRepository.findByDocumentDocIdOrderBySequenceAsc("exchange_20240201000002");
        assertThat(chunks).hasSize(1);
        assertThat(chunks.getFirst().getChunkId()).isEqualTo("exchange_20240201000002:chunk:0002");
        assertThat(chunks.getFirst().getText()).contains("계약금액");
        assertThat(chunks.getFirst().getDocument().getDocId()).isEqualTo("exchange_20240201000002");

        List<ExtractedFactEntity> facts = factRepository.findByDocumentDocId("exchange_20240201000002");
        assertThat(facts).hasSize(1);
        ExtractedFactEntity fact = facts.getFirst();
        assertThat(fact.getFactKey()).isEqualTo("contract_amount");
        assertThat(fact.getEvidenceChunk().getChunkId()).isEqualTo("exchange_20240201000002:chunk:0002");
        assertThat(fact.getDocument().getDocId()).isEqualTo("exchange_20240201000002");
        assertThat(fact.getNormalizedValue()).isEqualTo("80000000000");

        List<DisclosureRelationEntity> relations =
                persistenceService.findRelationsByDocId("exchange_20240201000002");
        assertThat(relations).hasSize(1);
        DisclosureRelationEntity relation = relations.getFirst();
        assertThat(relation.getEventKey())
                .isEqualTo("00000001:supply_contract:example_customer:example_contract");
        assertThat(relation.getOriginalDocument().getDocId()).isEqualTo("exchange_20240101000001");
        assertThat(relation.getRelatedDocument().getDocId()).isEqualTo("exchange_20240201000002");
        assertThat(relation.getRelationType()).isEqualTo(RelationType.AMENDMENT);
        assertThat(relation.getChangedFields()).hasSize(1);
        assertThat(relation.getChangedFields().getFirst().getFactKey()).isEqualTo("contract_amount");
        assertThat(relation.getChangedFields().getFirst().getBeforeValue()).isEqualTo("100000000000");
        assertThat(relation.getChangedFields().getFirst().getAfterValue()).isEqualTo("80000000000");
        assertThat(relation.getEvidenceChunkIds())
                .containsExactly("exchange_20240201000002:chunk:0002");

        assertThat(documentRepository.count()).isEqualTo(2);
        assertThat(relationRepository.findByEventKey(
                "00000001:supply_contract:example_customer:example_contract")).hasSize(1);
    }

    @Test
    void upsertReplacesSameDocId() {
        persistenceService.save(originalDocumentResult());
        persistenceService.save(relatedDocumentResult());

        DisclosureDocument updatedDocument = new DisclosureDocument(
                "exchange_20240201000002",
                "00000001",
                "예시전자",
                "예시전자",
                "000001",
                "IT",
                "반도체·전자부품",
                DocGroup.EXCHANGE,
                "supply_contract",
                "공급계약 체결(정정)",
                "20240201000002",
                LocalDate.of(2024, 2, 1),
                "예시전자",
                true,
                null,
                null,
                "raw/exchange/예시전자/20240201000002_supply_contract",
                ManifestFileFormat.XML,
                1
        );
        EvidenceChunk updatedChunk = new EvidenceChunk(
                "exchange_20240201000002:chunk:0002",
                "exchange_20240201000002",
                0,
                ContentType.TABLE,
                "계약금액이 700억원으로 변경되었습니다.",
                "raw/exchange/예시전자/20240201000002_supply_contract/20240201000002.xml",
                SourceFormat.HTML,
                null,
                "계약 내용",
                "계약 주요내용",
                "계약금액",
                null
        );
        ExtractedFact updatedFact = new ExtractedFact(
                "exchange_20240201000002:fact:0000",
                "exchange_20240201000002",
                "exchange_20240201000002:chunk:0002",
                "contract_amount",
                "계약금액",
                ValueType.NUMBER,
                "700",
                "억원",
                "70000000000",
                "KRW",
                null,
                null,
                LocalDate.of(2024, 2, 1),
                PeriodBasis.POINT_IN_TIME,
                Scope.NOT_APPLICABLE
        );
        PreprocessResult updatedRelated = new PreprocessResult(
                "0.1",
                "0.1.1",
                updatedDocument,
                List.of(updatedChunk),
                List.of(updatedFact),
                List.of(relatedRelation())
        );

        persistenceService.save(updatedRelated);

        DisclosureDocumentEntity reloaded = persistenceService.findByDocId("exchange_20240201000002")
                .orElseThrow();
        assertThat(reloaded.getParserVersion()).isEqualTo("0.1.1");
        assertThat(reloaded.getReportNm()).isEqualTo("공급계약 체결(정정)");

        List<EvidenceChunkEntity> chunks =
                chunkRepository.findByDocumentDocIdOrderBySequenceAsc("exchange_20240201000002");
        assertThat(chunks).hasSize(1);
        assertThat(chunks.getFirst().getText()).contains("700억원");

        List<ExtractedFactEntity> facts = factRepository.findByDocumentDocId("exchange_20240201000002");
        assertThat(facts).hasSize(1);
        assertThat(facts.getFirst().getNormalizedValue()).isEqualTo("70000000000");

        assertThat(persistenceService.findRelationsByDocId("exchange_20240201000002")).hasSize(1);
        assertThat(documentRepository.count()).isEqualTo(2);
    }

    private static PreprocessResult originalDocumentResult() {
        return new PreprocessResult(
                "0.1",
                "0.1.0",
                originalDocument(),
                List.of(originalChunk()),
                List.of(originalFact()),
                List.of()
        );
    }

    private static PreprocessResult relatedDocumentResult() {
        return new PreprocessResult(
                "0.1",
                "0.1.0",
                relatedDocument(),
                List.of(relatedChunk()),
                List.of(relatedFact()),
                List.of(relatedRelation())
        );
    }

    private static DisclosureDocument originalDocument() {
        return new DisclosureDocument(
                "exchange_20240101000001",
                "00000001",
                "예시전자",
                "예시전자",
                "000001",
                "IT",
                "반도체·전자부품",
                DocGroup.EXCHANGE,
                "supply_contract",
                "공급계약 체결",
                "20240101000001",
                LocalDate.of(2024, 1, 1),
                "예시전자",
                false,
                null,
                null,
                "raw/exchange/예시전자/20240101000001_supply_contract",
                ManifestFileFormat.XML,
                1
        );
    }

    private static DisclosureDocument relatedDocument() {
        return new DisclosureDocument(
                "exchange_20240201000002",
                "00000001",
                "예시전자",
                "예시전자",
                "000001",
                "IT",
                "반도체·전자부품",
                DocGroup.EXCHANGE,
                "supply_contract",
                "공급계약 체결",
                "20240201000002",
                LocalDate.of(2024, 2, 1),
                "예시전자",
                true,
                null,
                null,
                "raw/exchange/예시전자/20240201000002_supply_contract",
                ManifestFileFormat.XML,
                1
        );
    }

    private static EvidenceChunk originalChunk() {
        return new EvidenceChunk(
                "exchange_20240101000001:chunk:0000",
                "exchange_20240101000001",
                0,
                ContentType.TABLE,
                "계약금액 | 1000억원",
                "raw/exchange/예시전자/20240101000001_supply_contract/20240101000001.xml",
                SourceFormat.HTML,
                null,
                "계약 내용",
                "계약 주요내용",
                "계약금액",
                null
        );
    }

    private static EvidenceChunk relatedChunk() {
        return new EvidenceChunk(
                "exchange_20240201000002:chunk:0002",
                "exchange_20240201000002",
                0,
                ContentType.TABLE,
                "계약금액 | 800억원",
                "raw/exchange/예시전자/20240201000002_supply_contract/20240201000002.xml",
                SourceFormat.HTML,
                null,
                "계약 내용",
                "계약 주요내용",
                "계약금액",
                null
        );
    }

    private static ExtractedFact originalFact() {
        return new ExtractedFact(
                "exchange_20240101000001:fact:0000",
                "exchange_20240101000001",
                "exchange_20240101000001:chunk:0000",
                "contract_amount",
                "계약금액",
                ValueType.NUMBER,
                "1000",
                "억원",
                "100000000000",
                "KRW",
                null,
                null,
                LocalDate.of(2024, 1, 1),
                PeriodBasis.POINT_IN_TIME,
                Scope.NOT_APPLICABLE
        );
    }

    private static ExtractedFact relatedFact() {
        return new ExtractedFact(
                "exchange_20240201000002:fact:0000",
                "exchange_20240201000002",
                "exchange_20240201000002:chunk:0002",
                "contract_amount",
                "계약금액",
                ValueType.NUMBER,
                "800",
                "억원",
                "80000000000",
                "KRW",
                null,
                null,
                LocalDate.of(2024, 2, 1),
                PeriodBasis.POINT_IN_TIME,
                Scope.NOT_APPLICABLE
        );
    }

    private static DisclosureRelation relatedRelation() {
        return new DisclosureRelation(
                "exchange_20240101000001:exchange_20240201000002:amendment",
                "00000001:supply_contract:example_customer:example_contract",
                "exchange_20240101000001",
                "exchange_20240201000002",
                RelationType.AMENDMENT,
                "active",
                "active",
                List.of(new ChangedField(
                        "contract_amount",
                        "100000000000",
                        "80000000000",
                        "KRW"
                )),
                List.of("exchange_20240201000002:chunk:0002")
        );
    }
}
