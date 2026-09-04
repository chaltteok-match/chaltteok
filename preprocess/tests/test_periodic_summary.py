"""정기공시 요약재무정보 파서 테스트.

코퍼스 원본은 저장소에 없으므로, 실제 원문에서 확인된 예외를 재현하는
작은 합성 fixture로 검증한다 (docs/data-model.md §9).
실제 코퍼스 검증은 맨 아래 테스트에서 코퍼스가 있을 때만 실행한다.
"""

from __future__ import annotations

import os
from datetime import date
from pathlib import Path

import pytest

from chaltteok_etl.common.models import PeriodBasis, Scope
from chaltteok_etl.periodic import (
    PeriodicParseError,
    UnsupportedDocument,
    parse_summary_financials,
)

RCEPT_NO = "20250311001085"
DOC_ID = f"periodic_{RCEPT_NO}"
FILE_PATH = f"raw/periodic/예시전자/{RCEPT_NO}_annual_2024_12"


def manifest_row(**overrides):
    row = {
        "doc_id": DOC_ID,
        "corp_code": "00126380",
        "corp_name": "예시전자",
        "listed_name": "예시전자",
        "stock_code": "005930",
        "industry": "IT",
        "sector": "반도체·전자부품",
        "doc_group": "periodic",
        "doc_subtype": "annual",
        "report_nm": "사업보고서 (2024.12)",
        "rcept_no": RCEPT_NO,
        "rcept_dt": "20250311",
        "flr_nm": "예시전자",
        "is_correction": False,
        "base_year": 2024,
        "base_month": 12,
        "file_path": FILE_PATH,
        "file_format": "xml",
        "n_files": 1,
    }
    row.update(overrides)
    return row


def row_xml(label, *values):
    cells = "".join(f"<TD>{value}</TD>" for value in values)
    return f"<TR><TD>{label}</TD>{cells}</TR>"


def summary_table(header=("제56기", "제55기", "제54기"), rows=(), unit_row=None):
    head = "".join(f"<TH>{cell}</TH>" for cell in header)
    body = "".join(rows)
    unit = f"<TBODY><TR><TD>{unit_row}</TD></TR></TBODY>" if unit_row else ""
    return (
        f"<TABLE>{unit}<THEAD><TR><TH>구 분</TH>{head}</TR></THEAD>"
        f"<TBODY>{body}</TBODY></TABLE>"
    )


def build_xml(section_body):
    """목차를 앞에 둔 DART XML. 목차에도 같은 제목 문자열이 등장한다."""
    return (
        '<?xml version="1.0" encoding="utf-8"?>\n'
        '<DOCUMENT><DOCUMENT-NAME ACODE="11011">사업보고서</DOCUMENT-NAME><BODY>'
        "<TABLE><TBODY><TR><TD>III. 재무에 관한 사항</TD></TR>"
        "<TR><TD>1. 요약재무정보</TD></TR>"
        "<TR><TD>2. 연결재무제표</TD></TR></TBODY></TABLE>"
        '<SECTION-1><TITLE>III. 재무에 관한 사항</TITLE>'
        f"<SECTION-2><TITLE>1. 요약재무정보</TITLE>{section_body}</SECTION-2>"
        "<SECTION-2><TITLE>2. 연결재무제표</TITLE><P>생략</P></SECTION-2>"
        "</SECTION-1></BODY></DOCUMENT>"
    )


def write_corpus(tmp_path, section_body, **overrides):
    row = manifest_row(**overrides)
    folder = tmp_path / row["file_path"]
    folder.mkdir(parents=True)
    (folder / f"{row['rcept_no']}.xml").write_text(
        build_xml(section_body), encoding="utf-8"
    )
    return tmp_path, row


CONSOLIDATED_BODY = (
    "<P>가. 요약연결재무정보</P>"
    "<TABLE><TBODY><TR><TD>(단위 : 백만원)</TD></TR></TBODY></TABLE>"
    + summary_table(
        rows=(
            row_xml("자산총계", "514,531,948", "455,905,980", "448,424,507"),
            row_xml("부채총계", "112,339,878", "92,228,115", "93,674,903"),
            row_xml("자본총계", "402,192,070", "363,677,865", "354,749,604"),
            row_xml("매출액", "300,870,903", "258,935,494", "302,231,360"),
            row_xml("영업이익", "32,725,961", "6,566,976", "43,376,630"),
            row_xml("연결총당기순이익", "34,451,351", "15,487,100", "55,654,077"),
            row_xml("기본주당순이익(단위 : 원)", "5,082", "2,131", "8,057"),
            row_xml("비지배지분순이익", "1,000", "900", "800"),
        )
    )
)


