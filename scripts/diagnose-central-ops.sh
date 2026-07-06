#!/usr/bin/env bash
set -euo pipefail

REPO="${CENTRAL_OPS_GITHUB_REPO:-Hyeonjun0527/discord-ai-network-bot}"
SSH_HOST="${CENTRAL_OPS_SSH_HOST:-ssh.yeon.world}"
RUNNER_NAME="${CENTRAL_OPS_RUNNER_NAME:-yeon-arm}"
RUNNER_SERVICE="${CENTRAL_OPS_RUNNER_SERVICE:-actions.runner.Hyeonjun0527-discord-assistant.yeon-arm.service}"
DEPLOY_DIR="${CENTRAL_OPS_DEPLOY_DIR:-\$HOME/deploy/central-server}"
APP_PORT="${CENTRAL_OPS_APP_PORT:-8085}"
RUNNER_JOURNAL_SINCE="${CENTRAL_OPS_RUNNER_JOURNAL_SINCE:-30 min ago}"
REPAIR_RUNNER="${CENTRAL_OPS_REPAIR_RUNNER:-false}"
DB_SERVICE="${CENTRAL_OPS_DB_SERVICE:-db}"
DB_NAME="${CENTRAL_OPS_DB_NAME:-central}"
DB_USER="${CENTRAL_OPS_DB_USER:-central}"
FAILED=0

section() {
  printf '\n=== %s ===\n' "$1"
}

require_command() {
  if ! command -v "$1" >/dev/null 2>&1; then
    printf 'missing command: %s\n' "$1" >&2
    exit 127
  fi
}

require_command gh
require_command ssh
require_command curl

mark_failed() {
  FAILED=1
  printf '[FAIL] %s\n' "$1" >&2
}

bool_true() {
  case "${1:-}" in
    1 | true | TRUE | yes | YES | y | Y) return 0 ;;
    *) return 1 ;;
  esac
}

remote_psql_scalar() {
  local sql="$1"
  local quoted_sql
  quoted_sql="$(printf '%q' "$sql")"
  ssh -o BatchMode=yes -o ConnectTimeout=20 "$SSH_HOST" \
    "set -euo pipefail
     cd ${DEPLOY_DIR}
     docker compose exec -T ${DB_SERVICE} env PGOPTIONS='-c default_transaction_read_only=on' \
       psql -v ON_ERROR_STOP=1 -U ${DB_USER} -d ${DB_NAME} -Atc ${quoted_sql}"
}

assert_zero_count() {
  local name="$1"
  local count="$2"
  printf '%s=%s\n' "$name" "$count"
  if [ "${count:-0}" != "0" ]; then
    mark_failed "Policy audit failed: ${name}=${count}"
  fi
}

section "local tools"
printf 'gh: %s\n' "$(gh --version | head -1)"
printf 'ssh: %s\n' "$(ssh -V 2>&1)"
if command -v cloudflared >/dev/null 2>&1; then
  cloudflared --version
elif [ -x /opt/homebrew/bin/cloudflared ]; then
  /opt/homebrew/bin/cloudflared --version
else
  printf 'cloudflared: missing or not on PATH\n'
fi

section "github runner"
runner_row="$(
  gh api "repos/${REPO}/actions/runners" \
    --jq ".runners[] | select(.name==\"${RUNNER_NAME}\") | [.name,.status,.busy,(.labels|map(.name)|join(\",\"))] | @tsv"
)"
if [ -z "$runner_row" ]; then
  mark_failed "GitHub runner '${RUNNER_NAME}' was not found."
else
  printf '%s\n' "$runner_row"
fi
runner_status="$(printf '%s\n' "$runner_row" | awk -F '\t' 'NR==1 {print $2}')"
runner_busy="$(printf '%s\n' "$runner_row" | awk -F '\t' 'NR==1 {print $3}')"

section "active workflow runs"
gh run list --repo "$REPO" --limit 20 --json databaseId,name,status,conclusion,displayTitle,createdAt \
  --jq '.[] | select(.status != "completed") | [.createdAt,.databaseId,.name,.status,.conclusion,.displayTitle] | @tsv'

