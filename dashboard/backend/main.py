"""FastAPI dashboard backend.

Serves:
  GET  /health
  Auth endpoints (see auth.py)
  GET  /api/guilds/{guild_id}/config
  PUT  /api/guilds/{guild_id}/config
  GET  /api/guilds/{guild_id}/stats
  GET  /api/guilds/{guild_id}/feedback
  GET  /api/models
"""
from __future__ import annotations

import os
import time
from collections import defaultdict
from contextlib import asynccontextmanager
from datetime import datetime, timezone
from pathlib import Path
from typing import Any

import aiosqlite
import httpx
from dotenv import load_dotenv
from fastapi import FastAPI, HTTPException, Query, Request, status
from fastapi.middleware.cors import CORSMiddleware
from fastapi.responses import JSONResponse
from pydantic import BaseModel

from .auth import router as auth_router
from .deps import CurrentUser

# Simple in-memory rate limiter: ip -> list of timestamps
_rate_limit_store: dict[str, list[float]] = defaultdict(list)
_RATE_LIMIT_MAX = 60   # requests
_RATE_LIMIT_WINDOW = 60  # seconds


def _check_rate_limit(request: Request) -> None:
    ip = request.client.host if request.client else "unknown"
    now = time.monotonic()
    window_start = now - _RATE_LIMIT_WINDOW
    timestamps = [t for t in _rate_limit_store[ip] if t > window_start]
    timestamps.append(now)
    _rate_limit_store[ip] = timestamps
    if len(timestamps) > _RATE_LIMIT_MAX:
        raise HTTPException(
            status_code=status.HTTP_429_TOO_MANY_REQUESTS,
            detail="Too many requests. Please slow down.",
        )

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

# CORS — allow the Next.js dev server and any configured production origin.
# allow_origins 는 CORS_ORIGIN 환경변수 기반 명시 목록으로 유지하고,
# 메서드/헤더는 와일드카드 대신 실제 사용하는 값으로 좁힌다 (#42).
_cors_origin = os.getenv("CORS_ORIGIN", "http://localhost:3000")
# 중복 제거하면서 순서를 보존한 명시적 origin 목록
_allow_origins = list(dict.fromkeys([_cors_origin, "http://localhost:3000"]))
app.add_middleware(
    CORSMiddleware,
    allow_origins=_allow_origins,
    allow_credentials=True,
    # 이 API 가 실제로 사용하는 메서드만 허용 (preflight 용 OPTIONS 포함)
    allow_methods=["GET", "PUT", "POST", "DELETE", "OPTIONS"],
    # 인증 토큰(Authorization)과 JSON 바디(Content-Type) 헤더만 허용
    allow_headers=["Authorization", "Content-Type"],
)

# 전 라우트에 레이트 리밋을 일괄 적용하는 미들웨어 (#43).
# /health 류 메타 엔드포인트는 헬스체크 폭주를 막기 위해 제외한다.
_RATE_LIMIT_EXEMPT_PATHS = frozenset({"/health"})


@app.middleware("http")
async def _rate_limit_middleware(request: Request, call_next):
    """모든 요청에 IP 기반 레이트 리밋을 적용한다 (#43).

    개별 라우트에서 수동으로 ``_check_rate_limit`` 을 호출하던 방식을
    공통 미들웨어로 일괄화한다. preflight(OPTIONS) 및 /health 류는 제외한다.
    """
    if request.method != "OPTIONS" and request.url.path not in _RATE_LIMIT_EXEMPT_PATHS:
        try:
            _check_rate_limit(request)
        except HTTPException as exc:
            return JSONResponse(
                status_code=exc.status_code,
                content={"detail": exc.detail},
                headers=exc.headers,
            )
    return await call_next(request)


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
    # 자동 요약 주기(분) (#80). None 이면 자동 요약 비활성화, 그 외에는 5분 이상.
    auto_summary_interval: int | None = None


