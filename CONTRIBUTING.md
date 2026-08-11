# Contributing

## 작업 흐름

```text
Issue 생성
→ 최신 main에서 작업 브랜치 생성
→ 구현 및 테스트
→ Pull Request 생성
→ 상대방 실행·리뷰
→ 리뷰 의견 반영 및 승인
→ PR 작성자가 병합
```

## 이름 규칙

| 대상 | 형식 | 예시 |
| --- | --- | --- |
| Issue | `[type] 내용` | `[docs] API 요청·응답 명세 초안 작성` |
| Branch | `type/영문-작업명` | `docs/api-contract` |
| PR | `type: 내용` | `docs: API 요청·응답 명세 초안 작성` |
| Commit | `type: 내용` | `feat: Python 전처리 공통 모델 구현` |

사용하는 type은 다음과 같다.

- `feat`: 기능 추가
- `fix`: 오류 수정
- `docs`: 문서 작성·수정
- `chore`: 설정 및 기타 작업
- `test`: 테스트 추가·수정
- `refactor`: 기능 변경 없는 코드 구조 개선

Issue, Branch, PR, Commit에는 위 목록의 type만 사용한다.

## 작업 및 병합 규칙

- `main`에 직접 push하지 않는다.
- 최신 `main`에서 목적별 브랜치를 생성한다.
- 한 브랜치에는 하나의 목적만 포함한다.
- 모든 변경은 PR을 통해 병합한다.
- 상대방의 승인을 받고 리뷰 대화를 해결한 뒤 병합한다.
- PR 작성자가 리뷰 반영과 최종 상태를 확인하고 직접 병합한다.
- PR 병합 후 원격 작업 브랜치는 저장소의 `Automatically delete head branches` 설정으로 자동 삭제한다.
- 리뷰어는 가능하면 변경 사항을 로컬에서 실행한다.
- 작업은 실행, 테스트, 환경변수·문서 반영까지 완료해야 종료한다.
