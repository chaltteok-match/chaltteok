# Backend 실행 안내

## 평가 서버로 띄우기

`server` 프로필은 DB 접속 정보를 **환경변수로** 받습니다. 저장소를 clone한 곳에서 값만 채우면 뜹니다.

`local` 프로필로는 서버에서 뜨지 않습니다. 값이 `application-local.yml`에 있는데 그 파일은 커밋하지 않기 때문입니다.
프로필을 켜지 않으면 `application.yml`의 기본값 `local`로 떠서 아래처럼 죽습니다.

```
Failed to configure a DataSource: 'url' attribute is not specified
APPLICATION FAILED TO START
```

### 환경변수

| 이름 | 필수 | 기본값 | 설명 |
| --- | :---: | --- | --- |
| `SPRING_PROFILES_ACTIVE` | ○ | — | `server`로 둡니다 |
| `DB_URL` | ○ | — | `jdbc:postgresql://호스트:포트/chaltteok` |
| `DB_USERNAME` | ○ | — | DB 사용자 |
| `DB_PASSWORD` | ○ | — | DB 비밀번호 |
| `HCX_ENABLED` | ○ | `false` | **`true`가 아니면 근거를 찾은 질문이 전부 503입니다** |
| `HCX_API_KEY` | ○ | — | HyperCLOVA X API 키 |
| `SERVER_PORT` | | `8080` | 평가 규격은 80입니다. 앞단에서 돌리면 그대로 둬도 됩니다 |
| `DB_POOL_SIZE` | | `10` | 커넥션 풀 크기 |
| `LOG_LEVEL` | | `INFO` | `com.chaltteok` 로그 수준 |
| `HCX_MODEL` | | `HCX-005` | 그 밖의 `HCX_*`는 `application.yml` 참고 |

키는 파일에 적지 말고 환경변수로만 넘깁니다.

### 실행

```bash
cd backend
SPRING_PROFILES_ACTIVE=server \
DB_URL=jdbc:postgresql://localhost:5432/chaltteok \
DB_USERNAME=chaltteok \
DB_PASSWORD=... \
HCX_ENABLED=true \
HCX_API_KEY=... \
./gradlew bootRun
```

배포할 때는 jar로 띄웁니다.

```bash
./gradlew bootJar
java -jar build/libs/*.jar        # 환경변수는 위와 같습니다
```

### 뜬 뒤 확인

```bash
curl -s -o /dev/null -w '%{http_code}\n' http://localhost:8080/actuator/health
# 200

curl -s 'http://localhost:8080/answer?question_id=Q1&question=삼성전자의%202030년%20예상%20주가는?'
# 200, 5개 필드. 근거가 없는 질문이라 answer는 "확인할 수 없습니다"

curl -s -o /dev/null -w '%{http_code}\n' 'http://localhost:8080/answer?question_id=Q1'
# 400 (question 누락)
```

근거를 찾은 질문이 **503**이면 `HCX_ENABLED`나 `HCX_API_KEY`가 빠진 것입니다.

## 데이터를 처음부터 다시 만들기

DB가 빈 서버에서는 전처리 → 적재를 먼저 해야 합니다. 코퍼스 4,204건 기준 전처리 35초, 적재 1분 30초 걸립니다.

```bash
# 1) 전처리 — 4개 공시 유형 전부
cd preprocess
python -m pip install -r requirements.txt
python -m pip install -e .
python -m chaltteok_etl --corpus ../../corpus --out ../../work/preprocess-all.jsonl

# 2) 적재 — 웹 서버로 뜨지 않고 적재만 하고 끝납니다
cd ../backend
SPRING_PROFILES_ACTIVE=server,load \
DB_URL=... DB_USERNAME=... DB_PASSWORD=... \
./gradlew bootRun --args='--chaltteok.load.path=/절대/경로/work/preprocess-all.jsonl'
```

`work/`는 저장소 밖입니다. 산출물(JSONL 43MB)은 커밋하지 않습니다.

### 건수 (2026-09-05 기준)

