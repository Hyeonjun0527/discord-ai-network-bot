#!/usr/bin/env bash
# scripts/restore.sh — 백업본(.db)을 현재 DB로 복원한다 (#28).
#
# 동작:
#   1) 백업 파일의 무결성 검증 (PRAGMA integrity_check)
#   2) 현재 DB를 안전 백업 (data/backups/pre-restore_<ts>.db) 후
#   3) WAL/SHM 사이드카를 정리하고 백업본으로 교체
#
# 사용법:
#   bash scripts/restore.sh <backup-file.db>
#   bash scripts/restore.sh data/backups/bot_2026-05-29.db
#   FORCE=1 bash scripts/restore.sh <backup-file.db>   # 확인 프롬프트 생략(비대화형)
#
# 환경변수:
#   DATABASE_URL  — 대상 DB 경로(기본: sqlite:///./data/discord_assistant.db)
#   FORCE         — 1 이면 대화형 확인을 건너뛴다 (cron/CI 용)
#
# 주의: 봇을 멈춘 상태에서 복원하라(파일 잠금/WAL 충돌 방지).
#   docker compose -f compose.prod.yml stop bot
#   bash scripts/restore.sh data/backups/bot_YYYY-MM-DD.db
#   docker compose -f compose.prod.yml start bot
set -euo pipefail

LOG_PREFIX="[$(date '+%Y-%m-%d %H:%M:%S')]"

# ── 인자 검사 ─────────────────────────────────────────────────────────────────
if [[ $# -lt 1 ]]; then
  echo "Usage: bash scripts/restore.sh <backup-file.db>" >&2
  exit 2
fi
SRC="$1"

if [[ ! -f "$SRC" ]]; then
  echo "$LOG_PREFIX ERROR: 백업 파일을 찾을 수 없습니다: '$SRC'" >&2
  exit 1
fi

# ── 대상 DB 경로 해석 (DATABASE_URL 또는 기본값) ─────────────────────────────
DATABASE_URL="${DATABASE_URL:-sqlite:///./data/discord_assistant.db}"
DB_PATH="${DATABASE_URL#sqlite:///}"

echo "$LOG_PREFIX 복원 시작: '$SRC' → '$DB_PATH'"

# ── 백업 파일 무결성 검증 ────────────────────────────────────────────────────
if command -v sqlite3 &>/dev/null; then
  INTEGRITY="$(sqlite3 "$SRC" "PRAGMA integrity_check;" 2>&1 || echo "check-failed")"
  if [[ "$INTEGRITY" != "ok" ]]; then
    echo "$LOG_PREFIX ERROR: 백업 무결성 검증 실패: $INTEGRITY. 복원을 중단합니다." >&2
    exit 1
  fi
  echo "$LOG_PREFIX 무결성 검증 통과 (PRAGMA integrity_check = ok)."
else
  echo "$LOG_PREFIX WARNING: sqlite3 미설치 — 무결성 검증을 건너뜁니다." >&2
fi

# ── 비대화형이 아니면 확인 프롬프트 ──────────────────────────────────────────
if [[ "${FORCE:-0}" != "1" ]]; then
  # stdin 이 tty 가 아니면(cron/CI/파이프) read 가 빈 입력으로 즉시 'N' 취소되어
  # 복원이 안 된 채 exit 0(성공)으로 끝나 운영자가 '복원됨'으로 오판할 수 있다.
  # 자동화에서의 무음 스킵을 막기 위해 명시적으로 에러로 중단한다.
  if [[ ! -t 0 ]]; then
    echo "$LOG_PREFIX ERROR: 비대화형 환경에서는 확인 프롬프트를 받을 수 없습니다. 복원하려면 FORCE=1 을 설정하세요." >&2
    exit 1
  fi
  if [[ -f "$DB_PATH" ]]; then
    echo "$LOG_PREFIX 경고: 현재 DB '$DB_PATH' 를 덮어씁니다 (안전 백업은 자동 생성)."
  fi
  read -r -p "계속하시겠습니까? [y/N] " REPLY
  if [[ "$REPLY" != "y" && "$REPLY" != "Y" ]]; then
    echo "$LOG_PREFIX 사용자가 취소했습니다."
    exit 0
  fi
fi

# ── 대상 디렉터리 보장 ───────────────────────────────────────────────────────
DB_DIR="$(dirname "$DB_PATH")"
mkdir -p "$DB_DIR"

# ── 현재 DB 안전 백업 (존재하는 경우) ────────────────────────────────────────
if [[ -f "$DB_PATH" ]]; then
  SAFETY_DIR="$DB_DIR/backups"
  mkdir -p "$SAFETY_DIR"
  SAFETY="$SAFETY_DIR/pre-restore_$(date +%Y-%m-%d_%H-%M-%S).db"
  if command -v sqlite3 &>/dev/null; then
    # .backup 은 WAL 의 최신 커밋까지 포함한 일관 스냅샷을 만든다.
    sqlite3 "$DB_PATH" ".backup '$SAFETY'"
  else
    cp "$DB_PATH" "$SAFETY"
    [[ -f "${DB_PATH}-wal" ]] && cp "${DB_PATH}-wal" "${SAFETY}-wal"
    [[ -f "${DB_PATH}-shm" ]] && cp "${DB_PATH}-shm" "${SAFETY}-shm"
  fi
  echo "$LOG_PREFIX 기존 DB 안전 백업: $SAFETY"
else
  echo "$LOG_PREFIX 기존 DB 없음 — 안전 백업 생략, 새로 생성합니다."
fi

# ── WAL/SHM 사이드카 정리 후 교체 ────────────────────────────────────────────
# 복원 후 옛 WAL 이 남아 있으면 새 DB 위에 옛 트랜잭션이 재적용될 수 있어 제거한다.
rm -f "${DB_PATH}-wal" "${DB_PATH}-shm"

if command -v sqlite3 &>/dev/null; then
  # 백업본을 대상에 .backup 으로 기록하면 WAL 잔재 없이 깔끔히 복원된다.
  rm -f "$DB_PATH"
  sqlite3 "$SRC" ".backup '$DB_PATH'"
else
  cp "$SRC" "$DB_PATH"
fi

echo "$LOG_PREFIX 복원 완료: $DB_PATH ($(du -sh "$DB_PATH" | cut -f1))"

# ── 복원된 DB 재검증 ─────────────────────────────────────────────────────────
if command -v sqlite3 &>/dev/null; then
  POST="$(sqlite3 "$DB_PATH" "PRAGMA integrity_check;" 2>&1 || echo "check-failed")"
  if [[ "$POST" != "ok" ]]; then
    echo "$LOG_PREFIX ERROR: 복원 후 무결성 검증 실패: $POST" >&2
    exit 1
  fi
  echo "$LOG_PREFIX 복원 후 무결성 검증 통과."
fi

echo "$LOG_PREFIX 끝. 봇을 다시 시작하세요 (예: docker compose -f compose.prod.yml start bot)."
