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
cp "$DB_PATH" "$DEST"
echo "$LOG_PREFIX Backup written: $DEST ($(du -sh "$DEST" | cut -f1))"

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