| 공시 유형 | manifest | 적재 | 비고 |
| --- | ---: | ---: | --- |
| 거래소공시 | 1,469 | **1,469** | |
| 지분공시 | 1,083 | **1,083** | |
| 정기공시 | 1,054 | **1,048** | 3건 실패, 3건 값 없음 |
| 주요사항보고서 | 598 | **172** | 파서가 없는 서식 429건은 건너뜁니다 |
| 합계 | 4,204 | **3,772** | 근거 35,674 · 사실 55,799 · 관계 298 |

**전처리는 실패가 한 건이라도 있으면 종료 코드 1입니다**(`docs/data-model.md` §9).
현재 3건이 실패하므로 정상 실행에서도 1이 나옵니다. 산출물은 그대로 쓸 수 있습니다.

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

지분공시만 다시 넣을 때도 같습니다. `doc_group`만 `HOLDING`으로 바꿉니다.
테스트 픽스처를 먼저 넣었다면 실코퍼스 JSONL을 upsert해도 다른 `doc_id`는 남습니다.
다시 넣기 전에 HOLDING 행을 지우는 편이 낫습니다.

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

지분공시(대량보유상황보고서)는 파서가 `relations`를 비웁니다. 이력은 적재된 문서와
`OwnershipAnswerHandler`가 잇고, JSONL 줄 순서 때문에 적재가 실패하지는 않습니다.
원문은 `corpus/raw/holding`에 있고, 산출물은 Git에 올리지 않습니다(`corpus/`는 gitignore).

```bash
cd preprocess
source .venv/bin/activate
python -m chaltteok_etl --corpus ../corpus --out ../corpus/preprocessed_holding.jsonl --doc-group holding
```

전처리가 실패 0건인지 확인한 뒤 적재합니다. 경로를 이 저장소에 맞게 바꿉니다.

```bash
cd backend
./gradlew bootRun --args='--spring.profiles.active=local,load --chaltteok.load.path=/절대/경로/corpus/preprocessed_holding.jsonl'
```

적재가 끝나면 `disclosure_document`에서 `doc_group = HOLDING` 건수가 전처리 성공 건수와
같아야 합니다. 로컬에서 한 번 돌린 결과는 문서 1,083건, 근거·사실 각 5,415건, 관계 0건,
추출 상태 `ok` 5,415건이었습니다. 픽스처가 아닌 공시로 단건을 보려면 예를 들어
`삼성전자가 2023년 1월 20일 공시한 대량보유상황보고서의 지분율은?`을 씁니다.

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
| 21 | `BondIssuanceAnswerHandler` | 주요사항보고서(전환사채권·교환사채권 발행결정) |
| 22 | `TreasuryStockAnswerHandler` | 주요사항보고서(자기주식취득결정) |
| 25 | `OwnershipAnswerHandler` | 지분공시(대량보유상황보고서) |
| 30 | `FinancialAnswerHandler` | 정기공시 |

서식을 특정하는 Handler를 앞에 둡니다. "공급계약금액이 최근 매출액 대비 몇 %인가" 같은 질문은
'매출액'을 품고 있어 정기공시 Handler도 처리하겠다고 답하기 때문입니다.

### 순서만으로는 모자랍니다

질문이 유형 **두 개**를 가리키면 더 구체적인 쪽이 아니라 순서가 앞선 쪽이 가져갑니다.

```
한화에어로스페이스가 유상증자로 조달한 자금 중 시설투자에 쓰는 금액은?
  → 거래소공시가 '시설투자'를 주제 키워드로 들고 있고 순서가 앞서서 가져갔습니다
```

그래서 **유형을 못 박는 표현**이 질문에 있으면 그 유형이 아닌 Handler가 물러납니다.
표현은 `DisclosureTypeMarkers` 한 곳에 모아 둡니다.

| 유형 | 못 박는 표현 |
| --- | --- |
| 주요사항보고서(유상증자결정) | `유상증자` |
| 주요사항보고서(자기주식취득결정) | `자기주식` |
| 주요사항보고서(전환·교환사채) | `전환사채` · `교환사채` |
| 지분공시 | `대량보유` · `대량 보유` · `지분공시` · `보유상황` |