# ---------------------------------------------------------------------------
# Guild config endpoints
# ---------------------------------------------------------------------------


@app.get("/api/guilds/{guild_id}/config", tags=["guilds"])
async def get_guild_config(guild_id: int, user: CurrentUser, request: Request) -> JSONResponse:
    """Return the stored config for a guild.  The caller must belong to the guild."""
    # 레이트 리밋은 _rate_limit_middleware 가 일괄 적용한다 (#43).
    _assert_guild_access(user, guild_id)
    db = await _get_db()
    try:
        async with db.execute(
            "SELECT guild_id, model, summary_limit, language, provider, "
            "auto_summary_interval, updated_at "
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
                # #80: 기본은 자동 요약 비활성화(None).
                "auto_summary_interval": None,
                "updated_at": None,
            }
        )
    return JSONResponse(dict(row))


@app.put("/api/guilds/{guild_id}/config", tags=["guilds"])
async def update_guild_config(
    guild_id: int,
    body: GuildConfigUpdate,
    user: CurrentUser,
    request: Request,
) -> JSONResponse:
    """Update one or more config fields for a guild."""
    # 레이트 리밋은 _rate_limit_middleware 가 일괄 적용한다 (#43).
    # 설정 변경은 관리자(Administrator/소유자)만 허용한다 (#79).
    _assert_guild_admin(user, guild_id)

    db = await _get_db()
    try:
        # 전체 컬럼을 읽어 기존 값을 보존한다 (#30: 스키마 drift 방지).
        # 봇(storage.ConfigStore._upsert_sync)과 동일하게 모든 컬럼을 upsert 해야
        # 일부 컬럼만 쓰면서 나머지가 NULL 로 덮이는 drift 를 막을 수 있다.
        async with db.execute(
            "SELECT model, summary_limit, language, admin_role_id, provider, "
            "api_key_encrypted, auto_summary_interval, persona, "
            "custom_summarize_prompt, custom_ask_prompt, allowed_role_id "
            "FROM guild_config WHERE guild_id = ?",
            (guild_id,),
        ) as cursor:
            row = await cursor.fetchone()

        # 기존 행이 없으면 봇과 동일한 기본값으로 채운다(누락 컬럼은 None 보존).
        current: dict[str, Any] = (
            dict(row)
            if row
            else {
                "model": os.getenv("OLLAMA_MODEL", "llama3.1:8b"),
                "summary_limit": 50,
                "language": "ko",
                "admin_role_id": None,
                "provider": "ollama",
                "api_key_encrypted": None,
                "auto_summary_interval": None,
                "persona": None,
                "custom_summarize_prompt": None,
                "custom_ask_prompt": None,
                "allowed_role_id": None,
            }
        )

        # 대시보드가 노출하는 필드만 부분 갱신한다. 나머지 컬럼은 위에서
        # 읽어온 기존 값을 그대로 보존한다.
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
        # 자동 요약 주기(분) (#80). None 이 '미전송'이 아니라 '자동요약 끄기'를
        # 뜻할 수 있으므로, 필드가 실제로 전송됐는지(model_fields_set)로 판별한다.
        if "auto_summary_interval" in body.model_fields_set:
            interval = body.auto_summary_interval
            if interval is not None and interval < 5:
                # 너무 잦은 자동 요약은 막는다(최소 5분). None 은 비활성화로 허용.
                raise HTTPException(
                    status_code=400,
                    detail="auto_summary_interval must be None (off) or >= 5 minutes",
                )
            current["auto_summary_interval"] = interval

        now = datetime.now(timezone.utc).isoformat(timespec="seconds")
        await db.execute(
            """
            INSERT INTO guild_config
                (guild_id, model, summary_limit, language, admin_role_id,
                 provider, api_key_encrypted, auto_summary_interval, persona,
                 custom_summarize_prompt, custom_ask_prompt, allowed_role_id, updated_at)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            ON CONFLICT(guild_id) DO UPDATE SET
                model                   = excluded.model,
                summary_limit           = excluded.summary_limit,
                language                = excluded.language,
                admin_role_id           = excluded.admin_role_id,
                provider                = excluded.provider,
                api_key_encrypted       = excluded.api_key_encrypted,
                auto_summary_interval   = excluded.auto_summary_interval,
                persona                 = excluded.persona,
                custom_summarize_prompt = excluded.custom_summarize_prompt,
                custom_ask_prompt       = excluded.custom_ask_prompt,
                allowed_role_id         = excluded.allowed_role_id,
                updated_at              = excluded.updated_at
            """,
            (
                guild_id,
                current["model"],
                current["summary_limit"],
                current["language"],
                current["admin_role_id"],
                current["provider"],
                current["api_key_encrypted"],
                current["auto_summary_interval"],
                current["persona"],
                current["custom_summarize_prompt"],
                current["custom_ask_prompt"],
                current["allowed_role_id"],
                now,
            ),
        )
        await db.commit()
    finally:
        await db.close()

    # 응답에는 대시보드가 노출하는 필드만 반환하여 비밀값(api_key 등) 노출을 막는다.
    return JSONResponse(
        {
            "guild_id": guild_id,
            "model": current["model"],
            "summary_limit": current["summary_limit"],
            "language": current["language"],
            "provider": current["provider"],
            # #80: 자동 요약 주기(분) 도 노출한다.
            "auto_summary_interval": current["auto_summary_interval"],
            "updated_at": now,
        }
    )


