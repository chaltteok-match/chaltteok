"""거래소공시 서식 파서.

원문은 확장자가 ``.xml``이지만 실제로는 HTML이다(1,469건 전수 확인).
``ACODE``가 없어 **라벨 텍스트로 항목을 식별**하고, 값 셀은
``<span class="xforms_input">``으로 표시된다. 정기공시·주요사항보고서의 DART XML
파서를 재사용할 수 없어 별도 모듈로 둔다.

인코딩 선언은 1,469건 전부 ``euc-kr``이지만 실제 바이트는 전부 UTF-8이다.
선언을 믿고 디코딩하면 전량 깨지므로 UTF-8로 고정해 읽는다.

값은 원문에서 이미 정규화되어 있다(금액은 원 고정, 날짜는 ISO 고정). 정기공시와
달리 단위 표기가 흔들리지 않아 단위를 서식 표에 상수로 둔다.

서식 4종 중 ``투자판단관련주요경영사항``은 제목과 주요내용이 자유 서술이라
사실을 만들지 않고 근거 chunk만 남긴다. 금액 문자열이 있는 문서도
``5.9조원(보조금 0.7조원 포함)``처럼 단서가 붙어, 숫자만 뽑으면 근거와 어긋난다.
"""

from __future__ import annotations

import re
from dataclasses import dataclass
from html.parser import HTMLParser
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
PARSER_VERSION = "0.2.0"

# 값 셀을 표시하는 클래스. 서식 4종 1,469건 전부 이 클래스로 값을 감싼다.
VALUE_CLASS = "xforms_input"

_WHITESPACE = re.compile(r"\s+")
# 번호가 붙은 라벨은 대단원을 연다: "3. 계약상대"
_SECTION_LABEL = re.compile(r"^\d+\.\s*")
# 글머리가 붙은 라벨은 직전 대단원에 속한다: "-최근 매출액(원)"
_SUBITEM_LABEL = re.compile(r"^[-─]\s*")
_MISSING_RAW_VALUES = frozenset({"-", "–", "—"})

_NUMBER = re.compile(r"-?[\d,]+(?:\.\d+)?")
_DATE_ISO = re.compile(r"^(\d{4})[-.](\d{1,2})[-.](\d{1,2})$")
_DATE_KO = re.compile(r"^(\d{4})년\s*(\d{1,2})월\s*(\d{1,2})일$")


class ExchangeFormError(ValueError):
    """거래소공시 서식을 식별하지 못했거나 표를 읽지 못한 경우."""


@dataclass(frozen=True)
class _FieldSpec:
    """라벨 하나가 만드는 사실의 규격.

    단위를 라벨에서 뽑지 않고 여기 적는다. 조건부 계약 서식의 ``확정 계약금액``처럼
    라벨에 단위가 없는 항목이 있어, 라벨에서 뽑으면 같은 금액이 서식에 따라
    단위가 있고 없고가 갈린다.
    """

    fact_key: str
    value_type: ValueType
    raw_unit: str | None = None
    normalized_unit: str | None = None


_AMOUNT = (ValueType.NUMBER, "원", "KRW")
_RATIO = (ValueType.NUMBER, "%", "%")


def _amount(fact_key: str) -> _FieldSpec:
    return _FieldSpec(fact_key, *_AMOUNT)


def _ratio(fact_key: str) -> _FieldSpec:
    return _FieldSpec(fact_key, *_RATIO)


def _date(fact_key: str) -> _FieldSpec:
    return _FieldSpec(fact_key, ValueType.DATE)


def _text(fact_key: str) -> _FieldSpec:
    return _FieldSpec(fact_key, ValueType.TEXT)


# 서식별 라벨 → 사실 규격. 키는 _label_key()로 정규화한 라벨이다.
#
# 같은 항목이 서식에 따라 다른 라벨로 나온다(work/EXCHANGE_SPEC.md §5):
#   계약상대 / 계약상대방          — 조건부 계약 서식 26건
#   계약금액(원) / 계약금액총액(원)  — 조건부 계약 서식은 총액 행이 대응한다
#   계약(수주)일자 / 계약(수주)일    — 각 1,022건 / 58건
# 정정본이 공통으로 갖는 머리 항목. 서식과 무관하게 같은 라벨을 쓴다.
# `2. 정정관련 공시서류제출일`이 원본의 접수일을 가리켜 정정 이력을 잇는 열쇠가 된다.
_CORRECTION_FIELDS: dict[str, _FieldSpec] = {
    "정정일자": _date("correction_date"),
    "정정관련공시서류": _text("correction_target_form"),
    "정정관련공시서류제출일": _date("correction_target_filing_date"),
    "정정사유": _text("correction_reason"),
}

