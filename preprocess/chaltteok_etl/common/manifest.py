"""Manifest 로딩과 공통 문서 모델 생성.

``docs/data-model.md`` §2.3(원문 경로 해결)과 §3(공시 문서 모델)을 구현한다.
네 가지 공시 유형 파서가 모두 이 모듈로 ``DisclosureDocument``를 만든다.
"""

from __future__ import annotations

import json
import re
import unicodedata
from datetime import date
from pathlib import Path
from typing import Any, Iterator

from .models import DisclosureDocument

MANIFEST_FILENAME = "manifest.jsonl"


class CorpusPathError(RuntimeError):
    """manifest 경로를 실제 파일시스템에서 해석하지 못한 경우."""


def load_manifest(corpus_root: Path) -> list[dict[str, Any]]:
    """manifest.jsonl 전체를 읽어 dict 목록으로 돌려준다."""
    path = Path(corpus_root) / MANIFEST_FILENAME
    with path.open(encoding="utf-8") as stream:
        return [json.loads(line) for line in stream if line.strip()]


def iter_manifest(corpus_root: Path) -> Iterator[dict[str, Any]]:
    """manifest.jsonl을 한 줄씩 흘려보낸다(전체를 메모리에 올리지 않는다)."""
    path = Path(corpus_root) / MANIFEST_FILENAME
    with path.open(encoding="utf-8") as stream:
        for line in stream:
            if line.strip():
                yield json.loads(line)


def find_manifest_row(corpus_root: Path, doc_id: str) -> dict[str, Any]:
    for row in iter_manifest(corpus_root):
        if row["doc_id"] == doc_id:
            return row
    raise KeyError(f"manifest에 doc_id가 없습니다: {doc_id}")


def resolve_source_dir(corpus_root: Path, manifest_row: dict[str, Any]) -> Path:
    """manifest의 NFC 논리 경로를 실제 폴더로 해석한다.

    macOS는 한글 폴더명을 NFD로 저장하므로 문자열이 그대로 일치하지 않을 수 있다.
    직접 접근이 실패하면 상위 폴더를 훑어 NFC 기준으로 다시 비교한다.
    """
    corpus_root = Path(corpus_root)
    logical = manifest_row["file_path"]
    direct = corpus_root / logical
    if direct.is_dir():
        return direct

    current = corpus_root
    for part in logical.split("/"):
        candidate = current / part
        if candidate.is_dir():
            current = candidate
            continue
        wanted = unicodedata.normalize("NFC", part)
        matches = [
            child
            for child in current.iterdir()
            if child.is_dir() and unicodedata.normalize("NFC", child.name) == wanted
        ]
        if len(matches) != 1:
            raise CorpusPathError(
                f"원문 폴더를 찾지 못했습니다 (doc_id={manifest_row['doc_id']}, "
                f"논리경로={logical}, 후보 {len(matches)}개)"
            )
        current = matches[0]
    return current


def resolve_main_xml(source_dir: Path, manifest_row: dict[str, Any]) -> Path:
    """본문 XML을 고른다.

    한 폴더에 여러 XML이 있을 수 있다(사업보고서 291건 중 205건은 감사보고서 첨부 포함).
    본문은 접수번호와 파일명이 같고, 첨부는 ``{접수번호}_00760.xml``처럼 접미사가 붙는다.
    """
    main = source_dir / f"{manifest_row['rcept_no']}.xml"
    if main.is_file():
        return main
    candidates = sorted(p for p in source_dir.glob("*.xml") if "_" not in p.stem)
    if len(candidates) != 1:
        raise CorpusPathError(
            f"본문 XML을 특정하지 못했습니다 (doc_id={manifest_row['doc_id']}, "
            f"후보 {len(candidates)}개)"
        )
    return candidates[0]


def logical_path(corpus_root: Path, target: Path) -> str:
    """코퍼스 루트 기준 NFC POSIX 상대 경로."""
    relative = Path(target).resolve().relative_to(Path(corpus_root).resolve())
    return unicodedata.normalize("NFC", relative.as_posix())


# 주요사항보고서는 manifest의 doc_subtype이 비어 있어 report_nm에서 서식명을 뽑는다.
# 예: "[기재정정]주요사항보고서(유상증자결정)" -> "유상증자결정"
RE_MAJOR_FORM = re.compile(r"^(?:\[[^\]]+\])?주요사항보고서\((.+)\)$")
# 전환·교환사채 공시명은 공백이 들어가기도 한다. 검색 키는 공백 없는 서식명으로 맞춘다.
_COMPACT_MAJOR_SUBTYPES = frozenset({"전환사채권발행결정", "교환사채권발행결정"})


def resolve_doc_subtype(manifest_row: dict[str, Any]) -> str | None:
    """문서 세부 유형. 정기·거래소·지분공시는 manifest 값을 그대로 쓴다.

    주요사항보고서만 manifest에 값이 없어 공시명에서 서식명을 뽑는다.
    report_nm은 ``[기재정정]`` 접두가 절반가량 붙어 있어 검색 키로 쓰기 어렵다.
    서식명으로 정규화하면 ``자기주식취득결정``과 ``자기주식처분결정``처럼
    이름이 겹치는 서식도 구분된다.
    """
    existing = manifest_row.get("doc_subtype")
    if existing:
        return existing
    if manifest_row.get("doc_group") != "major":
        return existing
    report_nm = manifest_row.get("report_nm") or ""
    matched = RE_MAJOR_FORM.match(report_nm)
    if matched:
        form = matched.group(1).strip()
        compact = form.replace(" ", "")
        if compact in _COMPACT_MAJOR_SUBTYPES:
            return compact
        return form
    # 주요사항보고서 서식이 아니면 값을 만들지 않는다.
    # 두산로보틱스 20230823000334처럼 공시명이 '유상증자결정'뿐인 문서는
    # DART 서식 코드가 달라(ACODE=80518) 주요사항보고서 파서가 다루지 못한다.
    # 여기서 같은 값을 주면 검색에서 진짜 주요사항보고서와 섞인다.
    return None


def build_document(manifest_row: dict[str, Any]) -> DisclosureDocument:
    """manifest 한 행을 ``DisclosureDocument``로 옮긴다 (docs/data-model.md §3)."""
    rcept_dt = manifest_row["rcept_dt"]
    return DisclosureDocument(
        doc_id=manifest_row["doc_id"],
        corp_code=manifest_row["corp_code"],
        corp_name=manifest_row["corp_name"],
        listed_name=manifest_row["listed_name"],
        stock_code=manifest_row["stock_code"],
        industry=manifest_row["industry"],
        sector=manifest_row["sector"],
        doc_group=manifest_row["doc_group"],
        doc_subtype=resolve_doc_subtype(manifest_row),
        report_nm=manifest_row["report_nm"],
        rcept_no=manifest_row["rcept_no"],
        rcept_dt=date(int(rcept_dt[0:4]), int(rcept_dt[4:6]), int(rcept_dt[6:8])),
        flr_nm=manifest_row["flr_nm"],
        is_correction=bool(manifest_row["is_correction"]),
        base_year=manifest_row.get("base_year"),
        base_month=manifest_row.get("base_month"),
        source_path=unicodedata.normalize("NFC", manifest_row["file_path"]),
        manifest_file_format=manifest_row["file_format"],
        source_file_count=int(manifest_row["n_files"]),
    )
