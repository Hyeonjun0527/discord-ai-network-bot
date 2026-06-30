#!/usr/bin/env bash
set -euo pipefail

if [[ $# -ne 1 ]]; then
  echo "usage: $0 <correlation-id-or-decision-id>" >&2
  exit 64
fi

trace_id="$1"
db_url="${DATABASE_URL:-${CENTRAL_DATABASE_URL:-}}"

if [[ -z "$db_url" ]]; then
  echo "DATABASE_URL or CENTRAL_DATABASE_URL is required" >&2
  exit 65
fi

if ! command -v psql >/dev/null 2>&1; then
  echo "psql is required" >&2
  exit 69
fi

psql "$db_url" -v trace_id="$trace_id" <<'SQL'
\pset pager off
\pset null '(null)'

SELECT
  correlation_id,
  action_kind,
  model_version,
  judge_model_version,
  judge_prompt_version,
  fewshot_set_id,
  fewshot_version,
  raw_window_hash,
  raw_window_message_refs_json,
  evidence_refs,
  reason_code,
  shadow_baseline_action,
  final_decision_source,
  consumed_generation_quota,
  decided_at
FROM nexa_policy_decision_log
WHERE correlation_id = :'trace_id'
ORDER BY decided_at DESC
LIMIT 5;

SELECT
  decision_id,
  identity AS action_identity,
  action_type,
  status,
  failure_reason,
  execute_after,
  created_at,
  updated_at
FROM nexa_scheduled_action
WHERE decision_id = :'trace_id'
   OR identity = :'trace_id'
ORDER BY created_at DESC
LIMIT 20;

SELECT
  decision_id,
  correlation_id,
  outcome,
  social_act,
  blocked_stage,
  blocked_reason,
  high_risk_downgraded,
  consent_blocked,
  generated_candidate_count,
  critic_reasons_json,
  selected_content_ref,
  created_at
FROM nexa_speech_decision_log
WHERE correlation_id = :'trace_id'
   OR decision_id = :'trace_id'
ORDER BY created_at DESC
LIMIT 20;
SQL
