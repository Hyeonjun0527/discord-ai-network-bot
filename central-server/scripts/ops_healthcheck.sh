#!/usr/bin/env bash
# 운영 점검 자동화(차수 15 #230). actuator 헬스 + 풀 메트릭을 확인하고
# 비정상이면 비0 종료(크론/모니터에서 사용). 의존: curl, (선택) jq.
#
# 사용: BASE_URL=http://localhost:8085 scripts/ops_healthcheck.sh
set -euo pipefail
if [ -z "${BASE_URL:-}" ]; then
  if curl -fsS --max-time 2 "http://localhost:8085/actuator/health" >/dev/null 2>&1; then
    BASE_URL="http://localhost:8085"
  else
    BASE_URL="http://localhost:8080"
  fi
fi
MIN_PROVIDERS="${MIN_PROVIDERS:-0}"   # 이 값 미만이면 경고(기본 0=경고 안 함)

fail() { echo "❌ $1" >&2; exit 1; }

# 1) 헬스
health="$(curl -fsS --max-time 5 "$BASE_URL/actuator/health" || fail "actuator/health 응답 없음")"
echo "$health" | grep -q '"status":"UP"' || fail "헬스 상태 비정상: $health"
echo "✅ health UP"

# 2) 풀 메트릭
pool="$(curl -fsS --max-time 5 "$BASE_URL/api/metrics/pool" || fail "metrics/pool 응답 없음")"
echo "ℹ️  pool=$pool"
if command -v jq >/dev/null 2>&1; then
  active="$(echo "$pool" | jq -r '.activeProviders')"
  if [ "$active" -lt "$MIN_PROVIDERS" ]; then
    fail "활성 프로바이더 ${active} < 임계 ${MIN_PROVIDERS}"
  fi
  echo "✅ activeProviders=$active (>= $MIN_PROVIDERS)"
fi

echo "✅ 점검 통과"
