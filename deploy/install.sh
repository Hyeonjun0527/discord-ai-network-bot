#!/usr/bin/env bash
# deploy/install.sh — Install and enable the discord-assistant systemd service.
# Run as root (or with sudo).
#
# Environment variables (override defaults):
#   INSTALL_DIR   — installation path (default: /opt/discord-assistant)
#   BOT_USER      — system user to run the bot (default: discord-bot)
set -euo pipefail

INSTALL_DIR="${INSTALL_DIR:-/opt/discord-assistant}"
SERVICE_NAME="discord-assistant"
SERVICE_FILE="discord-assistant.service"
BOT_USER="${BOT_USER:-discord-bot}"

echo "[install] Checking for root privileges..."
if [[ $EUID -ne 0 ]]; then
  echo "ERROR: This script must be run as root (sudo $0)." >&2
  exit 1
fi

# ── Create system user if it does not exist ──────────────────────────────────
if ! id -u "$BOT_USER" &>/dev/null; then
  echo "[install] Creating system user: $BOT_USER"
  useradd --system --no-create-home --shell /sbin/nologin "$BOT_USER"
else
  echo "[install] User '$BOT_USER' already exists."
fi

# ── Copy project files ────────────────────────────────────────────────────────
echo "[install] Copying files to $INSTALL_DIR ..."
mkdir -p "$INSTALL_DIR"
cp -r . "$INSTALL_DIR/"
chown -R "$BOT_USER":"$BOT_USER" "$INSTALL_DIR"

# ── Create a virtual environment in the install dir ──────────────────────────
echo "[install] Creating Python virtual environment..."
python3 -m venv "$INSTALL_DIR/.venv"
"$INSTALL_DIR/.venv/bin/pip" install --quiet --upgrade pip
"$INSTALL_DIR/.venv/bin/pip" install --quiet "$INSTALL_DIR/"

# ── Create data and logs directories ─────────────────────────────────────────
mkdir -p "$INSTALL_DIR/data" "$INSTALL_DIR/logs" "$INSTALL_DIR/data/backups"
chown -R "$BOT_USER":"$BOT_USER" "$INSTALL_DIR/data" "$INSTALL_DIR/logs"

# ── Ensure .env file exists ───────────────────────────────────────────────────
if [[ ! -f "$INSTALL_DIR/.env" ]]; then
  echo "[install] WARNING: No .env found. Copying .env.example — remember to fill in secrets!"
  cp "$INSTALL_DIR/.env.example" "$INSTALL_DIR/.env"
  chown "$BOT_USER":"$BOT_USER" "$INSTALL_DIR/.env"
  chmod 600 "$INSTALL_DIR/.env"
fi

# ── Install systemd service ───────────────────────────────────────────────────
# The bundled unit hardcodes /opt/discord-assistant and the discord-bot user;
# substitute the actual INSTALL_DIR / BOT_USER so overrides take effect (#89).
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
echo "[install] Installing systemd service (path=$INSTALL_DIR, user=$BOT_USER) ..."
sed \
  -e "s|/opt/discord-assistant|${INSTALL_DIR}|g" \
  -e "s|^User=.*|User=${BOT_USER}|" \
  -e "s|^Group=.*|Group=${BOT_USER}|" \
  "$SCRIPT_DIR/$SERVICE_FILE" > "/etc/systemd/system/$SERVICE_FILE"

systemctl daemon-reload
systemctl enable "$SERVICE_NAME"
systemctl start "$SERVICE_NAME"

echo "[install] Done."
echo "[install] Status:"
systemctl status "$SERVICE_NAME" --no-pager
