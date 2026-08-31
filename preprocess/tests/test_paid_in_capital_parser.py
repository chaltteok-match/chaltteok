from __future__ import annotations

from datetime import date
from pathlib import Path, PurePosixPath

import pytest

from chaltteok_etl.common import (
    DisclosureDocument,
    DocGroup,
    ManifestFileFormat,
    PeriodBasis,
    PreprocessResult,
    Scope,
    SourceFormat,
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


def test_parser_emits_validated_preprocess_result_json(
    parsed_result: PreprocessResult,
) -> None:
    payload = parsed_result.model_dump(mode="json")
    restored = PreprocessResult.model_validate_json(parsed_result.model_dump_json())

    assert payload["schema_version"] == "0.1"
    assert payload["parser_version"] == "0.1.1"
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
