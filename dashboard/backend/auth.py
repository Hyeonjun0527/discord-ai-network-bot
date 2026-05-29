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
from fastapi import APIRouter, HTTPException, Request, Response, status
from fastapi.responses import JSONResponse, RedirectResponse

# In-memory state store for CSRF protection (maps state → expiry timestamp)
# For multi-process deployments, replace with Redis or DB-backed store.
_oauth_states: dict[str, datetime] = {}
_STATE_TTL_SECONDS = 600

# JWT 를 담는 httpOnly 쿠키 이름 (#34). 프론트는 JS 로 읽지 않고 브라우저가
# credentials: 'include' 로 자동 전송한다.
JWT_COOKIE_NAME = "dashboard_token"

# 로그아웃 시 무효화된 토큰의 jti 블랙리스트 (#44).
# jti → 해당 토큰의 exp(만료 시각). 만료가 지나면 자연히 거절되므로 정리한다.
# 다중 프로세스 배포에서는 Redis/DB 백엔드로 교체해야 한다.
_revoked_jti: dict[str, datetime] = {}

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
    """JWT 서명에 쓰는 키를 돌려준다 (#35).

    JWT 서명 키를 Fernet 암호화용 ``SECRET_KEY`` 와 분리한다. 전용
    ``JWT_SECRET_KEY`` 가 설정돼 있으면 그것을 쓰고, 없으면 기존 ``SECRET_KEY``
    로 폴백한다(백워드 호환). 이렇게 하면 봇 측 crypto(SECRET_KEY) 를 건드리지
    않고도 대시보드 토큰 서명 키를 독립적으로 회전할 수 있다.
    """
    jwt_key = os.getenv("JWT_SECRET_KEY")
    if jwt_key:
        return jwt_key
    return os.getenv("SECRET_KEY", "change-me-in-production")


def _jwt_algorithm() -> str:
    return "HS256"


def _is_secure_cookie() -> bool:
    """쿠키에 Secure 플래그를 붙일지 결정한다 (#34).

    기본은 True(운영 가정). 로컬 HTTP 개발에서는 Secure 쿠키가 전송되지 않으므로
    ``COOKIE_SECURE=false`` 로 끌 수 있다.
    """
    return os.getenv("COOKIE_SECURE", "true").strip().lower() not in {"false", "0", "no"}


def _set_auth_cookie(response: Response, token: str) -> None:
    """JWT 를 httpOnly+SameSite=Lax(+옵션 Secure) 쿠키로 심는다 (#34).

    XSS 로 토큰을 탈취당하지 않도록 httpOnly 로 두고, CSRF 완화를 위해
    SameSite=Lax 를 적용한다. max_age 는 토큰 수명(24h)과 맞춘다.
    """
    response.set_cookie(
        key=JWT_COOKIE_NAME,
        value=token,
        max_age=24 * 60 * 60,
        httponly=True,
        secure=_is_secure_cookie(),
        samesite="lax",
        path="/",
    )


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
        # 토큰마다 고유한 jti 를 부여해 로그아웃 시 개별 무효화(블랙리스트)할 수 있게 한다 (#44).
        "jti": secrets.token_urlsafe(16),
        "iat": datetime.now(timezone.utc),
        "exp": datetime.now(timezone.utc) + timedelta(hours=24),
    }
    return jwt.encode(payload, _secret_key(), algorithm=_jwt_algorithm())


def _prune_revoked(now: datetime | None = None) -> None:
    """만료가 지난 jti 항목을 블랙리스트에서 제거한다 (#44).

    만료된 토큰은 서명 검증 단계에서 어차피 거절되므로 블랙리스트에 남겨둘
    필요가 없다. 메모리 증가를 막기 위해 주기적으로 청소한다.
    """
    moment = now or datetime.now(timezone.utc)
    expired = [j for j, exp in _revoked_jti.items() if exp < moment]
    for j in expired:
        del _revoked_jti[j]


def revoke_jwt(token: str) -> bool:
    """주어진 토큰의 jti 를 블랙리스트에 올려 즉시 무효화한다 (#44).

    유효(서명/만료 정상)한 토큰의 jti 를 토큰 만료 시각까지 블랙리스트에 보관한다.
    이후 ``decode_jwt`` 가 해당 jti 를 거절한다. 토큰이 무효이거나 jti 가 없으면
    False 를 돌려준다(이미 사용할 수 없으므로 무효화 불필요).
    """
    try:
        payload = jwt.decode(token, _secret_key(), algorithms=[_jwt_algorithm()])
    except jwt.PyJWTError:
        return False
    jti = payload.get("jti")
    if not jti:
        return False
    exp = payload.get("exp")
    # exp(epoch) → aware datetime. 누락 시 24h 후로 보수적으로 잡는다.
    expiry = (
        datetime.fromtimestamp(exp, tz=timezone.utc)
        if isinstance(exp, (int, float))
        else datetime.now(timezone.utc) + timedelta(hours=24)
    )
    _revoked_jti[jti] = expiry
    _prune_revoked()
    return True


