# 운영 런북 — central-server (LAUNCH 차수 15)

## 관측
- 헬스: `GET /actuator/health` (`providerPool.activeProviderConnections`).
- 메트릭: `GET /actuator/prometheus` → Prometheus 수집 → Grafana(`docs/grafana-dashboard.json` import).
- 로그: `docker compose logs -f central-server`.
- 배포 위치: `ssh.yeon.world` → `~/deploy/central-server` (`central-server CI/CD (원격 배포)`의 self-hosted `yeon-arm`).
- 빠른 점검: 배포 위치에서 `./ops_runtime_secret_audit.sh`(host `.env*`/secret file/평문 env),
  `./ops_healthcheck.sh`(health+pool), `DISCORD_GUILD_ID=all ./ops_policy_audit.sh`(채널 정책).
  니아 무응답은 `./ops_nia_turn_trace.sh 30`으로 최근 30분의 정책→발화→예약→전송 상태와 종결 원인을 한 번에 본다.
  네 스크립트는 `central-deploy.yml`이 배포 디렉터리로 복사한다. 파일이 없다면 아직 최신 배포가 돌지 않은 상태다.
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

### 6) 니아가 메시지를 받았지만 답하지 않음
- 최근 턴을 원문이나 Discord ID 노출 없이 한 행씩 추적한다. `verdict`가 첫 번째 확인 지점이다.
  ```bash
  ssh ssh.yeon.world
  cd ~/deploy/central-server
  ./ops_nia_turn_trace.sh 30
  docker compose logs --since=30m central-server | grep 'NIA_TURN_'
  ```
- `BROKEN:MISSING_SPEECH`는 정책 이후 발화 파이프라인 미진입, `BROKEN:MISSING_ACTION`은 발화 성공 뒤 예약 실패,
  `PENDING:*`는 아직 실행 중, `FAILED:*`/`CANCELLED`는 예약 행의 종결 원인을 뜻한다.
- 한 결정만 다시 보려면 출력된 12자리 `trace` 해시를 두 번째 인자로 넘긴다.
  (`./ops_nia_turn_trace.sh 1440 <trace>`). 상관관계 원문이나 Discord ID는 출력하지 않는다.
- `docker compose ps redis`가 healthy인지 확인한다. Redis가 없거나 DOWN이면 분산 실행 permit이 fail-closed되어
  Discord 전송이 차단된다. 운영 compose는 Redis health 이후에만 central-server를 시작한다.
- `NEXA_FIELD_ENC_KEY`와 `OPENAI_API_KEY`는 배포 workflow의 필수 secret이며, 자율 전송 ON 상태에서 둘 중 하나가
  비어 있으면 readiness guard가 부팅을 실패시킨다. 값을 출력하지 말고 `./ops_runtime_secret_audit.sh`의
  `runtime secret files present`만 확인한다.

### 6-1) 니아 비용 최적화 롤백
- Structured Outputs와 turn boundary는 독립 스위치다. 장애 범위만 끄고 모델·few-shot·원문 보존 정책은 바꾸지 않는다.
  - Structured Outputs/provider 호환 문제:
    `NEXA_PARTICIPATION_JUDGE_STRUCTURED_OUTPUT_ENABLED=false`
  - turn boundary 지연·typing 문제:
    `NEXA_PARTICIPATION_TURN_BOUNDARY_ENABLED=false`,
    필요하면 `DISCORD_TYPING_INTENT_ENABLED=false`
- rawScene 고정폭 row는 `nia.participation-judge-input.v4`와 `nia-judge-prompt-v18`로 이전 cache와 분리된다.
  이 형식 자체를 되돌릴 때는 이전 이미지 태그로 롤백한다.
- Cloud action evaluator, Judge repair, OpenAI Conversation RAG embedding은 제거 상태를 유지한다. 다만
  Speech-style RAG는 `SPEAK` 확정 뒤에만 별도 OpenAI embedding을 최대 1회 쓴다. Judge와 Speech는 각각
  최대 1회이고 provider 내부 retry도 0이다. 비발화는 최대 1회, 발화는 실패 경로까지 포함해도 최대 3회다.
  이 구조를 되돌릴 때는 이전 이미지로 전체 롤백한 뒤 비용·품질 근거를 재검토한다.
- 비용 효과는 원문 로그가 아니라
  `central_openai_requests_total{purpose=...}`와
  `central_openai_tokens_total{purpose=...,category=...}`의 배포 전후 구간으로 비교한다.
- 운영 원문을 재생하거나 유료 테스트 호출을 자동으로 만들지 않는다. 실제 트래픽에서 오류율과 결정 건수 대비
  `nia_judge`, 발화 건수 대비 `nia_speech` 및 `nia_speech_style_embedding` 요청 비율을 관측한다. 세 비율은
  모두 1을 넘으면 회귀다. `nia_rag_embedding`, `nia_judge_repair`, `nia_action_evaluator` 신규 시계열은
  생기지 않아야 한다.

### 7) 폐루프 테이블 보존 정리
- WAIT outbox와 행동-결과 관측 행은 기본 30일 보존 후 매일 UTC 03:55에 정리된다.
- 설정은 `NEXA_CLOSED_LOOP_RETENTION_ENABLED`, `NEXA_CLOSED_LOOP_RETENTION_DAYS`,
  `NEXA_CLOSED_LOOP_RETENTION_CRON`이다. 기능을 끄더라도 과거 행 정리를 위해 retention은 기본 ON이다.
