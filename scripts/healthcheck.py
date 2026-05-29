#!/usr/bin/env python3
"""
scripts/healthcheck.py — Bot uptime / health probe.

Checks:
  1. SQLite DB is accessible and has the guild_config table.
  2. (Optional) /readyz endpoint responds 200 — only when the bot's health
     server is enabled (METRICS_PORT > 0). When disabled the check is skipped,
     so existing deployments keep relying on the DB check (backward compatible).
  3. (Optional) Ollama HTTP endpoint responds within 5 seconds —
     only when HEALTHCHECK_REQUIRE_OLLAMA is truthy. Cloud-API deployments
     (OpenAI/Anthropic) have no Ollama service, so this is skipped by default.

Exit codes:
  0 — healthy
  1 — unhealthy

Usage:
  python scripts/healthcheck.py
"""
from __future__ import annotations

import os
import sqlite3
import sys
import urllib.error
import urllib.request
from pathlib import Path


def _db_path() -> str:
    """Resolve the SQLite file path from DATABASE_URL or the default location."""
    db_url = os.getenv("DATABASE_URL", "sqlite:///./data/discord_assistant.db")
    # Strip the sqlite:/// prefix (handles both relative and absolute paths)
    if db_url.startswith("sqlite:///"):
        path = db_url[len("sqlite:///"):]
    else:
        path = db_url
    return path


def check_database() -> tuple[bool, str]:
    """Return (ok, message) for the SQLite health check."""
    path = _db_path()
    abs_path = Path(path).resolve()
    if not abs_path.exists():
        return False, f"DB file not found: {abs_path}"
    try:
        with sqlite3.connect(str(abs_path), timeout=5) as conn:
            cursor = conn.execute(
                "SELECT name FROM sqlite_master WHERE type='table' AND name='guild_config'"
            )
            if cursor.fetchone() is None:
                return False, "Table 'guild_config' does not exist — DB may not be initialised yet."
            return True, f"DB OK ({abs_path})"
    except sqlite3.Error as exc:
        return False, f"DB error: {exc}"


def check_ollama() -> tuple[bool, str]:
    """Return (ok, message) for the Ollama HTTP health check."""
    base_url = os.getenv("OLLAMA_BASE_URL", "http://localhost:11434").rstrip("/")
    url = f"{base_url}/api/tags"
    try:
        req = urllib.request.Request(url, method="GET")
        with urllib.request.urlopen(req, timeout=5) as resp:
            if resp.status == 200:
                return True, f"Ollama OK ({url})"
            return False, f"Ollama returned HTTP {resp.status} ({url})"
    except urllib.error.URLError as exc:
        return False, f"Ollama unreachable: {exc} ({url})"
    except OSError as exc:
        return False, f"Ollama connection error: {exc} ({url})"


def _metrics_port() -> int:
    """Resolve the bot health-server port from METRICS_PORT (0 = disabled)."""
    raw = os.getenv("METRICS_PORT", "").strip()
    if not raw:
        return 0
    try:
        port = int(raw)
    except ValueError:
        return 0
    return port if port > 0 else 0


def check_readyz() -> tuple[bool, str]:
    """Return (ok, message) for the bot /readyz endpoint health check."""
    port = _metrics_port()
    host = os.getenv("HEALTHCHECK_HOST", "localhost").strip() or "localhost"
    url = f"http://{host}:{port}/readyz"
    try:
        req = urllib.request.Request(url, method="GET")
        with urllib.request.urlopen(req, timeout=5) as resp:
            if resp.status == 200:
                return True, f"Bot ready ({url})"
            return False, f"/readyz returned HTTP {resp.status} ({url})"
    except urllib.error.HTTPError as exc:
        # 503 = bot not ready yet. Surface the status explicitly.
        return False, f"/readyz returned HTTP {exc.code} ({url})"
    except urllib.error.URLError as exc:
        return False, f"/readyz unreachable: {exc} ({url})"
    except OSError as exc:
        return False, f"/readyz connection error: {exc} ({url})"


def _require_ollama() -> bool:
    return os.getenv("HEALTHCHECK_REQUIRE_OLLAMA", "false").strip().lower() in {
        "1", "true", "yes", "y", "on",
    }


def main() -> None:
    results: list[tuple[bool, str]] = [check_database()]
    # Probe /readyz only when the health server is enabled (METRICS_PORT > 0).
    # When disabled, fall back to the DB check alone (backward compatible).
    if _metrics_port() > 0:
        results.append(check_readyz())
    # Only gate on Ollama when explicitly required (e.g. Ollama-backed deploys).
    if _require_ollama():
        results.append(check_ollama())

    all_ok = all(ok for ok, _ in results)

    for ok, msg in results:
        status = "OK  " if ok else "FAIL"
        print(f"[{status}] {msg}")

    if all_ok:
        print("Healthcheck PASSED")
        sys.exit(0)
    else:
        print("Healthcheck FAILED")
        sys.exit(1)


if __name__ == "__main__":
    main()