_SUPPLY_CONTRACT: dict[str, _FieldSpec] = {
    "판매ㆍ공급계약구분": _text("contract_type"),
    "판매ㆍ공급계약내용": _text("contract_name"),
    "판매ㆍ공급계약구분>체결계약명": _text("contract_name"),
    "판매ㆍ공급계약구분>세부내용": _text("contract_detail"),
    "계약금액(원)": _amount("contract_amount"),
    "계약금액총액(원)": _amount("contract_amount"),
    "확정계약금액": _amount("contract_fixed_amount"),
    "조건부계약금액": _amount("contract_conditional_amount"),
    "최근매출액(원)": _amount("contract_recent_revenue"),
    "매출액대비(%)": _ratio("contract_revenue_ratio"),
    "계약상대": _text("contract_counterparty"),
    "계약상대방": _text("contract_counterparty"),
    # 대단원 「3. 계약상대방」의 하위 항목은 상대방을 설명한다.
    # 글머리를 떼고 자사 최근매출액과 합치면 상대방 매출을 자사 매출로 답하게 된다.
    "계약상대방>최근매출액(원)": _amount("contract_counterparty_recent_revenue"),
    "판매ㆍ공급지역": _text("contract_region"),
    "시작일": _date("contract_start_date"),
    "종료일": _date("contract_end_date"),
    "계약(수주)일자": _date("contract_order_date"),
    "계약(수주)일": _date("contract_order_date"),
}

_SUPPLY_TERMINATION: dict[str, _FieldSpec] = {
    "판매ㆍ공급계약해지구분": _text("termination_type"),
    "판매ㆍ공급계약해지구분>해지계약명": _text("termination_contract_name"),
    "해지금액(원)": _amount("termination_amount"),
    "최근매출액(원)": _amount("termination_recent_revenue"),
    "매출액대비(%)": _ratio("termination_revenue_ratio"),
    "계약상대": _text("termination_counterparty"),
    "시작일": _date("termination_contract_start_date"),
    "종료일": _date("termination_contract_end_date"),
    "해지주요사유": _text("termination_reason"),
    "해지일자": _date("termination_date"),
}

_FACILITY_INVESTMENT: dict[str, _FieldSpec] = {
    "투자구분": _text("investment_type"),
    "투자구분>투자대상": _text("investment_target"),
    "투자금액(원)": _amount("investment_amount"),
    "자기자본(원)": _amount("investment_equity"),
    "자기자본대비(%)": _ratio("investment_equity_ratio"),
    "투자목적": _text("investment_purpose"),
    "시작일": _date("investment_start_date"),
    "종료일": _date("investment_end_date"),
    "이사회결의일(결정일)": _date("investment_board_resolution_date"),
}


@dataclass(frozen=True)
class _FormSpec:
    """거래소공시 서식 1종."""

    name: str
    fields: dict[str, _FieldSpec]


# report_nm에서 [기재정정] 접두와 괄호 부제를 뗀 이름이 서식이다.
# 136종으로 보이는 report_nm은 접두와 부제 때문이며 실제 서식은 이 4종뿐이다.
FORMS: tuple[_FormSpec, ...] = (
    _FormSpec("단일판매ㆍ공급계약체결", _CORRECTION_FIELDS | _SUPPLY_CONTRACT),
    _FormSpec("단일판매ㆍ공급계약해지", _CORRECTION_FIELDS | _SUPPLY_TERMINATION),
    _FormSpec("신규시설투자등", _CORRECTION_FIELDS | _FACILITY_INVESTMENT),
    # 제목과 주요내용이 자유 서술이라 본문에서는 사실을 만들지 않는다.
    # 정정 머리 항목은 자유 서술이 아니라 정형이라 이력을 잇기 위해 뽑는다.
    _FormSpec("투자판단관련주요경영사항", _CORRECTION_FIELDS),
)