- 정리 실패는 다음 주기에 재시도하며 `WAIT 재평가 outbox 정리 실패` 또는 `NEXA 행동-결과 관측 정리 실패` 로그를 남긴다.
- 운영에서 retention을 장기간 끄지 않는다. 보존 기간을 줄이기 전에는 해당 데이터가 사회적 결과 추적과 수습 학습에
  쓰인다는 점을 확인한다.

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
    central-server/scripts/ops_runtime_secret_audit.sh central-server/scripts/ops_nia_turn_trace.sh
  bash -n central-server/scripts/ops_healthcheck.sh central-server/scripts/ops_policy_audit.sh \
    central-server/scripts/ops_runtime_secret_audit.sh central-server/scripts/ops_nia_turn_trace.sh
  ```

## NIA Speech-style RAG private import

이 작업은 별도 운영 승인 대상이다. 현재 서비스에 카드를 자동으로 넣지 않는다.

1. `V91__nia_human_speech_style_rag.sql`을 포함한 이미지가 먼저 production에 배포되어 있는지 확인한다.
2. **현재 candidate가 아니라**, candidate JSONL digest에 묶인 실제 OpenAI retrieval-audit `PASS`, 그 audit digest까지 묶인
   70-case blind-quality `PASS`, 같은 audit에 묶인 fresh comparative RAG-value `PASS`가 manifest 안에 봉인된
   `nia-human-speech-style-import-manifest.v10` private import **directory**만 host protected directory로 복사한다. directory에는
   `human-speech-style-cards.jsonl`, `candidate-manifest.json`, `retrieval-audit.json`, `blind-quality-review.json`,
   `rag-value-review.json`, `manifest.json`이 모두 있어야 하며, 앱 시작 경로도 이 여섯 파일의 해시·내용 결속을 다시 검사한다.
   또한 trusted `human-speech-style-source-coverage.json`에 고정된 16개 source fingerprint 집합과 candidate/import manifest의
   `expected_source_*` 값이 정확히 일치해야 한다. 카드 수나 source 수만 같은 다른 집합은 거절한다.
   retrieval audit은 H2-only 검증 범위에서 실행되어야 하며 Judge·Discord·provider generation은
   모두 꺼져 있어야 한다. 복사 전·후 권한은 directory 0700, file 0600이어야 하며 Git/이미지/로그에 넣지 않는다. 이전 v1/v2
   manifest나 별도 `FAIL` review/audit는 import 대상이 아니다.

   ```bash
   ssh ssh.yeon.world 'install -d -m 700 ~/deploy/central-server/private'
   scp -pr <sealed-private-import-dir> ssh.yeon.world:~/deploy/central-server/private/
   ssh ssh.yeon.world 'chmod 700 ~/deploy/central-server/private/<sealed-private-import-dir-name> && chmod 600 ~/deploy/central-server/private/<sealed-private-import-dir-name>/*'
   ```

3. GitHub Actions의 `central Speech-style RAG import`를 manual dispatch한다. manifest의 `quality`와 같은
   `import_quality`를 선택한다. 현재 artifact가 `USER_RELEASED_REVIEW`이면 그 값을 명시적으로 선택해야 하며,
   `CURATION_APPROVED`로 바꿔 formal approval인 것처럼 import할 수 없다. workflow와 one-shot application은 manifest schema, JSONL
   digest, candidate manifest, 실제 OpenAI `text-embedding-3-small` retrieval-audit의 candidate binding·80% independent holdout coverage,
   그 audit digest에 묶인 seven-mode 70-case blind-quality threshold와 안전성 0건, 그리고 fixed mode baseline 대비 fresh comparative
   RAG-value threshold를 다시 확인한다. `production` approval 후
   one-shot container가 import하고 종료한다. 이 컨테이너는 Discord, autonomous send, web server를 전부 끈다.
4. workflow 출력에서 전체 카드 수·검색 가능 카드 수·source 수·검색 가능 enum 수·payload/vector 암호화만 확인한다.
   원문 카드 내용은 절대 출력하지 않는다.
   실패하면 live `central-server`는 그대로 두고 import container만 종료된다.

### Speech-style RAG import 롤백

- import 전에는 change-control 기록과 함께 **암호화된 운영 DB 백업**을 만든다. backup 파일이나 table dump의 내용은 로그·티켓·채팅에
  출력하지 않는다.
- one-shot import가 schema·hash·16-source 집합·evidence binding 또는 DB 사후 집계에서 실패하면, live 서비스는 그대로 두고
  import container만 종료한다. 이 경우 수동 row 수정이나 부분 재시도를 하지 않는다.
- import 뒤에 rollback이 필요하면 `NEXA_SPEECH_STYLE_RAG_ENABLED=false`로 유지한 채 승인된 백업으로
  `nia_human_speech_style_example`을 포함한 DB를 복구하고, 복구 대상의 digest·암호화 상태·source fingerprint 집합을 다시 읽기 전용으로
  검증한다. 새 artifact를 다시 import하려면 sealed directory 전체와 v10 evidence를 처음부터 재검증한다.
