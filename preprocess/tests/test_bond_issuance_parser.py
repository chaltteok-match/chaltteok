from __future__ import annotations

from datetime import date
from pathlib import Path

import pytest

from chaltteok_etl.common import DisclosureDocument, DocGroup, ManifestFileFormat
from chaltteok_etl.material.bond_issuance import parse_bond_issuance


def make_document(*, subtype: str = "전환사채권발행결정") -> DisclosureDocument:
    return DisclosureDocument(
        doc_id="major_20230630000403",
        corp_code="01160363",
        corp_name="에코프로비엠",
        listed_name="에코프로비엠",
        stock_code="247540",
        industry="소재",
        sector="2차전지",
        doc_group=DocGroup.MAJOR,
        doc_subtype=subtype,
        report_nm=f"주요사항보고서({subtype})",
        rcept_no="20230630000403",
        rcept_dt=date(2023, 6, 30),
        flr_nm="에코프로비엠",
        is_correction=False,
        base_year=None,
        base_month=None,
        source_path="raw/major/에코프로비엠/20230630000403",
        manifest_file_format=ManifestFileFormat.XML,
        source_file_count=1,
    )


def _write(tmp_path: Path, body: str, acode: str = "11324", name: str = "주요사항보고서(전환사채권 발행결정)") -> Path:
    xml_path = tmp_path / "20230630000403.xml"
    xml_path.write_text(
        '<?xml version="1.0" encoding="utf-8"?>\n'
        "<DOCUMENT>\n"
        f'<DOCUMENT-NAME ACODE="{acode}">{name}</DOCUMENT-NAME>\n'
        f"{body}\n"
        "</DOCUMENT>\n",
        encoding="utf-8",
    )
    return xml_path


def test_extracts_convertible_bond_fields(tmp_path: Path) -> None:
    source = _write(
        tmp_path,
        "<BODY><TABLE><TBODY>"
        '<TR><TD>2. 사채의 권면(전자등록)총액 (원)</TD>'
        '<TE ACODE="DNM_SUM">440,000,000,000</TE></TR>'
        '<TR><TD>표면이자율 (%)</TD><TE ACODE="PRFT_RATE">0.0</TE></TR>'
        '<TR><TD>5. 사채만기일</TD><TE ACODE="EXP_DT">2028년 07월 24일</TE></TR>'
        '<TR><TD>전환가액 (원/주)</TD><TE ACODE="EXE_PRC">275,000</TE></TR>'
        '<TR><TD>전환가액 결정방법</TD><TE ACODE="EXE_FUNC">시가</TE></TR>'
        '<TR><TD>주식수</TD><TE ACODE="STK_CNT">1,600,000</TE></TR>'
        '<TR><TD>주식총수 대비비율(%)</TD><TE ACODE="STK_RT">1.64</TE></TR>'
        "</TBODY></TABLE></BODY>",
    )
    result = parse_bond_issuance(source, make_document())
    by_key = {fact.fact_key: fact for fact in result.extracted_facts}
    assert by_key["bond_face_amount"].normalized_value == "440000000000"
    assert by_key["bond_coupon_rate"].normalized_value == "0"
    assert by_key["bond_maturity_date"].raw_value == "2028년 07월 24일"
    assert by_key["bond_exercise_price"].normalized_value == "275000"
    assert by_key["bond_exercise_shares"].normalized_value == "1600000"
    assert "bond_exercise_price" in by_key
    assert all(fact.fact_key != "EXE_FUNC" for fact in result.extracted_facts)


def test_extracts_exchangeable_bond_by_labels(tmp_path: Path) -> None:
    source = _write(
        tmp_path,
        "<BODY><TABLE><TBODY>"
        "<TR><TD>사채의 권면총액</TD><TE>1,974,450,000,000</TE></TR>"
        "<TR><TD>교환가액 (원/주)</TD><TE>111,180</TE></TR>"
        "<TR><TD>교환에 관한 사항 주식수</TD><TE>17,000,000</TE></TR>"
        "</TBODY></TABLE></BODY>",
        acode="11326",
        name="주요사항보고서(교환사채권발행결정)",
    )
    result = parse_bond_issuance(source, make_document(subtype="교환사채권발행결정"))
    by_key = {fact.fact_key: fact for fact in result.extracted_facts}
    assert by_key["bond_face_amount"].normalized_value == "1974450000000"
    assert by_key["bond_exercise_price"].normalized_value == "111180"
    assert by_key["bond_exercise_shares"].normalized_value == "17000000"


def test_rejects_other_major_forms(tmp_path: Path) -> None:
    source = _write(
        tmp_path,
        "<BODY></BODY>",
        acode="11306",
        name="주요사항보고서(유상증자결정)",
    )
    with pytest.raises(ValueError, match="전환·교환사채권발행결정 서식"):
        parse_bond_issuance(source, make_document())


def test_skips_missing_dash(tmp_path: Path) -> None:
    source = _write(
        tmp_path,
        "<BODY><TABLE><TBODY>"
        '<TR><TD>사채의 권면총액</TD><TE ACODE="DNM_SUM">-</TE></TR>'
        '<TR><TD>전환가액</TD><TE ACODE="EXE_PRC">275,000</TE></TR>'
        "</TBODY></TABLE></BODY>",
    )
    result = parse_bond_issuance(source, make_document())
    keys = {fact.fact_key for fact in result.extracted_facts}
    assert "bond_face_amount" not in keys
    assert "bond_exercise_price" in keys


def test_skips_not_applicable_text(tmp_path: Path) -> None:
    source = _write(
        tmp_path,
        "<BODY><TABLE><TBODY>"
        '<TR><TD>사채의 권면총액</TD><TE ACODE="DNM_SUM">해당사항 없음</TE></TR>'
        '<TR><TD>전환가액</TD><TE ACODE="EXE_PRC">275,000</TE></TR>'
        "</TBODY></TABLE></BODY>",
    )
    result = parse_bond_issuance(source, make_document())
    keys = {fact.fact_key for fact in result.extracted_facts}
    assert "bond_face_amount" not in keys
    assert "bond_exercise_price" in keys


def test_rejects_self_convertible_sale(tmp_path: Path) -> None:
    source = _write(
        tmp_path,
        "<BODY></BODY>",
        acode="11324",
        name="주요사항보고서(자기전환사채매도결정)",
    )
    with pytest.raises(ValueError, match="전환·교환사채권발행결정"):
        parse_bond_issuance(source, make_document())
