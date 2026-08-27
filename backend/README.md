# Backend 실행 안내

## 전처리 결과 JSONL 적재

Python 전처리가 만든 `PreprocessResult` JSONL을 PostgreSQL에 적재합니다.
`load` 프로필에서만 동작하며, 평가 API 서버로 뜨지 않고 적재만 하고 종료합니다.

PostgreSQL이 떠 있어야 하고, 테이블이 만들어져 있어야 합니다(`infra/README.md` 참고).
운영 마이그레이션 도입 전까지는 `application-local.yml`의 `spring.jpa.hibernate.ddl-auto: update`가
Entity 기준으로 스키마를 맞춥니다. 이 설정이 빠져 있으면 `relation "disclosure_document" does not exist`로 실패합니다.

```bash
cd backend
./gradlew bootRun --args='--spring.profiles.active=local,load --chaltteok.load.path=/절대/경로/result.jsonl'
```

| 설정 | 설명 |
| --- | --- |
| `chaltteok.load.path` | 적재할 JSONL 파일 경로. 1줄 = `PreprocessResult` 1건 |

동작 규칙은 다음과 같습니다.

- 한 줄이 실패해도 나머지를 계속 적재하고, 실패한 **줄 번호와 `doc_id`**를 모아 보고합니다.
- 적재가 끝나면 총 건수, 성공·실패 건수, 근거·사실·관계 수를 요약해 출력합니다.
- **실패가 한 건이라도 있으면 종료 코드가 0이 아닙니다**(`docs/data-model.md` §9).
- 같은 `doc_id`를 다시 적재하면 기존 행을 교체합니다(upsert). 같은 파일을 두 번 적재해도 결과가 같습니다.
- 계약에 없는 필드가 섞여 있으면 조용히 무시하지 않고 그 줄을 실패로 처리합니다.

`load` 프로필이 아니면 적재 러너와 로더 빈이 등록되지 않아 `/answer` 동작에 영향을 주지 않습니다.

## HyperCLOVA X 클라이언트

HyperCLOVA X 클라이언트는 기본적으로 비활성화되어 있어 API 키가 없는 로컬 환경과 CI에서도 애플리케이션과 테스트를 실행할 수 있습니다.

실제 호출에는 CLOVA Studio에서 발급한 테스트 또는 서비스 API 키가 필요합니다. 키는 설정 파일에 저장하지 말고 환경변수로 전달합니다.

```bash
export HCX_ENABLED=true
export HCX_MODEL="HCX-005"
read -s HCX_API_KEY
export HCX_API_KEY
```

`read -s HCX_API_KEY` 실행 후 API 키 값만 붙여넣고 Enter를 누릅니다. 입력한 키는 화면과 터미널 명령 기록에 표시되지 않습니다. `Bearer` 접두사는 클라이언트가 자동으로 추가하므로 키 값만 입력합니다.

선택 설정의 기본값은 다음과 같습니다.

| 환경변수 | 기본값 | 설명 |
| --- | --- | --- |
| `HCX_BASE_URL` | `https://clovastudio.stream.ntruss.com/v1/openai` | OpenAI 호환 API 기준 URL |
| `HCX_COMPLETIONS_PATH` | `/chat/completions` | Chat Completions 경로 |
| `HCX_MODEL` | `HCX-005` | 호출할 모델 |
| `HCX_TEMPERATURE` | `0.1` | 생성 다양성 |
| `HCX_MAX_TOKENS` | `1024` | 최대 출력 토큰 수 |
| `HCX_TIMEOUT_SECONDS` | `30` | 연결·응답 제한 시간 |

### 자동 테스트

Mock 기반 테스트는 실제 API를 호출하지 않으며 API 키 없이 실행됩니다.

```bash
./gradlew test
```

### 실제 호출 확인

실제 호출 테스트는 비용이 발생할 수 있으므로 기본 테스트에서는 비활성화되어 있습니다. 환경변수를 설정한 뒤 아래처럼 명시적으로 실행합니다.

```bash
export HCX_ENABLED=true
export HCX_LIVE_TEST=true
read -s HCX_API_KEY
export HCX_API_KEY
./gradlew test --tests com.chaltteok.hcx.HcxLiveApiTest
```

확인이 끝나면 현재 터미널에서 다음 환경변수를 제거합니다.

```bash
unset HCX_API_KEY
unset HCX_LIVE_TEST
unset HCX_ENABLED
```

현재 범위는 공통 HCX 클라이언트까지이며, `/answer`와의 연결은 `AnswerService` 구현에서 진행합니다.
