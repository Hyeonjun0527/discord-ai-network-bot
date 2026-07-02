#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd -- "$SCRIPT_DIR/.." && pwd)"
CENTRAL_JAVA_HOME="${NEXA_JAVA_HOME:-${JAVA_HOME:-/Library/Java/JavaVirtualMachines/amazon-corretto-21.jdk/Contents/Home}}"
PYTHON_BIN="${NEXA_PYTHON:-}"
if [[ -z "$PYTHON_BIN" ]]; then
  if [[ -x "$REPO_ROOT/.venv/bin/python" ]]; then
    PYTHON_BIN="$REPO_ROOT/.venv/bin/python"
  else
    PYTHON_BIN="python3"
  fi
fi
cd "$REPO_ROOT"

show_usage() {
  cat <<'USAGE'
사용법: ./scripts/nexa-verify.sh <scope> [scope...]

scope:
  docs      task graph, NEXA fixture, 문서 링크, diff 공백 검사
  nia       NIA humanlike participation 최종 검증(docs, fixtures, targeted tests, raw-log scan)
  central   central-server build
  agent     provider-agent pytest/ruff/mypy
  ml        ml/social-policy pytest/ruff/mypy (학습 데이터셋 빌더)
  i18n      i18n SSOT completeness + generated artifact drift
  protocol  wire contract drift + 양측 contract 테스트
  security-redaction  focused log redaction test + scanner gate
  contracts protocol 과 동일한 alias
  all       docs, central, agent, protocol 순서로 모두 실행

환경 변수:
  NEXA_JAVA_HOME   central-server 검증에 사용할 JDK 21 경로
  NEXA_PYTHON      docs/security 스크립트에 사용할 Python 경로(.venv 우선 자동 감지)
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
  run_command "$PYTHON_BIN" scripts/validate-nexa-task-graph.py
  run_command "$PYTHON_BIN" scripts/central-package-graph.py --check
  run_command "$PYTHON_BIN" scripts/validate-nexa-conversation-fixtures.py
  run_command "$PYTHON_BIN" scripts/validate-nexa-scenarios.py
  run_command "$PYTHON_BIN" scripts/validate-nexa-intervention-evals.py
  run_command "$PYTHON_BIN" scripts/validate-nexa-eval-report.py
  run_command "$PYTHON_BIN" scripts/validate-nexa-policy-fixtures.py
  run_command "$PYTHON_BIN" scripts/validate-nexa-architecture-ssot.py
  run_command "$PYTHON_BIN" scripts/validate-nexa-scan-exceptions.py
  run_command "$PYTHON_BIN" scripts/check_links.py
  run_command git diff --check
}

verify_nia() {
  run_command scripts/verify-nia-humanlike-participation.sh
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

verify_security_redaction() {
  local log_dir="${LOG_DIR:-}"
  if [[ -z "$log_dir" ]]; then
    log_dir="$(mktemp -d "${TMPDIR:-/tmp}/nexa-log-redaction.XXXXXX")"
  fi

  rm -rf "$log_dir"
  mkdir -p "$log_dir"
  (
    cd central-server
    run_command env JAVA_HOME="$CENTRAL_JAVA_HOME" SPRING_PROFILES_ACTIVE=prod LOG_DIR="$log_dir" ./gradlew cleanTest test \
      --no-daemon --console=plain \
      --tests "*SensitiveLoggingTest" \
      --tests "*RedactingMessageConverterTest"
  )

  shopt -s nullglob
  local logs=("$log_dir"/*.log "$log_dir"/*.log.gz)
  if [[ "${#logs[@]}" -eq 0 ]]; then
    printf 'redaction focused test did not create log files under LOG_DIR: %s\n' "$log_dir" >&2
    exit 1
  fi

  local gz_log
  for gz_log in "$log_dir"/*.log.gz; do
    [[ -e "$gz_log" ]] && gunzip -kf "$gz_log"
  done

  logs=("$log_dir"/*.log)
  if [[ "${#logs[@]}" -eq 0 ]]; then
    printf 'redaction focused test did not leave .log files after gunzip under LOG_DIR: %s\n' "$log_dir" >&2
    exit 1
  fi

  run_command "$PYTHON_BIN" scripts/scan-sensitive-logs.py "${logs[@]}"
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
    nia) verify_nia ;;
    central) verify_central ;;
    agent) verify_agent ;;
    ml) verify_ml ;;
    i18n) verify_i18n ;;
    security-redaction|redaction) verify_security_redaction ;;
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