# ---------------------------------------------------------------------------
# Stats endpoint
# ---------------------------------------------------------------------------


@app.get("/api/guilds/{guild_id}/stats", tags=["guilds"])
async def get_guild_stats(
    guild_id: int,
    user: CurrentUser,
    days: int = Query(
        30,
        ge=1,
        le=365,
        description="daily 집계 기간(일). 기본 30, 1~365 범위 (#84).",
    ),
) -> JSONResponse:
    """Return usage statistics for a guild.

    ``days`` 쿼리 파라미터로 daily 집계 기간을 조절한다 (#84).
    기본값 30일이며, 권장 프리셋(7/30/90) 외에도 1~365 범위 내 임의 값을 허용한다.
    """
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

        # 명령별 평균 응답시간(latency_ms) 집계 (#83).
        # 전역 avg_latency_ms 와 동일하게 정상 처리(status='ok')만 대상으로 하고,
        # latency_ms 가 기록된 행만 평균을 낸다(NULL 은 AVG 가 자동 제외).
        # 평균이 큰(느린) 명령부터 보이도록 내림차순 정렬한다.
        async with db.execute(
            "SELECT command, AVG(latency_ms) AS avg_ms FROM usage_log "
            "WHERE guild_id = ? AND status = 'ok' AND latency_ms IS NOT NULL "
            "GROUP BY command ORDER BY avg_ms DESC",
            (guild_id,),
        ) as c:
            latency_by_command = await c.fetchall()

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

        # 토큰 사용량 집계 (#82). usage_log.prompt_tokens/completion_tokens 합계.
        # 구(舊) 배포 DB 에는 토큰 컬럼이 없을 수 있으므로, 컬럼 존재 여부를 먼저
        # 확인하고 없으면 0 으로 폴백한다(백워드 호환).
        token_totals = await _aggregate_tokens(db, guild_id)

        # 최근 N일 daily 집계 (#84: '-30 days' 하드코딩 제거).
        # days 는 1~365 범위로 검증된 int 이므로 datetime() modifier 에
        # 바인드 파라미터로 안전하게 전달한다.
        async with db.execute(
            """
            SELECT substr(created_at, 1, 10) AS day, COUNT(*) AS cnt
            FROM usage_log
            WHERE guild_id = ?
              AND created_at >= datetime('now', ?)
            GROUP BY day
            ORDER BY day
            """,
            (guild_id, f"-{days} days"),
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
            # 명령별 평균 응답시간(ms). 기존 응답 필드는 그대로 두고 추가만 한다 (#83).
            "latency_by_command": [
                {"command": r["command"], "avg_latency_ms": round(r["avg_ms"])}
                for r in latency_by_command
            ],
            "error_rate": error_rate,
            "days": days,  # 집계에 사용된 기간(일) (#84)
            "daily": [{"day": r["day"], "count": r["cnt"]} for r in daily_rows],
            # 토큰 사용량 집계 (#82). 모델 단가를 모르므로 토큰 수만 노출한다.
            "tokens": token_totals,
        }
    )


