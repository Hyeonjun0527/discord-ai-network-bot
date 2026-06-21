#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd -- "$SCRIPT_DIR/.." && pwd)"
CENTRAL_JAVA_HOME="${NEXA_JAVA_HOME:-/Library/Java/JavaVirtualMachines/amazon-corretto-21.jdk/Contents/Home}"
cd "$REPO_ROOT"

show_usage() {
  cat <<'USAGE'
사용법: ./scripts/nexa-verify.sh <scope> [scope...]

scope:
  docs      task graph, NEXA fixture, 문서 링크, diff 공백 검사
  central   central-server build
  agent     provider-agent pytest/ruff/mypy
  ml        ml/social-policy pytest/ruff/mypy (학습 데이터셋 빌더)
  i18n      i18n SSOT completeness + generated artifact drift
  protocol  wire contract drift + 양측 contract 테스트
  contracts protocol 과 동일한 alias
  all       docs, central, agent, protocol 순서로 모두 실행
USAGE
}

print_command() {
  printf '+ '
  printf '%q ' "$@"
  printf '\n'
}

run_command() {
  print_command "$@"
  "$@"
}

verify_docs() {
  run_command python3 scripts/validate-nexa-task-graph.py
  run_command python3 scripts/central-package-graph.py --check
  run_command python3 scripts/validate-nexa-conversation-fixtures.py
  run_command python3 scripts/validate-nexa-policy-fixtures.py
  run_command python3 scripts/validate-nexa-architecture-ssot.py
  run_command python3 scripts/check_links.py
  run_command git diff --check
}

verify_central() {
  run_command env JAVA_HOME="$CENTRAL_JAVA_HOME" make central-build
}

verify_agent() {
  (
    cd provider-agent
    run_command ../.venv/bin/python -m pytest -q --cov=provider_agent --cov-fail-under=70
    run_command ../.venv/bin/ruff check src tests
    run_command ../.venv/bin/mypy src
  )
}

verify_ml() {
  (
    cd ml/social-policy
    run_command ../../.venv/bin/python -m pytest -q
    run_command ../../.venv/bin/ruff check src tests
    run_command ../../.venv/bin/mypy src
  )
}

verify_i18n() {
  run_command make i18n-check
}

verify_protocol() {
  run_command env JAVA_HOME="$CENTRAL_JAVA_HOME" make contract
}

verify_all() {
  verify_docs
  verify_central
  verify_agent
  verify_i18n
  verify_protocol
}

run_scope() {
  case "$1" in
    docs) verify_docs ;;
    central) verify_central ;;
    agent) verify_agent ;;
    ml) verify_ml ;;
    i18n) verify_i18n ;;
    protocol|contracts) verify_protocol ;;
    all) verify_all ;;
    -h|--help|help)
      show_usage
      ;;
    *)
      show_usage >&2
      printf '알 수 없는 scope: %s\n' "$1" >&2
      exit 2
      ;;
  esac
}

if [[ "$#" -eq 0 ]]; then
  show_usage >&2
  exit 2
fi

for scope in "$@"; do
  run_scope "$scope"
done
