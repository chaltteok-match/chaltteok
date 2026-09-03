"""정기공시 ``III. 재무에 관한 사항 > 1. 요약재무정보`` 파서.

설계 근거와 전수 조사 결과는 ``docs/architecture/periodic-parsing.md`` 참조.
핵심 결정 두 가지:

1. 기간 컬럼 앵커는 연도가 아니라 **기수(``제N기``)** 를 쓴다.
   전수 조사에서 기수 93.4% > 연도 88.9%였고, 연도 표기는 형식이 제각각이다.
2. 연도는 XML이 아니라 manifest ``base_year``에서 역산한다.
   ``N번째 컬럼 연도 = base_year - (당기 기수 - N번째 기수)`` (322건 검증, 100% 성립).
"""

from __future__ import annotations

import calendar
import re
from dataclasses import dataclass
from datetime import date
from decimal import Decimal, InvalidOperation
from pathlib import Path
from typing import Any

from ..common.manifest import (
    build_document,
    logical_path,
    resolve_main_xml,
    resolve_source_dir,
)
from ..common.models import (
    ContentType,
    EvidenceChunk,
    ExtractionStatus,
    ExtractedFact,
    PeriodBasis,
    PreprocessResult,
    Scope,
    SourceFormat,
    ValueType,
)
from . import dart_xml

PARSER_VERSION = "0.1.0"
SCHEMA_VERSION = "0.2"
SECTION_TITLE_KEYWORDS = ("요약재무정보", "요약연결재무정보")

RE_PERIOD_NO = re.compile(r"제\s*(\d+)\s*기")
RE_YEAR = re.compile(r"(?:19|20)\d{2}")
# 표기 흔들림 흡수: (단위 : 백만원) (단위:백만원) (단위: 백만원)
RE_UNIT = re.compile(r"\(\s*단위\s*[:：]?\s*([^)<]{1,20}?)\s*\)")
# 소제목 어순이 두 가지다: '가. 요약연결재무정보' / '1) 연결 요약재무정보'
RE_SCOPE_MARKER = re.compile(
    r"요약\s*(?P<after>연결|별도|개별)\s*재무\s*정보"
    r"|(?P<before>연결|별도|개별)\s*요약\s*재무\s*정보"
)
# 기준을 밝히지 않은 요약재무정보 소제목: '나. 요약 재무정보'
# 앞 소제목이 '가. 요약 연결 재무정보'였다고 해서 이 표까지 연결로 볼 수 없다.
RE_SUMMARY_HEADING = re.compile(r"요약\s*재무\s*정보")
# 소제목 없이 표 머리글에 기준을 적는 문서가 있다: '제44기 1분기말(연결)'
RE_SCOPE_IN_HEADER = re.compile(r"[(（]\s*(연결|별도|개별)\s*[)）]")
# 라벨 앞의 번호·로마자 접두 제거: '2.당기순이익', 'I.영업수익'
RE_LABEL_PREFIX = re.compile(r"^[\sㆍ·\-\*○●□■◦▪]*(?:[IVXivx]+|\d+)\s*[.)]\s*")
RE_LABEL_BULLET = re.compile(r"^[\sㆍ·\-\*○●□■◦▪.]+")
# 전각 로마자 접두: 'Ⅰ.영업이익'. RE_LABEL_PREFIX의 [IVXivx]는 ASCII만 잡는다.
RE_LABEL_ROMAN = re.compile(r"^[\u2160-\u217f]+\s*[.)]\s*")
# 라벨 전체를 감싼 대괄호만 벗긴다: '[매출액]' → '매출액'.
# 뒤쪽만 보고 벗기면 '영업이익(손실)'의 닫는 괄호를 지워 매칭이 깨진다.
RE_LABEL_WRAP = re.compile(r"^\[(.+)\]$")

UNIT_MULTIPLIERS: dict[str, Decimal] = {
    "원": Decimal(1),
    "천원": Decimal(10) ** 3,
    "백만원": Decimal(10) ** 6,
    "억원": Decimal(10) ** 8,
}