_CORRECTION_PREFIX = re.compile(r"^\[[^\]]+\]")


def resolve_form(report_nm: str) -> _FormSpec:
    """report_nm으로 서식을 정한다. 접두와 괄호 부제는 서식이 아니다."""
    base = _CORRECTION_PREFIX.sub("", report_nm or "").strip()
    for form in FORMS:
        if base.startswith(form.name):
            return form
    raise ExchangeFormError(f"알 수 없는 거래소공시 서식입니다: {report_nm!r}")


@dataclass(frozen=True)
class _Cell:
    text: str
    is_value: bool


class _TableReader(HTMLParser):
    """표를 행 단위로 읽는다. 값 셀은 xforms_input 클래스로 구분한다."""

    def __init__(self) -> None:
        super().__init__(convert_charrefs=True)
        self.rows: list[list[_Cell]] = []
        self._row: list[_Cell] | None = None
        self._buffer: list[str] = []
        self._in_cell = False
        self._is_value = False

    def handle_starttag(self, tag: str, attrs: list[tuple[str, str | None]]) -> None:
        if tag == "tr":
            self._row = []
        elif tag in {"td", "th"}:
            self._in_cell, self._is_value, self._buffer = True, False, []
        elif tag == "span" and self._in_cell:
            classes = dict(attrs).get("class") or ""
            if VALUE_CLASS in classes:
                self._is_value = True

    def handle_endtag(self, tag: str) -> None:
        if tag in {"td", "th"} and self._in_cell:
            if self._row is not None:
                self._row.append(_Cell(_squash("".join(self._buffer)), self._is_value))
            self._in_cell = False
        elif tag == "tr" and self._row is not None:
            if self._row:
                self.rows.append(self._row)
            self._row = None

    def handle_data(self, data: str) -> None:
        if self._in_cell:
            self._buffer.append(data)


@dataclass(frozen=True)
class _Pair:
    """표 한 행에서 뽑은 라벨-값 한 쌍."""

    labels: tuple[str, ...]
    value: str

    @property
    def label(self) -> str:
        """값에 가장 가까운 라벨. 항목을 정하는 것은 마지막 라벨이다."""
        return self.labels[-1]

    @property
    def section(self) -> str | None:
        """rowspan으로 묶인 상위 라벨(예: '2. 계약내역')."""
        return self.labels[0] if len(self.labels) > 1 else None


def _squash(text: str) -> str:
    return _WHITESPACE.sub(" ", text.replace("\xa0", " ")).strip()


def _iter_field_keys(pairs: list[_Pair]) -> list[str]:
    """라벨을 대단원까지 반영한 비교용 키로 바꾼다.

    글머리(``-``)가 붙은 라벨은 직전 번호 대단원에 속한다. 글머리만 떼면
    「3. 계약상대방」 아래의 ``-최근 매출액(원)``(상대방 매출)이 자사
    ``최근 매출액(원)``과 같은 키가 되어 서로 다른 회사의 매출이 뒤섞인다.

    공백은 지운다. '최근매출액(원)'과 '최근 매출액(원)'이 같은 항목인데
    서식마다 공백이 다르다.
    """
    keys: list[str] = []
    section: str | None = None
    for pair in pairs:
        label = pair.label
        if matched := _SECTION_LABEL.match(label):
            section = _WHITESPACE.sub("", label[matched.end():])
            keys.append(section)
        elif matched := _SUBITEM_LABEL.match(label):
            item = _WHITESPACE.sub("", label[matched.end():])
            keys.append(f"{section}>{item}" if section else item)
        else:
            keys.append(_WHITESPACE.sub("", label))
    return keys


def _read_pairs(source_html: Path) -> list[_Pair]:
    """원문에서 라벨-값 쌍을 순서대로 뽑는다.

    charset 선언(euc-kr)을 믿지 않고 UTF-8로 읽는다. 실제 바이트는 전수 UTF-8이다.
    """
    reader = _TableReader()
    reader.feed(source_html.read_bytes().decode("utf-8"))
    reader.close()

    pairs: list[_Pair] = []
    for row in reader.rows:
        labels = tuple(cell.text for cell in row if not cell.is_value and cell.text)
        values = [cell.text for cell in row if cell.is_value]
        if labels and values:
            pairs.append(_Pair(labels, values[0]))
    if not pairs:
        raise ExchangeFormError(f"라벨-값 쌍을 찾지 못했습니다: {source_html}")
    return pairs


