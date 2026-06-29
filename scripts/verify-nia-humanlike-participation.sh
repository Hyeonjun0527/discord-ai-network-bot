#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd -- "$SCRIPT_DIR/.." && pwd)"
CENTRAL_JAVA_HOME="${NEXA_JAVA_HOME:-/Library/Java/JavaVirtualMachines/amazon-corretto-21.jdk/Contents/Home}"
LOG_DIR="$(mktemp -d "${TMPDIR:-/tmp}/nia-humanlike-verify.XXXXXX")"
LOG_FILE="$LOG_DIR/verify.log"

cd "$REPO_ROOT"

if [[ -n "${NEXA_PYTHON:-}" ]]; then
  PYTHON_BIN="$NEXA_PYTHON"
elif [[ -x "$REPO_ROOT/.venv/bin/python" ]]; then
  PYTHON_BIN="$REPO_ROOT/.venv/bin/python"
else
  PYTHON_BIN="python3"
fi

cleanup() {
  if [[ "${NEXA_KEEP_VERIFY_LOG:-0}" == "1" ]]; then
    printf 'verification log kept: %s\n' "$LOG_FILE" >&2
  else
    rm -rf "$LOG_DIR"
  fi
}
trap cleanup EXIT

print_command() {
  printf '+ '
  printf '%q ' "$@"
  printf '\n'
}

contains_raw_canary() {
  local path="$1"
  local patterns=(
    "RAW_CONTEXT_LEAK_CANARY"
    "TOP-SECRET-원문-메시지-내용-leak-canary"
    "원문-PII-leak-canary"
    "위로해줘야지"
  )
  local pattern
  for pattern in "${patterns[@]}"; do
    if grep -Fq -- "$pattern" "$path"; then
      return 0
    fi
  done
  return 1
}

run_logged() {
  print_command "$@"
  local status=0
  "$@" >>"$LOG_FILE" 2>&1 || status="$?"
  if [[ "$status" -eq 0 ]]; then
    return 0
  fi

  if contains_raw_canary "$LOG_FILE"; then
    printf 'FAIL: verification output contained a raw-context canary; log body suppressed.\n' >&2
  else
    tail -n 80 "$LOG_FILE" >&2
  fi
  return "$status"
}

verify_no_raw_log() {
  run_logged "$PYTHON_BIN" scripts/scan-sensitive-logs.py "$LOG_FILE"
  if contains_raw_canary "$LOG_FILE"; then
    printf 'FAIL: verification output contained a raw-context canary.\n' >&2
    return 1
  fi
}

verify_docs_and_fixtures() {
  run_logged "$PYTHON_BIN" scripts/validate-nexa-task-graph.py
  run_logged "$PYTHON_BIN" scripts/validate-nexa-conversation-fixtures.py
  run_logged "$PYTHON_BIN" scripts/validate-nexa-scenarios.py
  run_logged "$PYTHON_BIN" scripts/validate-nexa-intervention-evals.py
  run_logged "$PYTHON_BIN" scripts/validate-nexa-eval-report.py
  run_logged "$PYTHON_BIN" scripts/validate-nexa-policy-fixtures.py
  run_logged "$PYTHON_BIN" scripts/validate-nexa-scan-exceptions.py
  run_logged "$PYTHON_BIN" scripts/check_links.py
}

verify_central_nia_tests() {
  run_logged env JAVA_HOME="$CENTRAL_JAVA_HOME" central-server/gradlew -p central-server test \
    --tests com.discordassistant.central.conversation.domain.service.rawcontext.RawContextRingBufferTest \
    --tests com.discordassistant.central.conversation.adapter.outbound.persistence.JpaRawContextStoreTest \
    --tests com.discordassistant.central.global.privacy.NiaRawContextPrivacyBoundaryTest \
    --tests com.discordassistant.central.global.privacy.ConsentRevocationEndToEndTest \
    --tests com.discordassistant.central.speech.application.NexaSpeechPipelineServiceTest \
    --tests com.discordassistant.central.speech.generation.CandidateGenerationServiceTest \
    --tests com.discordassistant.central.speech.prompt.ConversationContentIsolatorTest \
    --no-daemon \
    --console=plain
}

verify_docs_and_fixtures
verify_central_nia_tests
verify_no_raw_log
run_logged git diff --check

printf 'NIA humanlike participation verification passed.\n'
