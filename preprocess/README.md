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