# 화이트리스트 정확일치. '순이익'을 포함하는 라벨이 201종이라 부분일치를 쓰면
# 기본주당순이익(원 단위)·비지배지분순이익·법인세비용차감전순이익을 잘못 집어온다.
FACT_LABELS: dict[str, str] = {}
for _key, _labels in {
    "revenue": ("매출액", "영업수익", "매출", "영업수익(매출액)"),
    "operating_profit": (
        "영업이익", "영업이익(손실)", "영업이익(영업손실)", "영업손실", "영업손익",
    ),
    "net_income": (
        "당기순이익", "당기순이익(손실)", "당기순이익(당기순손실)", "연결당기순이익",
        "연결총당기순이익", "총당기순이익", "당기연결순이익", "반기순이익", "분기순이익",
        "당기순손실", "연결당기순손실", "반기순손실", "분기순손실",
    ),
    "total_assets": ("자산총계",),
    "total_liabilities": ("부채총계",),
    "total_equity": ("자본총계",),
}.items():
    for _label in _labels:
        FACT_LABELS[_label] = _key

BALANCE_KEYS = {"total_assets", "total_liabilities", "total_equity"}
MISSING_VALUES = {"", "-", "－", "—", "–", "‐", "n/a", "N/A", "해당사항없음", "해당없음"}
NO_DATA_PATTERNS = (
    re.compile(r"기재하지\s*아니"),
    re.compile(r"기재를?\s*생략"),
    re.compile(r"해당\s*사항\s*이?\s*없"),
    re.compile(r"소규모\s*기업"),
    re.compile(r"작성하지\s*아니"),
)

SCOPE_BY_MARKER = {
    "연결": Scope.CONSOLIDATED,
    "별도": Scope.SEPARATE,
    "개별": Scope.SEPARATE,
}


class UnsupportedDocument(RuntimeError):
    """이 파서의 대상이 아닌 문서 — 실패가 아니라 건너뛸 대상이다."""


class PeriodicParseError(RuntimeError):
    """원문을 열었으나 기대한 구조를 찾지 못한 경우 — 전처리 실패다.

    공시에 값이 없는 경우(``docs/data-model.md`` §9의 '정상 전처리 결과')와
    반드시 구분한다. 빈 결과를 정상으로 내보내지 않기 위한 예외다.
    """


@dataclass(frozen=True)
class PeriodColumn:
    """값 셀 하나가 가리키는 기간."""

    index: int
    label: str
    year: int
    end_month: int

    @property
    def end_date(self) -> date:
        last_day = calendar.monthrange(self.year, self.end_month)[1]
        return date(self.year, self.end_month, last_day)


def normalize_label(text: str) -> str:
    """비교용 라벨 정규화. 원문 라벨 자체는 보존한다."""
    text = RE_LABEL_PREFIX.sub("", text)
    text = RE_LABEL_ROMAN.sub("", text)
    text = RE_LABEL_BULLET.sub("", text)
    text = re.sub(r"\s+", "", text).strip()
    wrapped = RE_LABEL_WRAP.match(text)
    if wrapped:
        inner = RE_LABEL_PREFIX.sub("", wrapped.group(1))
        text = RE_LABEL_BULLET.sub("", inner)
    return text


def parse_amount(raw: str) -> Decimal | None:
    """표 셀의 금액 문자열을 Decimal로. 값이 없으면 None."""
    text = raw.strip()
    if text in MISSING_VALUES:
        return None
    negative = False
    if text.startswith(("△", "▲", "▽", "−", "-")):
        negative = True
        text = text[1:]
    if text.startswith("(") and text.endswith(")"):
        negative = True
        text = text[1:-1]
    text = text.replace(",", "").replace(" ", "").replace("원", "")
    if not re.fullmatch(r"\d+(?:\.\d+)?", text):
        return None
    try:
        value = Decimal(text)
    except InvalidOperation:
        return None
    return -value if negative else value