정기공시 항목(`매출액`·`영업이익`)은 표지가 **아닙니다.** 여러 유형의 문서에 함께 나오는 말이라
유형을 못 박지 못합니다. "계약금액이 최근 매출액의 몇 퍼센트인가"는 거래소공시가 답하는 것이 맞습니다.

기업을 유형별로 좁히는 것도 함께 막아 줍니다. `CorpIdentityRepository`가 Handler마다 자기가
답할 수 있는 유형의 기업만 넘기므로, 그 유형 문서가 없는 기업이면 `supports()`가 애초에 참이
되지 않습니다. 다만 **한 기업이 두 유형 문서를 다 가지면 이것으로는 막히지 않습니다.**
`AnswerHandlerRoutingTest`가 그 경우를 따로 확인합니다.

## 정기공시 기업 간 비교

`삼성전자와 SK하이닉스의 2024년 연결 매출액을 비교하면?`처럼 기업이 둘 이상인 질문은 **기업마다
근거를 붙여** 답합니다. 한 기업 수치만 근거로 내보내면 모델이 나머지를 지어내 비교 문장을 만듭니다.

다음 세 경우는 답하지 않습니다.

| 경우 | 예 | 이유 |
| --- | --- | --- |
| 한 기업이라도 수치가 없음 | `삼성전자와 삼성생명의 2024년 매출액 비교` | 어느 기업이 왜 빠졌는지 밝히고 답변 불가로 둡니다 |
| 코퍼스 전체 순위 | `2024년 매출액이 가장 큰 기업은?` | 70개사를 훑어야 하는데 적재되지 않은 기업이 빠진 채 「가장 크다」고 답하게 됩니다 |
| 상대 기업을 못 알아봄 | `삼성전자와 애플의 2024년 매출액 비교` | 한 기업 수치로 비교 답을 만들지 않습니다 |

`전년과 비교`·`2023년과 비교`는 기간을 잇는 말이라 기존 연도 간 비교 경로가 처리합니다.

전체 순위 질문은 기업 이름이 없어도 이 Handler가 맡습니다. 아무도 나서지 않으면 왜 답할 수
없는지 대신 기본 문구가 나갑니다. 정기공시 항목(`매출액`·`영업이익` 등)이 있을 때만 맡습니다.

로컬 DB 70개사 기준입니다. 단일 조회 건수는 이 변경 전후가 같습니다.

```
삼성전자와 나머지 69개사 2024년 연결 매출액 비교   답변 63 / 불가 6
```

## HyperCLOVA X 클라이언트

`AnswerService`가 Handler 근거로 프롬프트를 만들고 `HcxClient`를 호출합니다. 클라이언트는 기본적으로 비활성화되어 있어 API 키가 없는 로컬 환경과 CI에서도 애플리케이션과 테스트를 실행할 수 있습니다. `HCX_ENABLED=true`이면 CLOVA Studio OpenAI 호환 API를 실제로 호출합니다.

유상증자 근거에는 목적별 금액과 신주 수·액면가·발행가가 있습니다. 목적별 질문에는 그 목적만 답하고, 「예정 자금 조달금액」처럼 총액을 묻는 질문에는 값이 있는 목적별 금액을 합산해 **자금 사용 목적 합계**로 둡니다. `타법인 증권취득자금을 제외한 총액`처럼 목적을 빼 달라고 하면 그 목적을 빼고 더합니다. 포함과 제외가 한 문장에 있으면 제외에 붙은 목적만 뺍니다. `어떻게 바뀌었나`·`변경된 내역`처럼 바뀐 과정을 물으면 같은 증자 건의 원본·정정본을 날짜 순으로 붙이고, 금액이 그대로인 단계와 같은 날 원본은 접습니다. `정정한 유상증자`처럼 공시 한 건을 가리키면 그 날짜의 정정본만 고릅니다. 발행제비용 등으로 신주수×발행가액과 다를 수 있어 총 조달금액이라고 단정하지 않습니다. 질문에 `총액`·`총 조달금액`·`합계`가 있으면 합계로 보되, `시설자금 총액`처럼 목적 바로 뒤에 마커가 붙으면 그 목적만 봅니다. 「발행가」는 확정발행가가 없으면 예정발행가를 씁니다.

