"""유상증자결정(주요사항보고서) DART XML 파서.

한화에어로스페이스 20250408000012 원문 기준으로 추출한다.
값이 "-"이거나 비어 있으면 사실을 추측하지 않고 해당 ExtractedFact를 생략한다.

자금조달 목적(4. 자금조달의 목적)에는 총액 ACODE가 없다. 목적별 금액을
합산하지 않고 각 ACODE를 별도 fact로 둔다. ANC_ACQ_AMT는 총 조달금액이 아니라
타법인 증권취득 목적 항목의 금액이다.
"""

from __future__ import annotations

import re
import xml.etree.ElementTree as ET
from dataclasses import dataclass
from decimal import Decimal, InvalidOperation
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

SCHEMA_VERSION = "0.2"
PARSER_VERSION = "0.1.2"

# DART DOCUMENT-NAME ACODE. 유상증자결정 서식 식별 코드.
PAID_IN_CAPITAL_DOCUMENT_ACODE = "11306"
PAID_IN_CAPITAL_DOCUMENT_NAME_MARK = "유상증자결정"

# ACODE → 표준 fact_key. 규칙: <도메인>_<항목> snake_case
#
# 자금 사용목적 (원문 「4. 자금조달의 목적」 행 순서):
#   FND_USE1     시설자금
#   FND_USE_SQ   영업양수자금  (이 문서 값은 "-")
#   FND_USE2     운영자금
#   FND_USE_RD   채무상환자금
#   ANC_ACQ_AMT  타법인 증권취득자금 — 총 조달금액이 아님
#   FND_USE3     기타자금
ACODE_FACT_KEYS: dict[str, str] = {
    "FND_USE1": "fundraising_purpose_facility",
    "FND_USE_SQ": "fundraising_purpose_business_acquisition",
    "FND_USE2": "fundraising_purpose_operating",
    "FND_USE_RD": "fundraising_purpose_debt_repayment",
    "ANC_ACQ_AMT": "fundraising_purpose_securities_acquisition",
    "FND_USE3": "fundraising_purpose_other",
    "FVAL": "fundraising_par_value",
    "CST_CNT": "fundraising_common_new_shares",
    "BFR_CST_CNT": "fundraising_before_common_shares",
    "BFR_PST_CNT": "fundraising_before_other_class_shares",
    "CST_ISS_VAL": "fundraising_common_issue_price",
    "PST_CNT": "fundraising_other_class_new_shares",
    "PST_ISS_VAL": "fundraising_other_class_issue_price",
    "AOI_BAS": "fundraising_articles_basis",
    "ATD_CNT": "fundraising_outside_director_present",
    "NATD_CNT": "fundraising_outside_director_absent",
    "ETC_CNT": "fundraising_other_notes",
    "RPT_SBM": "fundraising_registration_exemption_reason",
    "STO_CNT": "fundraising_other_stock_details",
}

_TEXT_ACODES = frozenset({"AOI_BAS", "ETC_CNT", "RPT_SBM", "STO_CNT"})

_MISSING_RAW_VALUES = frozenset({"-"})
_WHITESPACE = re.compile(r"\s+")
# DART 원문에 실체참조가 아닌 & 와 태그가 아닌 < 가 섞여 ET.parse가 실패한다.
# 치환은 파서 입력에만 하고 원문 파일은 바꾸지 않는다. docs/data-model.md §9.
_BARE_AMP = re.compile(r"&(?!#\d+;|#x[0-9a-fA-F]+;|[A-Za-z][A-Za-z0-9]*;)")
_BARE_LT = re.compile(r"<(?=[^A-Za-z/!?])")
_UNIT_PATTERNS: tuple[tuple[re.Pattern[str], str, str | None], ...] = (
    (re.compile(r"\(원\)"), "원", "KRW"),
    (re.compile(r"\(주\)"), "주", "share"),
    (re.compile(r"\(%\)"), "%", "%"),
    (re.compile(r"\(명\)"), "명", None),
)


@dataclass(frozen=True)
class _AcodeSpec:
    fact_key: str
    value_type: ValueType


_ACODE_SPECS: dict[str, _AcodeSpec] = {
    acode: _AcodeSpec(
        fact_key,
        ValueType.TEXT if acode in _TEXT_ACODES else ValueType.NUMBER,
    )
    for acode, fact_key in ACODE_FACT_KEYS.items()
}


@dataclass(frozen=True)
class _ExtractedCell:
    acode: str
    raw_value: str
    row_label: str | None
    column_label: str | None
    section_title: str | None


