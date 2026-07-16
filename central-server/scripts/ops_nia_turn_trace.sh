#!/usr/bin/env bash
# 최근 니아 턴의 정책→발화→예약→전송 생명주기를 원문·Discord ID 없이 요약한다.
set -euo pipefail

COMPOSE_FILE="${COMPOSE_FILE:-compose.yml}"
DB_SERVICE="${DB_SERVICE:-db}"
PSQL_USER="${PSQL_USER:-central}"
PSQL_DB="${PSQL_DB:-central}"
MINUTES="${1:-30}"
TRACE_HASH="${2:-}"

if ! [[ "$MINUTES" =~ ^[1-9][0-9]*$ ]] || [ "$MINUTES" -gt 10080 ]; then
  echo "usage: $0 [minutes: 1..10080] [trace-hash]" >&2
  exit 64
fi
if [ -n "$TRACE_HASH" ] && ! [[ "$TRACE_HASH" =~ ^[a-f0-9]{12}$ ]]; then
  echo "trace-hash must be the 12-character value printed by this script" >&2
  exit 64
fi

docker compose -f "$COMPOSE_FILE" exec -T "$DB_SERVICE" \
  psql -v ON_ERROR_STOP=1 -U "$PSQL_USER" -d "$PSQL_DB" -P pager=off \
  -v minutes="$MINUTES" -v trace_hash="$TRACE_HASH" <<'SQL'
\pset null '-'
\echo '[intake]'
SELECT
  count(*) AS human_messages,
  to_char(max(created_at), 'YYYY-MM-DD HH24:MI:SS') AS latest_utc
FROM nexa_raw_context_message
WHERE source_type = 'HUMAN'
  AND created_at >= (CURRENT_TIMESTAMP AT TIME ZONE 'UTC') - make_interval(mins => :minutes);

\echo '[turns]'
WITH recent_policy AS (
  SELECT *
  FROM nexa_policy_decision_log
  WHERE decided_at >= (CURRENT_TIMESTAMP AT TIME ZONE 'UTC') - make_interval(mins => :minutes)
    AND (:'trace_hash' = '' OR left(encode(sha256(convert_to(correlation_id, 'UTF8')), 'hex'), 12) = :'trace_hash')
)
SELECT
  to_char(p.decided_at, 'HH24:MI:SS') AS policy_utc,
  left(encode(sha256(convert_to(p.correlation_id, 'UTF8')), 'hex'), 12) AS trace,
  p.action_kind AS policy,
  coalesce(s.outcome, '-') AS speech,
  coalesce(a.status, '-') AS action,
  CASE
    WHEN p.action_kind <> 'speak' THEN 'NO_SEND:POLICY_' || upper(p.action_kind)
    WHEN s.id IS NULL THEN 'BROKEN:MISSING_SPEECH'
    WHEN s.outcome <> 'SPEAK' THEN 'NO_SEND:SPEECH_' || s.outcome ||
      coalesce(':' || nullif(s.blocked_reason, ''), '')
    WHEN a.id IS NULL THEN 'BROKEN:MISSING_ACTION'
    WHEN a.status = 'COMPLETED' THEN 'SENT'
    WHEN a.status = 'FAILED' THEN 'FAILED:' || coalesce(a.failure_reason, 'UNKNOWN')
    WHEN a.status = 'CANCELLED' THEN 'CANCELLED'
    WHEN c.action_identity IS NULL THEN 'BROKEN:MISSING_CONTENT'
    ELSE 'PENDING:' || a.status
  END AS verdict,
  p.reason_code AS policy_reason,
  coalesce(s.blocked_stage, '-') AS speech_stage,
  coalesce(a.failure_reason, '-') AS action_reason
FROM recent_policy p
LEFT JOIN LATERAL (
  SELECT sl.*
  FROM nexa_speech_decision_log sl
  WHERE sl.correlation_id = p.correlation_id OR sl.decision_id = p.correlation_id
  ORDER BY sl.created_at DESC
  LIMIT 1
) s ON true
LEFT JOIN LATERAL (
  SELECT sa.*
  FROM nexa_scheduled_action sa
  WHERE sa.decision_id = p.correlation_id
  ORDER BY sa.created_at DESC
  LIMIT 1
) a ON true
LEFT JOIN nexa_scheduled_action_content c ON c.action_identity = a.identity
ORDER BY p.decided_at DESC;
SQL