section "ssh and remote runner"
ssh -o BatchMode=yes -o ConnectTimeout=20 "$SSH_HOST" \
  "set -euo pipefail
   hostname
   uname -a
   sudo -n systemctl is-active ${RUNNER_SERVICE} || systemctl is-active ${RUNNER_SERVICE} || true
   sudo -n systemctl status ${RUNNER_SERVICE} --no-pager || systemctl status ${RUNNER_SERVICE} --no-pager || true
   printf '\n--- runner journal tail ---\n'
   sudo -n journalctl -u ${RUNNER_SERVICE} -n 80 --no-pager || journalctl -u ${RUNNER_SERVICE} -n 80 --no-pager || true
   printf '\n--- runner processes ---\n'
   ps -eo pid,ppid,stat,etime,cmd | grep -E '[R]unner.Listener|[R]unner.Worker|[r]unsvc' || true"

section "runner health verdict"
runner_summary="$(
  ssh -o BatchMode=yes -o ConnectTimeout=20 "$SSH_HOST" \
    "RUNNER_SERVICE='${RUNNER_SERVICE}' RUNNER_JOURNAL_SINCE='${RUNNER_JOURNAL_SINCE}' bash -s" <<'REMOTE'
set -euo pipefail
service_state="$(sudo -n systemctl is-active "$RUNNER_SERVICE" 2>/dev/null || systemctl is-active "$RUNNER_SERVICE" 2>/dev/null || true)"
central_listener_count="$(
  ps -eo cmd |
    awk '/actions-runner-central\/.*Runner.Listener|actions-runner-central\/bin\/Runner.Listener/ {count++} END {print count + 0}'
)"
central_worker_count="$(
  ps -eo cmd |
    awk '/actions-runner-central\/.*Runner.Worker|actions-runner-central\/bin.*Runner.Worker/ {count++} END {print count + 0}'
)"
recent_conflict_count="$(
  (sudo -n journalctl -u "$RUNNER_SERVICE" --since "$RUNNER_JOURNAL_SINCE" --no-pager 2>/dev/null ||
    journalctl -u "$RUNNER_SERVICE" --since "$RUNNER_JOURNAL_SINCE" --no-pager 2>/dev/null ||
    true) |
    awk 'BEGIN {IGNORECASE=1} /A session for this runner already exists|Runner connect error: Error: Conflict|session.*already exists|offline/ {count++} END {print count + 0}'
)"
printf 'service_state=%s\n' "$service_state"
printf 'central_listener_count=%s\n' "$central_listener_count"
printf 'central_worker_count=%s\n' "$central_worker_count"
printf 'recent_conflict_count=%s\n' "$recent_conflict_count"
REMOTE
)"
printf '%s\n' "$runner_summary"

service_state="$(printf '%s\n' "$runner_summary" | awk -F= '$1=="service_state" {print $2}')"
central_listener_count="$(printf '%s\n' "$runner_summary" | awk -F= '$1=="central_listener_count" {print $2}')"
central_worker_count="$(printf '%s\n' "$runner_summary" | awk -F= '$1=="central_worker_count" {print $2}')"
recent_conflict_count="$(printf '%s\n' "$runner_summary" | awk -F= '$1=="recent_conflict_count" {print $2}')"

repair_needed=0
if [ "$runner_status" != "online" ]; then
  repair_needed=1
  mark_failed "GitHub runner is not online: ${runner_status:-missing}"
fi
if [ "$service_state" != "active" ]; then
  repair_needed=1
  mark_failed "Remote runner systemd service is not active: ${service_state:-missing}"
fi
if [ "${central_listener_count:-0}" -lt 1 ]; then
  repair_needed=1
  mark_failed "central runner listener process is missing."
fi
if [ "${recent_conflict_count:-0}" -gt 0 ]; then
  repair_needed=1
  mark_failed "Runner session conflict exists within ${RUNNER_JOURNAL_SINCE}."
