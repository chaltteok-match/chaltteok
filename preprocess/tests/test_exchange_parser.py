from __future__ import annotations

from datetime import date
from pathlib import Path, PurePosixPath

import pytest

from chaltteok_etl.common import (
    ContentType,
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
from chaltteok_etl.exchange.krx_forms import (
    ExchangeFormError,
    parse_exchange_disclosure,
    resolve_form,
)

REPO_ROOT = Path(__file__).resolve().parents[2]
SAMPLE_DATA_ROOT = REPO_ROOT / "sample-data"
EXCHANGE_ROOT = SAMPLE_DATA_ROOT / "raw" / "exchange"

SAMSUNG_XML = EXCHANGE_ROOT / "삼성전자" / "20250728800035" / "20250728800035.xml"
RAINBOW_XML = (
    EXCHANGE_ROOT / "레인보우로보틱스" / "20240315900173" / "20240315900173.xml"
)


def sample_xml(path: Path) -> Path:
    if path.is_file():
        return path
    relative = path.relative_to(REPO_ROOT)
    pytest.fail(f"거래소공시 원문이 없습니다. {relative} 경로에 원문을 넣어주세요.")


def make_document(
    source_xml: Path,
    *,
    doc_id: str,
    corp_name: str,
    report_nm: str,
    rcept_dt: date,
) -> DisclosureDocument:
    relative_file = source_xml.relative_to(SAMPLE_DATA_ROOT).as_posix()
    return DisclosureDocument(
        doc_id=doc_id,
        corp_code="00126380",
        corp_name=corp_name,
        listed_name=corp_name,
        stock_code="005930",
        industry="제조업",
        sector="전기·전자",
        doc_group=DocGroup.EXCHANGE,
        doc_subtype=None,
        report_nm=report_nm,
        rcept_no=source_xml.stem,
        rcept_dt=rcept_dt,
        flr_nm=corp_name,
        is_correction=report_nm.startswith("[기재정정]"),
        base_year=None,
        base_month=None,
        source_path=str(PurePosixPath(relative_file).parent),
        manifest_file_format=ManifestFileFormat.XML,
        source_file_count=1,
    )


@pytest.fixture(scope="module")
def samsung_result() -> PreprocessResult:
    source = sample_xml(SAMSUNG_XML)
    return parse_exchange_disclosure(
        source,
        make_document(
            source,
            doc_id="exchange_20250728800035",
            corp_name="삼성전자",
            report_nm="단일판매ㆍ공급계약체결",
            rcept_dt=date(2025, 7, 28),
        ),
    )


@pytest.fixture(scope="module")
def rainbow_result() -> PreprocessResult:
    source = sample_xml(RAINBOW_XML)
    return parse_exchange_disclosure(
        source,
        make_document(
            source,
            doc_id="exchange_20240315900173",
            corp_name="레인보우로보틱스",
            report_nm="단일판매ㆍ공급계약체결",
            rcept_dt=date(2024, 3, 15),
        ),
    )


def facts_by_key(result: PreprocessResult) -> dict[str, object]:
    return {fact.fact_key: fact for fact in result.extracted_facts}


def test_parser_emits_validated_preprocess_result_json(
    samsung_result: PreprocessResult,
) -> None:
    payload = samsung_result.model_dump(mode="json")
    restored = PreprocessResult.model_validate_json(samsung_result.model_dump_json())

    assert payload["schema_version"] == "0.2"
    assert payload["document"]["doc_group"] == "exchange"
    assert payload["evidence_chunks"][0]["source_format"] == "html"
    assert payload["relations"] == []
    assert restored == samsung_result


def test_reads_utf8_body_even_though_charset_declares_euc_kr(
    samsung_result: PreprocessResult,
) -> None:
    """선언은 euc-kr이지만 실제 바이트는 UTF-8이다(코퍼스 1,469건 전수 확인).

    선언대로 디코딩하면 한글이 전부 깨져 라벨을 하나도 찾지 못한다.
    """
    declared = SAMSUNG_XML.read_bytes()
    assert b"charset=euc-kr" in declared

    facts = facts_by_key(samsung_result)
    assert facts["contract_name"].raw_value == "반도체 위탁생산 공급계약"


def test_contract_amount_and_revenue_are_normalized_to_won(
    samsung_result: PreprocessResult,
) -> None:
    facts = facts_by_key(samsung_result)

    amount = facts["contract_amount"]
    assert amount.raw_value == "22,764,764,160,000"
    assert amount.normalized_value == "22764764160000"
    assert amount.normalized_unit == "KRW"
    assert amount.value_type is ValueType.NUMBER
    assert amount.extraction_status is ExtractionStatus.OK

    # 거래소공시 최근매출액은 정기공시 2024 연결 매출액(300,870,903 백만원)과 같다.
    revenue = facts["contract_recent_revenue"]
    assert revenue.normalized_value == "300870903000000"
    assert facts["contract_revenue_ratio"].normalized_value == "7.6"
    assert facts["contract_revenue_ratio"].normalized_unit == "%"


def test_contract_dates_are_normalized(samsung_result: PreprocessResult) -> None:
    facts = facts_by_key(samsung_result)

    assert facts["contract_start_date"].value_type is ValueType.DATE
    assert facts["contract_start_date"].normalized_value == "2025-07-24"
    assert facts["contract_end_date"].normalized_value == "2033-12-31"
    assert facts["contract_order_date"].normalized_value == "2025-07-26"


def test_event_facts_are_point_in_time_at_receipt_date(
    samsung_result: PreprocessResult,
) -> None:
    for fact in samsung_result.extracted_facts:
        assert fact.period_basis is PeriodBasis.POINT_IN_TIME
        assert fact.scope is Scope.NOT_APPLICABLE
        assert fact.as_of_date == date(2025, 7, 28)
        assert fact.period_start is None and fact.period_end is None


def test_counterparty_revenue_is_not_merged_into_own_revenue(
    rainbow_result: PreprocessResult,
) -> None:
    """「3. 계약상대방」 하위 '-최근 매출액(원)'은 상대방 매출이다.

    글머리만 떼고 자사 '최근 매출액(원)'과 같은 키로 두면 레인보우로보틱스의
    매출이 136억이 아니라 상대방인 한국항공우주산업의 2.7조로 답해진다.
    """
    facts = facts_by_key(rainbow_result)

    assert facts["contract_recent_revenue"].normalized_value == "13615411936"
    assert facts["contract_counterparty"].raw_value == "한국항공우주산업㈜"
    assert (
        facts["contract_counterparty_recent_revenue"].normalized_value
        == "2786888282702"
    )

    keys = [fact.fact_key for fact in rainbow_result.extracted_facts]
    assert len(keys) == len(set(keys)), "한 문서에 같은 fact_key가 두 번 나오면 안 된다"


def test_conditional_contract_form_maps_total_to_contract_amount(
    rainbow_result: PreprocessResult,
) -> None:
    """조건부 계약 서식(26건)은 '계약금액(원)' 대신 '계약금액 총액(원)'을 쓴다."""
    facts = facts_by_key(rainbow_result)

    assert facts["contract_amount"].normalized_value == "4150000000"
    assert facts["contract_fixed_amount"].normalized_value == "4150000000"
    # 조건부 계약금액은 "-"라 사실을 만들지 않는다.
    assert "contract_conditional_amount" not in facts


def test_dash_values_do_not_create_facts(samsung_result: PreprocessResult) -> None:
    """값이 '-'이면 정상적으로 값이 없는 문서다. 추측하지 않는다."""
    facts = facts_by_key(samsung_result)

    assert "contract_region" not in facts
    assert all(fact.raw_value != "-" for fact in samsung_result.extracted_facts)
    assert all(chunk.text.rsplit(" | ", 1)[-1] != "-" for chunk in samsung_result.evidence_chunks)


def test_every_fact_points_at_an_evidence_chunk(
    samsung_result: PreprocessResult,
) -> None:
    chunk_ids = {chunk.chunk_id for chunk in samsung_result.evidence_chunks}
    for fact in samsung_result.extracted_facts:
        assert fact.chunk_id in chunk_ids
    for chunk in samsung_result.evidence_chunks:
        assert chunk.content_type is ContentType.TABLE
        assert chunk.source_format is SourceFormat.HTML


@pytest.mark.parametrize(
    ("report_nm", "expected"),
    [
        ("단일판매ㆍ공급계약체결", "단일판매ㆍ공급계약체결"),
        ("[기재정정]단일판매ㆍ공급계약체결(자율공시)", "단일판매ㆍ공급계약체결"),
        ("투자판단관련주요경영사항(CT-P42 임상 3상 결과 발표)", "투자판단관련주요경영사항"),
        ("[기재정정]신규시설투자등", "신규시설투자등"),
        ("단일판매ㆍ공급계약해지", "단일판매ㆍ공급계약해지"),
    ],
)
def test_correction_prefix_and_subtitle_are_not_part_of_the_form(
    report_nm: str, expected: str
) -> None:
    """report_nm 136종은 [기재정정] 접두와 괄호 부제 때문이고 서식은 4종뿐이다."""
    assert resolve_form(report_nm).name == expected


def test_rejects_unknown_form() -> None:
    with pytest.raises(ExchangeFormError, match="알 수 없는"):
        resolve_form("주요사항보고서(유상증자결정)")


def test_free_text_form_makes_evidence_without_facts(tmp_path: Path) -> None:
    """투자판단관련주요경영사항은 금액이 본문에 섞여 있어 사실을 만들지 않는다.

    '5.9조원 (※ 보조금 0.7조원 포함)'에서 숫자만 뽑으면 단서가 사라진다.
    """
    source = tmp_path / "20250101900001.xml"
    source.write_text(
        "<html><body><div class='xforms'><table><tbody>"
        "<tr><td><span>1. 제목</span></td>"
        "<td><span class='xforms_input'>미국 신규 공장 건설</span></td></tr>"
        "<tr><td><span>2. 주요내용</span></td>"
        "<td><span class='xforms_input'>투자 금액: 5.9조원 "
        "(※ 美상무부 보조금 0.7조원 포함)</span></td></tr>"
        "</tbody></table></div></body></html>",
        encoding="utf-8",
    )
    document = make_document(
        SAMSUNG_XML,
        doc_id="exchange_20250101900001",
        corp_name="에스케이하이닉스",
        report_nm="투자판단관련주요경영사항",
        rcept_dt=date(2025, 1, 1),
    )

    result = parse_exchange_disclosure(source, document)

    assert result.extracted_facts == []
    assert len(result.evidence_chunks) == 2
    assert "보조금 0.7조원 포함" in result.evidence_chunks[1].text
