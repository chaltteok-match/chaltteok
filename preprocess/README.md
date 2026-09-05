# Python 전처리 개발 환경

공시 유형별 전처리 코드는 하나의 `chaltteok_etl` 패키지와 동일한 Python 환경을 사용합니다.

## 요구 사항

- Python 3.12
- Pydantic 2.13.4
- pytest 9.1.1

## 로컬 환경 구성

```bash
cd preprocess
python3.12 -m venv .venv
source .venv/bin/activate
python -m pip install --upgrade pip
python -m pip install -e ".[dev]"
```

`-e`는 현재 소스 디렉터리를 개발 모드로 설치하므로 코드를 수정할 때마다 다시 설치할 필요가 없습니다.

## 테스트

가상환경을 활성화한 상태에서 실행합니다.

```bash
cd preprocess
pytest
```

## 패키지 구성

```text
chaltteok_etl/
├── common/      # 공통 경로·문자열 정규화와 모델
├── periodic/    # 정기공시
├── exchange/    # 거래소공시
├── material/    # 주요사항보고서
└── ownership/   # 지분공시
```

코퍼스 원문, 전처리 결과와 API 키는 로컬에서만 관리하고 저장소에 커밋하지 않습니다.

Python 공통 출력 모델은 [`docs/data-model.md`](../docs/data-model.md)의 `PreprocessResult` 계약을 기준으로 구현합니다. 전처리 단계에서는 최종 API 답변이나 `think_trace`를 생성하지 않습니다.

유상증자결정 ACODE·목적별 `fact_key` 근거는 [`docs/architecture/paid-in-capital-acodes.md`](../docs/architecture/paid-in-capital-acodes.md)를 따릅니다. `ANC_ACQ_AMT`는 총 조달금액이 아닙니다.
자기주식취득결정(`ACODE=11332`, 구 서식 `11310`)은 취득예정 보통주식 수와 금액만 뽑습니다. 서식 근거는 [`docs/architecture/treasury-parsing.md`](../docs/architecture/treasury-parsing.md)를 따릅니다.
지분공시 대량보유 항목은 [`docs/architecture/holding-parsing.md`](../docs/architecture/holding-parsing.md)를 따릅니다.

거래소공시 원문은 확장자가 `.xml`이지만 실제로는 HTML이고 `ACODE`가 없어, DART XML 파서를 재사용하지 않고 별도 모듈로 둡니다. 서식·라벨 식별 근거는 [`docs/architecture/exchange-parsing.md`](../docs/architecture/exchange-parsing.md)를 따릅니다. charset 선언(`euc-kr`)과 달리 실제 바이트는 UTF-8입니다.

## 공통 출력 모델 사용

네 공시 유형 파서는 모두 `chaltteok_etl.common.PreprocessResult`를 최종 출력 모델로 사용합니다. 모델 생성 시 문서 코드와 날짜, NFC 논리 경로, chunk·fact·relation 참조 관계를 검증합니다.

```python
from chaltteok_etl.common import PreprocessResult

result = PreprocessResult.model_validate(parsed_document)
json_line = result.model_dump_json()
```

여러 문서를 저장할 때는 `json_line` 하나를 한 줄로 기록한 UTF-8 JSON Lines 파일을 만듭니다. 원문 텍스트와 `raw_value`는 보존하고, 기업 식별값과 논리 경로만 NFC로 정규화합니다.

## 전량 전처리 실행

manifest를 훑어 공시 유형별 파서를 돌리고 JSONL 한 파일로 씁니다.

```bash
cd preprocess
python -m chaltteok_etl --corpus ../../corpus --out ../../work/preprocess-all.jsonl
```

지분공시만 돌릴 때는 `--doc-group holding`을 줍니다. 산출물은 `corpus/` 아래 두어도 되고,
그 경로는 Git에 올라가지 않습니다.

```bash
cd preprocess
python -m chaltteok_etl --corpus ../corpus --out ../corpus/preprocessed_holding.jsonl --doc-group holding
```

| 옵션 | 설명 |
| --- | --- |
| `--corpus` | 코퍼스 루트 경로 (필수) |
| `--out` | 출력 JSONL 경로 (필수) |
| `--doc-group` | 처리할 공시 유형. 여러 번 지정 가능. 생략하면 전부 |
| `--corp-name` | 처리할 법인명. 여러 번 지정 가능 |
| `--limit` | 처리할 문서 수 상한. 시험 실행용 |

파서가 등록되지 않은 공시 유형은 실패가 아니라 건너뜁니다. 파서를 추가하면
`chaltteok_etl/runner.py`의 `PARSERS`에 등록합니다.

실행이 끝나면 문서 수, 성공·실패·건너뜀 건수, 근거·사실·관계 수, 추출 상태별 건수를
요약해 출력합니다. **실패가 한 건이라도 있으면 종료 코드가 0이 아닙니다**
(`docs/data-model.md` §9). 부분 결과는 출력 파일에 남습니다.

### 적재까지

```bash
cd ../backend
./gradlew bootRun --args='--spring.profiles.active=local,load \
    --chaltteok.load.path=/절대/경로/preprocess-all.jsonl'
```

지분공시 JSONL은 같은 명령의 `--chaltteok.load.path`에
`corpus/preprocessed_holding.jsonl`의 절대 경로를 넣습니다. 유형만 지우고 다시 넣는 SQL과
확인 질문은 `backend/README.md`를 따릅니다.
