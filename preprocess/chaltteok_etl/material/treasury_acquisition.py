"""자기주식취득결정(주요사항보고서) DART XML 파서.

취득예정 보통주식 수와 취득예정금액을 뽑는다. 값이 "-"이면 사실을 만들지 않는다.
처분·신탁 서식은 다루지 않는다.
"""

from __future__ import annotations

from dataclasses import dataclass
from pathlib import Path

from chaltteok_etl.common import (
    ContentType,
    DisclosureDocument,
    EvidenceChunk,
    ExtractedFact,
    ExtractionStatus,
    PeriodBasis,
    PreprocessResult,
    Scope,
    SourceFormat,
    ValueType,
)
from chaltteok_etl.common.dart_xml import (
    DartCell,
    collect_body_text,
    document_acode,
    document_name,
    is_missing_value,
    iter_te_cells,
    normalize_number,
    parse_dart_xml,
)

SCHEMA_VERSION = "0.2"
PARSER_VERSION = "0.1.0"

# 코퍼스 원문은 11332. 구 서식·테스트 픽스처는 11310을 쓴다.
TREASURY_ACQUISITION_DOCUMENT_ACODES = frozenset({"11310", "11332"})
TREASURY_ACQUISITION_DOCUMENT_ACODE = "11332"
DOCUMENT_NAME_MARK = "자기주식취득결정"

SHARES_FACT_KEY = "treasury_planned_common_shares"
AMOUNT_FACT_KEY = "treasury_planned_common_amount"

ACODE_FACT_KEYS: dict[str, str] = {
    "ACQ_OSTK": SHARES_FACT_KEY,
    "ACQ_OSTK_PRC": AMOUNT_FACT_KEY,
    "AQPLN_STK_OSTK": SHARES_FACT_KEY,
    "AQPLN_STK": SHARES_FACT_KEY,
    "AQ_PLN_CNT": SHARES_FACT_KEY,
    "AQPLN_PRC_OSTK": AMOUNT_FACT_KEY,
    "AQPLN_PRC": AMOUNT_FACT_KEY,
    "AQ_PLN_AMT": AMOUNT_FACT_KEY,
}


@dataclass(frozen=True)
class _MappedCell:
    fact_key: str
    cell: DartCell


def parse_treasury_stock_acquisition(
    source_xml: Path,
    document: DisclosureDocument,
) -> PreprocessResult:
    root = parse_dart_xml(source_xml)
    _assert_form(root)
    source_file = f"{document.source_path}/{source_xml.name}"
    mapped = _unique_mapped_cells(iter_te_cells(root))

    evidence_chunks: list[EvidenceChunk] = []
    extracted_facts: list[ExtractedFact] = []
    for sequence, item in enumerate(mapped):
        chunk = _to_chunk(item.cell, document.doc_id, sequence, source_file)
        evidence_chunks.append(chunk)
        extracted_facts.append(_to_fact(item, document, sequence, chunk.chunk_id))

    if not evidence_chunks:
        evidence_chunks.append(_fallback_chunk(root, document.doc_id, source_file))

    return PreprocessResult(
        schema_version=SCHEMA_VERSION,
        parser_version=PARSER_VERSION,
        document=document,
        evidence_chunks=evidence_chunks,
        extracted_facts=extracted_facts,
        relations=[],
    )


def _assert_form(root) -> None:
    name = document_name(root)
    compact_name = name.replace(" ", "")
    acode = document_acode(root)
    if acode not in TREASURY_ACQUISITION_DOCUMENT_ACODES:
        raise ValueError(
            "DOCUMENT-NAME ACODE must be "
            f"{sorted(TREASURY_ACQUISITION_DOCUMENT_ACODES)} (자기주식취득결정 서식), got {acode!r}"
        )
    if DOCUMENT_NAME_MARK not in compact_name:
        raise ValueError(f"DOCUMENT-NAME {name!r} is not a {DOCUMENT_NAME_MARK} form")
    if "신탁" in name or "처분" in name:
        raise ValueError(f"DOCUMENT-NAME {name!r} is not a 자기주식취득결정 form")


def _unique_mapped_cells(cells: list[DartCell]) -> list[_MappedCell]:
    mapped: list[_MappedCell] = []
    seen: set[str] = set()
    for cell in cells:
        if is_missing_value(cell.raw_value):
            continue
        fact_key = _fact_key_for(cell)
        if fact_key is None or fact_key in seen:
            continue
        seen.add(fact_key)
        mapped.append(_MappedCell(fact_key, cell))
    return mapped


def _fact_key_for(cell: DartCell) -> str | None:
    if cell.acode and cell.acode in ACODE_FACT_KEYS:
        return ACODE_FACT_KEYS[cell.acode]
    label = cell.combined_label
    if "기타주식" in label:
        return None
    if "취득예정주식" in label:
        return SHARES_FACT_KEY
    if "취득예정금액" in label:
        return AMOUNT_FACT_KEY
    return None


def _to_chunk(cell: DartCell, doc_id: str, sequence: int, source_file: str) -> EvidenceChunk:
    parts = [cell.section_title, cell.row_label, cell.column_label, cell.raw_value]
    text = " | ".join(part for part in parts if part)
    return EvidenceChunk(
        chunk_id=f"{doc_id}:chunk:{sequence:04d}",
        doc_id=doc_id,
        sequence=sequence,
        content_type=ContentType.TABLE,
        text=text,
        source_file=source_file,
        source_format=SourceFormat.DART_XML,
        page_number=None,
        section_title=cell.section_title,
        table_title=cell.section_title,
        row_label=cell.row_label,
        column_label=cell.column_label,
    )


def _to_fact(
    item: _MappedCell,
    document: DisclosureDocument,
    sequence: int,
    chunk_id: str,
) -> ExtractedFact:
    cell = item.cell
    if item.fact_key == SHARES_FACT_KEY:
        raw_unit, normalized_unit = "주", "share"
    else:
        raw_unit, normalized_unit = "원", "KRW"
    normalized_value = normalize_number(cell.raw_value)
    label_parts = [part for part in (cell.row_label, cell.column_label) if part]
    label = " > ".join(label_parts) if label_parts else item.fact_key
    return ExtractedFact(
        fact_id=f"{document.doc_id}:fact:{sequence:04d}",
        doc_id=document.doc_id,
        chunk_id=chunk_id,
        fact_key=item.fact_key,
        label=label,
        value_type=ValueType.NUMBER,
        raw_value=cell.raw_value,
        raw_unit=raw_unit,
        normalized_value=normalized_value,
        normalized_unit=normalized_unit,
        period_start=None,
        period_end=None,
        as_of_date=document.rcept_dt,
        period_basis=PeriodBasis.POINT_IN_TIME,
        scope=Scope.NOT_APPLICABLE,
        extraction_status=ExtractionStatus.OK,
    )


def _fallback_chunk(root, doc_id: str, source_file: str) -> EvidenceChunk:
    name = document_name(root)
    body_text = collect_body_text(root)
    text = body_text or name
    return EvidenceChunk(
        chunk_id=f"{doc_id}:chunk:0000",
        doc_id=doc_id,
        sequence=0,
        content_type=ContentType.PARAGRAPH,
        text=text,
        source_file=source_file,
        source_format=SourceFormat.DART_XML,
        page_number=None,
        section_title=name,
        table_title=None,
        row_label=None,
        column_label=None,
    )
