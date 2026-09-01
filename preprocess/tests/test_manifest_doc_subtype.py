"""주요사항보고서의 doc_subtype 파생 규칙 (docs/data-model.md §3)."""

import pytest

from chaltteok_etl.common.manifest import resolve_doc_subtype


def row(**overrides: object) -> dict[str, object]:
    values: dict[str, object] = {
        "doc_group": "major",
        "doc_subtype": None,
        "report_nm": "주요사항보고서(유상증자결정)",
    }
    values.update(overrides)
    return values


@pytest.mark.parametrize(
    ("report_nm", "expected"),
    [
        ("주요사항보고서(유상증자결정)", "유상증자결정"),
        ("[기재정정]주요사항보고서(유상증자결정)", "유상증자결정"),
        ("[첨부추가]주요사항보고서(유상증자결정)", "유상증자결정"),
        ("주요사항보고서(자기주식취득결정)", "자기주식취득결정"),
        ("주요사항보고서(자기주식취득신탁계약해지결정)", "자기주식취득신탁계약해지결정"),
        ("주요사항보고서(주식교환ㆍ이전결정)", "주식교환ㆍ이전결정"),
    ],
)
def test_주요사항보고서는_공시명에서_서식명을_뽑는다(report_nm: str, expected: str) -> None:
    assert resolve_doc_subtype(row(report_nm=report_nm)) == expected


def test_정정_접두가_붙어도_같은_서식으로_묶인다() -> None:
    original = resolve_doc_subtype(row(report_nm="주요사항보고서(유상증자결정)"))
    corrected = resolve_doc_subtype(row(report_nm="[기재정정]주요사항보고서(유상증자결정)"))

    assert original == corrected == "유상증자결정"


def test_이름이_서로를_포함하는_서식을_구분한다() -> None:
    # report_nm에 '자기주식'으로 검색하면 네 서식이 모두 걸린다.
    subtypes = {
        resolve_doc_subtype(row(report_nm=f"주요사항보고서({name})"))
        for name in (
            "자기주식취득결정",
            "자기주식처분결정",
            "자기주식취득신탁계약체결결정",
            "자기주식취득신탁계약해지결정",
        )
    }

    assert len(subtypes) == 4


def test_껍데기가_없는_공시명은_정정_접두만_벗긴다() -> None:
    assert resolve_doc_subtype(row(report_nm="유상증자결정")) == "유상증자결정"
    assert resolve_doc_subtype(row(report_nm="[기재정정]유상증자결정")) == "유상증자결정"


def test_다른_공시유형은_manifest_값을_그대로_쓴다() -> None:
    assert resolve_doc_subtype(
        {"doc_group": "periodic", "doc_subtype": "annual", "report_nm": "사업보고서 (2024.12)"}
    ) == "annual"
    assert resolve_doc_subtype(
        {
            "doc_group": "exchange",
            "doc_subtype": "단일판매공급계약체결",
            "report_nm": "단일판매ㆍ공급계약체결",
        }
    ) == "단일판매공급계약체결"


def test_주요사항보고서가_아니면_값을_만들지_않는다() -> None:
    assert resolve_doc_subtype(
        {"doc_group": "periodic", "doc_subtype": None, "report_nm": "사업보고서 (유상증자 관련)"}
    ) is None
