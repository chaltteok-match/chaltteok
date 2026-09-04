"""DART XML(dart4.xsd) 표에서 TE·TU 칸을 읽는다.

지분공시 파서가 같은 표 구조를 쓴다. `periodic/dart_xml.py`와는 역할이 다르다
(정규식으로 본문을 자르지 않고 ElementTree로 표를 읽는다). 유상증자 파서는 아직
자체 ElementTree를 쓴다. 원문 파일은 바꾸지 않고 입력 문자열만 고쳐 ET.parse가
실패하지 않게 한다(docs/data-model.md §9).
"""

from __future__ import annotations

import re
import xml.etree.ElementTree as ET
from dataclasses import dataclass
from decimal import Decimal, InvalidOperation
from pathlib import Path

_WHITESPACE = re.compile(r"\s+")
_BARE_AMP = re.compile(r"&(?!#\d+;|#x[0-9a-fA-F]+;|[A-Za-z][A-Za-z0-9]*;)")
_BARE_LT = re.compile(r"<(?=[^A-Za-z/!?])")
MISSING_RAW_VALUES = frozenset({"-"})


@dataclass(frozen=True)
class DartCell:
    acode: str | None
    raw_value: str
    row_label: str | None
    column_label: str | None
    section_title: str | None

    @property
    def combined_label(self) -> str:
        return " ".join(part for part in (self.row_label, self.column_label) if part)


def parse_dart_xml(source_xml: Path) -> ET.Element:
    try:
        xml_text = source_xml.read_text(encoding="utf-8")
        return ET.fromstring(sanitize_dart_xml(xml_text))
    except ET.ParseError as error:
        raise ValueError(f"failed to parse DART XML: {source_xml}") from error


def sanitize_dart_xml(xml_text: str) -> str:
    return _BARE_LT.sub("&lt;", _BARE_AMP.sub("&amp;", xml_text))


def document_name(root: ET.Element) -> str:
    element = root.find("DOCUMENT-NAME")
    if element is None:
        raise ValueError("DOCUMENT-NAME is missing")
    return readable_text(element)


def document_acode(root: ET.Element) -> str | None:
    element = root.find("DOCUMENT-NAME")
    if element is None:
        return None
    return element.get("ACODE")


def iter_te_cells(root: ET.Element) -> list[DartCell]:
    cells: list[DartCell] = []

    def walk(element: ET.Element, section_title: str | None) -> None:
        current_title = section_title
        if element.tag == "TITLE":
            title_text = readable_text(element)
            if title_text:
                current_title = title_text
        if element.tag == "TABLE":
            cells.extend(_extract_table_cells(element, current_title))
        for child in element:
            walk(child, current_title)

    walk(root, None)
    return cells


def _extract_table_cells(table: ET.Element, section_title: str | None) -> list[DartCell]:
    cells: list[DartCell] = []
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
            text = element_text(child)
            colspan = _int_attr(child, "COLSPAN")
            rowspan = _int_attr(child, "ROWSPAN")
            acode = child.get("ACODE") or child.get("AUNIT")

            if child.tag in {"TD", "TH"} and text:
                labels_before_value.append(readable_label(text))
            elif child.tag in {"TE", "TU"} and (acode or text):
                row_label = labels_before_value[0] if labels_before_value else None
                column_label = (
                    labels_before_value[-1] if len(labels_before_value) > 1 else None
                )
                cells.append(
                    DartCell(
                        acode=acode,
                        raw_value=text,
                        row_label=row_label,
                        column_label=column_label,
                        section_title=section_title,
                    )
                )

            if rowspan > 1 and text:
                rowspan_carry[column] = (rowspan - 1, readable_label(text))
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


def element_text(element: ET.Element) -> str:
    return "".join(element.itertext()).strip()


def readable_text(element: ET.Element) -> str:
    return readable_label(element_text(element))


def readable_label(value: str) -> str:
    return _WHITESPACE.sub(" ", value).strip()


def is_missing_value(raw_value: str) -> bool:
    stripped = raw_value.strip()
    return not stripped or stripped in MISSING_RAW_VALUES


def normalize_number(raw_value: str) -> str:
    compact = raw_value.replace(",", "").replace(" ", "").replace("\n", "").replace("%", "")
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


def collect_body_text(root: ET.Element) -> str:
    body = root.find("BODY")
    start = body if body is not None else root
    parts: list[str] = []
    seen: set[str] = set()
    for element in start.iter():
        if element.tag not in {"P", "TD", "TH", "TE", "TU", "TITLE"}:
            continue
        if element.tag in {"TITLE", "P"} and len(list(element)):
            continue
        text = readable_text(element)
        if not text or is_missing_value(text):
            continue
        if text in seen:
            continue
        seen.add(text)
        parts.append(text)
    return "\n".join(parts)


def _int_attr(element: ET.Element, name: str) -> int:
    raw = element.get(name)
    return int(raw) if raw else 1