자기주식취득결정은 취득예정 보통주식 수와 취득예정금액을 공시일로 특정해 답합니다. 처분·신탁 서식은 다루지 않습니다.

전환사채권·교환사채권 발행결정은 권면총액·표면이자율·만기일·전환/교환가액·대상 주식 수를 공시일로 특정해 답합니다. 신주인수권부사채와 주요사항보고서 시설투자 서식은 코퍼스에 없어 다루지 않습니다. 거래소 `신규시설투자등` 질문은 받지 않습니다.

대량보유상황보고서는 보고자·보유 주식 수·지분율을 공시일로 특정해 답합니다. `어떻게 바뀌었나`처럼 과정을 물으면 같은 보고자의 신규·변동·변경을 날짜 순으로 붙이고, 수량이 그대로인 단계와 같은 날 원본은 접습니다. 접고 난 단계가 많으면 처음·마지막과 변동 폭이 큰 보고만 남깁니다. `정정한 대량보유`처럼 공시 한 건을 가리키면 그 날짜의 정정본만 고릅니다.

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
| `HCX_MAX_TOKENS` | `768` | 최대 출력 토큰 수. 아래 「출력 길이 측정」 참조 |
| `HCX_TIMEOUT_SECONDS` | `30` | 연결·응답 제한 시간 |
| `HCX_RETRY_MAX_ATTEMPTS` | `3` | 한 질문에 부르는 최대 횟수(첫 호출 포함). `1`이면 재시도하지 않음 |
| `HCX_RETRY_INITIAL_INTERVAL_MILLIS` | `1000` | 첫 재시도까지 대기 |
| `HCX_RETRY_MULTIPLIER` | `2.0` | 대기 증가 배수 |
| `HCX_RETRY_MAX_INTERVAL_MILLIS` | `8000` | 한 번에 기다리는 최대 시간 |
| `HCX_RETRY_MAX_ELAPSED_SECONDS` | `120` | 재시도 대기에 쓸 수 있는 총 시간 |

429·타임아웃·5xx는 지수 백오프로 다시 겁니다. 인증·설정 오류, 429가 아닌 4xx, 빈 응답은
다시 걸어도 같은 결과라 즉시 실패합니다. 서버가 `Retry-After`를 주면 그 값을 따르되, 총 대기가
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

#### 2026-09-04 측정 결과 (HCX-005, 질문 11개)

| | 입력 | 출력 |
| --- | ---: | ---: |
| 최대 | 1,696 | **377** |
| 평균 | 741 | 143 |

최댓값은 둘 다 **현대건설 `20230626800002`** 입니다. 코퍼스에서 정정 사슬이 가장 길어
(14회 정정, 근거 15건) 답이 15단계를 나열합니다. 이보다 긴 사슬은 없습니다.

같은 질문을 두 번 불렀을 때 출력이 최대 **1.6배**까지 흔들렸습니다(192 → 119, 64 → 115).
잘린 답은 틀린 답이므로 최댓값의 두 배인 **768**로 둡니다.

| `maxTokens` | 최악 건당 TPM | 평균 건당 TPM |
| ---: | ---: | ---: |
| 1024 (이전) | 2,720 | 1,765 |
| **768** | **2,464** | **1,509** |
| 512 | 2,208 | 1,253 |

512는 측정 최댓값의 1.36배뿐이라 위 흔들림을 감당하지 못합니다.
지분공시·자기주식 답변이 붙으면 다시 재는 것이 좋습니다.

타임아웃·인증 실패는 `HcxClientException`으로 감싼 뒤 `/answer`에서 HTTP 503(`SERVICE_UNAVAILABLE`)으로 반환합니다. CI의 `FundraisingAnswerIT`는 HCX를 호출하지 않고 이 매핑과 대표 질문의 `retrieved_context`·`think_trace`를 확인합니다.

확인이 끝나면 현재 터미널에서 다음 환경변수를 제거합니다.

```bash
unset HCX_API_KEY
unset HCX_LIVE_TEST
unset HCX_ENABLED
```
