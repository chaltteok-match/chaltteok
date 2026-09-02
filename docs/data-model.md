# Python 전처리 출력 형식

- 문서 상태: Draft v0.2
- 적용 범위: MVP 공시 전처리 결과
- 관련 이슈: #9
- 최종 수정일: 2026-08-07

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
- 원본 폴더에 파일이 여러 개 있더라도 manifest의 문서 한 건을 하나의 `PreprocessResult`로 묶는다.
- 여러 결과를 파일로 저장할 때는 UTF-8 JSON Lines 형식을 사용한다.
- JSON Lines의 한 줄은 완전한 JSON 객체 한 개이며 줄 사이에 쉼표를 넣지 않는다.
- 출력 파일의 기본 이름은 `preprocessed_documents.jsonl`로 한다.
- Python 모델은 Pydantic v2 `BaseModel`로 구현하고 JSON 출력 전에 전체 필드를 검증한다.

### 2.2 직렬화 규칙

| 항목 | 규칙 |
| --- | --- |
| JSON 필드명 | `snake_case` |
| 문자 인코딩 | UTF-8 |
| 유니코드 정규화 | 기업 식별값과 논리적 경로는 NFC로 정규화 |
| 날짜 | `YYYY-MM-DD` 문자열 |
| 식별 코드 | 숫자가 아닌 문자열로 저장해 선행 0을 보존 |
| 정규화 수치 | 반올림 오차 방지를 위해 10진수 문자열로 저장 |
| 필수 목록 | 값이 없으면 `null`이 아니라 빈 배열 `[]` 사용 |
| 선택 필드 | 의미 있는 값을 추출하지 못한 경우에만 `null` 사용 |
| 원문 경로 | 코퍼스 루트 기준의 NFC 상대경로를 `/` 구분자로 저장 |

`corp_code`, `stock_code`, `rcept_no`는 계산에 사용하지 않으며 반드시 문자열로 유지한다.

- `corp_name`, `listed_name`, `source_path`, `source_file`은 NFC로 정규화해 출력한다.
- `source_path`와 `source_file`은 운영체제와 무관하게 `/`를 사용하는 POSIX 상대경로로 직렬화한다.
- `EvidenceChunk.text`, `ExtractedFact.raw_value`처럼 원문 증거를 보존하는 필드는 유니코드 형태를 임의로 바꾸거나 내용을 요약하지 않는다.

### 2.3 원문 경로 해결 규칙

코퍼스의 `raw/` 하위 한글 기업 폴더명은 NFD이고, manifest의 `corp_name`과 `file_path`는 NFC다. macOS에서는 NFC 경로로 NFD 폴더가 열릴 수 있지만 Linux·Docker에서는 같은 코드가 빈 결과를 반환할 수 있으므로, manifest 경로를 파일시스템 경로로 바로 사용하지 않는다.

