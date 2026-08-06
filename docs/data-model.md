# Python 전처리 출력 형식

- 문서 상태: Draft v0.1
- 적용 범위: MVP 공시 전처리 결과
- 관련 이슈: #9
- 최종 수정일: 2026-08-06

## 1. 목적

이 문서는 Python 전처리 프로그램이 공시 원문을 파싱한 뒤 PostgreSQL 저장 단계로 전달할 공통 출력 형식을 정의한다. 네 가지 공시 유형이 같은 형식을 사용해야 하며, Python 모델과 이후 Java·DB 모델은 이 문서의 필드 의미를 기준으로 구현한다.

이 형식은 외부 평가 API 응답이 아니다. 외부 `GET /answer` 응답은 `docs/api-contract.md`를 따르며, Spring Boot가 저장된 전처리 결과를 검색·계산·판단한 뒤 외부 응답 문자열로 변환한다.

```text
manifest.jsonl + 공시 원문
        ↓
Python 파싱·정규화
        ↓
PreprocessResult(JSON Lines)
        ↓
PostgreSQL 저장
        ↓
Spring 검색·계산·이력 판단
```

## 2. 출력 단위와 공통 규칙

### 2.1 출력 단위

- 원본 공시 문서 한 건당 `PreprocessResult` 한 건을 생성한다.
- 여러 결과를 파일로 저장할 때는 UTF-8 JSON Lines 형식을 사용한다.
- JSON Lines의 한 줄은 완전한 JSON 객체 한 개이며 줄 사이에 쉼표를 넣지 않는다.
- 출력 파일의 기본 이름은 `preprocessed_documents.jsonl`로 한다.
- Python 모델은 Pydantic v2 `BaseModel`로 구현하고 JSON 출력 전에 전체 필드를 검증한다.

### 2.2 직렬화 규칙

| 항목 | 규칙 |
| --- | --- |
| JSON 필드명 | `snake_case` |
| 문자 인코딩 | UTF-8 |
| 날짜 | `YYYY-MM-DD` 문자열 |
| 식별 코드 | 숫자가 아닌 문자열로 저장해 선행 0을 보존 |
| 정규화 수치 | 반올림 오차 방지를 위해 10진수 문자열로 저장 |
| 필수 목록 | 값이 없으면 `null`이 아니라 빈 배열 `[]` 사용 |
| 선택 필드 | 의미 있는 값을 추출하지 못한 경우에만 `null` 사용 |
| 원문 경로 | 코퍼스 루트를 기준으로 한 상대경로 사용 |

`corp_code`, `stock_code`, `rcept_no`는 계산에 사용하지 않으며 반드시 문자열로 유지한다.

### 2.3 최상위 구조

| 필드 | JSON 타입 | Python 타입 | 필수 | 설명 |
| --- | --- | --- | --- | --- |
| `schema_version` | string | `str` | 필수 | 이 출력 형식의 버전. MVP 초깃값은 `0.1` |
| `document` | object | `DisclosureDocument` | 필수 | 공시 문서 공통 메타데이터 |
| `evidence_chunks` | array | `list[EvidenceChunk]` | 필수 | 검색과 답변 근거로 사용할 본문·표 조각 |
| `extracted_facts` | array | `list[ExtractedFact]` | 필수 | 비교·계산·상태 판단에 사용할 구조화된 사실 |
| `relations` | array | `list[DisclosureRelation]` | 필수 | 원본과 정정·변경·해지·후속 공시의 관계 |

```json
{
  "schema_version": "0.1",
  "document": {},
  "evidence_chunks": [],
  "extracted_facts": [],
  "relations": []
}
```

## 3. 공시 문서 모델

`DisclosureDocument`는 제공 코퍼스의 `manifest.jsonl` 한 행을 기준으로 만든다. `rcept_dt`는 `YYYYMMDD`에서 `YYYY-MM-DD`로 변환하고, `file_path`와 `n_files`는 출력에서 의미가 분명한 이름으로 통일한다.

