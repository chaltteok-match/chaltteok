# 평가 서버 배포 절차

평가 기간(09-07 10:00 ~ 09-11 15:00) 동안 주최측이 우리 서버의 `/answer`를 호출합니다.
그 사이 서버가 계속 떠 있어야 하므로, 개인 PC가 아니라 클라우드 장비에 올립니다.

로컬 개발 환경은 [`infra/README.md`](../infra/README.md)를 보세요. 이 문서는 서버 전용입니다.

명령마다 **어디서 치는지** 적어 두었습니다. 헷갈리면 여기부터 보세요.

- `[노트북]` — 지금 쓰는 개발 장비. 코퍼스와 로컬 DB가 있는 곳
- `[서버]` — NCP 장비. SSH로 들어간 뒤

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
# [서버] Docker
sudo apt-get update && sudo apt-get install -y docker.io docker-compose-v2 git
sudo usermod -aG docker $USER && newgrp docker

# [서버] 시각 동기화 — 주최측 server_time과 크게 다르면 안 됩니다
timedatectl

# [서버] 아웃바운드 확인 — 우리 서버에서 주최측으로 나갈 수 있는지 봅니다
curl -s http://34.64.175.191/health
# {"status":"ok","server_time":"...","notice_version":"..."}
```

`timeout`이나 `connection refused`가 나오면 방화벽·프록시를 먼저 확인합니다.

`34.64.175.191`은 **주최측 주소**입니다. 우리 서버가 밖으로 나갈 수 있는지 보는 것이지,
우리 서버가 밖에서 보이는지 보는 것이 아닙니다. 그건 §6에서 우리 공인 IP로 따로 확인합니다.

## 3. 코드와 설정

```bash
# [서버]
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

**노트북 DB 이름과 서버 DB 이름은 다를 수 있습니다.** 노트북은 `infra/.env`의 `POSTGRES_DB`,
서버는 `infra/.env.server`의 `POSTGRES_DB`를 씁니다. 덤프는 노트북 이름으로 뜨고, 복원은 서버
이름으로 넣습니다. 이름이 다르면 `pg_restore`의 `-d`만 서버 이름으로 맞추면 됩니다.

```bash
# [노트북] 내 DB 컨테이너 이름과 DB 이름부터 확인합니다
docker ps --format '{{.Names}}'
grep POSTGRES_DB infra/.env

# [노트북] 덤프 — 컨테이너 이름과 DB 이름을 위에서 본 값으로 씁니다
docker exec <컨테이너이름> pg_dump -U <사용자> -Fc <노트북DB이름> > chaltteok.dump
scp chaltteok.dump <서버계정>@<공인IP>:~/
```

```bash
# [서버] DB만 먼저 띄웁니다
cd chaltteok/infra
docker compose -f docker-compose.server.yml --env-file .env.server up -d db

# [서버] healthy가 될 때까지 기다립니다. 뜨자마자 복원하면 연결이 거부됩니다
until [ "$(docker compose -f docker-compose.server.yml --env-file .env.server \
        ps db --format '{{.Health}}')" = "healthy" ]; do sleep 2; done

# [서버] 복원 — -d 는 .env.server의 POSTGRES_DB 값입니다
docker compose -f docker-compose.server.yml --env-file .env.server exec -T db \
    pg_restore -U chaltteok -d chaltteok --clean --if-exists < ~/chaltteok.dump

# [서버] 들어갔는지 확인 — 3,772건이면 정상입니다
docker compose -f docker-compose.server.yml --env-file .env.server exec -T db \
    psql -U chaltteok -d chaltteok -c 'select count(*) from disclosure_document;'
```

