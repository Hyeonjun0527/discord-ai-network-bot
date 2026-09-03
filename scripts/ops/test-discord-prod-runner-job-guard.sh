#!/usr/bin/env bash
set -euo pipefail

readonly SCRIPT_DIR="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
readonly GUARD="${SCRIPT_DIR}/discord-prod-runner-job-guard.sh"
readonly REPOSITORY="Hyeonjun0527/discord-ai-network-bot"

run_guard() {
  env -i \
    PATH="$PATH" \
    GITHUB_REPOSITORY="$REPOSITORY" \
    GITHUB_EVENT_NAME="$1" \
    GITHUB_REF="$2" \
    GITHUB_WORKFLOW_REF="$REPOSITORY/$3@$2" \
    bash "$GUARD"
}

run_guard push refs/heads/main .github/workflows/central-deploy.yml >/dev/null
run_guard workflow_dispatch refs/heads/main .github/workflows/central-ops-audit.yml >/dev/null
run_guard schedule refs/heads/main .github/workflows/central-ops-audit.yml >/dev/null
run_guard workflow_dispatch refs/heads/main .github/workflows/central-speech-style-rag-import.yml >/dev/null
run_guard push refs/tags/agent-v1.2.3 .github/workflows/agent-build.yml >/dev/null

if run_guard pull_request refs/pull/10/merge .github/workflows/central-deploy.yml >/dev/null 2>&1; then
  echo "PR job을 운영 runner에 허용했습니다." >&2
  exit 1
fi

if run_guard push refs/heads/main .github/workflows/central-server-ci.yml >/dev/null 2>&1; then
  echo "CI workflow를 운영 runner에 허용했습니다." >&2
  exit 1
fi

if run_guard workflow_dispatch refs/heads/main .github/workflows/central-server-deploy.yml >/dev/null 2>&1; then
  echo "삭제한 구 배포 workflow를 운영 runner에 허용했습니다." >&2
  exit 1
fi

if run_guard workflow_dispatch refs/heads/feature/test .github/workflows/central-ops-audit.yml >/dev/null 2>&1; then
  echo "main 밖의 수동 운영 job을 허용했습니다." >&2
  exit 1
fi

echo "discord production runner guard tests passed"
