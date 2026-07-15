# 운영 런북 — central-server (LAUNCH 차수 15)

## 관측
- 헬스: `GET /actuator/health` (`providerPool.activeProviderConnections`).
- 메트릭: `GET /actuator/prometheus` → Prometheus 수집 → Grafana(`docs/grafana-dashboard.json` import).
- 로그: `docker compose logs -f central-server`.
- 배포 위치: `ssh.yeon.world` → `~/deploy/central-server` (`central-server CI/CD (원격 배포)`의 self-hosted `yeon-arm`).
- 빠른 점검: 배포 위치에서 `./ops_runtime_secret_audit.sh`(host `.env*`/secret file/평문 env),
  `./ops_healthcheck.sh`(health+pool), `DISCORD_GUILD_ID=all ./ops_policy_audit.sh`(채널 정책).
  세 스크립트는 `central-deploy.yml`이 배포 디렉터리로 복사한다. 파일이 없다면 아직 최신 배포가 돌지 않은 상태다.
- 주기 점검: GitHub Actions `central ops audit`가 6시간마다 같은 runtime-secret/health/policy 감사를 읽기 전용으로 실행한다.

## 장애 대응
### 1) 서버 다운 / health DOWN
- `docker compose ps` 로 컨테이너 상태 확인 → `docker compose logs central-server` 마지막 50줄.
- DB 연결 실패면 `db` 컨테이너 health 확인(`pg_isready`). 복구 후 `docker compose up -d`.
- 기본 점검:
  ```bash
  ssh ssh.yeon.world
  cd ~/deploy/central-server
  EXTERNAL_BASE_URL=https://discord-ai.yeon.world ./ops_healthcheck.sh
  ```

### 2) 풀에 프로바이더 0명(활성 연결 0)
- `providerpool_active_connections == 0` → `/ask` 가 "처리 가능한 AI 없음" 반환.
- 대응: 관리자가 프로바이더에게 에이전트 실행 요청. 토큰 만료 시 `/settings` 웹 대시보드에서 재승인/재발급.

### 3) 실패율 급증
- `/settings` 웹 대시보드에서 provider별 상태/실패를 확인한다. 연속 실패 3회면 자동 UNHEALTHY 제외됨.
- 특정 provider 문제면 대시보드에서 제거하거나 해당 프로바이더에게 데스크톱 앱 중지를 요청한다.

### 4) DB 이슈
- 마이그레이션 실패: Flyway `flyway_schema_history` 확인. 잘못된 마이그레이션은 새 V_n 으로 보정(이전 것 수정 금지).

### 5) 자동응답/니아 채널이 "LLM 사용 불가"로 막힘
- 증상: 핀 메시지는 "멘션 없이 말 걸면 답해요"라고 안내하지만 봇 응답이 `이 채널에서는 LLM 을 사용할 수 없습니다.`로 끝난다.
- 원인 후보: `channel_ai.auto_respond=true` 또는 니아 자동 생성 채널(`ai채팅`/`ai그림`)이 guild LLM allow-list(`allowed_channel`)와 불일치.
- 읽기 전용 감사:
  ```bash
  ssh ssh.yeon.world
  cd ~/deploy/central-server
  DISCORD_GUILD_ID=all ./ops_policy_audit.sh      # 봇이 들어간 모든 서버 대조
  DISCORD_GUILD_ID=<guild_id> ./ops_policy_audit.sh  # 특정 서버만 볼 때
  ```
- 실패하면 출력된 `guild_id`/`channel_id`를 기준으로 웹 대시보드에서 해당 채널을 허용한다.
- 감사가 통과하면 채널 정책 문제는 아니므로 provider pool, role policy, quota, cloud/provider backend 오류를 이어서 본다.

## 롤백
- 이전 이미지 태그로 되돌리기:
  ```bash
  # central-server-image 워크플로의 이전 sha 태그 사용
  docker pull ghcr.io/<owner>/central-server:<이전sha>
  # docker-compose.yml 의 image 를 그 태그로 고정 후
  docker compose up -d
  ```

## 백업 / 복구 (Postgres)
- 현재 저장소/배포 워크플로는 운영 DB 백업을 자동 생성하지 않는다. 자동화 전까지 당직자는 배포·마이그레이션·위험 작업
  전에 수동 백업을 만들고, 암호화된 오프호스트 저장소로 옮긴 뒤 복구 리허설 여부를 기록한다.
- 백업(배포 호스트 `~/deploy/central-server`에서 실행):
  ```bash
  mkdir -p backups
  chmod 700 backups
  docker compose exec -T db pg_dump -U central -d central --no-owner --no-acl \
    | gzip > "backups/central_$(date -u +%Y%m%dT%H%M%SZ).sql.gz"
  ```
- 복구(가능하면 staging에서 먼저 리허설):
  ```bash
  gunzip -c backups/central_YYYYMMDDTHHMMSSZ.sql.gz \
    | docker compose exec -T db psql -U central -d central
  ```
- 최근 백업 확인(파일 존재만으로 복구 가능성을 보장하지 않음):
  ```bash
  find backups -maxdepth 1 -type f -name 'central_*.sql.gz' -printf '%TY-%Tm-%Td %TH:%TM %p\n' | sort | tail
  ```
- 볼륨 `pgdata` 가 데이터를 보존. `docker compose down -v` 는 데이터 삭제이므로 금지한다.

## 보안 점검
- `CENTRAL_DEV_ENABLED` 운영 false 확인(/dev/* 차단).
- `./ops_runtime_secret_audit.sh`가 `active_env=absent`, `runtime secret files present`,
  `inline secret env absent`를 출력하는지 확인.
- 토큰/DB 비밀이 로그·이미지·렌더링된 compose에 없는지 확인.

## 운영 스크립트 수정 검증
- `central-server/scripts/ops_*.sh`를 수정하면 병합 전에 다음을 실행한다.
  ```bash
  shellcheck central-server/scripts/ops_healthcheck.sh central-server/scripts/ops_policy_audit.sh \
    central-server/scripts/ops_runtime_secret_audit.sh
  bash -n central-server/scripts/ops_healthcheck.sh central-server/scripts/ops_policy_audit.sh \
    central-server/scripts/ops_runtime_secret_audit.sh
  ```
