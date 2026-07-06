#!/usr/bin/env bash
set -euo pipefail

REPO="${GH_RUN_WATCH_REPO:-Hyeonjun0527/discord-ai-network-bot}"
INTERVAL_SECONDS="${GH_RUN_WATCH_INTERVAL_SECONDS:-480}"
MAX_CHECKS="${GH_RUN_WATCH_MAX_CHECKS:-30}"
ALLOW_FAST="${GH_RUN_WATCH_ALLOW_FAST:-false}"

usage() {
  cat <<'USAGE' >&2
Usage: scripts/gh-run-watch-safe.sh RUN_ID [repo]

Poll one GitHub Actions run without burning API quota.

Environment:
  GH_RUN_WATCH_REPO              default: Hyeonjun0527/discord-ai-network-bot
  GH_RUN_WATCH_INTERVAL_SECONDS  default: 480 (8 minutes)
  GH_RUN_WATCH_MAX_CHECKS        default: 30
  GH_RUN_WATCH_ALLOW_FAST=true   allow interval below 480 seconds for a deliberate emergency watch
USAGE
}

bool_true() {
  case "${1:-}" in
    1 | true | TRUE | yes | YES | y | Y) return 0 ;;
    *) return 1 ;;
  esac
}

require_command() {
  if ! command -v "$1" >/dev/null 2>&1; then
    printf 'missing command: %s\n' "$1" >&2
    exit 127
  fi
}

if [ "${1:-}" = "-h" ] || [ "${1:-}" = "--help" ] || [ "$#" -lt 1 ]; then
  usage
  exit 64
fi

require_command gh

RUN_ID="$1"
if [ "$#" -ge 2 ]; then
  REPO="$2"
fi

if ! [[ "$INTERVAL_SECONDS" =~ ^[0-9]+$ ]] || [ "$INTERVAL_SECONDS" -le 0 ]; then
  printf 'GH_RUN_WATCH_INTERVAL_SECONDS must be a positive integer: %s\n' "$INTERVAL_SECONDS" >&2
  exit 64
fi
if ! [[ "$MAX_CHECKS" =~ ^[0-9]+$ ]] || [ "$MAX_CHECKS" -le 0 ]; then
  printf 'GH_RUN_WATCH_MAX_CHECKS must be a positive integer: %s\n' "$MAX_CHECKS" >&2
  exit 64
fi
if [ "$INTERVAL_SECONDS" -lt 480 ] && ! bool_true "$ALLOW_FAST"; then
  printf 'Refusing interval %ss. Use >=480s or set GH_RUN_WATCH_ALLOW_FAST=true deliberately.\n' "$INTERVAL_SECONDS" >&2
  exit 64
fi

printf 'Watching run %s in %s every %ss, max checks=%s\n' "$RUN_ID" "$REPO" "$INTERVAL_SECONDS" "$MAX_CHECKS"

for check in $(seq 1 "$MAX_CHECKS"); do
  row="$(
    gh run view "$RUN_ID" \
      --repo "$REPO" \
      --json databaseId,name,status,conclusion,headSha,url,createdAt,updatedAt \
      --jq '[.databaseId, .status, (.conclusion // ""), .name, .headSha, .updatedAt, .url] | @tsv'
  )"
  printf '[%s/%s] %s\n' "$check" "$MAX_CHECKS" "$row"

  status="$(printf '%s\n' "$row" | awk -F '\t' '{print $2}')"
  conclusion="$(printf '%s\n' "$row" | awk -F '\t' '{print $3}')"
  if [ "$status" = "completed" ]; then
    [ "$conclusion" = "success" ]
    exit $?
  fi

  if [ "$check" -lt "$MAX_CHECKS" ]; then
    sleep "$INTERVAL_SECONDS"
  fi
done

printf 'Run %s did not complete within %s checks.\n' "$RUN_ID" "$MAX_CHECKS" >&2
exit 124