def parse(tmp_path, body, **overrides):
    root, row = write_corpus(tmp_path, body, **overrides)
    return parse_summary_financials(root, row)


def facts_by_key(result, key):
    return [fact for fact in result.extracted_facts if fact.fact_key == key]


def test_목차의_같은_제목에_속지_않고_본문_섹션을_찾는다(tmp_path):
    result = parse(tmp_path, CONSOLIDATED_BODY)
    assert result.extracted_facts, "본문 섹션을 찾지 못하면 fact가 비어 있다"
    assert result.evidence_chunks[0].section_title == "1. 요약재무정보"


def test_기수로_연도를_역산한다(tmp_path):
    """헤더에 연도가 없어도 manifest base_year에서 역산한다."""
    result = parse(tmp_path, CONSOLIDATED_BODY)
    revenue = {fact.period_end.year: fact.normalized_value for fact in facts_by_key(result, "revenue")}
    assert revenue == {
        2024: "300870903000000",
        2023: "258935494000000",
        2022: "302231360000000",
    }


def test_기수와_기_사이에_당기_표시가_끼어도_읽는다(tmp_path):
    """하나금융지주는 '제20(당)기 기말'이라고 쓴다. 연도 표기도 없어 이 기수가 유일한 앵커다."""
    body = (
        "<P>가. 요약 연결 재무정보</P>"
        "<TABLE><TBODY><TR><TD>(단위 : 백만원)</TD></TR></TBODY></TABLE>"
        + summary_table(
            header=("제56(당)기 기말", "제 55(전)기 기말", "제 54(전전)기 기말"),
            rows=(row_xml("자산총계", "637,847,513", "591,925,995", "568,603,280"),),
        )
    )
    result = parse(tmp_path, body)
    assets = {
        fact.as_of_date.year: fact.raw_value
        for fact in facts_by_key(result, "total_assets")
    }
    assert assets == {
        2024: "637,847,513",
        2023: "591,925,995",
        2022: "568,603,280",
    }


def test_기수_표시가_기말에_바로_붙어도_읽는다(tmp_path):
    """우리금융지주는 '제5(당)기말'처럼 띄어쓰기 없이 쓴다."""
    body = (
        "<P>가. 요약 연결 재무정보</P>"
        "<TABLE><TBODY><TR><TD>(단위 : 백만원)</TD></TR></TBODY></TABLE>"
        + summary_table(
            header=("제56(당)기말", "제55(전)기말", "제54(전전)기말"),
            rows=(row_xml("자산총계", "1,000", "900", "800"),),
        )
    )
    result = parse(tmp_path, body)
    assert {fact.as_of_date.year for fact in facts_by_key(result, "total_assets")} == {
        2024,
        2023,
        2022,
    }


def test_컬럼이_오름차순이어도_가장_큰_기수를_당기로_본다(tmp_path):
    body = (
        "<P>가. 요약연결재무정보</P>"
        "<TABLE><TBODY><TR><TD>(단위 : 백만원)</TD></TR></TBODY></TABLE>"
        + summary_table(
            header=("제54기", "제55기", "제56기"),
            rows=(row_xml("매출액", "302,231,360", "258,935,494", "300,870,903"),),
        )
    )
    result = parse(tmp_path, body)
    revenue = {fact.period_end.year: fact.raw_value for fact in facts_by_key(result, "revenue")}
    assert revenue[2024] == "300,870,903"
    assert revenue[2022] == "302,231,360"


@pytest.mark.parametrize(
    "unit_text", ["(단위 : 백만원)", "(단위:백만원)", "(단위: 백만원)", "(단위　:　백만원)"]
)
def test_단위_표기_공백_변형을_흡수한다(tmp_path, unit_text):
    body = (
        "<P>가. 요약연결재무정보</P>"
        f"<TABLE><TBODY><TR><TD>{unit_text}</TD></TR></TBODY></TABLE>"
        + summary_table(rows=(row_xml("매출액", "300,870,903", "1", "2"),))
    )
    result = parse(tmp_path, body)
    assert facts_by_key(result, "revenue")[0].normalized_value == "300870903000000"


