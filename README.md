# Chaltteok

근거 기반 공시 검색·분석 AI Agent 프로젝트입니다.

제공된 공시 데이터에서 질문과 관련된 문서를 찾고, 정보를 추출·비교·계산하거나 정정·후속 공시를 추적한 뒤 HyperCLOVA X를 통해 근거와 정보 한계를 포함한 답변을 제공합니다.

## 평가용 API Endpoint

| 항목 | 값 |
| --- | --- |
| Endpoint URL | *(서버 배포 후 기입)* |
| 경로 | `GET /answer?question_id={id}&question={질문}` |
| 응답 | `application/json`, 5개 필드 모두 문자열 |
| 인증 | 없음 |

서버에 올리는 절차는 [`docs/deploy.md`](docs/deploy.md), 실행 방법과 환경변수는 [`backend/README.md`](backend/README.md#평가-서버로-띄우기)에 있습니다.

## MVP 목표

다음 네 가지 평가 유형을 하나의 `/answer` API로 처리합니다.

- 단일 조회: 특정 기업·기간·공시 유형의 정보 추출
- 비교·계산: 기업·연도별 수치 비교와 증감률 등 계산
- 이력 추적: 최초 공시와 정정·변경·해지 공시 연결
- 답변 불가: 근거가 부족하거나 제공 데이터 범위를 벗어난 질문 거절

모든 답변에는 사용한 수치와 단위, 공시명과 공시일, 검색 근거, 필요한 경우 정보 한계를 포함합니다.

## 데이터 및 모델 제약

- 프로젝트에서 제공된 공시 데이터만 사용합니다.
- 외부 뉴스·리포트·위키 및 OpenDART 실시간 호출은 사용하지 않습니다.
- 답변 생성 LLM은 HyperCLOVA X만 사용합니다.
- 공시에서 확인할 수 없는 내용은 추측하지 않습니다.
- 대용량 원본 데이터와 생성된 인덱스는 Git에 커밋하지 않습니다.

## 기술 스택

| 영역 | 기술 |
| --- | --- |
| Backend | Java 21, Spring Boot, Spring AI |
| Preprocessing | Python 3.12 |
| Database | PostgreSQL, Spring Data JPA |
| Search | 메타데이터·키워드 검색, 필요 시 pgvector |
| Test | JUnit 5, Spring Boot Test, pytest |
| Infrastructure | Docker, Docker Compose |
| Collaboration | GitHub Issues, Pull Requests, GitHub Actions |

Spring AI와 HyperCLOVA X의 호환 문제가 생기면 전체 구조는 유지하고 LLM 클라이언트만 Spring `RestClient` 구현으로 교체합니다.

## 처리 흐름

```text
제공 공시 데이터
→ Python 전처리 및 정규화
→ PostgreSQL 저장
→ Spring Boot 검색·비교·계산·이력 판단
→ HyperCLOVA X 답변 생성
→ 근거가 포함된 /answer 응답
```

## 모노레포 구성

```text
chaltteok/
├── backend/          # Java + Spring Boot 평가 API
├── preprocess/       # Python 공시 전처리
├── frontend/         # MVP 이후 사용자 서비스
├── infra/            # Docker Compose 및 DB 설정
├── docs/             # 계획, 아키텍처, API, 제안서
├── sample-data/      # 테스트에 필요한 최소 샘플
└── .github/          # Issue 및 PR 템플릿
```

각 프로젝트는 독립적으로 빌드·테스트하고, 전체 실행환경은 Docker Compose로 연결합니다. `frontend/`는 확장 경계만 마련하고 MVP 기간에는 구현하지 않습니다.

## 협업 규칙

```text
Issue 또는 문서로 작업 공유
→ 기능 브랜치에서 구현
→ Pull Request 생성
→ 상대방 실행·리뷰
→ 승인 후 main 병합
```

- `main`에 직접 푸시하지 않습니다.
- 한 브랜치에는 하나의 목적만 포함합니다.
- 공통 모델과 API 계약은 문서로 먼저 합의합니다.
- PR은 관련 테스트와 필요한 문서 변경을 포함합니다.
- 리뷰어는 가능하면 변경 사항을 로컬에서 실행합니다.

Issue·브랜치·PR 제목과 세부 리뷰·병합 방식은 [CONTRIBUTING.md](CONTRIBUTING.md)를 따릅니다.

## 문서

- [프로젝트 계획](docs/project-plan.md): 역할 분담, 페이즈별 작업 목록, 세부 작업 규칙 및 확장 방향
- [평가 요구사항](docs/evaluation-requirements.md): 주최측이 정한 제출·평가·비용 조건과 아직 답이 없는 항목
- [협업 가이드](CONTRIBUTING.md): Issue·브랜치·PR 이름과 리뷰·병합 규칙
