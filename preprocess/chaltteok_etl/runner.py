"""manifest를 훑어 공시 유형별 파서를 돌리고 JSONL로 쓴다.

네 공시 유형이 함께 쓰는 뼈대다. 파서 등록만 유형별로 다르고, 훑고·요약하고
실패를 보고하는 부분은 공통이다. 파서가 없는 유형은 실패가 아니라 건너뛴 것으로 센다.

실패 처리는 ``docs/data-model.md`` §9를 따른다. 파싱하지 못한 문서를 빈 결과로
내보내지 않고, 실패가 한 건이라도 있으면 종료 코드를 0으로 반환하지 않는다.
"""

from __future__ import annotations

import json
from collections import Counter
from dataclasses import dataclass, field
from pathlib import Path
from typing import Any, Callable, Iterable

from .common.manifest import (
    build_document,
    iter_manifest,
    logical_path,
    resolve_doc_subtype,
    resolve_main_xml,
    resolve_source_dir,
)
from .common.models import PreprocessResult
from .exchange.corrections import link_corrections
from .exchange.krx_forms import parse_exchange_disclosure
from .material.paid_in_capital import parse_paid_in_capital_increase
from .material.treasury_acquisition import parse_treasury_stock_acquisition
from .ownership.large_holding import parse_large_holding
from .periodic.summary_financials import (
    UnsupportedDocument,
    parse_summary_financials,
)

Parser = Callable[[Path, dict[str, Any]], PreprocessResult]
Supports = Callable[[dict[str, Any]], bool]
Linker = Callable[[list[PreprocessResult]], list[PreprocessResult]]

_MAJOR_PARSERS = {
    "유상증자결정": parse_paid_in_capital_increase,
    "자기주식취득결정": parse_treasury_stock_acquisition,
}


def _parse_major(corpus_root: Path, manifest_row: dict[str, Any]) -> PreprocessResult:
    """주요사항보고서 파서는 원문 경로와 문서를 직접 받으므로 여기서 맞춰 준다."""
    document = build_document(manifest_row)
    source_xml = resolve_main_xml(resolve_source_dir(corpus_root, manifest_row), manifest_row)
    parser = _MAJOR_PARSERS[resolve_doc_subtype(manifest_row)]
    return parser(source_xml, document)


def _parse_holding(corpus_root: Path, manifest_row: dict[str, Any]) -> PreprocessResult:
    document = build_document(manifest_row)
    source_xml = resolve_main_xml(resolve_source_dir(corpus_root, manifest_row), manifest_row)
    return parse_large_holding(source_xml, document)


def _parse_exchange(corpus_root: Path, manifest_row: dict[str, Any]) -> PreprocessResult:
    """거래소공시 파서도 원문 경로와 문서를 직접 받는다. 원문은 HTML이다."""
    document = build_document(manifest_row)
    source_html = resolve_main_xml(resolve_source_dir(corpus_root, manifest_row), manifest_row)
    return parse_exchange_disclosure(source_html, document)


@dataclass(frozen=True)
class ParserEntry:
    """공시 유형 하나를 처리하는 방법.

    ``supports``가 False인 문서는 실패가 아니라 대상 아님으로 센다.
    파서가 다루지 못하는 서식까지 실패로 세면 진짜 실패가 묻힌다.
    """

    parse: Parser
    supports: Supports
    link: Linker | None = None
    """문서를 다 읽은 뒤 문서 사이의 관계를 잇는다.

    파서는 문서 한 건만 보므로 정정본이 어느 문서를 고쳤는지 알 수 없다. 관계를
    만들려면 같은 기업의 다른 공시를 함께 봐야 한다. 이 훅이 있는 유형은 결과를
    모았다가 한 번에 처리하고, 없는 유형은 읽는 대로 흘려보낸다.

    관계의 두 문서는 적재 시점에 이미 저장돼 있어야 하므로, 훅은 원본이 앞에 오도록
    정렬해 돌려준다.
    """


PARSERS: dict[str, ParserEntry] = {
    "periodic": ParserEntry(
        parse=parse_summary_financials,
        # pdf+html 대체 수집분은 파서가 UnsupportedDocument로 알린다.
        supports=lambda row: True,
    ),
    "major": ParserEntry(
        parse=_parse_major,
        # 유상증자결정·자기주식취득결정만 파서가 있다. 나머지 서식은 아직 대상이 아니다.
        supports=lambda row: resolve_doc_subtype(row) in _MAJOR_PARSERS,
    ),
    "exchange": ParserEntry(
        parse=_parse_exchange,
        # 서식 4종이 전부다. 모르는 서식은 건너뛰지 않고 실패로 드러낸다.
        supports=lambda row: True,
        link=link_corrections,
    ),
    "holding": ParserEntry(
        parse=_parse_holding,
        supports=lambda row: "대량보유" in (resolve_doc_subtype(row) or row.get("report_nm") or ""),
    ),
}