def test_행에_붙은_단위가_표_단위를_덮어쓰지_않는다(tmp_path):
    """'기본주당순이익(단위 : 원)'은 그 행에만 적용된다. 표 단위는 백만원이다."""
    result = parse(tmp_path, CONSOLIDATED_BODY)
    revenue = facts_by_key(result, "revenue")[0]
    assert revenue.raw_unit == "백만원"
    assert revenue.normalized_value == "300870903000000"


def test_주당순이익과_지분별_순이익은_net_income으로_잡히지_않는다(tmp_path):
    result = parse(tmp_path, CONSOLIDATED_BODY)
    labels = {fact.label for fact in facts_by_key(result, "net_income")}
    assert labels == {"연결총당기순이익"}


def test_영업이익_라벨_변형과_음수_기호를_처리한다(tmp_path):
    body = (
        "<P>나. 요약별도재무정보</P>"
        "<TABLE><TBODY><TR><TD>(단위 : 백만원)</TD></TR></TBODY></TABLE>"
        + summary_table(
            rows=(row_xml("영업이익(손실)", "12,361,034", "△11,526,297", "25,319,329"),)
        )
    )
    result = parse(tmp_path, body)
    values = {fact.period_end.year: fact.normalized_value for fact in facts_by_key(result, "operating_profit")}
    assert values[2024] == "12361034000000"
    assert values[2023] == "-11526297000000"


def test_대괄호로_감싼_라벨을_인식한다(tmp_path):
    """이마트·삼성SDI·에코프로비엠·두산로보틱스는 '[매출액]'처럼 대괄호로 감싼다."""
    body = (
        "<P>가. 요약연결재무정보</P>"
        "<TABLE><TBODY><TR><TD>(단위 : 백만원)</TD></TR></TBODY></TABLE>"
        + summary_table(
            rows=(
                row_xml("[매출액]", "29,472,248", "29,332,414", "24,932,681"),
                row_xml("[영업이익]", "-46,935", "135,652", "316,791"),
                row_xml("[연결당기순손실]", "-187,466", "1,007,726", "1,589,074"),
            )
        )
    )
    result = parse(tmp_path, body)
    assert {f.period_end.year: f.normalized_value for f in facts_by_key(result, "revenue")}[2024] == "29472248000000"
    assert {f.period_end.year: f.normalized_value for f in facts_by_key(result, "operating_profit")}[2024] == "-46935000000"
    assert {f.period_end.year: f.normalized_value for f in facts_by_key(result, "net_income")}[2024] == "-187466000000"


def test_대괄호를_벗긴_뒤에도_전각_로마자_접두를_벗긴다(tmp_path):
    """'[Ⅰ.영업이익]'처럼 두 표기가 겹쳐도 인식한다. 코퍼스에는 없지만 규칙이 겹칠 수 있다."""
    body = (
        "<P>가. 요약연결재무정보</P>"
        "<TABLE><TBODY><TR><TD>(단위 : 백만원)</TD></TR></TBODY></TABLE>"
        + summary_table(rows=(row_xml("[Ⅰ.영업이익]", "1,000", "900", "800"),))
    )
    result = parse(tmp_path, body)
    assert facts_by_key(result, "operating_profit")


def test_전각_로마자_접두를_벗긴다(tmp_path):
    """신한지주는 'Ⅰ.영업이익'처럼 전각 로마자를 쓴다. ASCII I가 아니다."""
    body = (
        "<P>가. 요약연결재무정보</P>"
        "<TABLE><TBODY><TR><TD>(단위 : 백만원)</TD></TR></TBODY></TABLE>"
        + summary_table(
            rows=(
                row_xml("Ⅰ.영업이익", "1,000", "2,000", "3,000"),
                row_xml("Ⅵ.당기순이익", "4,000", "5,000", "6,000"),
            )
        )
    )
    result = parse(tmp_path, body)
    assert facts_by_key(result, "operating_profit")
    assert facts_by_key(result, "net_income")