| 필드 | JSON 타입 | Python 타입 | 필수 | `null` 허용 | 출처·규칙 |
| --- | --- | --- | --- | --- | --- |
| `doc_id` | string | `str` | 필수 | 불가 | manifest의 `doc_id`; 문서 기본 식별자 |
| `corp_code` | string | `str` | 필수 | 불가 | DART 고유번호 8자리 |
| `corp_name` | string | `str` | 필수 | 불가 | DART 공식 법인명 |
| `listed_name` | string | `str` | 필수 | 불가 | 거래소 통용 종목명 |
| `stock_code` | string | `str` | 필수 | 불가 | 종목코드 6자리 |
| `industry` | string | `str` | 필수 | 불가 | 업종 대분류 |
| `sector` | string | `str` | 필수 | 불가 | 섹터 분류 |
| `doc_group` | string | `DocGroup` | 필수 | 불가 | `periodic`, `major`, `exchange`, `holding` 중 하나 |
| `doc_subtype` | string | `str \| None` | 필수 | 허용 | 세부 유형. 주요사항보고서처럼 manifest 값이 없으면 `null` |
| `report_nm` | string | `str` | 필수 | 불가 | 공시명 |
| `rcept_no` | string | `str` | 필수 | 불가 | DART 접수번호 14자리 |
| `rcept_dt` | string | `date` | 필수 | 불가 | 접수일, `YYYY-MM-DD` |
| `flr_nm` | string | `str` | 필수 | 불가 | 제출인명 |
| `is_correction` | boolean | `bool` | 필수 | 불가 | `[기재정정]` 공시 여부 |
| `base_year` | integer | `int \| None` | 필수 | 허용 | 정기공시 보고 기준연도. 그 외 유형은 `null` |
| `base_month` | integer | `int \| None` | 필수 | 허용 | 정기공시 보고 기준월. 그 외 유형은 `null` |
| `source_path` | string | `str` | 필수 | 불가 | 코퍼스 루트 기준 원문 폴더 경로 |
| `file_format` | string | `FileFormat` | 필수 | 불가 | `xml`, `pdf+html` 중 하나 |
| `source_file_count` | integer | `int` | 필수 | 불가 | 원문 폴더의 파일 수. manifest의 `n_files` |

검증 규칙:

- `doc_id`는 manifest 값을 변경하지 않는다.
- `corp_code`는 정확히 8자리, `stock_code`는 정확히 6자리 숫자 문자열이어야 한다.
- `rcept_no`는 정확히 14자리 숫자 문자열이어야 한다.
- `base_month`는 값이 있다면 1 이상 12 이하여야 한다.
- `source_file_count`는 1 이상이어야 한다.
- `doc_group=periodic`이면 `base_year`와 `base_month`가 필수다.
- 그 외 공시 유형은 `base_year`와 `base_month`를 `null`로 출력한다.

## 4. 검색 근거 모델

`EvidenceChunk`는 검색 결과와 LLM 근거로 사용할 수 있는 최소 본문 단위다. XML 태그나 HTML 마크업은 제거하고 사람이 읽을 수 있는 텍스트를 저장한다. 표는 행·열 의미가 사라지지 않도록 항목명과 값을 함께 직렬화한다.

| 필드 | JSON 타입 | Python 타입 | 필수 | `null` 허용 | 설명 |
| --- | --- | --- | --- | --- | --- |
| `chunk_id` | string | `str` | 필수 | 불가 | 문서 안에서 안정적으로 재생성 가능한 근거 ID |
| `doc_id` | string | `str` | 필수 | 불가 | 소속 공시의 `doc_id` |
| `sequence` | integer | `int` | 필수 | 불가 | 원문 등장 순서, 0부터 시작 |
| `content_type` | string | `ContentType` | 필수 | 불가 | `paragraph`, `table`, `list` 중 하나 |
| `text` | string | `str` | 필수 | 불가 | 정규화한 근거 본문. 공백 문자열 불가 |
| `source_file` | string | `str` | 필수 | 불가 | 코퍼스 루트 기준 실제 XML·PDF·HTML 파일 경로 |
| `section_title` | string | `str \| None` | 필수 | 허용 | 상위 절 제목 |
| `table_title` | string | `str \| None` | 필수 | 허용 | 표 제목. 표가 아니면 `null` |
| `row_label` | string | `str \| None` | 필수 | 허용 | 표의 행 항목명 |
| `column_label` | string | `str \| None` | 필수 | 허용 | 표의 열 기준명 |

ID 규칙:

```text
chunk_id = {doc_id}:chunk:{sequence를 4자리로 0 채움}
예: periodic_20250311000001:chunk:0003
```

- 같은 원문과 같은 파서 버전으로 다시 실행하면 동일한 `chunk_id`가 생성되어야 한다.
- `content_type=table`이면 `table_title`, `row_label`, `column_label` 중 확인 가능한 값을 최대한 기록한다.
- 표 셀 하나만 저장하지 않고 행·열 제목을 포함해 그 값의 의미를 독립적으로 이해할 수 있게 한다.

## 5. 추출 사실 모델

