"""DART 고유 XML을 다루는 최소 도우미.

DART XML은 표준 XML 파서로 열리지 않는 문서가 있어(비정상 엔티티·중복 속성 등)
정규식 기반 텍스트 처리로 다룬다. ``docs/data-model.md`` §2.5 참조.

TODO: 거래소공시 파서가 추가되면 ``common/``으로 승격을 검토한다.
"""

from __future__ import annotations

import re
import unicodedata
from dataclasses import dataclass, field

_TAG = re.compile(r"<[^>]+>")
_ENTITIES = {"&nbsp;": " ", "&amp;": "&", "&lt;": "<", "&gt;": ">", "&quot;": '"'}

# SECTION-2 바로 아래 TITLE만 '진짜 중단원 제목'으로 인정한다.
# '1. 요약재무정보' 문자열은 목차(TOC)에도 등장하므로 문자열 검색을 쓰지 않는다.
RE_SECTION2 = re.compile(r"<SECTION-2[^>]*>\s*<TITLE[^>]*>([^<]*)</TITLE>", re.S)
RE_TABLE = re.compile(r"<TABLE\b[^>]*>(.*?)</TABLE>", re.S)
RE_ROW = re.compile(r"<TR\b[^>]*>(.*?)</TR>", re.S)
RE_CELL = re.compile(r"<(TD|TH|TU)\b[^>]*>(.*?)</\1>", re.S)


def clean(fragment: str) -> str:
    """태그를 제거하고 공백을 정리한 뒤 NFC로 정규화한다."""
    text = _TAG.sub(" ", fragment)
    for entity, replacement in _ENTITIES.items():
        text = text.replace(entity, replacement)
    text = unicodedata.normalize("NFC", text)
    return re.sub(r"\s+", " ", text).strip()


def read_document(path) -> str:
    """원문을 UTF-8로 읽는다. 잘못된 charset 선언은 무시하고 문자를 보존한다."""
    return path.read_text(encoding="utf-8", errors="replace")


@dataclass(frozen=True)
class Table:
    """표 하나. ``start``는 섹션 안에서의 시작 위치(단위 스코프 판정에 쓴다)."""

    start: int
    end: int
    rows: list[list[str]] = field(default_factory=list)

    @property
    def flat_text(self) -> str:
        return " ".join(cell for row in self.rows for cell in row)


def find_section(raw: str, *, title_contains: tuple[str, ...]) -> tuple[int, int] | None:
    """제목이 조건에 맞는 SECTION-2의 [시작, 끝) 위치를 돌려준다."""
    for match in RE_SECTION2.finditer(raw):
        title = clean(match.group(1)).replace(" ", "")
        if any(keyword in title for keyword in title_contains):
            start = match.start()
            following = [
                position
                for position in (
                    raw.find("<SECTION-2", match.end()),
                    raw.find("</SECTION-1", match.end()),
                )
                if position != -1
            ]
            return start, (min(following) if following else len(raw))
    return None


def parse_tables(section: str) -> list[Table]:
    """섹션 안의 모든 표를 행·셀 텍스트로 펼친다."""
    tables: list[Table] = []
    for match in RE_TABLE.finditer(section):
        rows = [
            [clean(cell) for _, cell in RE_CELL.findall(row)]
            for row in RE_ROW.findall(match.group(1))
        ]
        tables.append(Table(start=match.start(), end=match.end(), rows=rows))
    return tables
