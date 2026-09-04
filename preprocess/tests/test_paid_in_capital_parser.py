from __future__ import annotations

from datetime import date
from pathlib import Path, PurePosixPath

import pytest

from chaltteok_etl.common import (
    DisclosureDocument,
    DocGroup,
    ExtractionStatus,
    ManifestFileFormat,
    PeriodBasis,
    PreprocessResult,
    Scope,
    SourceFormat,
    ValueType,
)
from chaltteok_etl.material import ACODE_FACT_KEYS, parse_paid_in_capital_increase
from chaltteok_etl.material.paid_in_capital import PAID_IN_CAPITAL_DOCUMENT_ACODE

REPO_ROOT = Path(__file__).resolve().parents[2]
SAMPLE_DATA_ROOT = REPO_ROOT / "sample-data"
SAMPLE_XML = (
    SAMPLE_DATA_ROOT
    / "raw"
    / "major"
    / "한화에어로스페이스"
    / "20250408000012"
    / "20250408000012.xml"
)
CANONICAL_SOURCE_PATH = "raw/major/한화에어로스페이스/20250408000012"
CANONICAL_SOURCE_FILE = f"{CANONICAL_SOURCE_PATH}/20250408000012.xml"

PURPOSE_ACODES = {
    "FND_USE1": "fundraising_purpose_facility",
    "FND_USE_SQ": "fundraising_purpose_business_acquisition",
    "FND_USE2": "fundraising_purpose_operating",
    "FND_USE_RD": "fundraising_purpose_debt_repayment",
    "ANC_ACQ_AMT": "fundraising_purpose_securities_acquisition",
    "FND_USE3": "fundraising_purpose_other",
}


def sample_xml_path() -> Path:
    if SAMPLE_XML.is_file():
        return SAMPLE_XML
    pytest.fail(
        "유상증자결정 원문이 없습니다. "
        "sample-data/raw/major/한화에어로스페이스/20250408000012/20250408000012.xml "
        "경로에 원문을 넣어주세요."
    )


def make_document(source_xml: Path) -> DisclosureDocument:
    relative_file = source_xml.relative_to(SAMPLE_DATA_ROOT).as_posix()
    source_path = str(PurePosixPath(relative_file).parent)
    return DisclosureDocument(
        doc_id="major_20250408000012",
        corp_code="00126566",
        corp_name="한화에어로스페이스",
        listed_name="한화에어로스페이스",
        stock_code="012450",
        industry="제조업",
        sector="기계·장비",
        doc_group=DocGroup.MAJOR,
        doc_subtype=None,
        report_nm="주요사항보고서(유상증자결정)",
        rcept_no="20250408000012",
        rcept_dt=date(2025, 4, 8),
        flr_nm="한화에어로스페이스",
        is_correction=True,
        base_year=None,
        base_month=None,
        source_path=source_path,
        manifest_file_format=ManifestFileFormat.XML,
        source_file_count=1,
    )


@pytest.fixture(scope="module")
def parsed_result() -> PreprocessResult:
    source_xml = sample_xml_path()
    return parse_paid_in_capital_increase(source_xml, make_document(source_xml))


def test_purpose_acodes_are_named_by_use_of_proceeds() -> None:
    for acode, fact_key in PURPOSE_ACODES.items():
        assert ACODE_FACT_KEYS[acode] == fact_key
    assert "fundraising_expected_amount" not in ACODE_FACT_KEYS.values()
    assert all(
        fact_key.startswith("fundraising_") for fact_key in ACODE_FACT_KEYS.values()
    )


