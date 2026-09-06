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

주최측 발신 IP `34.64.175.191`은 GCP 서울 대역입니다. 장비는 네이버 클라우드 등 공인 IP를
붙일 수 있는 곳이면 됩니다. 스펙만 맞추면 됩니다.

| 항목 | 값 | 근거 |
| --- | --- | --- |
| 머신 | 2 vCPU, 메모리 **2GB 이상** | 아래 실측 |
| OS | Ubuntu 24.04 LTS | |
| 디스크 | 20GB | |
| 외부 IP | **고정(static) 예약** | 재부팅으로 주소가 바뀌면 제출한 URL이 죽습니다 |
| 방화벽 인바운드 22 | 운영하는 사람 IP만 | SSH |
| 방화벽 인바운드 80 | 평가 기간은 `0.0.0.0/0` | 공지는 `34.64.175.191/32`이나, 다른 주소에서 오면 막힌 줄도 모릅니다 |
| 방화벽 아웃바운드 | 기본 허용 | HyperCLOVA X·주최측 헬스체크 |

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

노트북(macOS)에서 `apt-get`을 치지 않습니다. 먼저 SSH로 **Ubuntu 서버**에 들어갑니다.

```bash
ssh root@<우리_공인_IP>
```

프롬프트가 `root@서버이름`이면 서버 안입니다. root면 `sudo`는 빼도 됩니다.

```bash
apt-get update && apt-get install -y docker.io docker-compose-v2 git rsync

timedatectl

# 이 서버 → 주최측 방향. 주최측이 우리를 치는 것과는 반대입니다.
curl -s http://34.64.175.191/health
# {"status":"ok","server_time":"...","notice_version":"..."}
```

`timeout`이나 `connection refused`가 나오면 이 서버의 **아웃바운드**를 먼저 확인합니다.

## 3. 코드와 설정

서버에 `backend/`과 `infra/`가 있어야 이미지를 만듭니다. `git clone`이거나, PAT 없이 노트북에서 `rsync`로 올려도 됩니다.

```bash
# 서버에서 clone할 때
git clone <작업_저장소> && cd chaltteok/infra
cp .env.server.example .env.server
```

`.env.server`에 값을 채웁니다. **이 파일은 커밋하지 않습니다.** `POSTGRES_DB`·`POSTGRES_USER` 기본값은 `chaltteok`입니다. 비어 있는 칸만 채우면 됩니다.

| 이름 | 설명 |
| --- | --- |
| `POSTGRES_PASSWORD` | 서버 Postgres 비밀번호. 로컬 `.env`와 달라도 됩니다 |
| `HCX_API_KEY` | HyperCLOVA X API 키. GitHub PAT이 아닙니다 |
| `HCX_ENABLED` | `true`. 키를 비운 채 `true`면 앱이 안 뜹니다 |
| `PUBLIC_PORT` | 주최측이 호출할 포트. 평가 규격은 `80` |

## 4. 데이터 넣기

서버에서 코퍼스를 받아 전처리를 돌릴 필요가 없습니다. 로컬 DB를 덤프해 옮깁니다(압축 1.9MB).

덤프 명령의 DB 이름은 **로컬 `infra/.env`의 `POSTGRES_DB`**입니다. 컨테이너 이름은 로컬 compose의 `container_name`입니다. 아래는 로컬 DB 이름이 `chaltteok-db`일 때의 예입니다.

```bash
# 노트북에서
docker exec chaltteok-db pg_dump -U chaltteok -Fc chaltteok-db > ~/chaltteok.dump
#          └ 컨테이너 이름         └ 사용자      └ 로컬 DB 이름
scp ~/chaltteok.dump root@<우리_공인_IP>:~/

# 서버에서 — DB만 먼저 띄우고, healthy가 된 뒤에 복원합니다.
# 뜨자마자 복원하면 소켓이 없어 pg_restore가 실패합니다.
cd ~/chaltteok/infra
docker compose -f docker-compose.server.yml --env-file .env.server up -d db
docker compose -f docker-compose.server.yml --env-file .env.server ps   # db가 healthy인지
docker compose -f docker-compose.server.yml --env-file .env.server exec -T db \
    pg_restore -U chaltteok -d chaltteok --clean --if-exists < ~/chaltteok.dump
```

서버 쪽 `-d chaltteok`은 `.env.server`의 `POSTGRES_DB`입니다. 로컬 이름이 `chaltteok-db`여도 복원 대상은 서버 DB 이름입니다.

