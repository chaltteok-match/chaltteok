# Backend 실행 안내

## 전처리 결과 JSONL 적재

Python 전처리가 만든 `PreprocessResult` JSONL을 PostgreSQL에 적재합니다.
`load` 프로필에서만 동작하며, 평가 API 서버로 뜨지 않고 적재만 하고 종료합니다.

PostgreSQL이 떠 있어야 하고, 테이블이 만들어져 있어야 합니다(`infra/README.md` 참고).
운영 마이그레이션 도입 전까지는 `application-local.yml`의 `spring.jpa.hibernate.ddl-auto: update`가
Entity 기준으로 스키마를 맞춥니다. 이 설정이 빠져 있으면 `relation "disclosure_document" does not exist`로 실패합니다.

`ddl-auto: update`는 이미 데이터가 있는 표에 `NOT NULL` 컬럼을 추가하지 못합니다.
`schema_version`이 올라가 필수 컬럼이 생기면 아래처럼 나오고, 그 뒤 모든 쿼리가 실패합니다.

```
ERROR: column "extraction_status" of relation "extracted_fact" contains null values
```

로컬 DB를 비우고 다시 만들면 됩니다. 개발용 데이터만 사라집니다.

```bash
cd infra
docker compose down -v && docker compose up -d
```

한 공시 유형만 지우고 다시 적재할 때는 **자식 표부터** 지워야 합니다. 순서를 지키지 않으면 외래 키 제약에 걸립니다.

```sql
delete from disclosure_relation_evidence_chunk;
delete from changed_field;
delete from disclosure_relation;
delete from extracted_fact where doc_id in (select doc_id from disclosure_document where doc_group = 'EXCHANGE');
delete from evidence_chunk   where doc_id in (select doc_id from disclosure_document where doc_group = 'EXCHANGE');
delete from disclosure_document where doc_group = 'EXCHANGE';
```

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
- 같은 `doc_id`가 한 파일에 여러 줄 있으면 마지막 줄이 남고, 요약에도 문서 1건으로 집계됩니다.

### 정정 관계가 있는 파일의 줄 순서

`relations`에 `original_doc_id`를 넣으려면 **그 원본 문서가 DB에 이미 있어야 합니다.**
없으면 그 줄은 `관계의 original_doc_id 문서가 없습니다`로 실패합니다.

같은 파일 안에서도 마찬가지라, 원본 문서가 뒷줄에 있으면 앞줄의 정정 문서가 실패합니다.
정정이 잦은 공시(주요사항보고서 등)를 넣을 때는 둘 중 하나로 맞춰 주세요.

- 원본 문서를 정정 문서보다 **앞줄**에 두거나,
- 원본을 먼저 적재한 뒤 정정 문서를 **다음 실행**으로 넣습니다.

실패해도 나머지 줄은 저장되고 실패한 줄 번호와 `doc_id`가 보고되므로,
순서를 고쳐 같은 파일을 다시 적재하면 됩니다(재적재해도 결과는 같습니다).

`load` 프로필을 켜지 않으면 적재가 시작되지 않습니다. 적재를 실행하는 `JsonlLoadRunner`가
`load` 프로필에서만 등록되기 때문입니다. 평가 API로 띄운 서버는 적재 동작을 하지 않습니다.

적재 기능 자체(`JsonlDisclosureLoader`)는 `chaltteok.disclosure.persistence.enabled=true`이면
프로필과 무관하게 빈으로 존재합니다. 다만 이를 호출하는 것은 위 러너뿐이므로,
`load` 없이 실행하면 아무것도 적재되지 않습니다.

## Handler 선택 순서

`AnswerHandlerSelector`는 `supports()`가 참인 **첫 번째** Handler를 쓰므로 순서가 답을 바꿉니다.
등록 순서에 좌우되지 않도록 `@Order`로 명시합니다.

| 순서 | Handler | 공시 유형 |
| --- | --- | --- |
| 10 | `ExchangeAnswerHandler` | 거래소공시 |
| 20 | `FundraisingAnswerHandler` | 주요사항보고서(유상증자결정) |
| 30 | `FinancialAnswerHandler` | 정기공시 |

서식을 특정하는 Handler를 앞에 둡니다. "공급계약금액이 최근 매출액 대비 몇 %인가" 같은 질문은
'매출액'을 품고 있어 정기공시 Handler도 처리하겠다고 답하기 때문입니다.

## HyperCLOVA X 클라이언트

