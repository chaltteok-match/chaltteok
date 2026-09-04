from __future__ import annotations

from datetime import date
from pathlib import Path, PurePosixPath

import pytest

from chaltteok_etl.common import DisclosureDocument, DocGroup, ManifestFileFormat, ValueType
from chaltteok_etl.ownership.large_holding import parse_large_holding


def make_document() -> DisclosureDocument:
    return DisclosureDocument(
        doc_id="holding_20250115000001",
        corp_code="00126380",
        corp_name="삼성전자",
        listed_name="삼성전자",
        stock_code="005930",
        industry="제조업",
        sector="전기전자",
        doc_group=DocGroup.HOLDING,
        doc_subtype="대량보유상황보고서",
        report_nm="주식등의 대량보유상황보고서",
        rcept_no="20250115000001",
        rcept_dt=date(2025, 1, 15),
        flr_nm="국민연금공단",
        is_correction=False,
        base_year=None,
        base_month=None,
        source_path="raw/holding/삼성전자/20250115000001",
        manifest_file_format=ManifestFileFormat.XML,
        source_file_count=1,
    )


def _write(tmp_path: Path, body: str, name: str = "주식등의 대량보유상황보고서") -> Path:
    xml_path = tmp_path / "20250115000001.xml"
    xml_path.write_text(
        '<?xml version="1.0" encoding="utf-8"?>\n'
        "<DOCUMENT>\n"
        f'<DOCUMENT-NAME ACODE="00600">{name}</DOCUMENT-NAME>\n'
        f"{body}\n"
        "</DOCUMENT>\n",
        encoding="utf-8",
    )
    return xml_path


def test_extracts_holder_shares_ratio_and_purpose(tmp_path: Path) -> None:
    source = _write(
        tmp_path,
        "<BODY><TABLE><TBODY>"
        '<TR><TD>대표보고자</TD><TE ACODE="RPROR">국민연금공단</TE></TR>'
        '<TR><TD>보고구분</TD><TE ACODE="RPT_TP">변경</TE></TR>'
        '<TR><TD>보유주식등의 수 (주)</TD><TE ACODE="STKQY">50,000,000</TE></TR>'
        '<TR><TD>보유비율 (%)</TD><TE ACODE="STKRT">8.25</TE></TR>'
        '<TR><TD>보유목적</TD><TE ACODE="HLD_PURP">단순투자</TE></TR>'
        "</TBODY></TABLE></BODY>",
    )

    result = parse_large_holding(source, make_document())
    by_key = {fact.fact_key: fact for fact in result.extracted_facts}

    assert by_key["ownership_holder"].raw_value == "국민연금공단"
    assert by_key["ownership_holder"].value_type is ValueType.TEXT
    assert by_key["ownership_shares"].normalized_value == "50000000"
    assert by_key["ownership_shares"].normalized_unit == "share"
    assert by_key["ownership_ratio"].normalized_value == "8.25"
    assert by_key["ownership_ratio"].normalized_unit == "%"
    assert by_key["ownership_report_type"].raw_value == "변경"
    assert by_key["ownership_purpose"].raw_value == "단순투자"
    assert result.relations == []


def test_falls_back_to_row_labels_when_acode_is_unknown(tmp_path: Path) -> None:
    source = _write(
        tmp_path,
        "<BODY><TABLE><TBODY>"
        "<TR><TD>보고자</TD><TE>홍길동</TE></TR>"
        "<TR><TD>보유주식수</TD><TE>1,000</TE></TR>"
        "<TR><TD>지분율 (%)</TD><TE>5.1</TE></TR>"
        "</TBODY></TABLE></BODY>",
    )

    result = parse_large_holding(source, make_document())
    by_key = {fact.fact_key: fact for fact in result.extracted_facts}

    assert by_key["ownership_holder"].raw_value == "홍길동"
    assert by_key["ownership_shares"].normalized_value == "1000"
    assert by_key["ownership_ratio"].normalized_value == "5.1"


def test_rejects_other_forms(tmp_path: Path) -> None:
    source = _write(tmp_path, "<BODY></BODY>", name="주요사항보고서(유상증자결정)")
    with pytest.raises(ValueError, match="대량보유상황보고서"):
        parse_large_holding(source, make_document())


def test_source_file_is_posix_under_document_path(tmp_path: Path) -> None:
    source = _write(
        tmp_path,
        "<BODY><TABLE><TBODY>"
        "<TR><TD>대표보고자</TD><TE>국민연금공단</TE></TR>"
        "</TBODY></TABLE></BODY>",
    )
    document = make_document()
    result = parse_large_holding(source, document)
    assert result.evidence_chunks[0].source_file == (
        str(PurePosixPath(document.source_path) / source.name)
    )