전처리부터 다시 만드는 절차는 [`backend/README.md`](../backend/README.md#데이터를-처음부터-다시-만들기)에 있습니다. 재현이 필요할 때만 쓰면 됩니다.

## 5. 실행

```bash
docker compose -f docker-compose.server.yml --env-file .env.server up -d --build
```

첫 빌드는 의존성을 모두 받느라 10분쯤 걸립니다. 두 번째부터는 짧습니다.

**앞 단계를 건너뛰면 안 됩니다.**

- `HCX_API_KEY`가 비어 있는데 `HCX_ENABLED=true`면 **앱이 아예 뜨지 않고 계속 재시작합니다**
  (`HCX_ENABLED=true일 때 HCX_API_KEY가 필요합니다`)
- 데이터를 복원하기 전에 앱을 올리면 표가 없어 **모든 질문이 503**입니다

## 6. 확인

질문 원문은 한글입니다. HTTP 주소줄에 공백·한글을 그대로 붙이면 Tomcat이 400 HTML을 줍니다.
`--data-urlencode`로 보내면 앱에는 원문이 들어갑니다. 주최측 HTTP 클라이언트도 이렇게 인코딩합니다.

```bash
# 서버 안에서
curl -s -o /dev/null -w '%{http_code}\n' http://localhost/actuator/health     # 200

curl -sG 'http://localhost/answer' \
  --data-urlencode 'question_id=Q1' \
  --data-urlencode 'question=삼성전자의 2030년 예상 주가는?'
# 200, JSON 5칸. 근거 없는 질문이라 answer는 "확인할 수 없습니다" 계열

curl -sG 'http://localhost/answer' \
  --data-urlencode 'question_id=Q2' \
  --data-urlencode 'question=삼성전자의 2024년 연결기준 매출액은 얼마인가?'
# 근거 있는 질문. 금액이 있으면 DB·HCX까지 된 것입니다
```

**밖에서의 `<외부IP>`는 주최측 `34.64.175.191`이 아닙니다.** README에 적는 **우리 서버 공인 IP**입니다.
노트북에서 우리 주소의 80이 실제로 열리는지 보는 단계입니다. 맥은 IPv6로 우회하는 경우가 있어 `-4`를 붙입니다.

```bash
# 노트북에서. <우리_공인_IP> 예: 49.50.143.91
curl -4 -s -o /dev/null -w '%{http_code}\n' http://<우리_공인_IP>/actuator/health
curl -4 -sG 'http://<우리_공인_IP>/answer' \
  --data-urlencode 'question_id=Q1' \
  --data-urlencode 'question=삼성전자의 2030년 예상 주가는?'
```

`34.64.175.191/health`는 **우리가 주최측으로 나가는지** 보는 공지 헬스체크입니다. 평가 호출과 반대 방향입니다.

근거를 찾은 질문이 **503**이면 `HCX_API_KEY` 또는 `HCX_ENABLED`입니다.
**모든** 질문이 503이면 덤프 복원이 안 된 경우가 많습니다.

확인이 끝나면 **우리 공인 IP**를 `README.md`의 Endpoint URL에 `http://<우리_공인_IP>/answer`로 적습니다.

## 7. 평가 기간 운영

```bash
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
| **모든** 질문이 503 | 덤프 복원 실패. `pg_restore`를 DB `healthy` 전에 치면 소켓 오류가 납니다 |
| `/answer`가 Tomcat HTML 400 | 한글·공백을 주소에 그대로 붙임. `--data-urlencode`를 씁니다 |
| 밖에서 `Connection refused` | 앱이 80에 없음. compose `up`이 안 됐거나 `PUBLIC_PORT`가 80이 아닙니다 |
| 밖에서 timeout | ACG 인바운드 80이 막힘. 맥 `curl`은 `-4`를 붙입니다 |
| 재부팅 후 주소가 바뀜 | 외부 IP를 고정으로 예약하지 않았습니다 |
| 앱이 안 뜨고 계속 재시작 | `HCX_ENABLED=true`인데 `HCX_API_KEY`가 비었습니다. `docker compose logs app`에 이유가 찍힙니다 |
| 노트북에서 `sudo apt-get` 실패 | 그 명령은 Ubuntu 서버용입니다. `ssh root@공인IP` 안에서 칩니다 |