- 코퍼스 위치는 개인 PC의 절대경로로 고정하지 않고 `CORPUS_ROOT` 환경변수 또는 실행 인자로 전달한다.
- 경로는 문자열 덧셈이나 `/`, `\` 직접 삽입 대신 `pathlib.Path`로 조합한다.
- manifest 상대경로와 실제 파일시스템 상대경로를 각각 NFC로 정규화한 뒤 비교해 실제 `Path`를 찾는다.
- 대소문자는 원본 그대로 비교한다. `NC`, `JYP Ent`, `삼성E&A` 같은 이름이 있으므로 `lower()`나 `casefold()`를 적용하지 않는다.
- 정규화 후 일치하는 경로가 없거나 둘 이상이면 전처리를 중단하고 오류 경로를 보고한다. `continue`로 조용히 건너뛰지 않는다.
- 전처리 시작 전에 manifest의 모든 문서 폴더가 정확히 한 개씩 매칭되고 실제 파일 수가 `n_files`와 일치하는지 사전 검증한다.
- NFC 상대경로가 중복되는 파일이 발견되면 정규화 충돌로 처리하고 임의의 한 파일을 선택하지 않는다.
- 출력에는 실제 운영체제의 NFD 경로가 아니라 manifest를 기준으로 한 NFC 논리 경로를 기록한다.

실제 경로를 찾기 위한 권장 방식은 코퍼스 시작 시 다음 인덱스를 만드는 것이다.

```text
NFC로 정규화한 코퍼스 상대경로 -> 실제 파일시스템 Path
```

### 2.4 원문 파일 순서

파일시스템의 순회 순서는 운영체제마다 다르므로 `iterdir()` 결과를 그대로 `sequence` 생성에 사용하지 않는다. 한 문서의 원문 파일은 다음 순서로 처리한다.

1. 파일명 본체가 `rcept_no`와 정확히 같은 주 원문 파일
2. 나머지 파일을 NFC POSIX 상대경로 오름차순으로 정렬
3. 각 XML·HTML 파일은 원문 요소 등장 순서, PDF는 페이지와 페이지 안의 읽기 순서

이 순서를 적용한 뒤 문서 전체에서 `sequence`를 0부터 부여한다. 같은 원문과 같은 파서 버전이면 운영체제와 관계없이 같은 `chunk_id`가 생성되어야 한다.

### 2.5 실제 파일 형식과 파서 선택

manifest의 `file_format`은 수집 당시의 분류이며 실제 파서 종류를 뜻하지 않는다. 2026-08-07 코퍼스 전수검사 결과는 다음과 같다.

| 공시 유형 | 실제 원문 파일 | 확인된 실제 형식 |
| --- | ---: | --- |
| 정기공시 | 1,472개 | DART XML 계열 1,466개, HTML 3개, PDF 3개 |
| 주요사항보고서 | 598개 | DART XML 계열 598개 |
| 거래소공시 | 1,469개 | HTML 1,469개 |
| 지분공시 | 1,083개 | DART XML 계열 1,083개 |

모든 거래소공시는 확장자가 `.xml`이고 manifest 값도 `xml`이지만 실제 내용은 HTML이다. 따라서 다음 순서로 실제 형식을 판별한다.

1. 파일 시그니처가 `%PDF-`이면 `pdf`
2. 선두 내용에 `<!DOCTYPE html>` 또는 `<html>`이 있으면 `html`
3. 그 외 DART `DOCUMENT` 계열 원문이면 `dart_xml`
4. 어느 형식에도 해당하지 않으면 추측해서 처리하지 않고 파싱 오류로 보고

파싱 공통 규칙:

- 코퍼스의 텍스트 원문은 UTF-8로 읽는다.
- 거래소공시 HTML 1,469개는 내부에 `euc-kr`로 선언되어 있지만 실제 바이트는 UTF-8이므로, HTML의 잘못된 charset 선언보다 코퍼스 인코딩 규칙을 우선한다.
- DART XML 계열 원문에는 이스케이프되지 않은 `&`와 잘못된 태그가 존재하므로 엄격한 XML 파싱만을 가정하지 않는다.
- DART XML 계열 3,147개 중 1,838개가 엄격한 XML 파싱에 실패했으므로 이 규칙을 일부 예외 파일에만 적용해서는 안 된다.
- DART XML을 정제하거나 복구 모드로 파싱할 때 `AT&T`가 `AT`로 바뀌는 것처럼 원문 문자가 손실되지 않았는지 검증한다.
- 외부 DTD나 네트워크 리소스를 가져오지 않는다.
- HTML의 `head`, `style`, `script`, 메뉴, iframe, 숨김 UI는 검색 근거에서 제외한다.
- `pdf+html` 3건 중 HTML 1건은 실제 공시 전문이고 2건은 목차·뷰어 껍데기다. 전문 HTML이면 HTML을 우선하고, 껍데기뿐이면 HTML을 제외하고 PDF를 사용한다.
- PDF 파서가 외부 실행 프로그램에 의존한다면 같은 의존성을 Docker 실행환경에도 포함한다.
- 파서와 주요 라이브러리 버전은 `requirements.txt` 또는 `pyproject.toml`에 고정한다.
- 대용량 원문과 전체 코퍼스를 한꺼번에 메모리에 올리지 않고 문서 또는 파일 단위로 처리한다.

### 2.6 최상위 구조

| 필드 | JSON 타입 | Python 타입 | 필수 | 설명 |
| --- | --- | --- | --- | --- |
| `schema_version` | string | `str` | 필수 | 이 출력 형식의 버전. 현재 `0.2` |
| `parser_version` | string | `str` | 필수 | 전처리 구현 버전. MVP 초깃값은 `0.1.0` |
| `document` | object | `DisclosureDocument` | 필수 | 공시 문서 공통 메타데이터 |
| `evidence_chunks` | array | `list[EvidenceChunk]` | 필수 | 검색과 답변 근거로 사용할 본문·표 조각 |
| `extracted_facts` | array | `list[ExtractedFact]` | 필수 | 비교·계산·상태 판단에 사용할 구조화된 사실 |
| `relations` | array | `list[DisclosureRelation]` | 필수 | 원본과 정정·변경·해지·후속 공시의 관계 |

```json
{
  "schema_version": "0.1",
  "parser_version": "0.1.0",
  "document": {},
  "evidence_chunks": [],
  "extracted_facts": [],
  "relations": []
}
```

- `schema_version`은 필드 삭제·타입 변경 등 출력 계약이 호환되지 않게 바뀔 때 올린다.
- `parser_version`은 파싱·정규화·chunk 분할 로직이 바뀌어 같은 원문에서 다른 결과가 나올 수 있을 때 올린다.

## 3. 공시 문서 모델

`DisclosureDocument`는 제공 코퍼스의 `manifest.jsonl` 한 행을 기준으로 만든다. `rcept_dt`는 `YYYYMMDD`에서 `YYYY-MM-DD`로 변환하고, `file_path`와 `n_files`는 출력에서 의미가 분명한 이름으로 통일한다.

| 필드 | JSON 타입 | Python 타입 | 필수 | `null` 허용 | 출처·규칙 |
| --- | --- | --- | --- | --- | --- |
| `doc_id` | string | `str` | 필수 | 불가 | manifest의 `doc_id`; 문서 기본 식별자 |
| `corp_code` | string | `str` | 필수 | 불가 | DART 고유번호 8자리 |
| `corp_name` | string | `str` | 필수 | 불가 | NFC로 정규화한 DART 공식 법인명 |
| `listed_name` | string | `str` | 필수 | 불가 | NFC로 정규화한 거래소 통용 종목명 |
| `stock_code` | string | `str` | 필수 | 불가 | 종목코드 6자리 |
| `industry` | string | `str` | 필수 | 불가 | 업종 대분류 |
| `sector` | string | `str` | 필수 | 불가 | 섹터 분류 |
| `doc_group` | string | `DocGroup` | 필수 | 불가 | `periodic`, `major`, `exchange`, `holding` 중 하나 |
| `doc_subtype` | string | `str \| None` | 필수 | 허용 | 문서 세부 유형. manifest 값이 없는 주요사항보고서는 공시명에서 서식명을 뽑아 채운다 |
| `report_nm` | string | `str` | 필수 | 불가 | 공시명 |
| `rcept_no` | string | `str` | 필수 | 불가 | DART 접수번호 14자리 |
| `rcept_dt` | string | `date` | 필수 | 불가 | 접수일, `YYYY-MM-DD` |
| `flr_nm` | string | `str` | 필수 | 불가 | 제출인명 |
| `is_correction` | boolean | `bool` | 필수 | 불가 | `[기재정정]` 공시 여부 |
| `base_year` | integer | `int \| None` | 필수 | 허용 | 정기공시 보고 기준연도. 그 외 유형은 `null` |
| `base_month` | integer | `int \| None` | 필수 | 허용 | 정기공시 보고 기준월. 그 외 유형은 `null` |
| `source_path` | string | `str` | 필수 | 불가 | 코퍼스 루트 기준 NFC POSIX 논리 폴더 경로 |
| `manifest_file_format` | string | `ManifestFileFormat` | 필수 | 불가 | manifest의 `file_format`; `xml`, `pdf+html` 중 하나이며 파서 선택에 사용하지 않음 |
| `source_file_count` | integer | `int` | 필수 | 불가 | 원문 폴더의 파일 수. manifest의 `n_files` |

검증 규칙:

- `doc_id`는 manifest 값을 변경하지 않는다.
- `corp_code`는 정확히 8자리, `stock_code`는 정확히 6자리 숫자 문자열이어야 한다.
- `rcept_no`는 정확히 14자리 숫자 문자열이어야 한다.
- `base_month`는 값이 있다면 1 이상 12 이하여야 한다.
- `source_file_count`는 1 이상이어야 한다.
- `manifest_file_format`은 manifest 값을 보존하며 실제 파일 내용과 다를 수 있다.
- `doc_group=periodic`이면 `base_year`와 `base_month`가 필수다.
- 그 외 공시 유형은 `base_year`와 `base_month`를 `null`로 출력한다.

`doc_subtype`은 `doc_group` 안에서 문서를 다시 나누는 값이다. 값의 의미는 공시 유형마다 다르다.

| `doc_group` | 값의 성격 | 예 |
| --- | --- | --- |
| `periodic` | 보고 주기 | `annual`, `half`, `quarter` |
| `exchange` | 서식 종류 | `단일판매공급계약체결`, `신규시설투자등` |
| `holding` | 서식 종류 | `대량보유상황보고서` |
| `major` | 서식 종류 | `유상증자결정`, `자기주식취득결정` |

주요사항보고서만 manifest에 값이 없어 `report_nm`에서 뽑는다.

```text
"[기재정정]주요사항보고서(유상증자결정)"  ->  "유상증자결정"
"주요사항보고서(자기주식취득결정)"        ->  "자기주식취득결정"
```

`report_nm`을 그대로 검색 키로 쓰지 않는 이유는 두 가지다.

- 절반가량에 `[기재정정]` 접두가 붙는다. 주요사항보고서 598건 중 유상증자결정 56건은 접두 유무로 표기가 갈린다.
- 이름이 서로를 포함한다. `자기주식`으로 검색하면 취득(91)·처분(157)·신탁계약체결(47)·신탁계약해지(43)가 모두 걸린다.

`주요사항보고서(...)` 형태가 아닌 공시명은 값을 만들지 않는다. DART 서식 코드가 달라 같은 서식으로 볼 수 없다.

## 4. 검색 근거 모델

`EvidenceChunk`는 검색 결과와 LLM 근거로 사용할 수 있는 최소 본문 단위다. XML 태그나 HTML 마크업은 제거하고 사람이 읽을 수 있는 텍스트를 저장한다. 표는 행·열 의미가 사라지지 않도록 항목명과 값을 함께 직렬화한다.

| 필드 | JSON 타입 | Python 타입 | 필수 | `null` 허용 | 설명 |
| --- | --- | --- | --- | --- | --- |
| `chunk_id` | string | `str` | 필수 | 불가 | 문서 안에서 안정적으로 재생성 가능한 근거 ID |
| `doc_id` | string | `str` | 필수 | 불가 | 소속 공시의 `doc_id` |
| `sequence` | integer | `int` | 필수 | 불가 | 원문 등장 순서, 0부터 시작 |
| `content_type` | string | `ContentType` | 필수 | 불가 | `paragraph`, `table`, `list` 중 하나 |
| `text` | string | `str` | 필수 | 불가 | 정규화한 근거 본문. 공백 문자열 불가 |
| `source_file` | string | `str` | 필수 | 불가 | 코퍼스 루트 기준 NFC POSIX 논리 파일 경로 |
| `source_format` | string | `SourceFormat` | 필수 | 불가 | 실제 내용을 판별한 형식. `dart_xml`, `html`, `pdf` 중 하나 |
| `page_number` | integer | `int \| None` | 필수 | 허용 | PDF의 1부터 시작하는 페이지 번호. XML·HTML이면 `null` |
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
- `source_format`은 파일 확장자나 `manifest_file_format`이 아니라 2.5의 실제 내용 판별 결과를 사용한다.
- `source_format=pdf`이면 `page_number`가 필수이고, 그 외 형식이면 `null`이어야 한다.
- `page_number`는 값이 있다면 1 이상이어야 한다.
- `content_type=table`이면 `table_title`, `row_label`, `column_label` 중 확인 가능한 값을 최대한 기록한다.
- 표 셀 하나만 저장하지 않고 행·열 제목을 포함해 그 값의 의미를 독립적으로 이해할 수 있게 한다.

`content_type`은 값의 의미가 아니라 원문에서 근거가 표현된 구조를 나타낸다.

| 원문 구조 | `content_type` | 적용 규칙 |
| --- | --- | --- |
| XML·HTML 문단 또는 PDF 텍스트 블록 | `paragraph` | 사람이 읽을 수 있는 한 문단 단위 |
| 표, 표의 행·셀 | `table` | 행·열 제목과 값을 함께 보존 |
| 실제 목록 또는 연속된 목록형 문단 | `list` | 항목 순서를 보존해 직렬화 |

- 거래소공시는 `계약금액: 값`처럼 보이더라도 실제 HTML 표로 구성되므로 `content_type=table`로 처리한다.
- `key_value`는 별도 `content_type`으로 추가하지 않는다. 항목과 값의 의미는 `ExtractedFact.fact_key`, `label`, `raw_value`로 표현한다.
- `rowspan`이나 `colspan`으로 상위 항목명이 생략된 표는 앞선 셀의 항목명을 이어받는다. 예: `2. 계약내역 > 계약금액(원)`.
- 제목 자체는 별도 근거 유형으로 만들지 않고 `section_title` 또는 `table_title`에 기록한다.

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
| `extraction_status` | string | `ExtractionStatus` | 필수 | 불가 | `ok`, `unit_missing` 중 하나. 정규화 결과 상태 |

`extraction_status`는 값을 정규화한 결과를 남긴다. `normalized_value`가 비는 원인이 하나가 아니므로, 상태를 남기지 않으면 파서 결함과 원문의 정보 한계를 구분할 수 없다(§9).

| 값 | 뜻 | `normalized_value` |
| --- | --- | --- |
| `ok` | 원문 값을 읽고 정규화까지 마쳤다 | 있음 |
| `unit_missing` | 값은 읽었지만 원문에 단위 표기가 없어 배수를 확정하지 못했다 | 비어 있음 |

원문에 값이 없으면(`-`, 미기재 안내문) fact를 만들지 않는다. 따라서 "값 없음"은 fact 상태가 아니라 fact가 없는 상태로 나타난다.

상태를 추가하면 기존 출력을 읽을 수 없으므로 `schema_version`을 올린다.

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

`RelationType`은 다음 기준으로 구분한다.

| 값 | 적용 기준 |
| --- | --- |
| `correction` | `[기재정정]`처럼 기존 공시 문서의 기재 내용을 바로잡은 경우 |
| `amendment` | 후속 공시로 같은 사건의 금액·기간·조건 등이 변경된 경우 |
| `termination` | 계약뿐 아니라 추적 중인 사건·관계·보고 의무가 종료되거나 해소된 경우 |
| `follow_up` | 같은 사건의 후속 진행이지만 정정·변경·종료에 해당하지 않는 경우 |

지분율이 기준 미만으로 내려가 보고 의무가 해소되는 지분공시는 `termination`으로 통일한다. 이때 유형별 상태 문자열은 다음처럼 사용할 수 있다.

```json
{
  "relation_type": "termination",
  "previous_status": "reporting_required",
  "current_status": "reporting_obligation_resolved"
}
```

관계 규칙:

- `original_doc_id`와 `related_doc_id`는 서로 달라야 한다.
- 두 문서는 원칙적으로 같은 `corp_code`에 속해야 한다.
- `related_doc_id`는 `(rcept_dt, rcept_no)` 순서상 `original_doc_id`보다 뒤에 있어야 한다. 같은 접수일이면 `rcept_no`를 순서 결정 기준으로 사용한다.
- `event_key` 생성 규칙은 공시 유형별 파서에서 정의하되, 같은 사건이면 재실행해도 같은 값이 나와야 한다.
- 관계 판단 근거를 찾지 못하면 `evidence_chunk_ids`를 비워서 저장하지 않고 관계 자체를 생성하지 않는다.
- 최종 상태는 관계 레코드에 중복 저장하지 않는다. Spring에서 같은 `event_key`의 관계를 `(rcept_dt, rcept_no)` 순으로 적용한 마지막 `current_status`를 최종 상태로 판단한다.

관계 ID는 다음 요소로 결정적으로 생성한다.

```text
relation_id = {original_doc_id}:{related_doc_id}:{relation_type}
```

## 7. 전체 출력 예시

다음 값은 스키마 설명을 위한 가상 예시이며 실제 공시 수치가 아니다.

```json
{
  "schema_version": "0.1",
  "parser_version": "0.1.0",
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
    "manifest_file_format": "xml",
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
      "source_format": "dart_xml",
      "page_number": null,
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
      "scope": "consolidated",
      "extraction_status": "ok"
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

## 9. 실패 처리와 실행환경 검증

경로 또는 파싱 문제를 답변 불가 상태와 혼동하지 않는다. 공시에 값이 없는 경우는 정상 전처리 결과지만, 원문을 열지 못했거나 파싱하지 못한 경우는 전처리 실패다.

- 경로를 찾지 못하거나 `source_file_count`가 맞지 않으면 해당 문서를 빈 결과로 만들지 않고 사전 검증을 실패시킨다.
- 지원하지 않는 파일 형식, 디코딩 오류, 파싱 오류가 발생하면 문서 ID·논리 경로·실제 경로·오류 원인을 기록한다.
- 파싱에 실패한 문서를 `evidence_chunks=[]`인 정상 결과로 출력하지 않는다.
- 한 문서에서 실제 공시 본문을 찾지 못하면 뷰어 UI나 CSS·JavaScript를 대신 근거로 저장하지 않는다.
- 실행 종료 시 manifest 문서 수, 성공·실패 문서 수, 읽은 원문 파일 수, 생성한 chunk·fact·relation 수를 요약한다.
- 실패가 한 건이라도 있으면 종료 코드를 성공으로 반환하지 않는다. 부분 결과를 보존하더라도 완전한 적재 결과로 표시하지 않는다.
- 원본 코퍼스는 읽기 전용으로 취급하며 전처리 과정에서 폴더명이나 파일 내용을 변경하지 않는다.

최소 호환성 테스트에는 다음 사례를 포함한다.

- NFD 한글 폴더와 NFC manifest 경로가 Linux·Docker에서 정상 매칭됨
- 공백·`&`·영문 대소문자가 포함된 기업 폴더를 정확히 찾음
- 거래소공시의 잘못된 `euc-kr` 선언을 무시하고 UTF-8 한글을 보존함
- 비정상 DART XML의 `AT&T` 같은 원문을 손실 없이 추출함
- 뷰어 껍데기 HTML을 제외하고 PDF 본문과 페이지 번호를 추출함
- 같은 입력을 macOS와 Linux·Docker에서 실행했을 때 파일 순서와 `chunk_id`가 동일함

전체 코퍼스를 매번 테스트에 포함하지 않는다. 위 예외를 재현하는 작은 `sample-data/` fixture를 저장소에 두고, 전체 코퍼스 검증은 별도 로컬·배포 전 점검으로 실행한다.

## 10. 평가 API와의 연결

| 평가 API 필드 | 전처리 결과 사용 방식 |
| --- | --- |
| `question_id` | API 요청에서 받은 값이며 전처리 결과에 저장하지 않음 |
| `question` | API 요청에서 받은 값이며 전처리 결과에 저장하지 않음 |
| `retrieved_context` | 선택된 `document`의 공시명·공시일·기업명과 `evidence_chunks.text`로 구성 |
| `think_trace` | 선택한 문서·사실·관계 및 계산 과정을 Spring에서 요약 |
| `answer` | 검색 결과와 `ExtractedFact`·`DisclosureRelation`을 근거로 HCX가 생성 |

전처리 단계에서는 최종 답변 문장이나 `think_trace`를 생성하지 않는다.

## 11. 구현 경계

이 문서에서 확정하는 것은 Python 전처리 출력의 논리적 계약이다. 다음 항목은 별도 구현 단계에서 결정한다.

- PostgreSQL 테이블명, 인덱스, 외래키와 JSONB 사용 여부
- Java Entity·Repository 구조
- 공시 유형별 `fact_key` 전체 목록과 추출 규칙
- 공시 유형별 `event_key` 생성 세부 규칙
- 검색용 chunk 크기와 분할 알고리즘
- DB 적재 방식과 재처리 시 upsert 정책
- XML·HTML·PDF 파서 라이브러리의 구체적인 선택

위 항목을 별도로 결정하더라도 2장에서 정의한 경로 정규화, 실제 형식 판별, UTF-8 처리, 결정적 파일 순서와 실패 보고 규칙은 반드시 지켜야 한다.

DB 구조가 달라져도 이 문서에서 정의한 필드의 의미와 정규화 규칙은 바꾸지 않는다. 필드 삭제·타입 변경처럼 호환되지 않는 변경이 필요하면 `schema_version`을 올리고 문서를 먼저 수정한다.

## 12. 검토 기준

- [ ] 제공 코퍼스의 manifest 필드를 손실 없이 공통 문서 모델로 옮길 수 있다.
- [ ] NFD 실제 경로와 NFC manifest 경로를 운영체제와 무관하게 안전하게 연결할 수 있다.
- [ ] 경로 누락·중복·파일 수 불일치가 빈 결과로 조용히 처리되지 않는다.
- [ ] 네 가지 공시 유형이 같은 최상위 형식으로 출력될 수 있다.
- [ ] manifest 형식과 실제 `dart_xml`·`html`·`pdf` 형식을 구분할 수 있다.
- [ ] 잘못된 charset 선언과 비정상 XML에서도 원문 문자가 손실되지 않는다.
- [ ] 여러 원문 파일의 처리 순서와 `chunk_id`가 실행환경에 관계없이 동일하다.
- [ ] 검색 근거가 원문 파일과 위치까지 추적 가능하다.
- [ ] PDF 근거가 실제 페이지 번호까지 추적 가능하다.
- [ ] 금액·비율·주식 수의 원문 값과 정규화 값이 함께 보존된다.
- [ ] 기간 수치와 특정 시점 수치를 구분할 수 있다.
- [ ] 연결·별도 기준을 구분하고 확인 불가 상태를 표현할 수 있다.
- [ ] 정정·변경·해지·후속 관계와 변경값을 표현할 수 있다.
- [ ] 평가 API 응답 필드가 어떤 전처리 결과에서 만들어지는지 명확하다.
- [ ] Python 모델과 이후 Java·DB 모델로 구현 가능한 형식이다.
