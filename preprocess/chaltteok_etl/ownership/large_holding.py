"""대량보유상황보고서(지분공시) DART XML 파서.

보유자·보유 주식 수·지분율·보고구분·보유목적을 뽑는다. 값이 "-"이거나 비어 있으면
사실을 만들지 않는다. 신규·변경·해소 사슬은 이 단계에서 잇지 않는다.
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
    document_name,
    is_missing_value,
    iter_te_cells,
    normalize_number,
    parse_dart_xml,
)

SCHEMA_VERSION = "0.2"
PARSER_VERSION = "0.1.0"

DOCUMENT_NAME_MARK = "대량보유상황보고서"

HOLDER_FACT_KEY = "ownership_holder"
SHARES_FACT_KEY = "ownership_shares"
RATIO_FACT_KEY = "ownership_ratio"
REPORT_TYPE_FACT_KEY = "ownership_report_type"
PURPOSE_FACT_KEY = "ownership_purpose"

ACODE_FACT_KEYS: dict[str, str] = {
    "RPROR": HOLDER_FACT_KEY,
    "REPROR": HOLDER_FACT_KEY,
    "STKQY": SHARES_FACT_KEY,
    "STKQTY": SHARES_FACT_KEY,
    "STKRT": RATIO_FACT_KEY,
    "HLD_RT": RATIO_FACT_KEY,
    "REPORT_TP": REPORT_TYPE_FACT_KEY,
    "RPT_TP": REPORT_TYPE_FACT_KEY,
    "REPORT_RESN": PURPOSE_FACT_KEY,
    "RPT_RESN": PURPOSE_FACT_KEY,
    "HLD_PURP": PURPOSE_FACT_KEY,
}

_TEXT_KEYS = frozenset({HOLDER_FACT_KEY, REPORT_TYPE_FACT_KEY, PURPOSE_FACT_KEY})

# 긴 표현을 먼저 본다. 보고자 성명이 보고자보다 앞에 있어야 한다.
_LABEL_FACT_KEYS: tuple[tuple[str, str], ...] = (
    ("대표보고자", HOLDER_FACT_KEY),
    ("보고자 성명", HOLDER_FACT_KEY),
    ("보고자의 성명", HOLDER_FACT_KEY),
    ("보유주식등의 수", SHARES_FACT_KEY),
    ("보유주식의 수", SHARES_FACT_KEY),
    ("보유주식수", SHARES_FACT_KEY),
    ("보유비율", RATIO_FACT_KEY),
    ("지분율", RATIO_FACT_KEY),
    ("보고구분", REPORT_TYPE_FACT_KEY),
    ("보고사유", PURPOSE_FACT_KEY),
    ("보유목적", PURPOSE_FACT_KEY),
    ("보고자", HOLDER_FACT_KEY),
)


@dataclass(frozen=True)
class _MappedCell:
    fact_key: str
    cell: DartCell


def parse_large_holding(source_xml: Path, document: DisclosureDocument) -> PreprocessResult:
    root = parse_dart_xml(source_xml)
    _assert_holding_form(root)
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


def _assert_holding_form(root) -> None:
    name = document_name(root)
    if DOCUMENT_NAME_MARK not in name and "대량보유상황" not in name:
        raise ValueError(f"DOCUMENT-NAME {name!r} is not a 대량보유상황보고서 form")


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
    for keyword, fact_key in _LABEL_FACT_KEYS:
        if keyword in label:
            return fact_key
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
    is_text = item.fact_key in _TEXT_KEYS
    raw_unit, normalized_unit = _units_for(item.fact_key, cell)
    if is_text:
        value_type = ValueType.TEXT
        normalized_value = None
        period_basis = PeriodBasis.NOT_APPLICABLE
        as_of_date = None
        status = ExtractionStatus.OK
    else:
        value_type = ValueType.NUMBER
        normalized_value = normalize_number(cell.raw_value)
        period_basis = PeriodBasis.POINT_IN_TIME
        as_of_date = document.rcept_dt
        status = ExtractionStatus.OK if normalized_value is not None else ExtractionStatus.UNIT_MISSING

    label_parts = [part for part in (cell.row_label, cell.column_label) if part]
    label = " > ".join(label_parts) if label_parts else item.fact_key

    return ExtractedFact(
        fact_id=f"{document.doc_id}:fact:{sequence:04d}",
        doc_id=document.doc_id,
        chunk_id=chunk_id,
        fact_key=item.fact_key,
        label=label,
        value_type=value_type,
        raw_value=cell.raw_value,
        raw_unit=raw_unit,
        normalized_value=normalized_value,
        normalized_unit=normalized_unit,
        period_start=None,
        period_end=None,
        as_of_date=as_of_date,
        period_basis=period_basis,
        scope=Scope.NOT_APPLICABLE,
        extraction_status=status,
    )


def _units_for(fact_key: str, cell: DartCell) -> tuple[str | None, str | None]:
    if fact_key == SHARES_FACT_KEY:
        return "주", "share"
    if fact_key == RATIO_FACT_KEY:
        return "%", "%"
    if "(" in cell.combined_label and "주" in cell.combined_label:
        return "주", "share"
    if "(" in cell.combined_label and "%" in cell.combined_label:
        return "%", "%"
    return None, None


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