def test_텍스트_항목은_정규화하지_않는다(tmp_path: Path) -> None:
    """정관의 근거처럼 조문 전문이 오는 항목은 계산·비교 대상이 아니다.

    normalized_value는 계산·비교용 값이라 길이 제한이 있다(varchar 128).
    실제 코퍼스에는 이 항목이 2,501자인 문서가 있다. 원문은 raw_value와 근거 chunk에
    남으므로 정규화값은 비운다(docs/data-model.md §5).
    """
    articles = "제9조(주식의 종류, 수 및 내용) " + "① 회사가 발행할 주식은 보통주식과 종류주식으로 한다. " * 30
    source = tmp_path / "20250101000001.xml"
    source.write_text(
        "<DOCUMENT>"
        '<DOCUMENT-NAME ACODE="11306">주요사항보고서(유상증자결정)</DOCUMENT-NAME>'
        "<BODY><TABLE><TBODY>"
        f'<TR><TD>정관의 근거</TD><TE ACODE="AOI_BAS">{articles}</TE></TR>'
        '<TR><TD>타법인 증권취득자금 (원)</TD>'
        '<TE ACODE="ANC_ACQ_AMT">1,600,000,000,000</TE></TR>'
        "</TBODY></TABLE></BODY></DOCUMENT>",
        encoding="utf-8",
    )
    document = make_document(SAMPLE_DATA_ROOT / "raw/major/한화에어로스페이스/20250408000012/20250408000012.xml")

    result = parse_paid_in_capital_increase(source, document)

    by_key = {fact.fact_key: fact for fact in result.extracted_facts}
    basis = by_key["fundraising_articles_basis"]
    assert basis.value_type is ValueType.TEXT
    assert len(basis.raw_value) > 128
    assert basis.normalized_value is None
    assert basis.normalized_unit is None
    assert basis.extraction_status is ExtractionStatus.OK

    amount = by_key["fundraising_purpose_securities_acquisition"]
    assert amount.normalized_value == "1600000000000"
    assert amount.extraction_status is ExtractionStatus.OK


def test_parser_emits_validated_preprocess_result_json(
    parsed_result: PreprocessResult,
) -> None:
    payload = parsed_result.model_dump(mode="json")
    restored = PreprocessResult.model_validate_json(parsed_result.model_dump_json())

    assert payload["schema_version"] == "0.2"
    assert payload["parser_version"] == "0.1.2"
    assert payload["document"]["doc_id"] == "major_20250408000012"
    assert payload["document"]["doc_group"] == "major"
    assert payload["document"]["source_path"] == CANONICAL_SOURCE_PATH
    assert payload["evidence_chunks"][0]["source_file"] == CANONICAL_SOURCE_FILE
    assert payload["relations"] == []
    assert restored == parsed_result


def test_anc_acq_amt_is_securities_acquisition_not_total_proceeds(
    parsed_result: PreprocessResult,
) -> None:
    fact = next(
        item
        for item in parsed_result.extracted_facts
        if item.fact_key == "fundraising_purpose_securities_acquisition"
    )

    assert fact.raw_value == "1,600,000,000,000"
    assert fact.raw_unit == "원"
    assert fact.normalized_value == "1600000000000"
    assert fact.normalized_unit == "KRW"
    assert fact.value_type is ValueType.NUMBER
    assert fact.period_basis is PeriodBasis.POINT_IN_TIME
    assert fact.scope is Scope.NOT_APPLICABLE
    assert fact.as_of_date == date(2025, 4, 8)
    assert "타법인 증권취득자금" in fact.label


def test_facility_purpose_is_extracted_from_fnd_use1(
    parsed_result: PreprocessResult,
) -> None:
    fact = next(
        item
        for item in parsed_result.extracted_facts
        if item.fact_key == "fundraising_purpose_facility"
    )
    assert fact.raw_value == "700,020,800,000"
    assert fact.normalized_value == "700020800000"
    assert fact.normalized_unit == "KRW"
    assert "시설자금" in fact.label


def test_dash_purpose_values_do_not_create_extracted_facts(
    parsed_result: PreprocessResult,
) -> None:
    missing_fact_keys = {
        ACODE_FACT_KEYS[acode]
        for acode in (
            "PST_CNT",
            "BFR_PST_CNT",
            "CST_ISS_VAL",
            "PST_ISS_VAL",
            "AOI_BAS",
            "NATD_CNT",
            "ETC_CNT",
            "FND_USE_SQ",
            "FND_USE2",
            "FND_USE_RD",
            "FND_USE3",
            "RPT_SBM",
            "STO_CNT",
        )
    }
    extracted_keys = {fact.fact_key for fact in parsed_result.extracted_facts}

    assert missing_fact_keys.isdisjoint(extracted_keys)
    assert extracted_keys == {
        "fundraising_common_new_shares",
        "fundraising_par_value",
        "fundraising_before_common_shares",
        "fundraising_purpose_facility",
        "fundraising_purpose_securities_acquisition",
        "fundraising_outside_director_present",
        "fundraising_common_planned_issue_price",
    }


