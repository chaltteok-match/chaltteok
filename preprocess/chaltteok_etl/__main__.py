"""전처리 실행 진입점.

    python -m chaltteok_etl --corpus ../corpus --out work/preprocess.jsonl
    python -m chaltteok_etl --corpus ../corpus --out work/periodic.jsonl --doc-group periodic

실패가 한 건이라도 있으면 종료 코드 1을 반환한다(docs/data-model.md §9).
"""

from __future__ import annotations

import argparse
import sys
from pathlib import Path

from .runner import PARSERS, run, select_rows


def build_parser() -> argparse.ArgumentParser:
    parser = argparse.ArgumentParser(
        prog="python -m chaltteok_etl",
        description="manifest를 훑어 공시 유형별 파서를 돌리고 JSONL로 쓴다.",
    )
    parser.add_argument("--corpus", type=Path, required=True, help="코퍼스 루트 경로")
    parser.add_argument("--out", type=Path, required=True, help="출력 JSONL 경로")
    parser.add_argument(
        "--doc-group",
        action="append",
        choices=["periodic", "major", "exchange", "holding"],
        help="처리할 공시 유형. 여러 번 줄 수 있다. 생략하면 전부",
    )
    parser.add_argument(
        "--corp-name", action="append", help="처리할 법인명. 여러 번 줄 수 있다"
    )
    parser.add_argument("--limit", type=int, help="처리할 문서 수 상한. 시험 실행용")
    return parser


def main(argv: list[str] | None = None) -> int:
    args = build_parser().parse_args(argv)

    if not args.corpus.is_dir():
        print(f"코퍼스 경로가 없습니다: {args.corpus}", file=sys.stderr)
        return 2

    rows = select_rows(args.corpus, args.doc_group, args.corp_name, args.limit)
    print(f"대상 문서 {len(rows)}건 (등록된 파서: {', '.join(sorted(PARSERS))})")

    summary = run(args.corpus, rows, args.out)
    print(summary.describe())

    if summary.failed:
        print(
            "실패한 문서가 있어 성공으로 끝내지 않습니다. "
            f"부분 결과는 {args.out}에 남아 있습니다.",
            file=sys.stderr,
        )
        return 1
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