fi
if [ "$runner_busy" = "true" ] && [ "${central_worker_count:-0}" -eq 0 ]; then
  repair_needed=1
  mark_failed "GitHub reports busy=true but central Runner.Worker is missing; likely cancelled-job residue."
fi

if [ "$repair_needed" -eq 0 ]; then
  printf '[OK] runner healthy: GitHub=%s busy=%s service=%s listener=%s worker=%s recent_conflict=%s\n' \
    "$runner_status" "$runner_busy" "$service_state" "$central_listener_count" "$central_worker_count" "$recent_conflict_count"
elif bool_true "$REPAIR_RUNNER"; then
  section "runner repair"
  ssh -o BatchMode=yes -o ConnectTimeout=20 "$SSH_HOST" \
    "set -euo pipefail
     sudo -n systemctl restart ${RUNNER_SERVICE} || systemctl restart ${RUNNER_SERVICE}
     sleep 8
     sudo -n systemctl is-active ${RUNNER_SERVICE} || systemctl is-active ${RUNNER_SERVICE}
     ps -eo pid,ppid,stat,etime,cmd | grep -E '[a]ctions-runner-central/.*/Runner.Listener|[a]ctions-runner-central/bin/Runner.Listener' || true"
  printf '[OK] runner restart issued. Re-run this script once to verify GitHub online/busy state.\n'
  FAILED=0
else
  printf 'To repair, run explicitly: CENTRAL_OPS_REPAIR_RUNNER=true %s\n' "$0" >&2
fi

section "remote compose and health"
ssh -o BatchMode=yes -o ConnectTimeout=20 "$SSH_HOST" \
  "set -euo pipefail
   cd ${DEPLOY_DIR}
   docker compose ps
   printf '\n--- deployed image ---\n'
   grep -E '^(CENTRAL_IMAGE|APP_PORT|SERVER_PORT)=' .env || true
   printf '\n--- local health ---\n'
   curl -fsS http://localhost:${APP_PORT}/actuator/health
   printf '\n--- recent central logs ---\n'
   docker compose logs --tail=80 central-server"

section "public health"
curl -fsS https://discord-ai.yeon.world/actuator/health
printf '\n'

section "ops policy audit"
missing_auto_respond_allow_list="$(
  remote_psql_scalar "
WITH restricted_guilds AS (
  SELECT guild_id
  FROM allowed_channel
  GROUP BY guild_id
  HAVING COUNT(*) > 0
)
SELECT COUNT(*)
FROM channel_ai ca
JOIN restricted_guilds rg ON rg.guild_id = ca.guild_id
LEFT JOIN allowed_channel ac ON ac.guild_id = ca.guild_id AND ac.channel_id = ca.channel_id
WHERE ca.auto_respond = TRUE
  AND ac.channel_id IS NULL;
"
)"
broken_auto_respond_behavior="$(
  remote_psql_scalar "
SELECT COUNT(*)
FROM channel_ai ca
LEFT JOIN ai_behavior_version bv ON bv.id = ca.active_behavior_version_id AND bv.channel_ai_id = ca.id
WHERE ca.auto_respond = TRUE
  AND (ca.active_behavior_version_id IS NULL OR bv.id IS NULL);
"
)"
stale_routing_policy_channel_ai="$(
  remote_psql_scalar "
SELECT COUNT(*)
FROM channel_ai_routing_policy rp
LEFT JOIN channel_ai ca ON ca.id = rp.channel_ai_id
WHERE rp.channel_ai_id IS NOT NULL
  AND (ca.id IS NULL OR ca.guild_id <> rp.guild_id OR ca.channel_id <> rp.channel_id);
"
)"

assert_zero_count "missing_auto_respond_allow_list" "$missing_auto_respond_allow_list"
assert_zero_count "broken_auto_respond_behavior" "$broken_auto_respond_behavior"
assert_zero_count "stale_routing_policy_channel_ai" "$stale_routing_policy_channel_ai"

exit "$FAILED"
