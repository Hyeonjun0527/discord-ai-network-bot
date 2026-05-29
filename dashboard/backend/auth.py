"""Discord OAuth2 flow and JWT helpers.

Endpoints
---------
GET /auth/login    — redirect to Discord OAuth2
GET /auth/callback — exchange code, issue JWT
GET /auth/me       — return current user info from JWT
"""
from __future__ import annotations

import asyncio  # noqa: E402
import os
import secrets
from datetime import datetime, timedelta, timezone
from urllib.parse import urlencode

import httpx
import jwt
from fastapi import APIRouter, HTTPException, Request, status
from fastapi.responses import JSONResponse, RedirectResponse

# In-memory state store for CSRF protection (maps state → expiry timestamp)
# For multi-process deployments, replace with Redis or DB-backed store.
_oauth_states: dict[str, datetime] = {}
_STATE_TTL_SECONDS = 600

router = APIRouter(prefix="/auth", tags=["auth"])

# ---------------------------------------------------------------------------
# Config helpers
# ---------------------------------------------------------------------------

DISCORD_API_BASE = "https://discord.com/api/v10"
DISCORD_OAUTH_URL = "https://discord.com/oauth2/authorize"
DISCORD_TOKEN_URL = "https://discord.com/api/oauth2/token"

SCOPES = "identify guilds"

# Discord 권한 비트: Administrator (0x8). 길드 소유자(owner)도 사실상 관리자다 (#79).
DISCORD_PERMISSION_ADMINISTRATOR = 0x8


def _guild_is_admin(guild: dict) -> bool:
    """Discord 길드 객체가 관리자 권한(또는 소유자)을 가졌는지 판정한다 (#79).

    ``/users/@me/guilds`` 응답의 각 길드는 ``permissions`` 문자열 비트필드와
    ``owner`` 불리언을 포함한다. 소유자이거나 Administrator(0x8) 비트가 켜져 있으면
    관리자로 본다. 파싱 불가한 값은 보수적으로 비관리자로 처리한다.

    토큰 재발급(#85) 시에는 이미 ``admin`` 플래그만 담긴 클레임이 들어오므로
    그 값을 그대로 신뢰해 권한이 유실되지 않게 한다.
    """
    if "admin" in guild and "permissions" not in guild and "owner" not in guild:
        return guild["admin"] is True
    if guild.get("owner") is True:
        return True
    raw = guild.get("permissions")
    if raw is None:
        return False
    try:
        perms = int(raw)
    except (TypeError, ValueError):
        return False
    return bool(perms & DISCORD_PERMISSION_ADMINISTRATOR)


def _client_id() -> str:
    v = os.getenv("DISCORD_CLIENT_ID", "")
    if not v:
        raise RuntimeError("DISCORD_CLIENT_ID is not set")
    return v


def _client_secret() -> str:
    v = os.getenv("DISCORD_CLIENT_SECRET", "")
    if not v:
        raise RuntimeError("DISCORD_CLIENT_SECRET is not set")
    return v


def _redirect_uri() -> str:
    return os.getenv("DISCORD_REDIRECT_URI", "http://localhost:8000/auth/callback")


def _secret_key() -> str:
    return os.getenv("SECRET_KEY", "change-me-in-production")


def _jwt_algorithm() -> str:
    return "HS256"


# ---------------------------------------------------------------------------
# JWT helpers
# ---------------------------------------------------------------------------


def create_jwt(user_id: str, guilds: list[dict]) -> str:
    """Issue a signed JWT with 24-hour expiry.

    각 길드 항목에 ``admin`` 플래그를 저장해 대시보드가 멤버십이 아닌
    Administrator(권한 비트 0x8)/소유자 기준으로 편집 권한을 강제할 수 있게 한다 (#79).
    기존 토큰(admin 키 없음)은 보수적으로 비관리자로 취급되므로 백워드 호환된다.
    """
    payload = {
        "sub": user_id,
        "guilds": [
            {
                "id": g["id"],
                "name": g["name"],
                "icon": g.get("icon"),
                "admin": _guild_is_admin(g),
            }
            for g in guilds
        ],
        "iat": datetime.now(timezone.utc),
        "exp": datetime.now(timezone.utc) + timedelta(hours=24),
    }
    return jwt.encode(payload, _secret_key(), algorithm=_jwt_algorithm())


def decode_jwt(token: str) -> dict | None:
    """Decode and verify a JWT.  Returns the payload or None on failure."""
    try:
        return jwt.decode(token, _secret_key(), algorithms=[_jwt_algorithm()])
    except jwt.PyJWTError:
        return None


# ---------------------------------------------------------------------------
# Routes
# ---------------------------------------------------------------------------


@router.get("/login")
async def login() -> RedirectResponse:
    """Redirect the browser to Discord's OAuth2 consent screen."""
    state = secrets.token_urlsafe(32)
    _oauth_states[state] = datetime.now(timezone.utc) + timedelta(seconds=_STATE_TTL_SECONDS)
    # Prune expired states
    now = datetime.now(timezone.utc)
    expired = [k for k, exp in _oauth_states.items() if exp < now]
    for k in expired:
        del _oauth_states[k]
    params = urlencode(
        {
            "client_id": _client_id(),
            "redirect_uri": _redirect_uri(),
            "response_type": "code",
            "scope": SCOPES,
            "state": state,
        }
    )
    return RedirectResponse(url=f"{DISCORD_OAUTH_URL}?{params}")