def decode_jwt(token: str) -> dict | None:
    """Decode and verify a JWT.  Returns the payload or None on failure.

    서명/만료 검증에 더해, 로그아웃으로 무효화된 jti(블랙리스트) 토큰도 거절한다 (#44).
    """
    try:
        payload = jwt.decode(token, _secret_key(), algorithms=[_jwt_algorithm()])
    except jwt.PyJWTError:
        return None
    jti = payload.get("jti")
    if jti and jti in _revoked_jti:
        # 무효화된(로그아웃된) 토큰은 거절한다.
        return None
    return payload


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


def _token_from_request(request: Request) -> str | None:
    """요청에서 JWT 를 꺼낸다 (#34).

    우선 httpOnly 쿠키(``JWT_COOKIE_NAME``)를 보고, 없으면 기존
    ``Authorization: Bearer`` 헤더로 폴백한다(한동안 병행 허용 — 백워드 호환).
    """
    cookie_token = request.cookies.get(JWT_COOKIE_NAME)
    if cookie_token:
        return cookie_token
    auth = request.headers.get("Authorization", "")
    if auth.startswith("Bearer "):
        return auth.removeprefix("Bearer ").strip() or None
    return None


@router.get("/callback")
async def callback(code: str | None = None, error: str | None = None, state: str | None = None) -> JSONResponse:
    """Exchange the authorization code for a token and issue a JWT.

    토큰은 응답 바디(JSON)가 아니라 httpOnly+SameSite=Lax(+Secure) 쿠키로 발급한다 (#34).
    XSS 로 토큰을 읽지 못하게 하기 위함이다. 바디에는 사용자 식별 정보만 담는다.
    기존 클라이언트 호환을 위해 ``token`` 필드도 한동안 함께 반환한다.
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
    resp = JSONResponse(
        {
            # 백워드 호환: 기존 클라이언트가 바디 token 을 읽던 흐름을 한동안 유지한다.
            "token": jwt_token,
            "user": {"id": user["id"], "username": user.get("username", "")},
        }
    )
    _set_auth_cookie(resp, jwt_token)
    return resp


@router.post("/refresh")
async def refresh(request: Request) -> JSONResponse:
    """유효한(만료 전) JWT 를 받아 동일한 클레임으로 새 24시간 토큰을 재발급한다 (#85).

    프론트(apiFetch)가 만료 임박 또는 401 직전에 호출해 사용자를 재로그인 없이
    유지한다. 만료/위조 토큰은 401 로 거절되어 재로그인 플로우로 빠진다.
    guilds 클레임(admin 플래그 포함)은 그대로 보존해 권한 정보를 유지한다.
    토큰은 httpOnly 쿠키(우선) 또는 Authorization 헤더에서 읽고, 새 토큰도 쿠키로 심는다 (#34).
    """
    token = _token_from_request(request)
    if not token:
        raise HTTPException(status_code=status.HTTP_401_UNAUTHORIZED, detail="Missing token")
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
    resp = JSONResponse({"token": new_token})
    _set_auth_cookie(resp, new_token)
    return resp


@router.post("/logout")
async def logout(request: Request) -> JSONResponse:
    """로그아웃: 현재 토큰을 무효화하고 인증 쿠키를 삭제한다 (#34, #44).

    토큰의 jti 를 블랙리스트에 올려 만료 전이라도 즉시 무효화하고(#44),
    httpOnly 쿠키를 지운다(#34). 토큰이 없거나 이미 무효여도 멱등하게 성공한다.
    """
    token = _token_from_request(request)
    if token:
        revoke_jwt(token)
    resp = JSONResponse({"logged_out": True})
    # 쿠키 삭제: set 과 동일한 path 로 만료시킨다.
    resp.delete_cookie(key=JWT_COOKIE_NAME, path="/")
    return resp


@router.get("/me")
async def me(request: Request) -> JSONResponse:
    """Return the current user's info decoded from the JWT (cookie 우선, 헤더 폴백) (#34)."""
    token = _token_from_request(request)
    if not token:
        raise HTTPException(status_code=status.HTTP_401_UNAUTHORIZED, detail="Missing token")
    payload = decode_jwt(token)
    if payload is None:
        raise HTTPException(status_code=status.HTTP_401_UNAUTHORIZED, detail="Invalid token")
    return JSONResponse({"sub": payload["sub"], "guilds": payload.get("guilds", [])})


