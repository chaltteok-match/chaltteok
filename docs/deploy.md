# 평가 서버 배포 절차

평가 기간(09-07 10:00 ~ 09-11 15:00) 동안 주최측이 우리 서버의 `/answer`를 호출합니다.
그 사이 서버가 계속 떠 있어야 하므로, 개인 PC가 아니라 클라우드 장비에 올립니다.

로컬 개발 환경은 [`infra/README.md`](../infra/README.md)를 보세요. 이 문서는 서버 전용입니다.

## 지금 범위 — 대회 제출용 최소 구성

장비 한 대에 애플리케이션과 PostgreSQL을 컨테이너로 함께 올립니다. 일부러 넣지 않은 것이 있습니다.

| 넣지 않은 것 | 이유 |
| --- | --- |
| HTTPS·인증서 | 주최측이 HTTP를 허용합니다 |
| DB를 별도 서비스로(Cloud SQL) | 같은 장비에 둡니다 |
| 자동 배포(CI/CD) | 손으로 한 번 올립니다 |
| 모니터링·알람·로그 수집 | 붙이지 않습니다 |
| 이중화·무중단 배포 | 장비 한 대입니다 |

반대로 **간단하다고 뺄 수 없는 것 셋**이 있습니다. 평가는 4일 연속이고 사람이 붙어 있을 수 없습니다.

- **고정 IP** — 재부팅으로 주소가 바뀌면 제출한 URL이 죽고 남은 기간이 통째로 날아갑니다
- **자동 재시작**(`restart: unless-stopped`) — 죽은 것을 아무도 못 보면 그 시간이 전부 손실입니다
- **2GB 메모리** — 부족해서 죽는 것도 같은 결과입니다

### 나중에 운영 방식으로 옮길 때

이 구성은 버리고 다시 만드는 게 아니라 바깥 껍질만 바꿉니다.

| | 그대로 씀 | 갈아끼움 |
| --- | --- | --- |
| 설정 | 환경변수 주입 방식 | 값의 출처가 `.env` → Secret Manager |
| 실행 | `Dockerfile`(같은 이미지) | compose → Kubernetes·Cloud Run |
| DB | JDBC 접속 | 같은 장비 → Cloud SQL (`DB_URL`만 교체) |
| 상태 확인 | `/actuator/health` | 사람이 확인 → 로드밸런서가 확인 |
| 배포 | — | 수동 → GitHub Actions |

DB 주소나 키를 코드·설정 파일에 적지 않고 환경변수로 뺀 것이 이 이동을 가능하게 합니다.
애플리케이션 코드는 어느 단계에서도 바뀌지 않습니다.

## 1. 장비

주최측 발신 IP `34.64.175.191`은 Google LLC 대역(GCP 서울, `asia-northeast3`)입니다.
같은 리전에 두면 경로가 가장 짧습니다.

| 항목 | 값 | 근거 |
| --- | --- | --- |
| 머신 | e2-small (2 vCPU, 2GB) | 아래 실측 |
| OS | Ubuntu 24.04 LTS | |
| 디스크 | 20GB | |
| 외부 IP | **고정(static) 예약** | 재부팅으로 주소가 바뀌면 제출한 URL이 죽습니다 |
| 방화벽 인바운드 | TCP `80` ← `34.64.175.191/32` | 실제 호출과 헬스체크가 같은 IP에서 옵니다 |
| 방화벽 아웃바운드 | 기본 허용 | HyperCLOVA X 호출에 필요합니다 |

메모리는 실측값입니다. `/answer`를 20번 호출한 뒤 쟀습니다.

```
JVM          208MB (유휴) → 295MB (요청 20건)
PostgreSQL   170MB
```

1GB 장비는 빠듯합니다. 평가 중 메모리 부족으로 죽으면 되돌릴 방법이 없습니다.

컨테이너에 **메모리 상한을 걸어 둡니다**(`APP_MEM_LIMIT` 1g / `DB_MEM_LIMIT` 512m).
걸지 않으면 JVM이 장비 전체 메모리를 기준으로 힙을 잡습니다. 2GB 장비에서 힙만 1.2GB를
잡으면 PostgreSQL과 OS가 쓸 자리가 남지 않습니다. 상한을 건 뒤 컨테이너로 실제 측정한 값은
아래와 같습니다.

