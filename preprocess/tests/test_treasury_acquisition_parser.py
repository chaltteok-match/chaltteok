from __future__ import annotations

from datetime import date
from pathlib import Path

import pytest

from chaltteok_etl.common import DisclosureDocument, DocGroup, ManifestFileFormat
from chaltteok_etl.material.treasury_acquisition import (
    TREASURY_ACQUISITION_DOCUMENT_ACODE,
    parse_treasury_stock_acquisition,
)


def make_document() -> DisclosureDocument:
    return DisclosureDocument(
        doc_id="major_20250301000001",
        corp_code="00126380",
        corp_name="삼성전자",
        listed_name="삼성전자",
        stock_code="005930",
        industry="제조업",
        sector="전기전자",
        doc_group=DocGroup.MAJOR,
        doc_subtype="자기주식취득결정",
        report_nm="주요사항보고서(자기주식취득결정)",
        rcept_no="20250301000001",
        rcept_dt=date(2025, 3, 1),
        flr_nm="삼성전자",
        is_correction=False,
        base_year=None,
        base_month=None,
        source_path="raw/major/삼성전자/20250301000001",
        manifest_file_format=ManifestFileFormat.XML,
        source_file_count=1,
    )


def _write(tmp_path: Path, body: str, acode: str = "11310", name: str = "주요사항보고서(자기주식취득결정)") -> Path:
    xml_path = tmp_path / "20250301000001.xml"
    xml_path.write_text(
        '<?xml version="1.0" encoding="utf-8"?>\n'
        "<DOCUMENT>\n"
        f'<DOCUMENT-NAME ACODE="{acode}">{name}</DOCUMENT-NAME>\n'
        f"{body}\n"
        "</DOCUMENT>\n",
        encoding="utf-8",
    )
    return xml_path


def test_extracts_planned_common_shares_and_amount(tmp_path: Path) -> None:
    source = _write(
        tmp_path,
        "<BODY><TABLE><TBODY>"
        '<TR><TD>취득예정주식(주)</TD><TD>보통주식 (주)</TD>'
        '<TE ACODE="AQPLN_STK_OSTK">1,000,000</TE></TR>'
        '<TR><TD>취득예정주식(주)</TD><TD>기타주식 (주)</TD><TE>10</TE></TR>'
        '<TR><TD>취득예정금액(원)</TD><TD>보통주식 (원)</TD>'
        '<TE ACODE="AQPLN_PRC_OSTK">50,000,000,000</TE></TR>'
        "</TBODY></TABLE></BODY>",
    )

    result = parse_treasury_stock_acquisition(source, make_document())
    by_key = {fact.fact_key: fact for fact in result.extracted_facts}

    assert by_key["treasury_planned_common_shares"].normalized_value == "1000000"
    assert by_key["treasury_planned_common_shares"].normalized_unit == "share"
    assert by_key["treasury_planned_common_amount"].normalized_value == "50000000000"
    assert by_key["treasury_planned_common_amount"].normalized_unit == "KRW"
    assert "기타" not in by_key["treasury_planned_common_shares"].label


def test_maps_labels_without_known_acode(tmp_path: Path) -> None:
    source = _write(
        tmp_path,
        "<BODY><TABLE><TBODY>"
        "<TR><TD>취득예정주식</TD><TD>보통주식 (주)</TD><TE>200,000</TE></TR>"
        "<TR><TD>취득예정금액</TD><TD>보통주식 (원)</TD><TE>10,000,000,000</TE></TR>"
        "</TBODY></TABLE></BODY>",
    )
    result = parse_treasury_stock_acquisition(source, make_document())
    by_key = {fact.fact_key: fact for fact in result.extracted_facts}
    assert by_key["treasury_planned_common_shares"].normalized_value == "200000"
    assert by_key["treasury_planned_common_amount"].normalized_value == "10000000000"


def test_rejects_other_major_forms(tmp_path: Path) -> None:
    source = _write(
        tmp_path,
        "<BODY></BODY>",
        acode="11306",
        name="주요사항보고서(유상증자결정)",
    )
    with pytest.raises(ValueError, match=TREASURY_ACQUISITION_DOCUMENT_ACODE):
        parse_treasury_stock_acquisition(source, make_document())


def test_rejects_trust_or_disposal_in_name(tmp_path: Path) -> None:
    source = _write(
        tmp_path,
        "<BODY></BODY>",
        name="주요사항보고서(자기주식취득신탁계약체결결정)",
    )
    with pytest.raises(ValueError, match="자기주식취득결정"):
        parse_treasury_stock_acquisition(source, make_document())