async def _aggregate_tokens(db: aiosqlite.Connection, guild_id: int) -> dict[str, int]:
    """길드의 prompt/completion 토큰 사용량 합계를 집계한다 (#82).

    ``usage_log`` 에 ``prompt_tokens``/``completion_tokens`` 컬럼이 존재할 때만
    합계를 내고, 구(舊) 스키마(컬럼 없음)에서는 0 으로 폴백한다(백워드 호환).
    모델별 단가 정보가 없으므로 비용 대신 토큰 수만 반환한다.
    """
    async with db.execute("PRAGMA table_info(usage_log)") as c:
        cols = {row["name"] for row in await c.fetchall()}
    if not {"prompt_tokens", "completion_tokens"} <= cols:
        return {"prompt": 0, "completion": 0, "total": 0}

    async with db.execute(
        "SELECT COALESCE(SUM(prompt_tokens), 0) AS p, "
        "COALESCE(SUM(completion_tokens), 0) AS c "
        "FROM usage_log WHERE guild_id = ?",
        (guild_id,),
    ) as c:
        row = await c.fetchone()
    prompt = int(row["p"]) if row else 0
    completion = int(row["c"]) if row else 0
    return {"prompt": prompt, "completion": completion, "total": prompt + completion}


# ---------------------------------------------------------------------------
# Feedback endpoint (#78)
# ---------------------------------------------------------------------------


