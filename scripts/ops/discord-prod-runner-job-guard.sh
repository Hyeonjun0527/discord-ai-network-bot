#!/usr/bin/env bash
set -euo pipefail

readonly EXPECTED_REPOSITORY="Hyeonjun0527/discord-ai-network-bot"

deny() {
  echo "운영 AMD64 runner가 허용되지 않은 job을 거부했습니다: $1" >&2
  exit 1
}

[[ "${GITHUB_REPOSITORY:-}" == "$EXPECTED_REPOSITORY" ]] || deny "repository"
[[ -n "${GITHUB_WORKFLOW_REF:-}" ]] || deny "workflow-ref"

workflow_path="${GITHUB_WORKFLOW_REF#${EXPECTED_REPOSITORY}/}"
workflow_path="${workflow_path%@*}"

case "$workflow_path" in
  .github/workflows/central-deploy.yml)
    [[ "${GITHUB_REF:-}" == "refs/heads/main" ]] || deny "central-deploy-ref"
    [[ "${GITHUB_EVENT_NAME:-}" == "push" || "${GITHUB_EVENT_NAME:-}" == "workflow_dispatch" ]] || deny "central-deploy-event"
    ;;
  .github/workflows/central-ops-audit.yml)
    [[ "${GITHUB_REF:-}" == "refs/heads/main" ]] || deny "central-ops-ref"
    [[ "${GITHUB_EVENT_NAME:-}" == "schedule" || "${GITHUB_EVENT_NAME:-}" == "workflow_dispatch" ]] || deny "central-ops-event"
    ;;
  .github/workflows/central-speech-style-rag-import.yml)
    [[ "${GITHUB_REF:-}" == "refs/heads/main" ]] || deny "manual-production-ref"
    [[ "${GITHUB_EVENT_NAME:-}" == "workflow_dispatch" ]] || deny "manual-production-event"
    ;;
  .github/workflows/agent-build.yml)
    [[ "${GITHUB_EVENT_NAME:-}" == "push" ]] || deny "agent-build-event"
    [[ "${GITHUB_REF:-}" == refs/tags/agent-v* ]] || deny "agent-build-ref"
    ;;
  *)
    deny "workflow-path"
    ;;
esac

echo "운영 AMD64 runner job 허용: ${workflow_path}"
