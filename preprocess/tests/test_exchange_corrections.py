from __future__ import annotations

from datetime import date

import pytest

from chaltteok_etl.common import (
    ContentType,
    DisclosureDocument,
    DocGroup,
    EvidenceChunk,
    ExtractedFact,
    ExtractionStatus,
    ManifestFileFormat,
    PeriodBasis,
    PreprocessResult,
    RelationType,
    Scope,
    SourceFormat,
    ValueType,
)
from chaltteok_etl.exchange.corrections import link_corrections


def document(
    rcept_no: str,
    *,
    corp_name: str = "대우건설",
    correction: bool = False,
    report_nm: str = "단일판매ㆍ공급계약체결",
) -> DisclosureDocument:
    return DisclosureDocument(
        doc_id=f"exchange_{rcept_no}",
        corp_code="00164839",
        corp_name=corp_name,
        listed_name=corp_name,
        stock_code="047040",
        industry="건설업",
        sector="건설",
        doc_group=DocGroup.EXCHANGE,
        doc_subtype=None,
        report_nm=("[기재정정]" + report_nm) if correction else report_nm,
        rcept_no=rcept_no,
        rcept_dt=date(int(rcept_no[:4]), int(rcept_no[4:6]), int(rcept_no[6:8])),
        flr_nm=corp_name,
        is_correction=correction,
        base_year=None,
        base_month=None,
        source_path=f"raw/exchange/{corp_name}/{rcept_no}",
        manifest_file_format=ManifestFileFormat.XML,
        source_file_count=1,
    )


def result(
    rcept_no: str,
    *,
    amount: str,
    counterparty: str = "발주처 A",
    correction: bool = False,
    target_filing_date: str | None = None,
    corp_name: str = "대우건설",
    report_nm: str = "단일판매ㆍ공급계약체결",
) -> PreprocessResult:
    doc = document(rcept_no, corp_name=corp_name, correction=correction, report_nm=report_nm)
    chunks: list[EvidenceChunk] = []
    facts: list[ExtractedFact] = []

    def add(fact_key: str, raw: str, normalized: str | None, unit: str | None,
            value_type: ValueType = ValueType.NUMBER) -> None:
        sequence = len(chunks)
        chunk_id = f"{doc.doc_id}:chunk:{sequence:04d}"
        chunks.append(EvidenceChunk(
            chunk_id=chunk_id, doc_id=doc.doc_id, sequence=sequence,
            content_type=ContentType.TABLE, text=f"{fact_key} | {raw}",
            source_file=f"{doc.source_path}/{rcept_no}.xml", source_format=SourceFormat.HTML,
            page_number=None, section_title=None, table_title=None,
            row_label=fact_key, column_label=None))
        facts.append(ExtractedFact(
            fact_id=f"{doc.doc_id}:fact:{len(facts):04d}", doc_id=doc.doc_id, chunk_id=chunk_id,
            fact_key=fact_key, label=fact_key, value_type=value_type, raw_value=raw,
            raw_unit=unit, normalized_value=normalized,
            normalized_unit="KRW" if unit == "원" else None,
            period_start=None, period_end=None, as_of_date=doc.rcept_dt,
            period_basis=PeriodBasis.POINT_IN_TIME, scope=Scope.NOT_APPLICABLE,
            extraction_status=ExtractionStatus.OK))

    if correction and target_filing_date:
        add("correction_target_filing_date", target_filing_date, target_filing_date, None,
            ValueType.DATE)
        add("correction_reason", "계약금액 변경", None, None, ValueType.TEXT)
    add("contract_amount", amount, amount.replace(",", ""), "원")
    add("contract_counterparty", counterparty, None, None, ValueType.TEXT)

    return PreprocessResult(
        schema_version="0.2", parser_version="0.2.0", document=doc,
        evidence_chunks=chunks, extracted_facts=facts, relations=[])


def relations_of(results: list[PreprocessResult]) -> dict[str, list]:
    return {item.document.doc_id: item.relations for item in results}


