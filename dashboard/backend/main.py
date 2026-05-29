"""FastAPI dashboard backend.

Serves:
  GET  /health
  Auth endpoints (see auth.py)
  GET  /api/guilds/{guild_id}/config
  PUT  /api/guilds/{guild_id}/config
  GET  /api/guilds/{guild_id}/stats
  GET  /api/models
"""
from __future__ import annotations

import os
from contextlib import asynccontextmanager
from pathlib import Path
from typing import Any

import aiosqlite
from dotenv import load_dotenv
from fastapi import FastAPI, HTTPException, status
from fastapi.middleware.cors import CORSMiddleware
from fastapi.responses import JSONResponse
from pydantic import BaseModel

from .auth import router as auth_router
from .deps import CurrentUser

load_dotenv()

# ---------------------------------------------------------------------------
# DB helpers
# ---------------------------------------------------------------------------

_DB_PATH: str = ""


def _resolve_db_path() -> str:
    """Resolve the DATABASE_URL to a filesystem path for aiosqlite."""
    raw = os.getenv("DATABASE_URL", "sqlite:///../../data/discord_assistant.db")
    if raw.startswith("sqlite:///"):
        path = raw.removeprefix("sqlite:///")
    elif raw == ":memory:":
        return raw
    else:
        path = raw
    # Resolve relative paths relative to the project root (two levels up from this file)
    p = Path(path)
    if not p.is_absolute():
        here = Path(__file__).parent  # dashboard/backend/
        p = (here / path).resolve()
    return str(p)


async def _get_db() -> aiosqlite.Connection:
    """Open and return an aiosqlite connection. Caller must close it."""
    db_path = _DB_PATH or _resolve_db_path()
    conn = await aiosqlite.connect(db_path)
    conn.row_factory = aiosqlite.Row
    return conn


# ---------------------------------------------------------------------------
# Lifespan
# ---------------------------------------------------------------------------


@asynccontextmanager
async def lifespan(app: FastAPI):
    global _DB_PATH
    _DB_PATH = _resolve_db_path()
    yield


# ---------------------------------------------------------------------------
# App
# ---------------------------------------------------------------------------

app = FastAPI(title="Discord Assistant Dashboard API", lifespan=lifespan)

# CORS — allow the Next.js dev server and any configured production origin
_cors_origin = os.getenv("CORS_ORIGIN", "http://localhost:3000")
app.add_middleware(
    CORSMiddleware,
    allow_origins=[_cors_origin, "http://localhost:3000"],
    allow_credentials=True,
    allow_methods=["*"],
    allow_headers=["*"],
)

app.include_router(auth_router)


# ---------------------------------------------------------------------------
# Health
# ---------------------------------------------------------------------------


@app.get("/health", tags=["meta"])
async def health() -> JSONResponse:
    return JSONResponse({"status": "ok"})


# ---------------------------------------------------------------------------
# Pydantic models
# ---------------------------------------------------------------------------


class GuildConfigUpdate(BaseModel):
    model: str | None = None
    summary_limit: int | None = None
    language: str | None = None
    provider: str | None = None


# ---------------------------------------------------------------------------
# Guild config endpoints
# ---------------------------------------------------------------------------


@app.get("/api/guilds/{guild_id}/config", tags=["guilds"])
async def get_guild_config(guild_id: int, user: CurrentUser) -> JSONResponse:
    """Return the stored config for a guild.  The caller must belong to the guild."""
    _assert_guild_access(user, guild_id)
    db = await _get_db()
    try:
        async with db.execute(
            "SELECT guild_id, model, summary_limit, language, provider, updated_at "
            "FROM guild_config WHERE guild_id = ?",
            (guild_id,),
        ) as cursor:
            row = await cursor.fetchone()
    finally:
        await db.close()

    if row is None:
        # Return sensible defaults when no row exists yet
        return JSONResponse(
            {
                "guild_id": guild_id,
                "model": os.getenv("OLLAMA_MODEL", "llama3.1:8b"),
                "summary_limit": 50,
                "language": "ko",
                "provider": "ollama",
                "updated_at": None,
            }
        )
    return JSONResponse(dict(row))


@app.put("/api/guilds/{guild_id}/config", tags=["guilds"])
async def update_guild_config(
    guild_id: int,
    body: GuildConfigUpdate,
    user: CurrentUser,
) -> JSONResponse:
    """Update one or more config fields for a guild."""
    _assert_guild_access(user, guild_id)

    db = await _get_db()
    try:
        # Load current row (or defaults)
        async with db.execute(
            "SELECT model, summary_limit, language, provider FROM guild_config WHERE guild_id = ?",
            (guild_id,),
        ) as cursor:
            row = await cursor.fetchone()

        current: dict[str, Any] = (
            dict(row)
            if row
            else {
                "model": os.getenv("OLLAMA_MODEL", "llama3.1:8b"),
                "summary_limit": 50,
                "language": "ko",
                "provider": "ollama",
            }
        )

        # Apply partial updates
        if body.model is not None:
            current["model"] = body.model.strip()
        if body.summary_limit is not None:
            if not (1 <= body.summary_limit <= 200):
                raise HTTPException(status_code=400, detail="summary_limit must be 1–200")
            current["summary_limit"] = body.summary_limit
        if body.language is not None:
            current["language"] = body.language.strip()
        if body.provider is not None:
            current["provider"] = body.provider.strip()

        from datetime import datetime, timezone

        now = datetime.now(timezone.utc).isoformat(timespec="seconds")
        await db.execute(
            """
            INSERT INTO guild_config
                (guild_id, model, summary_limit, language, provider, updated_at)
            VALUES (?, ?, ?, ?, ?, ?)
            ON CONFLICT(guild_id) DO UPDATE SET
                model          = excluded.model,
                summary_limit  = excluded.summary_limit,
                language       = excluded.language,
                provider       = excluded.provider,
                updated_at     = excluded.updated_at
            """,
            (
                guild_id,
                current["model"],
                current["summary_limit"],
                current["language"],
                current["provider"],
                now,
            ),
        )
        await db.commit()
    finally:
        await db.close()

    return JSONResponse({"guild_id": guild_id, **current, "updated_at": now})


