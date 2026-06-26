#!/usr/bin/env bash
# 운영 점검 자동화(차수 15 #230). actuator 헬스 + 풀 메트릭을 확인하고
# 비정상이면 비0 종료(크론/모니터에서 사용). 의존: curl, (선택) jq.
#
# 사용: BASE_URL=http://localhost:8085 scripts/ops_healthcheck.sh
set -euo pipefail
COMPOSE_FILE="${COMPOSE_FILE:-compose.yml}"
APP_SERVICE="${APP_SERVICE:-central-server}"
if [ -z "${BASE_URL:-}" ]; then
  if curl -fsS --max-time 2 "http://localhost:8085/actuator/health" >/dev/null 2>&1; then
    BASE_URL="http://localhost:8085"
  else
    BASE_URL="http://localhost:8080"
  fi
fi
MIN_PROVIDERS="${MIN_PROVIDERS:-0}"   # 이 값 미만이면 경고(기본 0=경고 안 함)
CHECK_COMPOSE_ENV="${CHECK_COMPOSE_ENV:-auto}" # auto|true|false

fail() { echo "❌ $1" >&2; exit 1; }

json_field() {
  field="$1"
  if command -v jq >/dev/null 2>&1; then
    jq -r ".$field"
    return
  fi
  if command -v python3 >/dev/null 2>&1; then
    FIELD="$field" python3 -c 'import json, os, sys; print(json.load(sys.stdin).get(os.environ["FIELD"]))'
    return
  fi
  fail "jq 또는 python3 가 없어 JSON 필드($field)를 읽을 수 없습니다"
}

should_check_compose_env() {
  case "$CHECK_COMPOSE_ENV" in
    true) return 0 ;;
    false) return 1 ;;
    auto) [ -f "$COMPOSE_FILE" ] && command -v docker >/dev/null 2>&1 ;;
    *) fail "CHECK_COMPOSE_ENV는 auto, true, false 중 하나여야 합니다" ;;
  esac
}

# 1) 헬스
health="$(curl -fsS --max-time 5 "$BASE_URL/actuator/health" || fail "actuator/health 응답 없음")"
echo "$health" | grep -q '"status":"UP"' || fail "헬스 상태 비정상: $health"
echo "✅ health UP"

# 2) 풀 메트릭
pool="$(curl -fsS --max-time 5 "$BASE_URL/api/metrics/pool" || fail "metrics/pool 응답 없음")"
echo "ℹ️  pool=$pool"
active="$(echo "$pool" | json_field activeProviders)"
[[ "$active" =~ ^[0-9]+$ ]] || fail "metrics/pool activeProviders 값이 숫자가 아닙니다: $active"
if [ "$active" -lt "$MIN_PROVIDERS" ]; then
  fail "활성 프로바이더 ${active} < 임계 ${MIN_PROVIDERS}"
fi
echo "✅ activeProviders=$active (>= $MIN_PROVIDERS)"

# 3) 운영 compose 환경이면 dev 엔드포인트 노출을 같이 확인한다.
if should_check_compose_env; then
  dev_enabled="$(docker compose -f "$COMPOSE_FILE" exec -T "$APP_SERVICE" printenv CENTRAL_DEV_ENABLED 2>/dev/null | tr -d '\r\n' || true)"
  [ "$dev_enabled" = "false" ] || fail "CENTRAL_DEV_ENABLED가 false가 아닙니다: ${dev_enabled:-<unset>}"
  echo "✅ CENTRAL_DEV_ENABLED=false"
fi

echo "✅ 점검 통과"