def format_decimal(value: Decimal) -> str:
    """계약이 요구하는 10진수 문자열(지수 표기 금지)."""
    quantized = value.normalize()
    if quantized == quantized.to_integral_value():
        return str(quantized.quantize(Decimal(1)))
    return format(quantized, "f")


def _unit_of(match: re.Match[str]) -> str:
    """'(단위 : 백만원, 주당순이익 : 원)'처럼 복합 표기면 앞의 단위를 쓴다."""
    return match.group(1).split(",")[0].replace(" ", "").strip()


def find_table_unit(rows: list[list[str]]) -> str | None:
    """표 자체가 선언한 단위.

    셀 전체가 단위 표기인 경우만 표 단위로 인정한다.
    ``기본주당순이익(단위 : 원)``처럼 항목명에 붙은 단위는 그 **행**에만 적용되므로
    표 전체의 단위로 삼으면 안 된다 (한 섹션에 단위가 다른 표·행이 섞인다).
    """
    for row in rows:
        for cell in row:
            match = RE_UNIT.fullmatch(cell.strip())
            if match:
                return _unit_of(match)
    return None


def find_unit_before(section: str, position: int) -> str | None:
    """표 앞에서 가장 마지막으로 선언된 단위."""
    last = None
    for match in RE_UNIT.finditer(section, 0, position):
        last = match
    return _unit_of(last) if last else None


def resolve_columns(
    rows: list[list[str]], *, base_year: int, base_month: int
) -> tuple[list[PeriodColumn], int] | None:
    """표의 머리글 행에서 기간 컬럼을 해석한다. 실패하면 None."""
    for row_index, row in enumerate(rows):
        cells = list(row)
        while cells and not cells[-1]:
            cells.pop()
        if len(cells) < 2:
            continue
        headers = cells[1:]
        if not any(RE_PERIOD_NO.search(c) or RE_YEAR.search(c) for c in headers):
            continue

        numbers = [RE_PERIOD_NO.search(c) for c in headers]
        if all(numbers):
            values = [int(m.group(1)) for m in numbers]
            # 컬럼 순서가 오름차순인 문서도 있으므로 '가장 큰 기수 = 당기'로 고정한다.
            current = max(values)
            columns = [
                PeriodColumn(
                    index=i,
                    label=headers[i],
                    year=base_year - (current - values[i]),
                    end_month=base_month if values[i] == current else 12,
                )
                for i in range(len(headers))
            ]
            return columns, row_index

        years = [RE_YEAR.search(c) for c in headers]
        if all(years):
            columns = [
                PeriodColumn(
                    index=i,
                    label=headers[i],
                    year=int(years[i].group(0)),
                    end_month=base_month if int(years[i].group(0)) == base_year else 12,
                )
                for i in range(len(headers))
            ]
            return columns, row_index
    return None


def scope_from_header(column_labels: list[str]) -> Scope | None:
    """표 머리글이 밝힌 연결·별도 기준.

    소제목을 쓰지 않고 ``제44기 1분기말(연결)``처럼 머리글에 적는 문서가 있다.
    한 머리글에 연결과 별도가 섞여 있으면 판정하지 않는다.
    """
    found = {
        SCOPE_BY_MARKER[match.group(1)]
        for label in column_labels
        if (match := RE_SCOPE_IN_HEADER.search(label))
    }
    return found.pop() if len(found) == 1 else None


def _heading_text(section: str, position: int, fallback: str) -> str:
    block_start = section.rfind("<P", 0, position)
    block_end = section.find("</P>", position)
    if block_start != -1 and block_end != -1:
        candidate = dart_xml.clean(section[block_start:block_end])
        if candidate and len(candidate) <= 40:
            return candidate
    return fallback


