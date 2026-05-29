# Rollback Procedure

This document describes how to stop the bot, restore the database from a backup, and roll back to a previous code version.

---

## 1. Stop the Bot

Choose the method that matches your deployment:

### systemd
```bash
sudo systemctl stop discord-assistant
sudo systemctl status discord-assistant  # confirm it is "inactive (dead)"
```

### Docker Compose
```bash
docker compose stop bot
# or stop everything:
docker compose down
```

### tmux / screen session
```bash
# List sessions
tmux ls

# Attach and kill the process
tmux attach -t discord-assistant
# then press Ctrl+C to stop the bot
# or send a kill signal directly:
tmux send-keys -t discord-assistant C-c
```

---

## 2. Restore the Database from Backup

Backups are stored in `data/backups/bot_YYYY-MM-DD.db` (created daily at 03:00 by `scripts/backup.sh`).

```bash
# List available backups
ls -lh /opt/discord-assistant/data/backups/

# Replace the live DB with the desired backup
# (adjust the date as needed)
cp /opt/discord-assistant/data/backups/bot_2026-05-28.db \
   /opt/discord-assistant/data/discord_assistant.db

# Verify the restored file looks sane
sqlite3 /opt/discord-assistant/data/discord_assistant.db \
  "SELECT name FROM sqlite_master WHERE type='table';"
```

> **Note:** Stopping the bot before restoring prevents write conflicts.

---

## 3. Roll Back Code to a Previous Tag

```bash
cd /opt/discord-assistant

# List available tags
git tag --sort=-creatordate | head -20

# Check out the desired release tag
git checkout v1.2.3      # replace with actual tag

# Re-install dependencies for that version
.venv/bin/pip install .

# Restart the bot
sudo systemctl start discord-assistant
# or
docker compose up -d bot
```

If you need to roll back without git (e.g., the repo was overwritten), restore the code from a release archive:

```bash
# Extract the archive and overwrite the install directory
tar -xzf discord-assistant-v1.2.3.tar.gz -C /opt/discord-assistant --strip-components=1
.venv/bin/pip install .
sudo systemctl start discord-assistant
```

---

## 4. Verify Recovery

```bash
# Check the bot is running
sudo systemctl status discord-assistant

# Run the healthcheck script
python /opt/discord-assistant/scripts/healthcheck.py

# Tail logs for startup errors
sudo journalctl -u discord-assistant -f
# or
tail -f /opt/discord-assistant/logs/bot.log
```

---

## 5. Emergency Contacts

| Role | Name | Contact |
|------|------|---------|
| Primary developer | _(your name)_ | _(Discord handle / email)_ |
| Server administrator | _(name)_ | _(contact)_ |
| Backup contact | _(name)_ | _(contact)_ |

> Fill in the table above before deploying to production.