def test_대괄호를_벗기다가_괄호_라벨을_깨뜨리지_않는다(tmp_path):
    """'영업이익(손실)'의 닫는 괄호를 지우면 매칭이 깨진다."""
    body = (
        "<P>나. 요약별도재무정보</P>"
        "<TABLE><TBODY><TR><TD>(단위 : 백만원)</TD></TR></TBODY></TABLE>"
        + summary_table(
            rows=(
                row_xml("영업이익(손실)", "12,361,034", "△11,526,297", "25,319,329"),
                row_xml("당기순이익(손실)", "100", "200", "300"),
            )
        )
    )
    result = parse(tmp_path, body)
    assert facts_by_key(result, "operating_profit")
    assert facts_by_key(result, "net_income")


def test_대괄호를_벗겨도_대상이_아닌_항목은_그대로_거른다(tmp_path):
    """'[유동자산]'은 벗겨도 '유동자산'이라 화이트리스트에 없다."""
    body = (
        "<P>가. 요약연결재무정보</P>"
        "<TABLE><TBODY><TR><TD>(단위 : 백만원)</TD></TR></TBODY></TABLE>"
        + summary_table(
            rows=(
                row_xml("[유동자산]", "1", "2", "3"),
                row_xml("[법인세차감전계속영업순손실]", "4", "5", "6"),
                row_xml("[자산총계]", "7", "8", "9"),
            )
        )
    )
    result = parse(tmp_path, body)
    assert {f.fact_key for f in result.extracted_facts} == {"total_assets"}


def test_연결과_별도를_구분한다(tmp_path):
    body = CONSOLIDATED_BODY + (
        "<P>나. 요약별도재무정보</P>"
        "<TABLE><TBODY><TR><TD>(단위 : 백만원)</TD></TR></TBODY></TABLE>"
        + summary_table(rows=(row_xml("매출액", "209,052,241", "170,374,090", "211,867,483"),))
    )
    result = parse(tmp_path, body)
    scopes = {
        (fact.scope, fact.period_end.year): fact.normalized_value
        for fact in facts_by_key(result, "revenue")
    }
    assert scopes[(Scope.CONSOLIDATED, 2024)] == "300870903000000"
    assert scopes[(Scope.SEPARATE, 2024)] == "209052241000000"


def test_소제목_어순이_반대여도_인식한다(tmp_path):
    """'가. 요약연결재무정보'와 '1) 연결 요약재무정보' 두 어순이 모두 쓰인다."""
    body = (
        "<P>1) 연결 요약재무정보</P>"
        "<TABLE><TBODY><TR><TD>(단위 : 백만원)</TD></TR></TBODY></TABLE>"
        + summary_table(rows=(row_xml("매출액", "1,000", "900", "800"),))
        + "<P>2) 별도 요약재무정보</P>"
        "<TABLE><TBODY><TR><TD>(단위 : 백만원)</TD></TR></TBODY></TABLE>"
        + summary_table(rows=(row_xml("매출액", "500", "400", "300"),))
    )
    result = parse(tmp_path, body)
    scopes = {
        (fact.scope, fact.period_end.year): fact.raw_value
        for fact in facts_by_key(result, "revenue")
    }
    assert scopes[(Scope.CONSOLIDATED, 2024)] == "1,000"
    assert scopes[(Scope.SEPARATE, 2024)] == "500"


def test_소제목이_재무제표라고_써도_인식한다(tmp_path):
    """현대건설·엘에스일렉트릭은 '재무정보'가 아니라 '재무제표'라고 쓴다."""
    body = (
        "<P>가. 연결 요약재무제표</P>"
        "<TABLE><TBODY><TR><TD>(단위 : 백만원)</TD></TR></TBODY></TABLE>"
        + summary_table(rows=(row_xml("매출액", "1,000", "900", "800"),))
        + "<P>나. 별도 요약재무제표</P>"
        "<TABLE><TBODY><TR><TD>(단위 : 백만원)</TD></TR></TBODY></TABLE>"
        + summary_table(rows=(row_xml("매출액", "500", "400", "300"),))
    )
    result = parse(tmp_path, body)
    scopes = {
        (fact.scope, fact.period_end.year): fact.raw_value
        for fact in facts_by_key(result, "revenue")
    }
    assert scopes[(Scope.CONSOLIDATED, 2024)] == "1,000"
    assert scopes[(Scope.SEPARATE, 2024)] == "500"