def parse_paid_in_capital_increase(
    source_xml: Path,
    document: DisclosureDocument,
) -> PreprocessResult:
    """Parse one paid-in capital increase XML into a validated PreprocessResult."""

    root = _parse_dart_xml(source_xml)
    _assert_paid_in_capital_form(root)
    source_file = f"{document.source_path}/{source_xml.name}"
    cells = [
        cell
        for cell in _iter_core_cells(root)
        if not _is_missing_value(cell.raw_value)
    ]

    evidence_chunks: list[EvidenceChunk] = []
    extracted_facts: list[ExtractedFact] = []
    for sequence, cell in enumerate(cells):
        chunk = _to_chunk(cell, document.doc_id, sequence, source_file)
        evidence_chunks.append(chunk)
        extracted_facts.append(_to_fact(cell, document, sequence, chunk.chunk_id))

    if not evidence_chunks:
        evidence_chunks.append(
            _fallback_chunk(root, document.doc_id, source_file)
        )

    return PreprocessResult(
        schema_version=SCHEMA_VERSION,
        parser_version=PARSER_VERSION,
        document=document,
        evidence_chunks=evidence_chunks,
        extracted_facts=extracted_facts,
        relations=[],
    )


def _parse_dart_xml(source_xml: Path) -> ET.Element:
    try:
        # Corpus text is UTF-8. External DTDs are not loaded by default.
        xml_text = source_xml.read_text(encoding="utf-8")
        return ET.fromstring(_sanitize_dart_xml(xml_text))
    except ET.ParseError as error:
        raise ValueError(f"failed to parse DART XML: {source_xml}") from error


def _sanitize_dart_xml(xml_text: str) -> str:
    """Escape bare & and < so well-known DART XML quirks parse without losing text."""

    return _BARE_LT.sub("&lt;", _BARE_AMP.sub("&amp;", xml_text))


def _assert_paid_in_capital_form(root: ET.Element) -> None:
    """Cross-check DOCUMENT-NAME ACODE 11306 and 유상증자결정 표기."""

    element = root.find("DOCUMENT-NAME")
    if element is None:
        raise ValueError("DOCUMENT-NAME is missing")
    acode = element.get("ACODE")
    name = _readable_text(element)
    if acode != PAID_IN_CAPITAL_DOCUMENT_ACODE:
        raise ValueError(
            "DOCUMENT-NAME ACODE must be "
            f"{PAID_IN_CAPITAL_DOCUMENT_ACODE} (유상증자결정 서식), got {acode!r}"
        )
    if PAID_IN_CAPITAL_DOCUMENT_NAME_MARK not in name:
        raise ValueError(
            f"DOCUMENT-NAME {name!r} is not a {PAID_IN_CAPITAL_DOCUMENT_NAME_MARK} form"
        )


def _iter_core_cells(root: ET.Element) -> list[_ExtractedCell]:
    cells: list[_ExtractedCell] = []

    def walk(element: ET.Element, section_title: str | None) -> None:
        current_title = section_title
        if element.tag == "TITLE":
            title_text = _readable_text(element)
            if title_text:
                current_title = title_text
        if element.tag == "TABLE":
            cells.extend(_extract_table_cells(element, current_title))
        for child in element:
            walk(child, current_title)

    walk(root, None)
    return cells


def _extract_table_cells(
    table: ET.Element, section_title: str | None
) -> list[_ExtractedCell]:
    cells: list[_ExtractedCell] = []
    rowspan_carry: dict[int, tuple[int, str]] = {}

    for row in _table_rows(table):
        labels_before_value: list[str] = []
        column = 0
        child_index = 0
        children = list(row)

        while child_index < len(children) or column in rowspan_carry:
            if column in rowspan_carry:
                remaining, text = rowspan_carry[column]
                if text:
                    labels_before_value.append(text)
                if remaining <= 1:
                    del rowspan_carry[column]
                else:
                    rowspan_carry[column] = (remaining - 1, text)
                column += 1
                continue

            child = children[child_index]
            child_index += 1
            text = _element_text(child)
            colspan = _int_attr(child, "COLSPAN")
            rowspan = _int_attr(child, "ROWSPAN")
            acode = child.get("ACODE")

            if child.tag in {"TD", "TH"} and text:
                labels_before_value.append(_readable_label(text))
            elif child.tag == "TE" and acode in _ACODE_SPECS:
                row_label = labels_before_value[0] if labels_before_value else None
                column_label = (
                    labels_before_value[-1] if len(labels_before_value) > 1 else None
                )
                cells.append(
                    _ExtractedCell(
                        acode=acode,
                        raw_value=text,
                        row_label=row_label,
                        column_label=column_label,
                        section_title=section_title,
                    )
                )

            if rowspan > 1 and text:
                rowspan_carry[column] = (rowspan - 1, _readable_label(text))
            column += colspan

    return cells


def _table_rows(table: ET.Element) -> list[ET.Element]:
    rows: list[ET.Element] = []
    for section in table:
        if section.tag in {"THEAD", "TBODY"}:
            rows.extend(child for child in section if child.tag == "TR")
        elif section.tag == "TR":
            rows.append(section)
    return rows


