#!/usr/bin/env bash
# scripts/backup.sh — Daily SQLite backup with 7-day retention.
#
# Usage:
#   bash scripts/backup.sh
#
# Cron (see deploy/backup.cron):
#   0 3 * * * cd /opt/discord-assistant && bash scripts/backup.sh >> logs/backup.log 2>&1
set -euo pipefail

# Include time (not just date) so a second run on the same day does not silently
# overwrite the first day's backup. Prune still globs bot_*.db and sorts by mtime.
TIMESTAMP="$(date +%Y-%m-%d_%H-%M-%S)"
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

# ── Pick a consistent-snapshot backend ───────────────────────────────────────
# The DB runs in WAL mode and the bot holds a live write connection, so a raw
# `cp` of the .db file can capture an inconsistent mid-transaction state. Prefer
# the sqlite3 CLI `.backup` (online backup). The deploy container ships Python
# but not the sqlite3 CLI, so fall back to Python's sqlite3 `.backup()` API,
# which performs the same consistent online backup. Only if neither is available
# do we fall back to a (best-effort, possibly inconsistent) cp.
PY_BIN=""
for c in python3 python; do
  if command -v "$c" &>/dev/null; then PY_BIN="$c"; break; fi
done

# ── Copy the database file ────────────────────────────────────────────────────
DEST="$BACKUP_DIR/bot_${TIMESTAMP}.db"
if command -v sqlite3 &>/dev/null; then
  sqlite3 "$DB_PATH" ".backup '$DEST'"
elif [[ -n "$PY_BIN" ]]; then
  # Consistent online backup via Python's sqlite3 module (no CLI needed).
  echo "$LOG_PREFIX sqlite3 CLI not found — using Python sqlite3 .backup() for a consistent snapshot."
  "$PY_BIN" - "$DB_PATH" "$DEST" <<'PYEOF'
import sqlite3
import sys

src_path, dest_path = sys.argv[1], sys.argv[2]
# uri=True + mode=ro avoids creating/modifying the source; the online backup
# API copies a transactionally consistent snapshot even while the bot writes.
src = sqlite3.connect(f"file:{src_path}?mode=ro", uri=True)
try:
    dest = sqlite3.connect(dest_path)
    try:
        with dest:
            src.backup(dest)
    finally:
        dest.close()
finally:
    src.close()
PYEOF
else
  # Last-resort fallback: no consistent-snapshot tool available. WAL mode keeps
  # recent commits in the -wal sidecar; copy it (and -shm) too so the snapshot
  # is not missing the latest transactions. This is NOT atomic and may be
  # inconsistent for a live DB.
  echo "$LOG_PREFIX WARNING: neither sqlite3 CLI nor python found — using cp fallback (may be inconsistent; copies -wal/-shm if present)." >&2
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
elif [[ -n "$PY_BIN" ]]; then
  # Integrity check via Python's sqlite3 module when the CLI is absent.
  INTEGRITY="$("$PY_BIN" - "$DEST" <<'PYEOF' 2>&1 || echo "check-failed"
import sqlite3
import sys

conn = sqlite3.connect(sys.argv[1])
try:
    row = conn.execute("PRAGMA integrity_check;").fetchone()
finally:
    conn.close()
print(row[0] if row else "no-result")
PYEOF
)"
  if [[ "$INTEGRITY" != "ok" ]]; then
    echo "$LOG_PREFIX ERROR: Backup integrity check failed: $INTEGRITY. Removing corrupt backup." >&2
    rm -f "$DEST"
    exit 1
  fi
  echo "$LOG_PREFIX Integrity check passed (PRAGMA integrity_check = ok, via python)."
else
  echo "$LOG_PREFIX WARNING: neither sqlite3 CLI nor python found — skipping integrity check."
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
  # Also remove the -wal/-shm sidecars the cp fallback may have created, so they
  # do not accumulate forever (the bot_*.db glob above does not match them).
  rm -f "$old" "${old}-wal" "${old}-shm"
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