def test_기준을_괄호_안에_적어도_인식한다(tmp_path):
    """현대자동차는 '(1) 요약재무정보(연결재무제표 기준)'처럼 괄호 안에 적는다."""
    body = (
        "<P>(1) 요약재무정보(연결재무제표 기준)</P>"
        "<TABLE><TBODY><TR><TD>(단위 : 백만원)</TD></TR></TBODY></TABLE>"
        + summary_table(rows=(row_xml("매출액", "1,000", "900", "800"),))
        + "<P>(2) 요약재무정보(별도재무제표 기준)</P>"
        "<TABLE><TBODY><TR><TD>(단위 : 백만원)</TD></TR></TBODY></TABLE>"
        + summary_table(rows=(row_xml("매출액", "500", "400", "300"),))
    )
    result = parse(tmp_path, body)
    scopes = {
        (fact.scope, fact.period_end.year): fact.raw_value
        for fact in facts_by_key(result, "revenue")
    }
    assert scopes[(Scope.CONSOLIDATED, 2024)] == "1,000"
    assert scopes[(Scope.SEPARATE, 2024)] == "500"


def test_기준을_괄호로_앞뒤에_떼어_적어도_인식한다(tmp_path):
    """세아베스틸지주는 '(연결)요약재무정보', 한전기술은 '요약재무정보 ( 연결 )'로 쓴다."""
    body = (
        "<P>가. (연결)요약재무정보</P>"
        "<TABLE><TBODY><TR><TD>(단위 : 백만원)</TD></TR></TBODY></TABLE>"
        + summary_table(rows=(row_xml("매출액", "1,000", "900", "800"),))
        + "<P>나 . 요약재무정보 ( 별도 )</P>"
        "<TABLE><TBODY><TR><TD>(단위 : 백만원)</TD></TR></TBODY></TABLE>"
        + summary_table(rows=(row_xml("매출액", "500", "400", "300"),))
    )
    result = parse(tmp_path, body)
    scopes = {
        (fact.scope, fact.period_end.year): fact.raw_value
        for fact in facts_by_key(result, "revenue")
    }
    assert scopes[(Scope.CONSOLIDATED, 2024)] == "1,000"
    assert scopes[(Scope.SEPARATE, 2024)] == "500"


def test_요약이_없는_소제목도_번호가_붙으면_인식한다(tmp_path):
    """현대제철은 '가. 연결재무제표'라고만 쓴다. 번호를 앵커로 삼는다."""
    body = (
        "<P>가. 연결재무제표</P>"
        "<TABLE><TBODY><TR><TD>(단위 : 백만원)</TD></TR></TBODY></TABLE>"
        + summary_table(rows=(row_xml("매출액", "1,000", "900", "800"),))
        + "<P>나. 별도재무제표</P>"
        "<TABLE><TBODY><TR><TD>(단위 : 백만원)</TD></TR></TBODY></TABLE>"
        + summary_table(rows=(row_xml("매출액", "500", "400", "300"),))
    )
    result = parse(tmp_path, body)
    scopes = {
        (fact.scope, fact.period_end.year): fact.raw_value
        for fact in facts_by_key(result, "revenue")
    }
    assert scopes[(Scope.CONSOLIDATED, 2024)] == "1,000"
    assert scopes[(Scope.SEPARATE, 2024)] == "500"


def test_본문_속_재무제표_언급을_소제목으로_보지_않는다(tmp_path):
    """'당사는 연결재무제표를 작성합니다'는 소제목이 아니다. 번호가 앞에 없다."""
    body = (
        "<P>가. 요약연결재무정보</P>"
        "<TABLE><TBODY><TR><TD>(단위 : 백만원)</TD></TR></TBODY></TABLE>"
        + summary_table(rows=(row_xml("매출액", "1,000", "900", "800"),))
        + "<P>당사는 별도재무제표를 함께 작성하고 있습니다.</P>"
        + summary_table(rows=(row_xml("영업이익", "100", "90", "80"),))
    )
    result = parse(tmp_path, body)
    scopes = {fact.scope for fact in facts_by_key(result, "operating_profit")}
    assert scopes == {Scope.CONSOLIDATED}