`ExtractedFact`는 검색용 텍스트와 별도로 비교·계산·이력 판단에 사용할 값을 구조화한 모델이다. 숫자뿐 아니라 계약 상대방, 보유 목적, 상태처럼 공시 유형별로 필요한 텍스트·날짜·불리언 값도 같은 구조로 표현한다.

| 필드 | JSON 타입 | Python 타입 | 필수 | `null` 허용 | 설명 |
| --- | --- | --- | --- | --- | --- |
| `fact_id` | string | `str` | 필수 | 불가 | 문서 안에서 안정적으로 재생성 가능한 사실 ID |
| `doc_id` | string | `str` | 필수 | 불가 | 출처 공시의 `doc_id` |
| `chunk_id` | string | `str` | 필수 | 불가 | 값을 확인한 `EvidenceChunk`의 ID |
| `fact_key` | string | `str` | 필수 | 불가 | 코드에서 사용하는 표준 항목명. 예: `revenue`, `contract_amount` |
| `label` | string | `str` | 필수 | 불가 | 원문에 표시된 사람이 읽을 수 있는 항목명 |
| `value_type` | string | `ValueType` | 필수 | 불가 | `number`, `text`, `date`, `boolean` 중 하나 |
| `raw_value` | string | `str` | 필수 | 불가 | 원문에서 읽은 값 |
| `raw_unit` | string | `str \| None` | 필수 | 허용 | 원문 단위. 단위가 없으면 `null` |
| `normalized_value` | string | `str \| None` | 필수 | 허용 | 계산·비교용 정규화 값 |
| `normalized_unit` | string | `str \| None` | 필수 | 허용 | 정규화 단위. 예: `KRW`, `%`, `share` |
| `period_start` | string | `date \| None` | 필수 | 허용 | 기간 값의 시작일 |
| `period_end` | string | `date \| None` | 필수 | 허용 | 기간 값의 종료일 |
| `as_of_date` | string | `date \| None` | 필수 | 허용 | 특정 시점 값의 기준일 |
| `period_basis` | string | `PeriodBasis` | 필수 | 불가 | `cumulative`, `quarter_only`, `point_in_time`, `not_applicable` 중 하나 |
| `scope` | string | `Scope` | 필수 | 불가 | `consolidated`, `separate`, `not_applicable`, `unknown` 중 하나 |

ID 규칙:

```text
fact_id = {doc_id}:fact:{문서 내 순번을 4자리로 0 채움}
예: periodic_20250311000001:fact:0001
```

값 정규화 규칙:

- `value_type=number`이면 `normalized_value`는 쉼표와 단위 배수를 제거한 10진수 문자열이어야 한다.
- 금액은 원화 기준 최소 단위인 `KRW`로 환산한다. 예: `100`억원 → `normalized_value="10000000000"`, `normalized_unit="KRW"`.
- 비율은 퍼센트 값으로 통일한다. 예: `5.25%` → `normalized_value="5.25"`, `normalized_unit="%"`.
- 주식 수는 주 단위로 통일한다. 예: `1,000주` → `normalized_value="1000"`, `normalized_unit="share"`.
- `value_type=date`이면 `normalized_value`는 `YYYY-MM-DD`로 저장한다.
- 텍스트를 불필요하게 요약하거나 LLM으로 다시 쓰지 않고, 비교에 필요한 표기만 정규화한다.
- 기간 값은 `period_start`와 `period_end`를 사용하고, 지분율·잔액처럼 특정 시점의 값은 `as_of_date`를 사용한다.
- 원문에서 연결·별도 기준을 확인할 수 없으면 임의로 추정하지 않고 `scope=unknown`으로 기록한다.

## 6. 정정·후속공시 관계 모델

`DisclosureRelation`은 정정공시를 별도 공시 유형으로 만들지 않고 원본 또는 앞선 공시와 연결한다. 관계를 확정할 수 없는 문서는 추측해서 연결하지 않으며 `relations=[]`로 출력한다.

| 필드 | JSON 타입 | Python 타입 | 필수 | `null` 허용 | 설명 |
| --- | --- | --- | --- | --- | --- |
| `relation_id` | string | `str` | 필수 | 불가 | 관계 식별자 |
| `event_key` | string | `str` | 필수 | 불가 | 같은 계약·조달·지분 사건을 묶는 유형별 식별자 |
| `original_doc_id` | string | `str` | 필수 | 불가 | 최초 공시의 `doc_id` |
| `related_doc_id` | string | `str` | 필수 | 불가 | 정정·변경·해지·후속 공시의 `doc_id` |
| `relation_type` | string | `RelationType` | 필수 | 불가 | `correction`, `amendment`, `termination`, `follow_up` 중 하나 |
| `previous_status` | string | `str \| None` | 필수 | 허용 | 관계 적용 전 상태 |
| `current_status` | string | `str \| None` | 필수 | 허용 | 관계 적용 후 상태 |
| `changed_fields` | array | `list[ChangedField]` | 필수 | 불가 | 변경된 값 목록. 없으면 `[]` |
| `evidence_chunk_ids` | array | `list[str]` | 필수 | 불가 | 관계 판단에 사용한 근거 ID. 최소 1개 |