전처리부터 다시 만드는 절차는 [`backend/README.md`](../backend/README.md#데이터를-처음부터-다시-만들기)에 있습니다. 재현이 필요할 때만 쓰면 됩니다.

## 5. 실행

```bash
# [서버]
docker compose -f docker-compose.server.yml --env-file .env.server up -d --build
```

첫 빌드는 의존성을 모두 받느라 10분쯤 걸립니다. 두 번째부터는 짧습니다.

**앞 단계를 건너뛰면 안 됩니다.**

- `HCX_API_KEY`가 비어 있는데 `HCX_ENABLED=true`면 **앱이 아예 뜨지 않고 계속 재시작합니다**
  (`HCX_ENABLED=true일 때 HCX_API_KEY가 필요합니다`)
- 데이터를 복원하기 전에 앱을 올리면 표가 없어 **모든 질문이 503**입니다

## 6. 확인

**질문은 `--data-urlencode`로 넘깁니다.** 직접 퍼센트 인코딩하면 문장 끝의 `?`가 그대로 들어가
`question`이 잘리고 **400이 납니다.**

```bash
# [서버] 안에서
curl -s -o /dev/null -w '%{http_code}\n' http://localhost/actuator/health     # 200

curl -s -G 'http://localhost/answer' \
    --data-urlencode 'question_id=Q1' \
    --data-urlencode 'question=삼성전자의 2030년 예상 주가는?'
# 200, 5개 필드. 근거가 없는 질문이라 answer는 "확인할 수 없습니다"

curl -s -G -o /dev/null -w '%{http_code}\n' 'http://localhost/answer' \
    --data-urlencode 'question_id=Q2' \
    --data-urlencode 'question=삼성전자의 2024년 연결 매출액은?'
# 200이어야 합니다. 503이면 HCX 설정을 봅니다
```

```bash
# [노트북] 밖에서 — 여기서 응답이 와야 평가가 됩니다.
# 주소는 우리 서버의 공인 IP입니다. §2의 34.64.175.191(주최측)이 아닙니다.
curl -s -o /dev/null -w '%{http_code}\n' http://<우리공인IP>/actuator/health

curl -s -G 'http://<우리공인IP>/answer' \
    --data-urlencode 'question_id=Q3' \
    --data-urlencode 'question=삼성전자의 2024년 연결 매출액은?'
```

밖에서 연결이 안 되면 ACG 인바운드 80을 봅니다. 주최측 IP만 열려 있으면 우리 자리에서는
막히는 것이 정상이라, 확인하려면 우리 IP를 잠깐 넣거나 80을 열어야 합니다.

근거를 찾은 질문이 **503**이면 `HCX_API_KEY`가 비었거나 `HCX_ENABLED`가 `false`입니다.

확인이 끝나면 **우리 공인 IP를 `README.md`의 Endpoint URL에 적습니다.**

## 7. 평가 기간 운영

```bash
# [서버]
docker compose -f docker-compose.server.yml --env-file .env.server ps      # 상태
docker compose -f docker-compose.server.yml --env-file .env.server logs -f app
docker compose -f docker-compose.server.yml --env-file .env.server restart app
```

`restart: unless-stopped`라 컨테이너가 죽거나 장비가 재부팅돼도 알아서 다시 뜹니다.

### 멈추기만 한 앱도 되살린다

`restart` 정책은 컨테이너가 **죽었을 때**만 걸립니다. 앱이 죽지 않고 응답만 못 하는 상태가 되면
Docker Engine은 healthcheck 결과를 보고도 **아무것도 하지 않습니다**(그건 Swarm 기능입니다).
평가는 4일 연속이고 그동안 사람이 붙어 있을 수 없어, `autoheal` 컨테이너가 30초마다 보고
`unhealthy`면 직접 재시작합니다.

`docker pause`로 멈춤을 재현해 확인한 결과입니다.

```
15초  status=paused   health=unhealthy
30초  status=running  health=starting   ← autoheal이 재시작
75초  healthy, /answer 200
```

autoheal은 `/var/run/docker.sock`을 마운트합니다. 이 소켓을 가진 컨테이너는 호스트에서
root와 같은 힘을 갖습니다. 평가 전용 장비라 감수한 선택입니다.

로그는 컨테이너당 10MB × 3개로 상한을 걸어 뒀습니다. 상한이 없으면 4일치가 디스크를 채웁니다.

## 자주 막히는 곳

| 증상 | 원인 |
| --- | --- |
| `Failed to configure a DataSource` | `SPRING_PROFILES_ACTIVE`가 `server`가 아니거나 `DB_*`가 비었습니다 |
| 근거 있는 질문이 503 | `HCX_API_KEY` 또는 `HCX_ENABLED` |
| 밖에서 접속 안 됨 | 방화벽 인바운드 80, 또는 `PUBLIC_PORT`가 80이 아닙니다 |
| `/answer`가 400 | 질문을 직접 퍼센트 인코딩했습니다. `--data-urlencode`를 씁니다 |
| `pg_restore`가 연결 거부 | DB가 `healthy`가 되기 전에 복원했습니다 |
| `pg_restore`가 `database ... does not exist` | `-d`가 서버 `POSTGRES_DB`와 다릅니다 |
| 재부팅 후 주소가 바뀜 | 외부 IP를 고정으로 예약하지 않았습니다 |
| 앱이 안 뜨고 계속 재시작 | `HCX_ENABLED=true`인데 `HCX_API_KEY`가 비었습니다. `docker compose logs app`에 이유가 찍힙니다 |
| **모든** 질문이 503 | 데이터를 복원하지 않았습니다. 표가 없어 조회가 실패합니다 |
