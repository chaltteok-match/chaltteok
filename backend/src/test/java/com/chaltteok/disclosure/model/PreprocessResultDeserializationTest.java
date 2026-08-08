package com.chaltteok.disclosure.model;

import static org.assertj.core.api.Assertions.assertThat;

import com.chaltteok.disclosure.model.enums.ContentType;
import com.chaltteok.disclosure.model.enums.DocGroup;
import com.chaltteok.disclosure.model.enums.ManifestFileFormat;
import com.chaltteok.disclosure.model.enums.PeriodBasis;
import com.chaltteok.disclosure.model.enums.RelationType;
import com.chaltteok.disclosure.model.enums.Scope;
import com.chaltteok.disclosure.model.enums.SourceFormat;
import com.chaltteok.disclosure.model.enums.ValueType;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import java.time.LocalDate;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * docs/data-model.md §7 예시 JSON 역직렬화·필드 매핑 검증.
 */
class PreprocessResultDeserializationTest {

    private ObjectMapper objectMapper;
    private Validator validator;

    @BeforeEach
    void setUp() {
        objectMapper = new ObjectMapper()
                .registerModule(new JavaTimeModule())
                .setPropertyNamingStrategy(PropertyNamingStrategies.SNAKE_CASE);
        validator = Validation.buildDefaultValidatorFactory().getValidator();
    }

    @Test
    void deserializesSection7Example() throws Exception {
        String json = """
                {
                  "schema_version": "0.1",
                  "parser_version": "0.1.0",
                  "document": {
                    "doc_id": "periodic_20250311000001",
                    "corp_code": "00000001",
                    "corp_name": "예시전자",
                    "listed_name": "예시전자",
                    "stock_code": "000001",
                    "industry": "IT",
                    "sector": "반도체·전자부품",
                    "doc_group": "periodic",
                    "doc_subtype": "annual",
                    "report_nm": "사업보고서 (2024.12)",
                    "rcept_no": "20250311000001",
                    "rcept_dt": "2025-03-11",
                    "flr_nm": "예시전자",
                    "is_correction": false,
                    "base_year": 2024,
                    "base_month": 12,
                    "source_path": "raw/periodic/예시전자/20250311000001_annual_2024_12",
                    "manifest_file_format": "xml",
                    "source_file_count": 1
                  },
                  "evidence_chunks": [
                    {
                      "chunk_id": "periodic_20250311000001:chunk:0000",
                      "doc_id": "periodic_20250311000001",
                      "sequence": 0,
                      "content_type": "table",
                      "text": "연결 포괄손익계산서 | 매출액 | 2024년 | 100,000백만원",
                      "source_file": "raw/periodic/예시전자/20250311000001_annual_2024_12/20250311000001.xml",
                      "source_format": "dart_xml",
                      "page_number": null,
                      "section_title": "재무에 관한 사항",
                      "table_title": "연결 포괄손익계산서",
                      "row_label": "매출액",
                      "column_label": "2024년"
                    }
                  ],
                  "extracted_facts": [
                    {
                      "fact_id": "periodic_20250311000001:fact:0000",
                      "doc_id": "periodic_20250311000001",
                      "chunk_id": "periodic_20250311000001:chunk:0000",
                      "fact_key": "revenue",
                      "label": "매출액",
                      "value_type": "number",
                      "raw_value": "100,000",
                      "raw_unit": "백만원",
                      "normalized_value": "100000000000",
                      "normalized_unit": "KRW",
                      "period_start": "2024-01-01",
                      "period_end": "2024-12-31",
                      "as_of_date": null,
                      "period_basis": "cumulative",
                      "scope": "consolidated"
                    }
                  ],
                  "relations": []
                }
                """;

        PreprocessResult result = objectMapper.readValue(json, PreprocessResult.class);

        assertThat(result.schemaVersion()).isEqualTo("0.1");
        assertThat(result.parserVersion()).isEqualTo("0.1.0");

        DisclosureDocument document = result.document();
        assertThat(document.docId()).isEqualTo("periodic_20250311000001");
        assertThat(document.corpCode()).isEqualTo("00000001");
        assertThat(document.corpName()).isEqualTo("예시전자");
        assertThat(document.listedName()).isEqualTo("예시전자");
        assertThat(document.stockCode()).isEqualTo("000001");
        assertThat(document.industry()).isEqualTo("IT");
        assertThat(document.sector()).isEqualTo("반도체·전자부품");
        assertThat(document.docGroup()).isEqualTo(DocGroup.PERIODIC);
        assertThat(document.docSubtype()).isEqualTo("annual");
        assertThat(document.reportNm()).isEqualTo("사업보고서 (2024.12)");
        assertThat(document.rceptNo()).isEqualTo("20250311000001");
        assertThat(document.rceptDt()).isEqualTo(LocalDate.of(2025, 3, 11));
        assertThat(document.flrNm()).isEqualTo("예시전자");
        assertThat(document.isCorrection()).isFalse();
        assertThat(document.baseYear()).isEqualTo(2024);
        assertThat(document.baseMonth()).isEqualTo(12);
        assertThat(document.sourcePath())
                .isEqualTo("raw/periodic/예시전자/20250311000001_annual_2024_12");
        assertThat(document.manifestFileFormat()).isEqualTo(ManifestFileFormat.XML);
        assertThat(document.sourceFileCount()).isEqualTo(1);

        assertThat(result.evidenceChunks()).hasSize(1);
        EvidenceChunk chunk = result.evidenceChunks().getFirst();
        assertThat(chunk.chunkId()).isEqualTo("periodic_20250311000001:chunk:0000");
        assertThat(chunk.docId()).isEqualTo("periodic_20250311000001");
        assertThat(chunk.sequence()).isZero();
        assertThat(chunk.contentType()).isEqualTo(ContentType.TABLE);
        assertThat(chunk.text()).isEqualTo("연결 포괄손익계산서 | 매출액 | 2024년 | 100,000백만원");
        assertThat(chunk.sourceFile())
                .isEqualTo("raw/periodic/예시전자/20250311000001_annual_2024_12/20250311000001.xml");
        assertThat(chunk.sourceFormat()).isEqualTo(SourceFormat.DART_XML);
        assertThat(chunk.pageNumber()).isNull();
        assertThat(chunk.sectionTitle()).isEqualTo("재무에 관한 사항");
        assertThat(chunk.tableTitle()).isEqualTo("연결 포괄손익계산서");
        assertThat(chunk.rowLabel()).isEqualTo("매출액");
        assertThat(chunk.columnLabel()).isEqualTo("2024년");

        assertThat(result.extractedFacts()).hasSize(1);
        ExtractedFact fact = result.extractedFacts().getFirst();
        assertThat(fact.factId()).isEqualTo("periodic_20250311000001:fact:0000");
        assertThat(fact.docId()).isEqualTo("periodic_20250311000001");
        assertThat(fact.chunkId()).isEqualTo("periodic_20250311000001:chunk:0000");
        assertThat(fact.factKey()).isEqualTo("revenue");
        assertThat(fact.label()).isEqualTo("매출액");
        assertThat(fact.valueType()).isEqualTo(ValueType.NUMBER);
        assertThat(fact.rawValue()).isEqualTo("100,000");
        assertThat(fact.rawUnit()).isEqualTo("백만원");
        assertThat(fact.normalizedValue()).isEqualTo("100000000000");
        assertThat(fact.normalizedUnit()).isEqualTo("KRW");
        assertThat(fact.periodStart()).isEqualTo(LocalDate.of(2024, 1, 1));
        assertThat(fact.periodEnd()).isEqualTo(LocalDate.of(2024, 12, 31));
        assertThat(fact.asOfDate()).isNull();
        assertThat(fact.periodBasis()).isEqualTo(PeriodBasis.CUMULATIVE);
        assertThat(fact.scope()).isEqualTo(Scope.CONSOLIDATED);

        assertThat(result.relations()).isEmpty();

        Set<ConstraintViolation<PreprocessResult>> violations = validator.validate(result);
        assertThat(violations).isEmpty();
    }