`ChangedField`는 다음 필드를 사용한다.

| 필드 | JSON 타입 | Python 타입 | 필수 | `null` 허용 | 설명 |
| --- | --- | --- | --- | --- | --- |
| `fact_key` | string | `str` | 필수 | 불가 | 변경 항목의 표준 항목명 |
| `before_value` | string | `str \| None` | 필수 | 허용 | 변경 전 정규화 값 |
| `after_value` | string | `str \| None` | 필수 | 허용 | 변경 후 정규화 값 |
| `unit` | string | `str \| None` | 필수 | 허용 | 두 값의 공통 정규화 단위 |

관계 규칙:

- `original_doc_id`와 `related_doc_id`는 서로 달라야 한다.
- 두 문서는 원칙적으로 같은 `corp_code`에 속해야 한다.
- `related_doc_id`의 접수일은 `original_doc_id`의 접수일보다 같거나 늦어야 한다.
- `event_key` 생성 규칙은 공시 유형별 파서에서 정의하되, 같은 사건이면 재실행해도 같은 값이 나와야 한다.
- 관계 판단 근거를 찾지 못하면 `evidence_chunk_ids`를 비워서 저장하지 않고 관계 자체를 생성하지 않는다.
- 최종 상태는 관계 레코드에 중복 저장하지 않는다. Spring에서 같은 `event_key`의 관계를 접수일 순으로 적용한 마지막 `current_status`를 최종 상태로 판단한다.

관계 ID는 다음 요소로 결정적으로 생성한다.

```text
relation_id = {original_doc_id}:{related_doc_id}:{relation_type}
```

## 7. 전체 출력 예시

다음 값은 스키마 설명을 위한 가상 예시이며 실제 공시 수치가 아니다.

```json
{
  "schema_version": "0.1",
  "document": {
    "doc_id": "periodic_20250311000001",
    "corp_code": "00000001",
    "corp_name": "예시전자",
    "listed_name": "예시전자",
    "stock_code": "000001",
    "industry": "IT",
    "sector": "반도체·전자부품",
    "doc_group": "periodic",
    "doc_subtype": "annual",
    "report_nm": "사업보고서 (2024.12)",
    "rcept_no": "20250311000001",
    "rcept_dt": "2025-03-11",
    "flr_nm": "예시전자",
    "is_correction": false,
    "base_year": 2024,
    "base_month": 12,
    "source_path": "raw/periodic/예시전자/20250311000001_annual_2024_12",
    "file_format": "xml",
    "source_file_count": 1
  },
  "evidence_chunks": [
    {
      "chunk_id": "periodic_20250311000001:chunk:0000",
      "doc_id": "periodic_20250311000001",
      "sequence": 0,
      "content_type": "table",
      "text": "연결 포괄손익계산서 | 매출액 | 2024년 | 100,000백만원",
      "source_file": "raw/periodic/예시전자/20250311000001_annual_2024_12/20250311000001.xml",
      "section_title": "재무에 관한 사항",
      "table_title": "연결 포괄손익계산서",
      "row_label": "매출액",
      "column_label": "2024년"
    }
  ],
  "extracted_facts": [
    {
      "fact_id": "periodic_20250311000001:fact:0000",
      "doc_id": "periodic_20250311000001",
      "chunk_id": "periodic_20250311000001:chunk:0000",
      "fact_key": "revenue",
      "label": "매출액",
      "value_type": "number",
      "raw_value": "100,000",
      "raw_unit": "백만원",
      "normalized_value": "100000000000",
      "normalized_unit": "KRW",
      "period_start": "2024-01-01",
      "period_end": "2024-12-31",
      "as_of_date": null,
      "period_basis": "cumulative",
      "scope": "consolidated"
    }
  ],
  "relations": []
}
```

정정 또는 후속공시에서 값이 바뀐 경우 관계는 다음처럼 표현한다.

