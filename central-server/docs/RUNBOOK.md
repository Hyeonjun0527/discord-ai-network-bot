# 운영 런북 — central-server (LAUNCH 차수 15)

## 관측
- 헬스: `GET /actuator/health` (`providerPool.activeProviderConnections`).
- 메트릭: `GET /actuator/prometheus` → Prometheus 수집 → Grafana(`docs/grafana-dashboard.json` import).
- 로그: `docker compose logs -f central-server`.

## 장애 대응
### 1) 서버 다운 / health DOWN
- `docker compose ps` 로 컨테이너 상태 확인 → `docker compose logs central-server` 마지막 50줄.
- DB 연결 실패면 `db` 컨테이너 health 확인(`pg_isready`). 복구 후 `docker compose up -d`.

### 2) 풀에 프로바이더 0명(활성 연결 0)
- `providerpool_active_connections == 0` → `/ask` 가 "처리 가능한 AI 없음" 반환.
- 대응: 관리자가 프로바이더에게 에이전트 실행 요청. 토큰 만료 시 `/provider-approve` 재발급.

### 3) 실패율 급증
- `/fairness`(관리자)로 provider별 실패 확인. 연속 실패 3회면 자동 UNHEALTHY 제외됨.
- 특정 provider 문제면 `/provider-remove`.

### 4) DB 이슈
- 마이그레이션 실패: Flyway `flyway_schema_history` 확인. 잘못된 마이그레이션은 새 V_n 으로 보정(이전 것 수정 금지).

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
