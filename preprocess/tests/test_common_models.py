import json
import unicodedata
from datetime import date

import pytest
from pydantic import ValidationError

from chaltteok_etl.common import (
    ContentType,
    DisclosureDocument,
    DisclosureRelation,
    DocGroup,
    EvidenceChunk,
    ExtractedFact,
    ExtractionStatus,
    ManifestFileFormat,
    PeriodBasis,
    PreprocessResult,
    RelationType,
    Scope,
    SourceFormat,
    ValueType,
)


DOC_ID = "periodic_20250311000001"
CHUNK_ID = f"{DOC_ID}:chunk:0000"


def make_document(**overrides: object) -> DisclosureDocument:
    values: dict[str, object] = {
        "doc_id": DOC_ID,
        "corp_code": "00000001",
        "corp_name": "예시전자",
        "listed_name": "예시전자",
        "stock_code": "000001",
        "industry": "IT",
        "sector": "반도체·전자부품",
        "doc_group": DocGroup.PERIODIC,
        "doc_subtype": "annual",
        "report_nm": "사업보고서 (2024.12)",
        "rcept_no": "20250311000001",
        "rcept_dt": date(2025, 3, 11),
        "flr_nm": "예시전자",
        "is_correction": False,
        "base_year": 2024,
        "base_month": 12,
        "source_path": "raw/periodic/예시전자/20250311000001_annual_2024_12",
        "manifest_file_format": ManifestFileFormat.XML,
        "source_file_count": 1,
    }
    values.update(overrides)
    return DisclosureDocument(**values)


def make_chunk(**overrides: object) -> EvidenceChunk:
    values: dict[str, object] = {
        "chunk_id": CHUNK_ID,
        "doc_id": DOC_ID,
        "sequence": 0,
        "content_type": ContentType.TABLE,
        "text": "연결 포괄손익계산서 | 매출액 | 2024년 | 100,000백만원",
        "source_file": (
            "raw/periodic/예시전자/20250311000001_annual_2024_12/"
            "20250311000001.xml"
        ),
        "source_format": SourceFormat.DART_XML,
        "page_number": None,
        "section_title": "재무에 관한 사항",
        "table_title": "연결 포괄손익계산서",
        "row_label": "매출액",
        "column_label": "2024년",
    }
    values.update(overrides)
    return EvidenceChunk(**values)


def make_fact(**overrides: object) -> ExtractedFact:
    values: dict[str, object] = {
        "fact_id": f"{DOC_ID}:fact:0000",
        "doc_id": DOC_ID,
        "chunk_id": CHUNK_ID,
        "fact_key": "revenue",
        "label": "매출액",
        "value_type": ValueType.NUMBER,
        "raw_value": "100,000",
        "raw_unit": "백만원",
        "normalized_value": "100000000000",
        "normalized_unit": "KRW",
        "period_start": date(2024, 1, 1),
        "period_end": date(2024, 12, 31),
        "as_of_date": None,
        "period_basis": PeriodBasis.CUMULATIVE,
        "scope": Scope.CONSOLIDATED,
        "extraction_status": ExtractionStatus.OK,
    }
    values.update(overrides)
    return ExtractedFact(**values)


def make_result(**overrides: object) -> PreprocessResult:
    values: dict[str, object] = {
        "schema_version": "0.2",
        "parser_version": "0.1.0",
        "document": make_document(),
        "evidence_chunks": [make_chunk()],
        "extracted_facts": [make_fact()],
        "relations": [],
    }
    values.update(overrides)
    return PreprocessResult(**values)


def test_preprocess_result_round_trips_as_contract_json() -> None:
    result = make_result()

    payload = json.loads(result.model_dump_json())
    restored = PreprocessResult.model_validate_json(result.model_dump_json())

    assert payload["document"]["rcept_dt"] == "2025-03-11"
    assert payload["document"]["doc_group"] == "periodic"
    assert payload["evidence_chunks"][0]["source_format"] == "dart_xml"
    assert payload["extracted_facts"][0]["normalized_value"] == "100000000000"
    assert restored == result


def test_identifying_names_and_paths_are_normalized_to_nfc() -> None:
    nfd_company_name = unicodedata.normalize("NFD", "예시전자")
    document = make_document(
        corp_name=nfd_company_name,
        listed_name=nfd_company_name,
        source_path=f"raw/periodic/{nfd_company_name}/document",
    )
    chunk = make_chunk(source_file=f"raw/periodic/{nfd_company_name}/document/file.xml")

    assert unicodedata.is_normalized("NFC", document.corp_name)
    assert unicodedata.is_normalized("NFC", document.source_path)
    assert unicodedata.is_normalized("NFC", chunk.source_file)