# ---------------------------------------------------------------------------
# Stats endpoint
# ---------------------------------------------------------------------------


@app.get("/api/guilds/{guild_id}/stats", tags=["guilds"])
async def get_guild_stats(guild_id: int, user: CurrentUser) -> JSONResponse:
    """Return usage statistics for a guild."""
    _assert_guild_access(user, guild_id)

    db = await _get_db()
    try:
        async with db.execute(
            "SELECT COUNT(*) AS cnt FROM usage_log WHERE guild_id = ?",
            (guild_id,),
        ) as c:
            total_row = await c.fetchone()
        total: int = total_row["cnt"] if total_row else 0

        async with db.execute(
            "SELECT command, COUNT(*) AS cnt FROM usage_log WHERE guild_id = ? "
            "GROUP BY command ORDER BY cnt DESC",
            (guild_id,),
        ) as c:
            by_command = await c.fetchall()

        async with db.execute(
            "SELECT AVG(latency_ms) AS avg_ms FROM usage_log WHERE guild_id = ? AND status = 'ok'",
            (guild_id,),
        ) as c:
            avg_row = await c.fetchone()
        avg_latency = round(avg_row["avg_ms"]) if avg_row and avg_row["avg_ms"] is not None else 0

        async with db.execute(
            "SELECT COUNT(*) AS cnt FROM usage_log WHERE guild_id = ? AND status = 'error'",
            (guild_id,),
        ) as c:
            err_row = await c.fetchone()
        error_count: int = err_row["cnt"] if err_row else 0

        # Daily usage for the last 30 days
        async with db.execute(
            """
            SELECT substr(created_at, 1, 10) AS day, COUNT(*) AS cnt
            FROM usage_log
            WHERE guild_id = ?
              AND created_at >= datetime('now', '-30 days')
            GROUP BY day
            ORDER BY day
            """,
            (guild_id,),
        ) as c:
            daily_rows = await c.fetchall()
    finally:
        await db.close()

    error_rate = round(error_count / total * 100, 1) if total > 0 else 0.0
    return JSONResponse(
        {
            "total": total,
            "by_command": [{"command": r["command"], "count": r["cnt"]} for r in by_command],
            "avg_latency_ms": avg_latency,
            "error_rate": error_rate,
            "daily": [{"day": r["day"], "count": r["cnt"]} for r in daily_rows],
        }
    )


# ---------------------------------------------------------------------------
# API key management
# ---------------------------------------------------------------------------


@app.get("/api/guilds/{guild_id}/api-key", tags=["guilds"])
async def get_api_key_status(guild_id: int, user: CurrentUser) -> JSONResponse:
    """Return whether an API key is set (never returns the actual key)."""
    _assert_guild_access(user, guild_id)
    db = await _get_db()
    try:
        async with db.execute(
            "SELECT api_key_encrypted FROM guild_config WHERE guild_id = ?",
            (guild_id,),
        ) as c:
            row = await c.fetchone()
    finally:
        await db.close()

    has_key = bool(row and row["api_key_encrypted"])
    return JSONResponse({"has_key": has_key})


@app.delete("/api/guilds/{guild_id}/api-key", tags=["guilds"])
async def clear_api_key(guild_id: int, user: CurrentUser) -> JSONResponse:
    """Clear the stored API key for a guild."""
    _assert_guild_access(user, guild_id)
    db = await _get_db()
    try:
        await db.execute(
            "UPDATE guild_config SET api_key_encrypted = NULL WHERE guild_id = ?",
            (guild_id,),
        )
        await db.commit()
    finally:
        await db.close()
    return JSONResponse({"cleared": True})


# ---------------------------------------------------------------------------
# Models endpoint (Ollama)
# ---------------------------------------------------------------------------


@app.get("/api/models", tags=["models"])
async def list_models(user: CurrentUser) -> JSONResponse:
    """Return the list of installed Ollama models."""
    import httpx as _httpx

    ollama_url = os.getenv("OLLAMA_BASE_URL", "http://localhost:11434").rstrip("/")
    try:
        async with _httpx.AsyncClient(timeout=5) as client:
            resp = await client.get(f"{ollama_url}/api/tags")
        if resp.status_code != 200:
            return JSONResponse({"models": [], "error": f"Ollama returned {resp.status_code}"})
        data = resp.json()
        models = [
            {
                "name": m.get("name", ""),
                "size": m.get("size", 0),
                "modified_at": m.get("modified_at", ""),
            }
            for m in data.get("models", [])
        ]
        return JSONResponse({"models": models})
    except Exception as exc:
        return JSONResponse({"models": [], "error": str(exc)})


# ---------------------------------------------------------------------------
# Helpers
# ---------------------------------------------------------------------------


def _assert_guild_access(user: dict, guild_id: int) -> None:
    """Raise 403 if the JWT does not include the requested guild."""
    guilds: list[dict] = user.get("guilds", [])
    ids = {int(g["id"]) for g in guilds}
    if guild_id not in ids:
        raise HTTPException(
            status_code=status.HTTP_403_FORBIDDEN,
            detail="You are not a member of this guild",
        )