def test_소제목_번호가_여러_단계여도_인식한다(tmp_path):
    """현대제철은 '1-1. 연결재무제표'처럼 번호를 두 단계로 쓴다."""
    body = (
        "<P>1-1. 연결재무제표</P>"
        "<TABLE><TBODY><TR><TD>(단위 : 백만원)</TD></TR></TBODY></TABLE>"
        + summary_table(rows=(row_xml("매출액", "1,000", "900", "800"),))
        + "<P>1-2. 별도재무제표</P>"
        "<TABLE><TBODY><TR><TD>(단위 : 백만원)</TD></TR></TBODY></TABLE>"
        + summary_table(rows=(row_xml("매출액", "500", "400", "300"),))
    )
    result = parse(tmp_path, body)
    scopes = {
        (fact.scope, fact.period_end.year): fact.raw_value
        for fact in facts_by_key(result, "revenue")
    }
    assert scopes[(Scope.CONSOLIDATED, 2024)] == "1,000"
    assert scopes[(Scope.SEPARATE, 2024)] == "500"


def test_표_아래_주석의_기수_표기를_소제목으로_보지_않는다(tmp_path):
    """'(주2) 제20기(당기) 재무제표는 …'의 '기)'는 소제목 번호와 모양이 같다.

    소제목은 글 조각의 맨 앞에서 시작한다. 문장 가운데 나오면 본문이다.
    """
    body = (
        "<P>가. 요약연결재무정보</P>"
        "<TABLE><TBODY><TR><TD>(단위 : 백만원)</TD></TR></TBODY></TABLE>"
        + summary_table(rows=(row_xml("자산총계", "1,000", "900", "800"),))
        + "<P>(주1) 한국채택국제회계기준에 따라 작성하였습니다."
        "(주2) 제56기(당기) 재무제표는 주주총회 승인전 재무제표입니다.</P>"
        + summary_table(rows=(row_xml("영업이익", "100", "90", "80"),))
    )
    result = parse(tmp_path, body)
    assert {fact.scope for fact in facts_by_key(result, "operating_profit")} == {
        Scope.CONSOLIDATED
    }


def test_다른_단원을_가리키는_안내문을_소제목으로_보지_않는다(tmp_path):
    """'"가. 재무제표 재작성 등 유의사항"을 참고하시기 바랍니다'는 안내문이다."""
    body = (
        "<P>가. 요약연결재무정보</P>"
        "<TABLE><TBODY><TR><TD>(단위 : 백만원)</TD></TR></TBODY></TABLE>"
        + summary_table(rows=(row_xml("자산총계", "1,000", "900", "800"),))
        + "<P>구체적인 내용은 III. 재무에 관한 사항 8. 기타 재무에 관한 사항 "
        "\"가. 재무제표 재작성 등 유의사항\"을 참고하시기 바랍니다.</P>"
        + summary_table(rows=(row_xml("영업이익", "100", "90", "80"),))
    )
    result = parse(tmp_path, body)
    assert {fact.scope for fact in facts_by_key(result, "operating_profit")} == {
        Scope.CONSOLIDATED
    }


def test_기준을_밝히지_않은_소제목은_앞_기준을_물려받지_않는다(tmp_path):
    """카카오처럼 '가. 요약 연결 재무정보' 다음에 '나. 요약 재무정보'가 오는 문서가 있다.

    두 번째 표는 별도인데 기준을 적지 않는다. 앞 소제목을 물려주면 별도 수치가
    연결로 기록되어 3배 틀린 답이 나간다. 근거가 없으면 unknown으로 둔다.
    """
    body = (
        "<P>가. 요약 연결 재무정보</P>"
        + summary_table(rows=(row_xml("매출액", "7,871,692", "7,557,002", "6,798,742"),))
        + "<P>나. 요약 재무정보</P>"
        + summary_table(rows=(row_xml("매출액", "2,595,101", "2,499,600", "2,456,591"),))
    )
    result = parse(tmp_path, body)
    scopes = {
        (fact.scope, fact.period_end.year): fact.raw_value
        for fact in facts_by_key(result, "revenue")
    }

    assert scopes[(Scope.CONSOLIDATED, 2024)] == "7,871,692"
    assert scopes[(Scope.UNKNOWN, 2024)] == "2,595,101"
    assert (Scope.CONSOLIDATED, 2024) in scopes
    # 앞 기준이 물려졌다면 2,595,101도 연결로 기록되어 값이 덮였을 것이다
    assert scopes[(Scope.CONSOLIDATED, 2024)] != "2,595,101"