def test_original_evidence_and_raw_fact_values_are_not_unicode_normalized() -> None:
    nfd_text = unicodedata.normalize("NFD", "원문 증거")

    assert make_chunk(text=nfd_text).text == nfd_text
    fact = make_fact(raw_value=nfd_text, value_type="text", normalized_value=None)

    assert fact.raw_value == nfd_text


@pytest.mark.parametrize(
    ("field_name", "invalid_value"),
    [("corp_code", 1), ("stock_code", 1), ("rcept_no", 20250311000001)],
)
def test_identifier_codes_reject_non_string_values(
    field_name: str, invalid_value: int
) -> None:
    with pytest.raises(ValidationError):
        make_document(**{field_name: invalid_value})


def test_periodic_and_non_periodic_base_period_rules_are_enforced() -> None:
    with pytest.raises(ValidationError, match="base_year and base_month are required"):
        make_document(base_year=None)

    with pytest.raises(ValidationError, match="must be null"):
        make_document(doc_group=DocGroup.EXCHANGE)


def test_source_paths_must_be_relative_canonical_posix_paths() -> None:
    with pytest.raises(ValidationError, match="path separator"):
        make_document(source_path=r"raw\periodic\example")

    with pytest.raises(ValidationError, match="relative"):
        make_chunk(source_file="/raw/periodic/example.xml")

    with pytest.raises(ValidationError, match="canonical"):
        make_chunk(source_file="raw/../periodic/example.xml")


def test_chunk_id_and_pdf_page_rules_are_enforced() -> None:
    with pytest.raises(ValidationError, match="chunk_id must be"):
        make_chunk(chunk_id=f"{DOC_ID}:chunk:9999")

    with pytest.raises(ValidationError, match="page_number is required"):
        make_chunk(source_format=SourceFormat.PDF)

    with pytest.raises(ValidationError, match="must be null"):
        make_chunk(page_number=1)


def test_fact_must_use_stable_id_and_valid_normalized_number() -> None:
    with pytest.raises(ValidationError, match="fact_id must use"):
        make_fact(fact_id=f"{DOC_ID}:fact:1")

    with pytest.raises(ValidationError, match="decimal string"):
        make_fact(normalized_value="100,000")


def test_result_requires_contiguous_chunks_and_valid_fact_references() -> None:
    with pytest.raises(ValidationError, match="contiguous from zero"):
        make_result(
            evidence_chunks=[
                make_chunk(
                    chunk_id=f"{DOC_ID}:chunk:0001",
                    sequence=1,
                )
            ],
            extracted_facts=[],
        )

    with pytest.raises(ValidationError, match="existing chunk"):
        make_result(extracted_facts=[make_fact(chunk_id=f"{DOC_ID}:chunk:9999")])


def test_result_requires_at_least_one_evidence_chunk() -> None:
    with pytest.raises(ValidationError):
        make_result(evidence_chunks=[], extracted_facts=[])


def test_relation_must_reference_current_document_and_existing_evidence() -> None:
    current_doc_id = "exchange_20240201000002"
    current_chunk_id = f"{current_doc_id}:chunk:0000"
    relation = DisclosureRelation(
        relation_id=(
            "exchange_20240101000001:exchange_20240201000002:amendment"
        ),
        event_key="00000001:supply_contract:customer:contract",
        original_doc_id="exchange_20240101000001",
        related_doc_id=current_doc_id,
        relation_type=RelationType.AMENDMENT,
        previous_status="active",
        current_status="active",
        changed_fields=[],
        evidence_chunk_ids=[current_chunk_id],
    )
    document = make_document(
        doc_id=current_doc_id,
        doc_group=DocGroup.EXCHANGE,
        doc_subtype="supply_contract",
        rcept_no="20240201000002",
        base_year=None,
        base_month=None,
    )
    chunk = make_chunk(
        doc_id=current_doc_id,
        chunk_id=current_chunk_id,
    )

    result = make_result(
        document=document,
        evidence_chunks=[chunk],
        extracted_facts=[],
        relations=[relation],
    )

    assert result.relations == [relation]

    with pytest.raises(ValidationError, match="existing chunk"):
        make_result(
            document=document,
            evidence_chunks=[chunk],
            extracted_facts=[],
            relations=[
                relation.model_copy(
                    update={"evidence_chunk_ids": [f"{current_doc_id}:chunk:9999"]}
                )
            ],
        )


def test_unknown_fields_are_rejected() -> None:
    payload = make_result().model_dump(mode="json")
    payload["answer"] = "Python 전처리 단계에서 생성하면 안 되는 필드"

    with pytest.raises(ValidationError, match="Extra inputs are not permitted"):
        PreprocessResult.model_validate(payload)