@app.get("/api/guilds/{guild_id}/feedback", tags=["guilds"])
async def get_guild_feedback(
    guild_id: int,
    user: CurrentUser,
    limit: int = Query(
        50,
        ge=1,
        le=200,
        description="최근 피드백 목록 개수(기본 50, 1~200) (#78).",
    ),
) -> JSONResponse:
    """길드의 피드백을 집계해 반환한다 (#78).

    반환 형태::

        {
          "total": 12,
          "rating_distribution": [{"rating": 1, "count": 9}, {"rating": -1, "count": 3}],
          "by_command": [{"command": "ask", "positive": 5, "negative": 1, "total": 6}, ...],
          "recent": [{"message_id": ..., "user_id": ..., "rating": 1, "command": "ask", "created_at": ...}, ...]
        }

    피드백 ``rating`` 은 봇(storage.save_feedback)이 +1(좋아요)/-1(싫어요) 로 저장한다.
    멤버 누구나 열람 가능하므로 ``_assert_guild_access`` 만 적용한다.
    """
    _assert_guild_access(user, guild_id)

    db = await _get_db()
    try:
        # 전체 건수
        async with db.execute(
            "SELECT COUNT(*) AS cnt FROM feedback WHERE guild_id = ?",
            (guild_id,),
        ) as c:
            total_row = await c.fetchone()
        total: int = total_row["cnt"] if total_row else 0

        # rating 분포 (예: +1 / -1 각각의 건수)
        async with db.execute(
            "SELECT rating, COUNT(*) AS cnt FROM feedback WHERE guild_id = ? "
            "GROUP BY rating ORDER BY rating DESC",
            (guild_id,),
        ) as c:
            rating_rows = await c.fetchall()

        # command 별 긍정/부정 집계 (command 가 NULL 이면 'unknown' 으로 묶는다)
        async with db.execute(
            """
            SELECT COALESCE(command, 'unknown') AS command,
                   SUM(CASE WHEN rating > 0 THEN 1 ELSE 0 END) AS positive,
                   SUM(CASE WHEN rating < 0 THEN 1 ELSE 0 END) AS negative,
                   COUNT(*) AS total
            FROM feedback
            WHERE guild_id = ?
            GROUP BY COALESCE(command, 'unknown')
            ORDER BY total DESC
            """,
            (guild_id,),
        ) as c:
            command_rows = await c.fetchall()

        # 최근 목록 (limit 개, 최신순)
        async with db.execute(
            "SELECT message_id, user_id, rating, command, created_at FROM feedback "
            "WHERE guild_id = ? ORDER BY id DESC LIMIT ?",
            (guild_id, limit),
        ) as c:
            recent_rows = await c.fetchall()
    finally:
        await db.close()

    positive_total = sum(int(r["positive"]) for r in command_rows)
    negative_total = sum(int(r["negative"]) for r in command_rows)
    # 만족도(%) = 긍정 / (긍정+부정). 평가가 없으면 None 으로 둔다.
    rated = positive_total + negative_total
    satisfaction = round(positive_total / rated * 100, 1) if rated > 0 else None

    return JSONResponse(
        {
            "total": total,
            "positive": positive_total,
            "negative": negative_total,
            "satisfaction": satisfaction,
            "rating_distribution": [
                {"rating": int(r["rating"]), "count": int(r["cnt"])} for r in rating_rows
            ],
            "by_command": [
                {
                    "command": r["command"],
                    "positive": int(r["positive"]),
                    "negative": int(r["negative"]),
                    "total": int(r["total"]),
                }
                for r in command_rows
            ],
            "recent": [
                {
                    "message_id": r["message_id"],
                    "user_id": r["user_id"],
                    "rating": int(r["rating"]),
                    "command": r["command"],
                    "created_at": r["created_at"],
                }
                for r in recent_rows
            ],
        }
    )


# ---------------------------------------------------------------------------
# API key management
# ---------------------------------------------------------------------------


@app.get("/api/guilds/{guild_id}/api-key", tags=["guilds"])
async def get_api_key_status(guild_id: int, user: CurrentUser) -> JSONResponse:
    """Return whether an API key is set (never returns the actual key)."""
    # API 키 상태/조작은 관리자만 접근할 수 있게 한다 (#79).
    _assert_guild_admin(user, guild_id)
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
    # 키 삭제는 관리자만 허용한다 (#79).
    _assert_guild_admin(user, guild_id)
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
    ollama_url = os.getenv("OLLAMA_BASE_URL", "http://localhost:11434").rstrip("/")
    try:
        async with httpx.AsyncClient(timeout=5) as client:
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


def _assert_guild_admin(user: dict, guild_id: int) -> None:
    """길드 멤버십에 더해 Administrator(권한 비트 0x8)/소유자 여부까지 검사한다 (#79).

    설정 변경(PUT)·API 키 라우트처럼 쓰기 권한이 필요한 엔드포인트에 적용한다.
    JWT 의 각 길드 항목에 저장된 ``admin`` 플래그(auth.create_jwt)를 신뢰한다.
    멤버가 아니면 403, 멤버지만 관리자가 아니면 403(권한 부족)으로 거절한다.
    ``admin`` 키가 없는 구(舊) 토큰은 보수적으로 비관리자로 취급한다(백워드 호환).
    """
    _assert_guild_access(user, guild_id)
    guilds: list[dict] = user.get("guilds", [])
    is_admin = any(int(g["id"]) == guild_id and g.get("admin") is True for g in guilds)
    if not is_admin:
        raise HTTPException(
            status_code=status.HTTP_403_FORBIDDEN,
            detail="You need Administrator permission on this server to perform this action",
        )