def test_소제목이_없으면_표_머리글의_기준_표기를_읽는다(tmp_path):
    """소제목 대신 '제56기(연결)'처럼 머리글에 기준을 적는 문서가 있다."""
    body = (
        "<TABLE><TBODY><TR><TD>(단위 : 백만원)</TD></TR></TBODY></TABLE>"
        + summary_table(
            header=("제56기(연결)", "제55기(연결)", "제54기(연결)"),
            rows=(row_xml("매출액", "1,000", "900", "800"),))
    )
    result = parse(tmp_path, body)
    assert facts_by_key(result, "revenue")[0].scope is Scope.CONSOLIDATED


def test_머리글의_기준이_섹션_소제목보다_우선한다(tmp_path):
    """머리글은 그 표에만 적용되므로 섹션 소제목보다 구체적이다."""
    body = (
        "<P>가. 요약연결재무정보</P>"
        "<TABLE><TBODY><TR><TD>(단위 : 백만원)</TD></TR></TBODY></TABLE>"
        + summary_table(
            header=("제56기(별도)", "제55기(별도)", "제54기(별도)"),
            rows=(row_xml("매출액", "500", "400", "300"),))
    )
    result = parse(tmp_path, body)
    assert facts_by_key(result, "revenue")[0].scope is Scope.SEPARATE


def test_머리글에_연결과_별도가_섞이면_판정하지_않는다(tmp_path):
    body = (
        "<TABLE><TBODY><TR><TD>(단위 : 백만원)</TD></TR></TBODY></TABLE>"
        + summary_table(
            header=("제56기(연결)", "제56기(별도)", "제55기"),
            rows=(row_xml("매출액", "1,000", "500", "900"),))
    )
    result = parse(tmp_path, body)
    assert facts_by_key(result, "revenue")[0].scope is Scope.UNKNOWN


def test_소제목이_없으면_scope를_추정하지_않는다(tmp_path):
    body = (
        "<TABLE><TBODY><TR><TD>(단위 : 백만원)</TD></TR></TBODY></TABLE>"
        + summary_table(rows=(row_xml("매출액", "1,000", "2,000", "3,000"),))
    )
    result = parse(tmp_path, body)
    assert facts_by_key(result, "revenue")[0].scope is Scope.UNKNOWN


def test_손익과_재무상태의_기간_기준이_다르다(tmp_path):
    result = parse(tmp_path, CONSOLIDATED_BODY)
    revenue = [f for f in facts_by_key(result, "revenue") if f.period_end.year == 2024][0]
    assets = [f for f in facts_by_key(result, "total_assets") if f.as_of_date.year == 2024][0]

    assert revenue.period_basis is PeriodBasis.CUMULATIVE
    assert (revenue.period_start, revenue.period_end) == (date(2024, 1, 1), date(2024, 12, 31))
    assert revenue.as_of_date is None

    assert assets.period_basis is PeriodBasis.POINT_IN_TIME
    assert assets.as_of_date == date(2024, 12, 31)
    assert assets.period_start is None


def test_분기보고서는_당기만_부분기간이다(tmp_path):
    result = parse(tmp_path, CONSOLIDATED_BODY, doc_subtype="quarter", base_month=3)
    periods = {f.period_end.year: (f.period_start, f.period_end) for f in facts_by_key(result, "revenue")}
    assert periods[2024] == (date(2024, 1, 1), date(2024, 3, 31))
    assert periods[2023] == (date(2023, 1, 1), date(2023, 12, 31))


def test_값이_없다고_밝힌_공시는_실패가_아니다(tmp_path):
    body = "<P>당사는 소규모기업에 해당하여 별도로 기재하지 아니 하였습니다.</P>"
    result = parse(tmp_path, body)
    assert result.extracted_facts == []
    assert len(result.evidence_chunks) == 1
    assert "기재하지 아니" in result.evidence_chunks[0].text


def test_구조를_인식하지_못하면_빈_결과_대신_실패로_보고한다(tmp_path):
    body = "<TABLE><TBODY><TR><TD>구 분</TD><TD>금액</TD></TR>" \
           "<TR><TD>매출액</TD><TD>1,000</TD></TR></TBODY></TABLE>"
    with pytest.raises(PeriodicParseError):
        parse(tmp_path, body)