def _scope_spans(section: str) -> list[tuple[int, Scope, str]]:
    """섹션 안의 요약재무정보 소제목 위치를 모은다.

    기준을 밝힌 소제목('가. 요약연결재무정보')은 그 기준으로, 밝히지 않은 소제목
    ('나. 요약 재무정보')은 ``unknown``으로 둔다. 밝히지 않은 소제목을 건너뛰면
    앞 소제목의 기준이 그대로 물려져 별도 표가 연결로 기록된다.
    """
    spans: list[tuple[int, Scope, str]] = []
    covered: list[tuple[int, int]] = []
    for match in RE_SCOPE_MARKER.finditer(section):
        scope = SCOPE_BY_MARKER[match.group("after") or match.group("before")]
        spans.append((match.start(), scope, _heading_text(section, match.start(), match.group(0))))
        covered.append((match.start(), match.end()))

    for match in RE_SUMMARY_HEADING.finditer(section):
        if any(start <= match.start() < end for start, end in covered):
            continue
        spans.append(
            (match.start(), Scope.UNKNOWN, _heading_text(section, match.start(), match.group(0)))
        )

    spans.sort(key=lambda span: span[0])
    return spans


def _scope_for(spans, position: int) -> tuple[Scope, str | None]:
    chosen = None
    for start, scope, heading in spans:
        if start <= position:
            chosen = (scope, heading)
        else:
            break
    # 소제목으로 판정되지 않으면 추정하지 않는다 (docs/data-model.md §5)
    return chosen if chosen else (Scope.UNKNOWN, None)