def _is_missing(raw_value: str) -> bool:
    return not raw_value or raw_value in _MISSING_RAW_VALUES


def _normalize_number(raw_value: str) -> str | None:
    matched = _NUMBER.fullmatch(raw_value)
    if not matched:
        return None
    return raw_value.replace(",", "")


def _normalize_date(raw_value: str) -> str | None:
    matched = _DATE_ISO.match(raw_value) or _DATE_KO.match(raw_value)
    if not matched:
        return None
    year, month, day = (int(part) for part in matched.groups())
    try:
        from datetime import date as _date_type

        return _date_type(year, month, day).isoformat()
    except ValueError:
        return None


def parse_exchange_disclosure(
    source_html: Path,
    document: DisclosureDocument,
) -> PreprocessResult:
    """거래소공시 원문 1건을 검증된 PreprocessResult로 만든다."""
    form = resolve_form(document.report_nm)
    pairs = _read_pairs(source_html)
    source_file = f"{document.source_path}/{source_html.name}"

    evidence_chunks: list[EvidenceChunk] = []
    extracted_facts: list[ExtractedFact] = []
    for pair, field_key in zip(pairs, _iter_field_keys(pairs), strict=True):
        if _is_missing(pair.value):
            continue
        chunk = _to_chunk(pair, document.doc_id, len(evidence_chunks), source_file)
        evidence_chunks.append(chunk)

        spec = form.fields.get(field_key)
        if spec is None:
            continue
        extracted_facts.append(
            _to_fact(pair, spec, document, len(extracted_facts), chunk.chunk_id)
        )

    if not evidence_chunks:
        raise ExchangeFormError(f"값이 있는 항목이 없습니다: {source_html}")

    return PreprocessResult(
        schema_version=SCHEMA_VERSION,
        parser_version=PARSER_VERSION,
        document=document,
        evidence_chunks=evidence_chunks,
        extracted_facts=extracted_facts,
        relations=[],
    )


def _to_chunk(
    pair: _Pair,
    doc_id: str,
    sequence: int,
    source_file: str,
) -> EvidenceChunk:
    section = pair.section
    text = " | ".join(part for part in (section, pair.label, pair.value) if part)
    return EvidenceChunk(
        chunk_id=f"{doc_id}:chunk:{sequence:04d}",
        doc_id=doc_id,
        sequence=sequence,
        content_type=ContentType.TABLE,
        text=text,
        source_file=source_file,
        source_format=SourceFormat.HTML,
        page_number=None,
        section_title=section,
        table_title=section,
        row_label=pair.label,
        column_label=None,
    )


def _to_fact(
    pair: _Pair,
    spec: _FieldSpec,
    document: DisclosureDocument,
    sequence: int,
    chunk_id: str,
) -> ExtractedFact:
    if spec.value_type is ValueType.NUMBER:
        normalized_value = _normalize_number(pair.value)
    elif spec.value_type is ValueType.DATE:
        normalized_value = _normalize_date(pair.value)
    else:
        # 텍스트 항목은 계산·비교 대상이 아니라 정규화하지 않는다(docs/data-model.md §5).
        normalized_value = None

    return ExtractedFact(
        fact_id=f"{document.doc_id}:fact:{sequence:04d}",
        doc_id=document.doc_id,
        chunk_id=chunk_id,
        fact_key=spec.fact_key,
        label=pair.label,
        value_type=spec.value_type,
        raw_value=pair.value,
        raw_unit=spec.raw_unit,
        normalized_value=normalized_value,
        normalized_unit=spec.normalized_unit if normalized_value else None,
        period_start=None,
        period_end=None,
        # 거래소공시는 사건 공시다. 값은 접수 시점의 상태를 가리킨다.
        as_of_date=document.rcept_dt,
        period_basis=PeriodBasis.POINT_IN_TIME,
        scope=Scope.NOT_APPLICABLE,
        extraction_status=ExtractionStatus.OK,
    )
