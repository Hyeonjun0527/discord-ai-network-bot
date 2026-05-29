#!/usr/bin/env bash
# scripts/backup.sh — Daily SQLite backup with 7-day retention.
#
# Usage:
#   bash scripts/backup.sh
#
# Cron (see deploy/backup.cron):
#   0 3 * * * cd /opt/discord-assistant && bash scripts/backup.sh >> logs/backup.log 2>&1
set -euo pipefail

TIMESTAMP="$(date +%Y-%m-%d)"
LOG_PREFIX="[$(date '+%Y-%m-%d %H:%M:%S')]"

# ── Resolve source DB path from DATABASE_URL or default ──────────────────────
DATABASE_URL="${DATABASE_URL:-sqlite:///./data/discord_assistant.db}"
# Strip sqlite:/// prefix
DB_PATH="${DATABASE_URL#sqlite:///}"

# ── Backup destination directory ─────────────────────────────────────────────
BACKUP_DIR="$(dirname "$DB_PATH")/backups"

echo "$LOG_PREFIX Starting backup of '$DB_PATH' to '$BACKUP_DIR/'"

# ── Validate source file ──────────────────────────────────────────────────────
if [[ ! -f "$DB_PATH" ]]; then
  echo "$LOG_PREFIX ERROR: Source DB not found at '$DB_PATH'. Aborting." >&2
  exit 1
fi

# ── Create backup directory if it does not exist ─────────────────────────────
mkdir -p "$BACKUP_DIR"

# ── Copy the database file ────────────────────────────────────────────────────
DEST="$BACKUP_DIR/bot_${TIMESTAMP}.db"
# Use sqlite3 .backup for a consistent snapshot when sqlite3 is available;
# fall back to cp otherwise.
if command -v sqlite3 &>/dev/null; then
  sqlite3 "$DB_PATH" ".backup '$DEST'"
else
  # WAL mode keeps recent commits in the -wal sidecar; copy it (and -shm) too
  # so the fallback snapshot is not missing the latest transactions.
  echo "$LOG_PREFIX WARNING: sqlite3 not found — using cp fallback (copies -wal/-shm if present)." >&2
  cp "$DB_PATH" "$DEST"
  [[ -f "${DB_PATH}-wal" ]] && cp "${DB_PATH}-wal" "${DEST}-wal"
  [[ -f "${DB_PATH}-shm" ]] && cp "${DB_PATH}-shm" "${DEST}-shm"
fi
echo "$LOG_PREFIX Backup written: $DEST ($(du -sh "$DEST" | cut -f1))"

# ── Verify backup integrity ──────────────────────────────────────────────────
if command -v sqlite3 &>/dev/null; then
  INTEGRITY="$(sqlite3 "$DEST" "PRAGMA integrity_check;" 2>&1 || echo "check-failed")"
  if [[ "$INTEGRITY" != "ok" ]]; then
    echo "$LOG_PREFIX ERROR: Backup integrity check failed: $INTEGRITY. Removing corrupt backup." >&2
    rm -f "$DEST"
    exit 1
  fi
  echo "$LOG_PREFIX Integrity check passed (PRAGMA integrity_check = ok)."
else
  echo "$LOG_PREFIX WARNING: sqlite3 not found — skipping integrity check."
fi

# ── 오프호스트 복제 (선택) ───────────────────────────────────────────────────
# BACKUP_REMOTE 가 설정되면 검증된 백업본을 원격으로 복제한다 (#75).
#   - rclone remote(예: "myremote:bot-backups") → rclone 사용
#   - 그 외(예: "user@host:/srv/backups" 또는 "/mnt/nas/backups") → rsync 사용
# 미설정 시 조용히 스킵. 시크릿/자격증명은 rclone/ssh 설정 또는 환경에 위임하며
# 이 스크립트는 어떤 비밀도 출력하지 않는다.
BACKUP_REMOTE="${BACKUP_REMOTE:-}"
if [[ -n "$BACKUP_REMOTE" ]]; then
  if [[ "$BACKUP_REMOTE" == *:* && "$BACKUP_REMOTE" != /* && "$BACKUP_REMOTE" != *@*:* ]] \
     && command -v rclone &>/dev/null; then
    # "remote:path" 형태이고 rclone 이 있으면 rclone 으로 복제
    echo "$LOG_PREFIX Replicating backup to rclone remote (target redacted)…"
    if rclone copy "$DEST" "$BACKUP_REMOTE/" >/dev/null 2>&1; then
      echo "$LOG_PREFIX Off-host replication via rclone succeeded."
    else
      echo "$LOG_PREFIX WARNING: rclone replication failed (backup kept locally)." >&2
    fi
  elif command -v rsync &>/dev/null; then
    # rsync 대상(로컬 경로 또는 user@host:path)
    echo "$LOG_PREFIX Replicating backup to rsync target (target redacted)…"
    if rsync -a "$DEST" "$BACKUP_REMOTE" >/dev/null 2>&1; then
      echo "$LOG_PREFIX Off-host replication via rsync succeeded."
    else
      echo "$LOG_PREFIX WARNING: rsync replication failed (backup kept locally)." >&2
    fi
  else
    echo "$LOG_PREFIX WARNING: BACKUP_REMOTE set but neither rclone nor rsync available — skipping." >&2
  fi
fi

# ── Prune backups older than 7 days ─────────────────────────────────────────
KEEP=7
DELETED=0
# List backups by modification time (oldest first), skip the newest $KEEP files
mapfile -t OLD_BACKUPS < <(
  ls -1t "$BACKUP_DIR"/bot_*.db 2>/dev/null | tail -n +"$((KEEP + 1))"
)
for old in "${OLD_BACKUPS[@]}"; do
  rm -f "$old"
  echo "$LOG_PREFIX Deleted old backup: $old"
  DELETED=$((DELETED + 1))
done

if [[ $DELETED -eq 0 ]]; then
  echo "$LOG_PREFIX No old backups to prune."
else
  echo "$LOG_PREFIX Pruned $DELETED backup(s)."
fi

echo "$LOG_PREFIX Backup complete. Current backups:"
ls -lh "$BACKUP_DIR"/bot_*.db 2>/dev/null | awk '{print "  " $0}' || true