def parse_summary_financials(
    corpus_root: Path, manifest_row: dict[str, Any]
) -> PreprocessResult:
    """정기공시 한 건의 요약재무정보를 ``PreprocessResult``로 변환한다."""
    if manifest_row.get("doc_group") != "periodic":
        raise UnsupportedDocument(
            f"정기공시가 아닙니다: doc_group={manifest_row.get('doc_group')}"
        )
    if manifest_row.get("file_format") != "xml":
        raise UnsupportedDocument(
            f"XML 원문이 아닙니다: file_format={manifest_row.get('file_format')}"
        )

    document = build_document(manifest_row)
    corpus_root = Path(corpus_root)
    source_dir = resolve_source_dir(corpus_root, manifest_row)
    main_xml = resolve_main_xml(source_dir, manifest_row)
    source_file = logical_path(corpus_root, main_xml)

    raw = dart_xml.read_document(main_xml)
    span = dart_xml.find_section(raw, title_contains=SECTION_TITLE_KEYWORDS)
    if span is None:
        raise PeriodicParseError(
            f"'1. 요약재무정보' 중단원을 찾지 못했습니다 (doc_id={document.doc_id})"
        )
    section = raw[span[0] : span[1]]
    section_title = dart_xml.clean(
        dart_xml.RE_SECTION2.search(section).group(1)
    ) if dart_xml.RE_SECTION2.search(section) else "1. 요약재무정보"

    spans = _scope_spans(section)
    tables = dart_xml.parse_tables(section)

    chunks: list[EvidenceChunk] = []
    facts: list[ExtractedFact] = []
    doc_id = document.doc_id
    base_year = document.base_year
    base_month = document.base_month
    last_unit: str | None = None

    for table in tables:
        table_unit = find_table_unit(table.rows) or find_unit_before(section, table.start)
        if table_unit:
            last_unit = table_unit
        else:
            table_unit = last_unit

        resolved = resolve_columns(
            table.rows, base_year=base_year, base_month=base_month
        )
        if resolved is None:
            continue
        columns, header_index = resolved
        # 머리글은 그 표에만 적용되므로 섹션 소제목보다 구체적이다. 머리글을 먼저 본다.
        scope, heading = _scope_for(spans, table.start)
        header_scope = scope_from_header([column.label for column in columns])
        if header_scope is not None:
            scope = header_scope
        multiplier = UNIT_MULTIPLIERS.get(table_unit or "")

        for row in table.rows[header_index + 1 :]:
            cells = list(row)
            while cells and not cells[-1]:
                cells.pop()
            if len(cells) != len(columns) + 1:
                # 셀 수가 맞지 않으면 열 정렬을 신뢰할 수 없다. 추측하지 않고 건너뛴다.
                continue
            fact_key = FACT_LABELS.get(normalize_label(cells[0]))
            if fact_key is None:
                continue

            values = cells[1:]
            amounts = [parse_amount(v) for v in values]
            if all(a is None for a in amounts):
                continue

            sequence = len(chunks)
            chunk_id = f"{doc_id}:chunk:{sequence:04d}"
            detail = " / ".join(
                f"{column.label}: {values[column.index]}" for column in columns
            )
            prefix = f"[{heading}] " if heading else ""
            unit_note = f" (단위: {table_unit})" if table_unit else ""
            chunks.append(
                EvidenceChunk(
                    chunk_id=chunk_id,
                    doc_id=doc_id,
                    sequence=sequence,
                    content_type=ContentType.TABLE,
                    text=f"{prefix}{cells[0]} — {detail}{unit_note}",
                    source_file=source_file,
                    source_format=SourceFormat.DART_XML,
                    page_number=None,
                    section_title=section_title,
                    table_title=heading,
                    row_label=cells[0],
                    column_label=None,
                )
            )

            for column in columns:
                amount = amounts[column.index]
                if amount is None:
                    continue
                is_balance = fact_key in BALANCE_KEYS
                # 단위를 확인하지 못하면 배수를 추측하지 않는다(docs/data-model.md §5).
                # 값을 버리지 않고 raw_value만 보존한 채 정규화값을 비워 둔다 —
                # 비어 있다는 사실이 데이터에 남아야 조용한 실패가 되지 않는다.
                normalized = (
                    format_decimal(amount * multiplier) if multiplier is not None else None
                )
                facts.append(
                    ExtractedFact(
                        fact_id=f"{doc_id}:fact:{len(facts):04d}",
                        doc_id=doc_id,
                        chunk_id=chunk_id,
                        fact_key=fact_key,
                        label=cells[0],
                        value_type=ValueType.NUMBER,
                        raw_value=values[column.index],
                        raw_unit=table_unit,
                        normalized_value=normalized,
                        normalized_unit="KRW" if normalized is not None else None,
                        period_start=None if is_balance else date(column.year, 1, 1),
                        period_end=None if is_balance else column.end_date,
                        as_of_date=column.end_date if is_balance else None,
                        period_basis=(
                            PeriodBasis.POINT_IN_TIME
                            if is_balance
                            else PeriodBasis.CUMULATIVE
                        ),
                        scope=scope,
                        extraction_status=(
                            ExtractionStatus.OK
                            if normalized is not None
                            else ExtractionStatus.UNIT_MISSING
                        ),
                    )
                )

    if not chunks:
        chunks.append(_no_data_chunk(document, section, source_file, section_title))

    return PreprocessResult(
        schema_version=SCHEMA_VERSION,
        parser_version=PARSER_VERSION,
        document=document,
        evidence_chunks=chunks,
        extracted_facts=facts,
        relations=[],
    )


def _no_data_chunk(document, section: str, source_file: str, section_title: str):
    """값이 없는 경우와 파싱 실패를 구분한다 (docs/data-model.md §9).

    공시가 '기재하지 아니하였습니다'라고 밝힌 경우는 정상적인 값 없음이므로
    문서와 근거는 저장하고 fact만 만들지 않는다. 그 외에는 전처리 실패로 올린다.
    """
    body = dart_xml.clean(section)
    if not any(pattern.search(body) for pattern in NO_DATA_PATTERNS):
        raise PeriodicParseError(
            "요약재무정보 섹션에서 인식 가능한 표를 찾지 못했습니다 "
            f"(doc_id={document.doc_id}); 값 없음이라는 근거 문구도 없습니다"
        )
    return EvidenceChunk(
        chunk_id=f"{document.doc_id}:chunk:0000",
        doc_id=document.doc_id,
        sequence=0,
        content_type=ContentType.PARAGRAPH,
        text=body[:2000],
        source_file=source_file,
        source_format=SourceFormat.DART_XML,
        page_number=None,
        section_title=section_title,
        table_title=None,
        row_label=None,
        column_label=None,
    )
