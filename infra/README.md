# Infra — PostgreSQL (Docker Compose)

로컬에서 PostgreSQL을 실행하고 Spring Boot와 연결하는 방법입니다.

## 설정 구조

| 파일 | Git | 역할 |
| --- | --- | --- |
| `infra/.env` | 안 올림 | Docker가 DB를 만들 때 읽는 `POSTGRES_*` |
| `infra/.env.example` | 올림 | `.env` 템플릿 |
| `backend/.../application.yml` | 올림 | 공통 설정 + `spring.profiles.active: local` |
| `backend/.../application-local.yml` | 안 올림 | 로컬 DB 접속 정보 (url/username/password) |
| `backend/.../application-local.example.yml` | 올림 | `application-local.yml` 템플릿 |

로컬 개발 흐름:

1. Docker로 PostgreSQL 기동 (`infra/.env`)
2. Spring DB 정보는 `application-local.yml`에 작성 (`.env`와 동일 값)
3. `./gradlew bootRun` — `application.yml`에 `local` 프로필이 이미 켜져 있음

`infra/.env`의 `POSTGRES_*`는 Docker 전용입니다. Spring이 자동으로 읽지 않습니다.

## 1. `.env`와 `application-local.yml` 생성

### Docker용 `.env`

```bash
cd infra
cp .env.example .env
```

`.env` 값을 로컬에 맞게 수정합니다.

```text
POSTGRES_DB=chaltteok
POSTGRES_USER=chaltteok
POSTGRES_PASSWORD=change-me
```

### Spring용 `application-local.yml`

```bash
cd backend/src/main/resources
cp application-local.example.yml application-local.yml
```

`application-local.yml`의 DB 정보를 `.env`와 **같은 값**으로 맞춥니다.

```yaml
spring:
  datasource:
    url: jdbc:postgresql://localhost:5432/chaltteok   # POSTGRES_DB와 동일
    username: chaltteok                               # POSTGRES_USER와 동일
    password: change-me                               # POSTGRES_PASSWORD와 동일
```

> `.env`와 `application-local.yml`은 Git에 커밋하지 않습니다.

## 2. 두 설정에 동일한 DB 정보 입력

| 항목 | `infra/.env` (Docker) | `application-local.yml` (Spring) |
| --- | --- | --- |
| DB 이름 | `POSTGRES_DB` | JDBC URL의 DB명 |
| 유저 | `POSTGRES_USER` | `spring.datasource.username` |
| 비밀번호 | `POSTGRES_PASSWORD` | `spring.datasource.password` |

값이 하나라도 다르면 Spring 연결에 실패합니다.

## 3. PostgreSQL 실행·종료·상태 확인

```bash
cd infra

# 실행
docker compose up -d

# 상태
docker compose ps
docker compose exec db pg_isready -U "$POSTGRES_USER" -d "$POSTGRES_DB"

# 로그
docker compose logs -f db

# 종료 (데이터 유지)
docker compose down

# 종료 + 볼륨 삭제 (DB 데이터까지 삭제)
docker compose down -v
```

`.env`를 읽지 않는 셸에서는 유저/DB명을 직접 넣어도 됩니다.

```bash
docker compose exec db pg_isready -U chaltteok -d chaltteok
```

## 4. Spring Boot 실행

`application.yml`에 다음이 설정되어 있어, 실행 시마다 프로필을 넘길 필요가 없습니다.

```yaml
spring:
  profiles:
    active: local
```

PostgreSQL이 떠 있는 상태에서:

```bash
cd backend
./gradlew bootRun
```

`local` 프로필이 활성화되면 `application.yml`과 함께 `application-local.yml`이 로드됩니다.  
`application-local.yml`이 없거나 `.env`와 DB 정보가 다르면 기동에 실패합니다.

다른 프로필로 실행하려면 환경변수로 덮어씁니다.

```bash
SPRING_PROFILES_ACTIVE=prod ./gradlew bootRun
```

### (선택) 환경변수로 datasource 주입

`application-local.yml` 대신 Spring 표준 환경변수를 쓸 수도 있습니다.

```bash
cd backend
export SPRING_DATASOURCE_URL=jdbc:postgresql://localhost:5432/chaltteok
export SPRING_DATASOURCE_USERNAME=chaltteok
export SPRING_DATASOURCE_PASSWORD=change-me
./gradlew bootRun
```

## 5. `/actuator/health`로 연결 확인

앱이 `http://localhost:8080`에서 기동된 뒤:

```bash
curl http://localhost:8080/actuator/health
```

정상 연결 시 대략 다음처럼 보입니다.

```json
{
  "status": "UP"
}
```

DB까지 상세히 보려면 (설정에 따라):

```bash
curl http://localhost:8080/actuator/health/db
```

`status`가 `UP`이면 Spring ↔ PostgreSQL 연결이 된 것입니다.  
`DOWN`이거나 기동 실패 시 다음을 확인하세요.

- `docker compose ps`로 DB가 떠 있는지
- `application-local.yml`이 있는지
- `.env`와 `application-local.yml` 값이 같은지

## 참고: 테스트

`./gradlew test`는 DB 없이 통과하도록 `backend/src/test/resources/application.yml`에서  
DataSource·JPA 자동설정을 끕니다.  
실제 DB 연결 확인은 위 `bootRun` + `/actuator/health` 절차를 사용합니다.