def test_rejects_non_paid_in_capital_document_form(
    tmp_path: Path, parsed_result: PreprocessResult
) -> None:
    xml_path = tmp_path / "other.xml"
    xml_path.write_text(
        """<?xml version="1.0" encoding="utf-8"?>
<DOCUMENT>
<DOCUMENT-NAME ACODE="11310">주요사항보고서(자기주식취득결정)</DOCUMENT-NAME>
</DOCUMENT>
""",
        encoding="utf-8",
    )

    with pytest.raises(ValueError, match=PAID_IN_CAPITAL_DOCUMENT_ACODE):
        parse_paid_in_capital_increase(xml_path, parsed_result.document)


def _write_form(tmp_path: Path, body: str) -> Path:
    xml_path = tmp_path / "doc.xml"
    xml_path.write_text(
        '<?xml version="1.0" encoding="utf-8"?>\n'
        "<DOCUMENT>\n"
        '<DOCUMENT-NAME ACODE="11306">주요사항보고서(유상증자결정)</DOCUMENT-NAME>\n'
        f"{body}\n"
        "</DOCUMENT>\n",
        encoding="utf-8",
    )
    return xml_path


def test_parses_bare_ampersand_without_losing_at_and_t(
    tmp_path: Path, parsed_result: PreprocessResult
) -> None:
    xml_path = _write_form(
        tmp_path,
        """<BODY>
<TABLE>
<TR>
<TD>기타 투자판단과 관련한 중요사항</TD>
<TE ACODE="ETC_CNT">Ernst & Young</TE>
</TR>
</TABLE>
</BODY>""",
    )

    result = parse_paid_in_capital_increase(xml_path, parsed_result.document)
    fact = next(
        item
        for item in result.extracted_facts
        if item.fact_key == "fundraising_other_notes"
    )
    assert fact.raw_value == "Ernst & Young"


def test_parses_bare_lt_in_paragraph(
    tmp_path: Path, parsed_result: PreprocessResult
) -> None:
    xml_path = _write_form(
        tmp_path,
        """<BODY>
<P><참조1> 신주발행 주식수 산정 방법 </P>
<TABLE>
<TR>
<TD>타법인 증권취득자금 (원)</TD>
<TE ACODE="ANC_ACQ_AMT">1,000</TE>
</TR>
</TABLE>
</BODY>""",
    )

    result = parse_paid_in_capital_increase(xml_path, parsed_result.document)
    fact = next(
        item
        for item in result.extracted_facts
        if item.fact_key == "fundraising_purpose_securities_acquisition"
    )
    assert fact.raw_value == "1,000"


def test_fallback_chunk_keeps_correction_body_when_all_values_are_dash(
    tmp_path: Path, parsed_result: PreprocessResult
) -> None:
    xml_path = _write_form(
        tmp_path,
        """<BODY>
<P>1. 정정대상 공시서류 : 주요사항보고서(유상증자결정)</P>
<P>3. 정정사항 공개매수 종료일 이후 회사 주가가 하락할 것이라는 전망</P>
<TABLE>
<TR>
<TD>타법인 증권취득자금 (원)</TD>
<TE ACODE="ANC_ACQ_AMT">-</TE>
</TR>
<TR>
<TD>시설자금 (원)</TD>
<TE ACODE="FND_USE1">-</TE>
</TR>
</TABLE>
</BODY>""",
    )

    result = parse_paid_in_capital_increase(xml_path, parsed_result.document)
    assert result.extracted_facts == []
    assert len(result.evidence_chunks) == 1
    assert "정정대상 공시서류" in result.evidence_chunks[0].text
    assert "주가가 하락" in result.evidence_chunks[0].text
    assert result.evidence_chunks[0].text != "주요사항보고서(유상증자결정)"