`AnswerService`가 Handler 근거로 프롬프트를 만들고 `HcxClient`를 호출합니다. 클라이언트는 기본적으로 비활성화되어 있어 API 키가 없는 로컬 환경과 CI에서도 애플리케이션과 테스트를 실행할 수 있습니다. `HCX_ENABLED=true`이면 CLOVA Studio OpenAI 호환 API를 실제로 호출합니다.

유상증자 근거에는 목적별 금액만 있습니다. 목적별 질문에는 그 목적만 답하고, 「예정 자금 조달금액」처럼 총액을 묻는 질문에는 값이 있는 목적별 금액을 합산해 **자금 사용 목적 합계**로 둡니다. `타법인 증권취득자금을 제외한 총액`처럼 목적을 빼 달라고 하면 그 목적을 빼고 더합니다. `어떻게 바뀌었나`·`변경된 내역`처럼 바뀐 과정을 물으면 같은 증자 건의 원본·정정본을 날짜 순으로 붙이고, 금액이 그대로인 단계와 같은 날 원본은 접습니다. `정정한 유상증자`처럼 공시 한 건을 가리키면 그 날짜의 정정본만 고릅니다. 발행제비용 등으로 신주수×발행가액과 다를 수 있어 총 조달금액이라고 단정하지 않습니다. 질문에 `총액`·`총 조달금액`·`합계`가 있으면 합계로 보되, `시설자금 총액`처럼 목적 바로 뒤에 마커가 붙으면 그 목적만 봅니다.

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
| `HCX_RETRY_MAX_ATTEMPTS` | `3` | 한 질문에 부르는 최대 횟수(첫 호출 포함). `1`이면 재시도하지 않음 |
| `HCX_RETRY_INITIAL_INTERVAL_MILLIS` | `1000` | 첫 재시도까지 대기 |
| `HCX_RETRY_MULTIPLIER` | `2.0` | 대기 증가 배수 |
| `HCX_RETRY_MAX_INTERVAL_MILLIS` | `8000` | 한 번에 기다리는 최대 시간 |
| `HCX_RETRY_MAX_ELAPSED_SECONDS` | `120` | 재시도 대기에 쓸 수 있는 총 시간 |

429·타임아웃·5xx는 지수 백오프로 다시 겁니다. 인증·설정 오류와 빈 응답은 다시 걸어도 같은
결과라 즉시 실패합니다. 서버가 `Retry-After`를 주면 그 값을 따르되, 총 대기가
`HCX_RETRY_MAX_ELAPSED_SECONDS`를 넘길 상황이면 기다리지 않고 실패합니다. 평가 서버 응답
타임아웃 300초를 넘기지 않기 위해서입니다.

재시도를 다 써도 실패하면 지금처럼 503으로 나갑니다. 근거를 찾았는데 문장만 못 만든 것을
「답변 불가」로 내보내면 시스템 실패를 정상 답변으로 위장하는 것이라 그렇게 하지 않습니다.

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
./gradlew test --tests com.chaltteok.fundraising.FundraisingHcxLiveApiTest
```

### 출력 길이 측정 — `HCX_MAX_TOKENS` 정하기

CLOVA Studio TPM은 실제 소비가 아니라 `input token + maxTokens`로 계산됩니다. 답이 200토큰이어도
`maxTokens=1024`면 1024를 쓴 것으로 셉니다. 낮출수록 429에 덜 닿지만 너무 낮추면 답이 잘리므로,
바닥이 어딘지는 실제로 재야 압니다.

적재된 로컬 DB가 있어야 합니다(진짜 근거로 프롬프트를 만듭니다). 질문 10개를 한 번씩 부릅니다.

```bash
./gradlew test --tests com.chaltteok.hcx.HcxMaxTokensLiveTest --info
```

출력 토큰의 최대·평균과, 그때 건당 TPM이 표로 나옵니다. 모델을 바꾸면 다시 재야 합니다.

타임아웃·인증 실패는 `HcxClientException`으로 감싼 뒤 `/answer`에서 HTTP 503(`SERVICE_UNAVAILABLE`)으로 반환합니다. CI의 `FundraisingAnswerIT`는 HCX를 호출하지 않고 이 매핑과 대표 질문의 `retrieved_context`·`think_trace`를 확인합니다.

확인이 끝나면 현재 터미널에서 다음 환경변수를 제거합니다.

```bash
unset HCX_API_KEY
unset HCX_LIVE_TEST
unset HCX_ENABLED
```