def test_links_correction_to_the_filing_it_names() -> None:
    """정정 대상은 같은 날 문서가 아니라 '정정관련 공시서류제출일'이 가리키는 문서다.

    대우건설 2023-06-30에는 원본 공급계약과, 2023-03-10 계약의 정정본이 함께 있다.
    같은 날이라는 이유로 이으면 서로 다른 계약이 묶인다.
    """
    linked = link_corrections([
        result("20230310800034", amount="1,046,363,500,000"),
        result("20230630800613", amount="400,119,500,000", counterparty="발주처 B"),
        result("20230630800615", amount="1,046,363,500,000",
               correction=True, target_filing_date="2023-03-10"),
    ])
    relations = relations_of(linked)

    assert relations["exchange_20230310800034"] == []
    # 같은 날 원본은 별개 계약이라 관계가 없다.
    assert relations["exchange_20230630800613"] == []
    [relation] = relations["exchange_20230630800615"]
    assert relation.original_doc_id == "exchange_20230310800034"
    assert relation.relation_type is RelationType.CORRECTION


def test_records_changed_values() -> None:
    linked = link_corrections([
        result("20230222800734", amount="3,095,146,354,000"),
        result("20230308801043", amount="2,389,104,200,000",
               correction=True, target_filing_date="2023-02-22"),
    ])
    [relation] = relations_of(linked)["exchange_20230308801043"]
    [changed] = [field for field in relation.changed_fields
                 if field.fact_key == "contract_amount"]

    assert changed.before_value == "3095146354000"
    assert changed.after_value == "2389104200000"
    assert changed.unit == "KRW"


def test_chain_of_corrections_shares_one_event_key() -> None:
    """정정의 정정도 한 사건이다. 사건 키는 직전 문서가 아니라 사슬의 뿌리다."""
    linked = link_corrections([
        result("20230213800847", amount="469,257,248,431"),
        result("20230411800470", amount="469,287,482,556",
               correction=True, target_filing_date="2023-02-13"),
        result("20230414800608", amount="469,317,716,681",
               correction=True, target_filing_date="2023-04-11"),
    ])
    relations = relations_of(linked)

    assert relations["exchange_20230411800470"][0].event_key == "exchange_20230213800847"
    assert relations["exchange_20230414800608"][0].event_key == "exchange_20230213800847"
    # 관계 자체는 직전 문서를 가리킨다.
    assert relations["exchange_20230414800608"][0].original_doc_id == "exchange_20230411800470"


def test_does_not_link_when_the_original_is_outside_the_corpus() -> None:
    """정정본 절반가량은 원본이 2023-01-01 이전이다. 결함이 아니므로 잇지 않는다."""
    linked = link_corrections([
        result("20230301800100", amount="100,000,000",
               correction=True, target_filing_date="2021-12-23"),
    ])
    assert relations_of(linked)["exchange_20230301800100"] == []


def test_narrows_same_day_candidates_by_counterparty() -> None:
    linked = link_corrections([
        result("20230310800034", amount="1,000,000,000", counterparty="발주처 A"),
        result("20230310800909", amount="2,000,000,000", counterparty="발주처 B"),
        result("20230630800615", amount="1,500,000,000", counterparty="발주처 B",
               correction=True, target_filing_date="2023-03-10"),
    ])
    [relation] = relations_of(linked)["exchange_20230630800615"]
    assert relation.original_doc_id == "exchange_20230310800909"


def test_does_not_guess_when_candidates_stay_ambiguous() -> None:
    linked = link_corrections([
        result("20230310800034", amount="1,000,000,000", counterparty="발주처 A"),
        result("20230310800909", amount="2,000,000,000", counterparty="발주처 A"),
        result("20230630800615", amount="1,500,000,000", counterparty="발주처 A",
               correction=True, target_filing_date="2023-03-10"),
    ])
    assert relations_of(linked)["exchange_20230630800615"] == []


def test_does_not_link_across_different_forms() -> None:
    linked = link_corrections([
        result("20230310800034", amount="1,000,000,000"),
        result("20230630800615", amount="1,000,000,000", correction=True,
               target_filing_date="2023-03-10", report_nm="단일판매ㆍ공급계약해지"),
    ])
    assert relations_of(linked)["exchange_20230630800615"] == []


def test_orders_originals_before_corrections() -> None:
    """적재는 관계의 두 문서가 이미 저장돼 있어야 하므로 원본이 먼저 나와야 한다."""
    linked = link_corrections([
        result("20230414800608", amount="3", correction=True, target_filing_date="2023-02-13"),
        result("20230213800847", amount="1"),
    ])
    order = [item.document.doc_id for item in linked]
    assert order == ["exchange_20230213800847", "exchange_20230414800608"]

    saved: set[str] = set()
    for item in linked:
        for relation in item.relations:
            assert relation.original_doc_id in saved
        saved.add(item.document.doc_id)
