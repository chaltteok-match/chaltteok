# Backend 실행 안내

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