@dataclass
class RunSummary:
    """실행 1회 결과. ``docs/data-model.md`` §9가 요구하는 요약이다."""

    documents: int = 0
    succeeded: int = 0
    skipped_no_parser: Counter = field(default_factory=Counter)
    skipped_unsupported: int = 0
    evidence_chunks: int = 0
    extracted_facts: int = 0
    relations: int = 0
    extraction_status: Counter = field(default_factory=Counter)
    failures: list[tuple[str, str, str]] = field(default_factory=list)

    @property
    def failed(self) -> int:
        return len(self.failures)

    def describe(self) -> str:
        skipped_total = sum(self.skipped_no_parser.values()) + self.skipped_unsupported
        lines = [
            f"전처리 요약: 문서 {self.documents}건 / 성공 {self.succeeded}건 "
            f"/ 실패 {self.failed}건 / 건너뜀 {skipped_total}건",
            f"  근거 {self.evidence_chunks}, 사실 {self.extracted_facts}, 관계 {self.relations}",
        ]
        if self.extraction_status:
            counts = ", ".join(
                f"{status} {count}" for status, count in sorted(self.extraction_status.items())
            )
            lines.append(f"  추출 상태: {counts}")
        if self.skipped_no_parser:
            counts = ", ".join(
                f"{group} {count}" for group, count in sorted(self.skipped_no_parser.items())
            )
            lines.append(f"  파서 없음: {counts}")
        if self.skipped_unsupported:
            lines.append(f"  대상 아님: {self.skipped_unsupported}건")
        for doc_id, path, reason in self.failures:
            lines.append(f"  실패 {doc_id} ({path}): {reason}")
        return "\n".join(lines)


def select_rows(
    corpus_root: Path,
    doc_groups: Iterable[str] | None = None,
    corp_names: Iterable[str] | None = None,
    limit: int | None = None,
) -> list[dict[str, Any]]:
    """처리할 manifest 행을 고른다."""
    groups = set(doc_groups) if doc_groups else None
    names = set(corp_names) if corp_names else None
    rows: list[dict[str, Any]] = []
    for row in iter_manifest(corpus_root):
        if groups is not None and row.get("doc_group") not in groups:
            continue
        if names is not None and row.get("corp_name") not in names:
            continue
        rows.append(row)
        if limit is not None and len(rows) >= limit:
            break
    return rows


def run(corpus_root: Path, rows: list[dict[str, Any]], output: Path) -> RunSummary:
    """고른 문서를 처리해 ``output``에 JSONL로 쓴다. 1줄 = PreprocessResult 1건."""
    summary = RunSummary(documents=len(rows))
    output.parent.mkdir(parents=True, exist_ok=True)
    # 관계를 잇는 유형만 모아 둔다. 나머지는 읽는 대로 흘려보낸다.
    pending: dict[str, list[PreprocessResult]] = {}

    with output.open("w", encoding="utf-8") as sink:
        for row in rows:
            doc_group = row.get("doc_group")
            entry = PARSERS.get(doc_group)
            if entry is None:
                summary.skipped_no_parser[doc_group] += 1
                continue
            if not entry.supports(row):
                summary.skipped_unsupported += 1
                continue
            try:
                result = entry.parse(corpus_root, row)
            except UnsupportedDocument:
                summary.skipped_unsupported += 1
                continue
            except Exception as error:  # noqa: BLE001 - 원인을 기록하고 계속 진행한다
                summary.failures.append(
                    (row.get("doc_id", "-"), _logical(corpus_root, row), _reason(error))
                )
                continue

            if entry.link is None:
                _write(sink, result, summary)
            else:
                pending.setdefault(doc_group, []).append(result)

        for doc_group, results in pending.items():
            for result in PARSERS[doc_group].link(results):
                _write(sink, result, summary)

    return summary


def _write(sink, result: PreprocessResult, summary: RunSummary) -> None:
    sink.write(result.model_dump_json() + "\n")
    summary.succeeded += 1
    summary.evidence_chunks += len(result.evidence_chunks)
    summary.extracted_facts += len(result.extracted_facts)
    summary.relations += len(result.relations)
    for fact in result.extracted_facts:
        summary.extraction_status[fact.extraction_status.value] += 1


def _logical(corpus_root: Path, row: dict[str, Any]) -> str:
    try:
        return logical_path(corpus_root, corpus_root / row["file_path"])
    except Exception:  # noqa: BLE001 - 경로를 못 만들어도 실패 보고는 남긴다
        return str(row.get("file_path", "-"))


_MAX_REASON = 200


def _reason(error: Exception) -> str:
    message = str(error).strip() or type(error).__name__
    first_line = message.splitlines()[0]
    if len(first_line) <= _MAX_REASON:
        return f"{type(error).__name__}: {first_line}"
    return f"{type(error).__name__}: {first_line[:_MAX_REASON]}… (생략)"