```
app   361MB / 1GB     (요청 50건 뒤)
db     37MB / 512MB
```

## 2. 서버 준비

```bash
# Docker
sudo apt-get update && sudo apt-get install -y docker.io docker-compose-v2 git
sudo usermod -aG docker $USER && newgrp docker

# 시각 동기화 — 주최측 server_time과 크게 다르면 안 됩니다
timedatectl

# 아웃바운드 확인 — 공지의 헬스체크
curl -s http://34.64.175.191/health
# {"status":"ok","server_time":"...","notice_version":"..."}
```

`timeout`이나 `connection refused`가 나오면 방화벽·프록시를 먼저 확인합니다.

## 3. 코드와 설정

```bash
git clone <저장소 주소> && cd chaltteok/infra
cp .env.server.example .env.server
```

`.env.server`에 값을 채웁니다. **이 파일은 커밋되지 않습니다.**

| 이름 | 설명 |
| --- | --- |
| `POSTGRES_PASSWORD` | DB 비밀번호. 아무 값이나 정해서 씁니다 |
| `HCX_API_KEY` | HyperCLOVA X API 키. **없으면 근거를 찾은 질문이 전부 503입니다** |
| `PUBLIC_PORT` | 주최측이 호출할 포트. 평가 규격은 `80` |

## 4. 데이터 넣기

서버에서 코퍼스를 받아 전처리를 돌릴 필요가 없습니다. 로컬 DB를 덤프해 옮깁니다(압축 1.9MB).

```bash
# 로컬에서
docker exec chaltteok-db pg_dump -U chaltteok -Fc chaltteok > chaltteok.dump
scp chaltteok.dump 서버주소:~/

# 서버에서 — DB만 먼저 띄우고 복원
cd chaltteok/infra
docker compose -f docker-compose.server.yml --env-file .env.server up -d db
docker compose -f docker-compose.server.yml --env-file .env.server exec -T db \
    pg_restore -U chaltteok -d chaltteok --clean --if-exists < ~/chaltteok.dump
```

전처리부터 다시 만드는 절차는 [`backend/README.md`](../backend/README.md#데이터를-처음부터-다시-만들기)에 있습니다. 재현이 필요할 때만 쓰면 됩니다.

## 5. 실행

```bash
docker compose -f docker-compose.server.yml --env-file .env.server up -d --build
```

첫 빌드는 의존성을 모두 받느라 10분쯤 걸립니다. 두 번째부터는 짧습니다.

## 6. 확인

```bash
# 서버 안에서
curl -s -o /dev/null -w '%{http_code}\n' http://localhost/actuator/health     # 200
curl -s 'http://localhost/answer?question_id=Q1&question=삼성전자의%202030년%20예상%20주가는?'
# 200, 5개 필드. 근거가 없는 질문이라 answer는 "확인할 수 없습니다"

# 밖에서 — 여기서 응답이 와야 평가가 됩니다
curl -s -o /dev/null -w '%{http_code}\n' http://<외부IP>/actuator/health
```

근거를 찾은 질문이 **503**이면 `HCX_API_KEY`가 비었거나 `HCX_ENABLED`가 `false`입니다.

확인이 끝나면 **외부 IP를 `README.md`의 Endpoint URL에 적습니다.**

## 7. 평가 기간 운영

```bash
docker compose -f docker-compose.server.yml --env-file .env.server ps      # 상태
docker compose -f docker-compose.server.yml --env-file .env.server logs -f app
docker compose -f docker-compose.server.yml --env-file .env.server restart app
```

`restart: unless-stopped`라 컨테이너가 죽거나 장비가 재부팅돼도 알아서 다시 뜹니다.
사람이 붙어 있지 않아도 되도록 건 설정입니다.

## 자주 막히는 곳

| 증상 | 원인 |
| --- | --- |
| `Failed to configure a DataSource` | `SPRING_PROFILES_ACTIVE`가 `server`가 아니거나 `DB_*`가 비었습니다 |
| 근거 있는 질문이 503 | `HCX_API_KEY` 또는 `HCX_ENABLED` |
| 밖에서 접속 안 됨 | 방화벽 인바운드 80, 또는 `PUBLIC_PORT`가 80이 아닙니다 |
| 재부팅 후 주소가 바뀜 | 외부 IP를 고정으로 예약하지 않았습니다 |
