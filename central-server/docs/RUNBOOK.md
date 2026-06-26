# 운영 런북 — central-server (LAUNCH 차수 15)

## 관측
- 헬스: `GET /actuator/health` (`providerPool.activeProviderConnections`).
- 메트릭: `GET /actuator/prometheus` → Prometheus 수집 → Grafana(`docs/grafana-dashboard.json` import).
- 로그: `docker compose logs -f central-server`.
- 배포 위치: `ssh.yeon.world` → `~/deploy/central-server` (`central-server CI/CD (원격 배포)`의 self-hosted `yeon-arm`).
- 빠른 점검: 배포 위치에서 `./ops_healthcheck.sh`(health+pool), `DISCORD_GUILD_ID=all ./ops_policy_audit.sh`(채널 정책).
  두 스크립트는 `central-deploy.yml`이 배포 디렉터리로 복사한다. 파일이 없다면 아직 최신 배포가 돌지 않은 상태다.

## 장애 대응
### 1) 서버 다운 / health DOWN
- `docker compose ps` 로 컨테이너 상태 확인 → `docker compose logs central-server` 마지막 50줄.
- DB 연결 실패면 `db` 컨테이너 health 확인(`pg_isready`). 복구 후 `docker compose up -d`.
- 기본 점검:
  ```bash
  ssh ssh.yeon.world
  cd ~/deploy/central-server
  ./ops_healthcheck.sh
  ```

### 2) 풀에 프로바이더 0명(활성 연결 0)
- `providerpool_active_connections == 0` → `/ask` 가 "처리 가능한 AI 없음" 반환.
- 대응: 관리자가 프로바이더에게 에이전트 실행 요청. 토큰 만료 시 `/provider-approve` 재발급.

### 3) 실패율 급증
- `/fairness`(관리자)로 provider별 실패 확인. 연속 실패 3회면 자동 UNHEALTHY 제외됨.
- 특정 provider 문제면 `/provider-remove`.

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
- 실패하면 출력된 `guild_id`/`channel_id`를 기준으로 관리자 명령(`/llm-allow-channel`) 또는 대시보드에서 해당 채널을 허용한다.
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
- 백업(정기 cron 권장):
  ```bash
  docker compose exec -T db pg_dump -U central central > backup_$(date +%F).sql
  ```
- 복구:
  ```bash
  docker compose exec -T db psql -U central central < backup_YYYY-MM-DD.sql
  ```
- 볼륨 `pgdata` 가 데이터를 보존. `docker compose down -v` 는 데이터 삭제이므로 주의.

## 보안 점검
- `CENTRAL_DEV_ENABLED` 운영 false 확인(/dev/* 차단).
- 토큰/DB 비밀이 로그·이미지에 없는지 확인.

## 운영 스크립트 수정 검증
- `central-server/scripts/ops_*.sh`를 수정하면 병합 전에 다음을 실행한다.
  ```bash
  shellcheck central-server/scripts/ops_healthcheck.sh central-server/scripts/ops_policy_audit.sh
  bash -n central-server/scripts/ops_healthcheck.sh central-server/scripts/ops_policy_audit.sh
  ```
