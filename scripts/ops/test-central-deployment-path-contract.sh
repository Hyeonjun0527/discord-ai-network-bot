#!/usr/bin/env bash
set -euo pipefail

readonly deploy_dir="/srv/central-server"
readonly checked_files=(
  .github/workflows/central-deploy.yml
  .github/workflows/central-ops-audit.yml
  .github/workflows/central-speech-style-rag-import.yml
  central-server/docs/OPERATIONS.md
  central-server/scripts/ops_policy_audit.sh
  docs/nexa/human-dialogue-speech-rag-runtime.md
)

for file in "${checked_files[@]}"; do
  if grep -Eq '\$HOME/deploy/central-server|~/deploy/central-server' "${file}"; then
    echo "중앙 서버의 폐기된 홈 디렉터리 배포 경로가 남아 있습니다: ${file}" >&2
    exit 1
  fi
done

for workflow in \
  .github/workflows/central-deploy.yml \
  .github/workflows/central-ops-audit.yml \
  .github/workflows/central-speech-style-rag-import.yml; do
  grep -Fq "${deploy_dir}" "${workflow}" || {
    echo "중앙 서버 배포 경로 계약이 없습니다: ${workflow}" >&2
    exit 1
  }
done

echo "central deployment path contract tests passed"