```json
{
  "relation_id": "exchange_20240101000001:exchange_20240201000002:amendment",
  "event_key": "00000001:supply_contract:example_customer:example_contract",
  "original_doc_id": "exchange_20240101000001",
  "related_doc_id": "exchange_20240201000002",
  "relation_type": "amendment",
  "previous_status": "active",
  "current_status": "active",
  "changed_fields": [
    {
      "fact_key": "contract_amount",
      "before_value": "100000000000",
      "after_value": "80000000000",
      "unit": "KRW"
    }
  ],
  "evidence_chunk_ids": [
    "exchange_20240201000002:chunk:0002"
  ]
}
```

## 8. 공시 유형별 표현 기준

| 공시 유형 | 문서 메타데이터 | 주요 `ExtractedFact` 예시 | 관계 적용 예시 |
| --- | --- | --- | --- |
| 정기공시 | 기준연도·기준월·annual/half/quarter | 매출액, 영업이익, 자산·부채, 연결·별도 기준 | 기재정정 전후 수치 변경 |
| 주요사항보고서 | 공시명과 접수일 | 조달 유형, 발행 금액, 일정, 발행 조건 | 조건 정정·일정 변경·후속 상태 |
| 거래소공시 | 계약·투자 세부 유형 | 계약명, 상대방, 계약 금액, 계약 기간, 상태 | 계약 변경·해지·투자 변경 |
| 지분공시 | 대량보유상황보고서 | 보유자, 보유 주식 수, 지분율, 보유 목적 | 신규·변경·해소 및 시점별 지분 변화 |

모든 공시 유형은 최소한 다음을 제공해야 한다.

- `document` 한 건
- 답변 근거로 사용할 수 있는 `evidence_chunks` 한 건 이상
- 질문 처리에 필요한 값이 있다면 해당 `extracted_facts`
- 정정·후속 관계를 확정할 수 있다면 해당 `relations`

문서에서 필요한 값을 찾지 못한 경우 값을 추측한 `ExtractedFact`를 만들지 않는다. 이 경우에도 문서와 실제로 추출한 근거는 저장할 수 있다.

## 9. 평가 API와의 연결

| 평가 API 필드 | 전처리 결과 사용 방식 |
| --- | --- |
| `question_id` | API 요청에서 받은 값이며 전처리 결과에 저장하지 않음 |
| `question` | API 요청에서 받은 값이며 전처리 결과에 저장하지 않음 |
| `retrieved_context` | 선택된 `document`의 공시명·공시일·기업명과 `evidence_chunks.text`로 구성 |
| `think_trace` | 선택한 문서·사실·관계 및 계산 과정을 Spring에서 요약 |
| `answer` | 검색 결과와 `ExtractedFact`·`DisclosureRelation`을 근거로 HCX가 생성 |

전처리 단계에서는 최종 답변 문장이나 `think_trace`를 생성하지 않는다.

## 10. 구현 경계

이 문서에서 확정하는 것은 Python 전처리 출력의 논리적 계약이다. 다음 항목은 별도 구현 단계에서 결정한다.

- PostgreSQL 테이블명, 인덱스, 외래키와 JSONB 사용 여부
- Java Entity·Repository 구조
- 공시 유형별 `fact_key` 전체 목록과 추출 규칙
- 공시 유형별 `event_key` 생성 세부 규칙
- 검색용 chunk 크기와 분할 알고리즘
- DB 적재 방식과 재처리 시 upsert 정책

DB 구조가 달라져도 이 문서에서 정의한 필드의 의미와 정규화 규칙은 바꾸지 않는다. 필드 삭제·타입 변경처럼 호환되지 않는 변경이 필요하면 `schema_version`을 올리고 문서를 먼저 수정한다.

## 11. 검토 기준

- [ ] 제공 코퍼스의 manifest 필드를 손실 없이 공통 문서 모델로 옮길 수 있다.
- [ ] 네 가지 공시 유형이 같은 최상위 형식으로 출력될 수 있다.
- [ ] 검색 근거가 원문 파일과 위치까지 추적 가능하다.
- [ ] 금액·비율·주식 수의 원문 값과 정규화 값이 함께 보존된다.
- [ ] 기간 수치와 특정 시점 수치를 구분할 수 있다.
- [ ] 연결·별도 기준을 구분하고 확인 불가 상태를 표현할 수 있다.
- [ ] 정정·변경·해지·후속 관계와 변경값을 표현할 수 있다.
- [ ] 평가 API 응답 필드가 어떤 전처리 결과에서 만들어지는지 명확하다.
- [ ] Python 모델과 이후 Java·DB 모델로 구현 가능한 형식이다.