    @Test
    void deserializesRelationExample() throws Exception {
        String json = """
                {
                  "relation_id": "exchange_20240101000001:exchange_20240201000002:amendment",
                  "event_key": "00000001:supply_contract:example_customer:example_contract",
                  "original_doc_id": "exchange_20240101000001",
                  "related_doc_id": "exchange_20240201000002",
                  "relation_type": "amendment",
                  "previous_status": "active",
                  "current_status": "active",
                  "changed_fields": [
                    {
                      "fact_key": "contract_amount",
                      "before_value": "100000000000",
                      "after_value": "80000000000",
                      "unit": "KRW"
                    }
                  ],
                  "evidence_chunk_ids": [
                    "exchange_20240201000002:chunk:0002"
                  ]
                }
                """;

        DisclosureRelation relation = objectMapper.readValue(json, DisclosureRelation.class);

        assertThat(relation.relationId())
                .isEqualTo("exchange_20240101000001:exchange_20240201000002:amendment");
        assertThat(relation.eventKey())
                .isEqualTo("00000001:supply_contract:example_customer:example_contract");
        assertThat(relation.originalDocId()).isEqualTo("exchange_20240101000001");
        assertThat(relation.relatedDocId()).isEqualTo("exchange_20240201000002");
        assertThat(relation.relationType()).isEqualTo(RelationType.AMENDMENT);
        assertThat(relation.previousStatus()).isEqualTo("active");
        assertThat(relation.currentStatus()).isEqualTo("active");
        assertThat(relation.changedFields()).hasSize(1);
        ChangedField field = relation.changedFields().getFirst();
        assertThat(field.factKey()).isEqualTo("contract_amount");
        assertThat(field.beforeValue()).isEqualTo("100000000000");
        assertThat(field.afterValue()).isEqualTo("80000000000");
        assertThat(field.unit()).isEqualTo("KRW");
        assertThat(relation.evidenceChunkIds()).containsExactly("exchange_20240201000002:chunk:0002");

        assertThat(validator.validate(relation)).isEmpty();
    }

    @Test
    void deserializesPdfHtmlManifestFormat() throws Exception {
        String json = "\"pdf+html\"";
        ManifestFileFormat format = objectMapper.readValue(json, ManifestFileFormat.class);
        assertThat(format).isEqualTo(ManifestFileFormat.PDF_HTML);
        assertThat(objectMapper.writeValueAsString(format)).isEqualTo("\"pdf+html\"");
    }
}