def test_단위를_확인하지_못하면_값을_추측하지_않는다(tmp_path):
    """고려아연 6건 재현 — 원문에 단위 표기가 없다. 값은 보존하되 정규화하지 않는다."""
    body = "<P>1) 연결 요약재무정보</P>" + summary_table(
        rows=(row_xml("자산총계", "12,046,071,311,650", "1", "2"),)
    )
    result = parse(tmp_path, body)
    fact = facts_by_key(result, "total_assets")[0]
    assert fact.raw_value == "12,046,071,311,650"
    assert fact.raw_unit is None
    assert fact.normalized_value is None
    assert fact.normalized_unit is None


def test_정기공시가_아니면_대상_아님으로_구분한다(tmp_path):
    with pytest.raises(UnsupportedDocument):
        parse(tmp_path, CONSOLIDATED_BODY, doc_group="major", base_year=None, base_month=None)


def test_xml이_아닌_대체수집분은_대상_아님이다(tmp_path):
    with pytest.raises(UnsupportedDocument):
        parse(tmp_path, CONSOLIDATED_BODY, file_format="pdf+html")


def test_id_규칙과_참조_무결성을_지킨다(tmp_path):
    result = parse(tmp_path, CONSOLIDATED_BODY)
    chunk_ids = {chunk.chunk_id for chunk in result.evidence_chunks}
    for index, chunk in enumerate(result.evidence_chunks):
        assert chunk.chunk_id == f"{DOC_ID}:chunk:{index:04d}"
    for index, fact in enumerate(result.extracted_facts):
        assert fact.fact_id == f"{DOC_ID}:fact:{index:04d}"
        assert fact.chunk_id in chunk_ids
    assert result.schema_version == "0.2"
    assert result.parser_version == "0.1.0"


def test_근거_문장에_항목명과_기간과_단위가_함께_남는다(tmp_path):
    result = parse(tmp_path, CONSOLIDATED_BODY)
    chunk = next(c for c in result.evidence_chunks if c.row_label == "매출액")
    assert "가. 요약연결재무정보" in chunk.text
    assert "제56기: 300,870,903" in chunk.text
    assert "단위: 백만원" in chunk.text
    assert chunk.table_title == "가. 요약연결재무정보"


# --- 실제 코퍼스가 있을 때만 실행하는 검증 -------------------------------------

CORPUS_ROOT = Path(
    os.environ.get("CHALTTEOK_CORPUS_ROOT", Path.home() / "Documents/Chaltteock/corpus")
)


@pytest.mark.skipif(
    not (CORPUS_ROOT / "manifest.jsonl").is_file(),
    reason="코퍼스 원본이 없는 환경(CI 등)에서는 건너뛴다",
)
def test_실제_삼성전자_2024_사업보고서_검증값():
    from chaltteok_etl.common.manifest import find_manifest_row

    row = find_manifest_row(CORPUS_ROOT, "periodic_20250311001085")
    result = parse_summary_financials(CORPUS_ROOT, row)
    actual = {
        (fact.fact_key, fact.scope.value, (fact.period_end or fact.as_of_date).year): fact.normalized_value
        for fact in result.extracted_facts
    }
    assert actual[("revenue", "consolidated", 2024)] == "300870903000000"
    assert actual[("revenue", "consolidated", 2023)] == "258935494000000"
    assert actual[("revenue", "separate", 2024)] == "209052241000000"
    assert actual[("operating_profit", "consolidated", 2024)] == "32725961000000"
    assert actual[("total_assets", "consolidated", 2024)] == "514531948000000"


@pytest.mark.skipif(
    not (CORPUS_ROOT / "manifest.jsonl").is_file(),
    reason="코퍼스 원본이 없는 환경(CI 등)에서는 건너뛴다",
)
def test_실제_하나금융지주_2024_사업보고서_검증값():
    """머리글이 '제20(당)기 기말'이라 한때 문서 전체가 버려지던 보고서."""
    from chaltteok_etl.common.manifest import find_manifest_row

    row = find_manifest_row(CORPUS_ROOT, "periodic_20250317000894")
    result = parse_summary_financials(CORPUS_ROOT, row)
    actual = {
        (fact.fact_key, fact.scope.value, (fact.period_end or fact.as_of_date).year): fact.normalized_value
        for fact in result.extracted_facts
    }
    assert actual[("total_assets", "consolidated", 2024)] == "637847513000000"
    assert actual[("total_assets", "consolidated", 2023)] == "591925995000000"
    assert actual[("operating_profit", "consolidated", 2024)] == "4855182000000"
    assert actual[("net_income", "consolidated", 2024)] == "3768529000000"
