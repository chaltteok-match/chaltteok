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
        '<TR><TD>보고구분</TD><TU AUNIT="RPT_DST1" AUNITVALUE="3">변경</TU></TR>'
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


def test_uses_this_report_acodes_not_previous_or_largest_shareholder(tmp_path: Path) -> None:
    source = _write(
        tmp_path,
        "<BODY>"
        "<TABLE><TBODY>"
        '<TR><TD>보고자 :</TD><TE ACODE="RPT_RSP_NM">삼성물산주식회사</TE></TR>'
        '<TR><TD>보고구분</TD><TU AUNIT="RPT_DST1" AUNITVALUE="4">변동ㆍ변경</TU></TR>'
        '<TR><TD>최대주주 지분율(%)</TD><TE ACODE="SUB_LRT">18.13</TE></TR>'
        "</TBODY></TABLE>"
        "<TABLE><THEAD><TR><TH>구분</TH><TH>보유주식등의 수</TH><TH>보유비율</TH></TR></THEAD><TBODY>"
        '<TR><TD>직전 보고서</TD><TE ACODE="BFR_STK_CNT">1,238,767,819</TE>'
        '<TE ACODE="BFR_STK_RT">20.75</TE></TR>'
        '<TR><TD>이번 보고서</TD><TE ACODE="THS_STK_CNT">1,237,964,708</TE>'
        '<TE ACODE="THS_STK_RT">20.74</TE></TR>'
        "</TBODY></TABLE>"
        '<SECTION-2><TITLE>4. 보유목적</TITLE>'
        '<TABLE><TBODY><TR><TU AUNIT="PUR_OWN" AUNITVALUE="01">경영권 영향</TU></TR></TBODY></TABLE>'
        "</SECTION-2>"
        "<TABLE><TBODY>"
        "<TR><TD>보고사유</TD><TE>단순 매매</TE></TR>"
        "</TBODY></TABLE>"
        "</BODY>",
    )

    result = parse_large_holding(source, make_document())
    by_key = {fact.fact_key: fact for fact in result.extracted_facts}

    assert by_key["ownership_holder"].raw_value == "삼성물산주식회사"
    assert by_key["ownership_shares"].normalized_value == "1237964708"
    assert by_key["ownership_ratio"].normalized_value == "20.74"
    assert by_key["ownership_report_type"].raw_value == "변동ㆍ변경"
    assert by_key["ownership_purpose"].raw_value == "경영권 영향"


def test_acode_this_report_overrides_earlier_vote_count_label(tmp_path: Path) -> None:
    source = _write(
        tmp_path,
        "<BODY>"
        "<TABLE><TBODY>"
        '<TR><TD>의결권의 수 및 보유비율</TD><TD>이번 보고서</TD>'
        '<TE ACODE="VR_TMT_CNT">191,500,000</TE>'
        '<TE ACODE="VR_TMT_RT">81.84</TE></TR>'
        '<TR><TD>이번보고서</TD><TE ACODE="THS_STK_CNT">191,500,000</TE>'
        '<TE ACODE="THS_STK_RT">81.84</TE></TR>'
        "</TBODY></TABLE></BODY>",
    )
    result = parse_large_holding(source, make_document())
    by_key = {fact.fact_key: fact for fact in result.extracted_facts}
    assert by_key["ownership_ratio"].normalized_value == "81.84"
    assert by_key["ownership_shares"].normalized_value == "191500000"


def test_label_fallback_picks_this_report_column(tmp_path: Path) -> None:
    source = _write(
        tmp_path,
        "<BODY><TABLE><TBODY>"
        "<TR><TD>보유주식등의 수</TD><TD>직전 보고서</TD><TE>59,508,387</TE>"
        "<TD>이번 보고서</TD><TE>56,349,109</TE></TR>"
        "<TR><TD>보유비율</TD><TD>직전 보고서</TD><TE>8.17</TE>"
        "<TD>이번 보고서</TD><TE>7.74</TE></TR>"
        "<TR><TD>최대주주 지분율</TD><TE>18.13</TE></TR>"
        "</TBODY></TABLE></BODY>",
    )

    result = parse_large_holding(source, make_document())
    by_key = {fact.fact_key: fact for fact in result.extracted_facts}

    assert by_key["ownership_shares"].normalized_value == "56349109"
    assert by_key["ownership_ratio"].normalized_value == "7.74"


def _corpus_xml(rcept_no: str) -> Path | None:
    corpus = Path("/Users/sn15d/Documents/spring/chaltteok-main/corpus/raw/holding")
    matches = list(corpus.rglob(f"{rcept_no}.xml"))
    return matches[0] if matches else None


@pytest.mark.skipif(
    _corpus_xml("20230120000563") is None,
    reason="holding corpus XML is not checked out",
)
def test_samsung_corpus_this_report_ratio() -> None:
    source = _corpus_xml("20230120000563")
    assert source is not None
    result = parse_large_holding(source, make_document())
    by_key = {fact.fact_key: fact for fact in result.extracted_facts}
    assert by_key["ownership_shares"].normalized_value == "1237964708"
    assert by_key["ownership_ratio"].normalized_value == "20.74"
    assert by_key["ownership_holder"].raw_value == "삼성물산주식회사"
    assert by_key["ownership_purpose"].raw_value == "경영권 영향"


@pytest.mark.skipif(
    _corpus_xml("20230607000335") is None,
    reason="holding corpus XML is not checked out",
)
def test_sk_hynix_corpus_this_report_ratio() -> None:
    source = _corpus_xml("20230607000335")
    assert source is not None
    result = parse_large_holding(source, make_document())
    by_key = {fact.fact_key: fact for fact in result.extracted_facts}
    assert by_key["ownership_shares"].normalized_value == "56349109"
    assert by_key["ownership_ratio"].normalized_value == "7.74"
    assert by_key["ownership_purpose"].raw_value == "단순투자"