@router.get("/callback")
async def callback(code: str | None = None, error: str | None = None, state: str | None = None) -> JSONResponse:
    """Exchange the authorization code for a token and issue a JWT.

    On success the response contains ``{"token": "<jwt>"}`` so that the
    Next.js app can store it and include it in subsequent API requests.
    """
    if error:
        raise HTTPException(
            status_code=status.HTTP_400_BAD_REQUEST,
            detail=f"Discord OAuth2 error: {error}",
        )
    if not code:
        raise HTTPException(
            status_code=status.HTTP_400_BAD_REQUEST,
            detail="Missing authorization code",
        )
    # CSRF state validation
    if not state or state not in _oauth_states:
        raise HTTPException(
            status_code=status.HTTP_400_BAD_REQUEST,
            detail="Invalid or missing OAuth2 state parameter",
        )
    if _oauth_states[state] < datetime.now(timezone.utc):
        del _oauth_states[state]
        raise HTTPException(
            status_code=status.HTTP_400_BAD_REQUEST,
            detail="OAuth2 state has expired. Please try logging in again.",
        )
    del _oauth_states[state]

    # Exchange code for access token
    async with httpx.AsyncClient() as client:
        token_resp = await client.post(
            DISCORD_TOKEN_URL,
            data={
                "grant_type": "authorization_code",
                "code": code,
                "redirect_uri": _redirect_uri(),
                "client_id": _client_id(),
                "client_secret": _client_secret(),
            },
            headers={"Content-Type": "application/x-www-form-urlencoded"},
            timeout=10,
        )

    if token_resp.status_code != 200:
        raise HTTPException(
            status_code=status.HTTP_502_BAD_GATEWAY,
            detail=f"Discord token exchange failed: {token_resp.text}",
        )

    token_data = token_resp.json()
    access_token = token_data.get("access_token")
    if not access_token:
        raise HTTPException(
            status_code=status.HTTP_502_BAD_GATEWAY,
            detail="No access_token in Discord response",
        )

    # Fetch user info and guild list in parallel
    auth_header = {"Authorization": f"Bearer {access_token}"}
    async with httpx.AsyncClient() as client:
        user_resp, guilds_resp = await asyncio.gather(
            client.get(f"{DISCORD_API_BASE}/users/@me", headers=auth_header, timeout=10),
            client.get(f"{DISCORD_API_BASE}/users/@me/guilds", headers=auth_header, timeout=10),
        )

    if user_resp.status_code != 200:
        raise HTTPException(
            status_code=status.HTTP_502_BAD_GATEWAY,
            detail="Failed to fetch Discord user info",
        )

    user = user_resp.json()
    guilds = guilds_resp.json() if guilds_resp.status_code == 200 else []

    jwt_token = create_jwt(str(user["id"]), guilds if isinstance(guilds, list) else [])
    return JSONResponse({"token": jwt_token, "user": {"id": user["id"], "username": user.get("username", "")}})


@router.post("/refresh")
async def refresh(request: Request) -> JSONResponse:
    """유효한(만료 전) JWT 를 받아 동일한 클레임으로 새 24시간 토큰을 재발급한다 (#85).

    프론트(apiFetch)가 만료 임박 또는 401 직전에 호출해 사용자를 재로그인 없이
    유지한다. 만료/위조 토큰은 401 로 거절되어 재로그인 플로우로 빠진다.
    guilds 클레임(admin 플래그 포함)은 그대로 보존해 권한 정보를 유지한다.
    """
    auth = request.headers.get("Authorization", "")
    if not auth.startswith("Bearer "):
        raise HTTPException(status_code=status.HTTP_401_UNAUTHORIZED, detail="Missing token")
    token = auth.removeprefix("Bearer ").strip()
    payload = decode_jwt(token)
    if payload is None:
        # 만료되었거나 위조된 토큰은 재발급하지 않는다(재로그인 필요).
        raise HTTPException(
            status_code=status.HTTP_401_UNAUTHORIZED,
            detail="Invalid or expired token",
            headers={"WWW-Authenticate": "Bearer"},
        )
    # 기존 guilds 클레임을 그대로 보존하여 새 만료시각의 토큰을 발급한다.
    # create_jwt 는 admin/icon 키를 그대로 통과시키므로 권한 정보가 유지된다.
    guilds = payload.get("guilds", [])
    new_token = create_jwt(str(payload["sub"]), guilds if isinstance(guilds, list) else [])
    return JSONResponse({"token": new_token})


@router.get("/me")
async def me(request: Request) -> JSONResponse:
    """Return the current user's info decoded from the Bearer JWT."""
    auth = request.headers.get("Authorization", "")
    if not auth.startswith("Bearer "):
        raise HTTPException(status_code=status.HTTP_401_UNAUTHORIZED, detail="Missing token")
    token = auth.removeprefix("Bearer ").strip()
    payload = decode_jwt(token)
    if payload is None:
        raise HTTPException(status_code=status.HTTP_401_UNAUTHORIZED, detail="Invalid token")
    return JSONResponse({"sub": payload["sub"], "guilds": payload.get("guilds", [])})