def _to_chunk(
    cell: _ExtractedCell,
    doc_id: str,
    sequence: int,
    source_file: str,
) -> EvidenceChunk:
    parts = [
        cell.section_title,
        cell.row_label,
        cell.column_label,
        cell.raw_value,
    ]
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
    cell: _ExtractedCell,
    document: DisclosureDocument,
    sequence: int,
    chunk_id: str,
) -> ExtractedFact:
    spec = _ACODE_SPECS[cell.acode]
    raw_unit, normalized_unit = _units_for(cell, spec)
    if spec.value_type is ValueType.NUMBER:
        normalized_value = _normalize_number(cell.raw_value)
        period_basis = PeriodBasis.POINT_IN_TIME
        as_of_date = document.rcept_dt
    else:
        normalized_value = cell.raw_value
        period_basis = PeriodBasis.NOT_APPLICABLE
        as_of_date = None

    label_parts = [part for part in (cell.row_label, cell.column_label) if part]
    label = " > ".join(label_parts) if label_parts else spec.fact_key

    return ExtractedFact(
        fact_id=f"{document.doc_id}:fact:{sequence:04d}",
        doc_id=document.doc_id,
        chunk_id=chunk_id,
        fact_key=spec.fact_key,
        label=label,
        value_type=spec.value_type,
        raw_value=cell.raw_value,
        raw_unit=raw_unit,
        normalized_value=normalized_value,
        normalized_unit=normalized_unit,
        period_start=None,
        period_end=None,
        as_of_date=as_of_date,
        period_basis=period_basis,
        scope=Scope.NOT_APPLICABLE,
        extraction_status=(
            ExtractionStatus.OK
            if normalized_value is not None
            else ExtractionStatus.UNIT_MISSING
        ),
    )


def _fallback_chunk(
    root: ET.Element, doc_id: str, source_file: str
) -> EvidenceChunk:
    document_name = _first_text(root, "DOCUMENT-NAME") or "주요사항보고서(유상증자결정)"
    body_text = _collect_body_text(root)
    text = body_text or document_name
    return EvidenceChunk(
        chunk_id=f"{doc_id}:chunk:0000",
        doc_id=doc_id,
        sequence=0,
        content_type=ContentType.PARAGRAPH,
        text=text,
        source_file=source_file,
        source_format=SourceFormat.DART_XML,
        page_number=None,
        section_title=document_name,
        table_title=None,
        row_label=None,
        column_label=None,
    )


def _collect_body_text(root: ET.Element) -> str:
    """Keep visible body copy when every mapped TE value is missing ('-')."""

    body = root.find("BODY")
    start = body if body is not None else root
    parts: list[str] = []
    seen: set[str] = set()
    for element in start.iter():
        if element.tag not in {"P", "TD", "TH", "TE", "TITLE"}:
            continue
        if element.tag in {"TITLE", "P"} and len(list(element)):
            continue
        text = _readable_text(element)
        if not text or _is_missing_value(text):
            continue
        if text in seen:
            continue
        seen.add(text)
        parts.append(text)
    return "\n".join(parts)


def _units_for(
    cell: _ExtractedCell, spec: _AcodeSpec
) -> tuple[str | None, str | None]:
    if spec.value_type is not ValueType.NUMBER:
        return None, None
    combined_label = " ".join(
        part for part in (cell.column_label, cell.row_label) if part
    )
    for pattern, raw_unit, normalized_unit in _UNIT_PATTERNS:
        if pattern.search(combined_label):
            return raw_unit, normalized_unit
    return None, None


def _normalize_number(raw_value: str) -> str:
    compact = raw_value.replace(",", "").replace(" ", "").replace("\n", "")
    try:
        number = Decimal(compact)
    except InvalidOperation as error:
        raise ValueError(f"cannot normalize number {raw_value!r}") from error
    if not number.is_finite():
        raise ValueError(f"non-finite number {raw_value!r}")
    formatted = format(number, "f")
    if "." in formatted:
        formatted = formatted.rstrip("0").rstrip(".")
    return formatted


def _is_missing_value(raw_value: str) -> bool:
    stripped = raw_value.strip()
    return not stripped or stripped in _MISSING_RAW_VALUES


def _element_text(element: ET.Element) -> str:
    return "".join(element.itertext()).strip()


def _readable_text(element: ET.Element) -> str:
    return _readable_label(_element_text(element))


def _readable_label(value: str) -> str:
    return _WHITESPACE.sub(" ", value).strip()


def _first_text(root: ET.Element, tag: str) -> str | None:
    element = root.find(tag)
    if element is None:
        return None
    text = _readable_text(element)
    return text or None


def _int_attr(element: ET.Element, name: str) -> int:
    raw = element.get(name)
    return int(raw) if raw else 1
