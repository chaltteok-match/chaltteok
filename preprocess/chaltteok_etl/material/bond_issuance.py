"""전환사채권·교환사채권 발행결정(주요사항보고서) DART XML 파서.

권면총액·표면이자율·만기일·전환/교환가액·대상 주식 수를 뽑는다. 값이 "-"이면 사실을
만들지 않는다. 신주인수권부사채·자기전환사채·제3자 전환권 행사는 다루지 않는다.
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

CONVERTIBLE_DOCUMENT_ACODE = "11324"
EXCHANGEABLE_DOCUMENT_ACODE = "11326"
DOCUMENT_ACODES = frozenset({CONVERTIBLE_DOCUMENT_ACODE, EXCHANGEABLE_DOCUMENT_ACODE})

FACE_AMOUNT_FACT_KEY = "bond_face_amount"
COUPON_RATE_FACT_KEY = "bond_coupon_rate"
MATURITY_DATE_FACT_KEY = "bond_maturity_date"
EXERCISE_PRICE_FACT_KEY = "bond_exercise_price"
EXERCISE_SHARES_FACT_KEY = "bond_exercise_shares"

ACODE_FACT_KEYS: dict[str, str] = {
    "DNM_SUM": FACE_AMOUNT_FACT_KEY,
    "PRFT_RATE": COUPON_RATE_FACT_KEY,
    "EXP_DT": MATURITY_DATE_FACT_KEY,
    "EXE_PRC": EXERCISE_PRICE_FACT_KEY,
    "STK_CNT": EXERCISE_SHARES_FACT_KEY,
}

_TEXT_KEYS = frozenset({MATURITY_DATE_FACT_KEY})


@dataclass(frozen=True)
class _MappedCell:
    fact_key: str
    cell: DartCell


def parse_bond_issuance(source_xml: Path, document: DisclosureDocument) -> PreprocessResult:
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
    compact = name.replace(" ", "")
    acode = document_acode(root)
    if acode not in DOCUMENT_ACODES:
        raise ValueError(
            "DOCUMENT-NAME ACODE must be "
            f"{sorted(DOCUMENT_ACODES)} (전환·교환사채권발행결정 서식), got {acode!r}"
        )
    if "자기전환" in compact or "제3자의전환" in compact:
        raise ValueError(f"DOCUMENT-NAME {name!r} is not a 전환·교환사채권발행결정 form")
    if "전환사채권발행결정" not in compact and "교환사채권발행결정" not in compact:
        raise ValueError(f"DOCUMENT-NAME {name!r} is not a 전환·교환사채권발행결정 form")


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
    if "권면" in label and "총액" in label and "해외" not in label and "한도" not in label:
        return FACE_AMOUNT_FACT_KEY
    if "표면이자율" in label:
        return COUPON_RATE_FACT_KEY
    if "사채만기일" in label or (label.strip().endswith("만기일") and "이자" not in label):
        return MATURITY_DATE_FACT_KEY
    if ("전환가액" in label or "교환가액" in label) and "결정방법" not in label and "조정" not in label:
        return EXERCISE_PRICE_FACT_KEY
    if "주식수" in label.replace(" ", "") and "대비" not in label and "총수" not in label:
        return EXERCISE_SHARES_FACT_KEY
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
    label_parts = [part for part in (cell.row_label, cell.column_label) if part]
    label = " > ".join(label_parts) if label_parts else item.fact_key
    if is_text:
        return ExtractedFact(
            fact_id=f"{document.doc_id}:fact:{sequence:04d}",
            doc_id=document.doc_id,
            chunk_id=chunk_id,
            fact_key=item.fact_key,
            label=label,
            value_type=ValueType.TEXT,
            raw_value=cell.raw_value,
            raw_unit=None,
            normalized_value=None,
            normalized_unit=None,
            period_start=None,
            period_end=None,
            as_of_date=None,
            period_basis=PeriodBasis.NOT_APPLICABLE,
            scope=Scope.NOT_APPLICABLE,
            extraction_status=ExtractionStatus.OK,
        )
    raw_unit, normalized_unit = _units_for(item.fact_key)
    normalized_value = normalize_number(cell.raw_value)
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
        extraction_status=(
            ExtractionStatus.OK if normalized_value is not None else ExtractionStatus.UNIT_MISSING
        ),
    )


def _units_for(fact_key: str) -> tuple[str, str]:
    if fact_key == EXERCISE_SHARES_FACT_KEY:
        return "주", "share"
    if fact_key == COUPON_RATE_FACT_KEY:
        return "%", "%"
    return "원", "KRW"


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
