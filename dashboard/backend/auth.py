"""Discord OAuth2 flow and JWT helpers.

Endpoints
---------
GET /auth/login    — redirect to Discord OAuth2
GET /auth/callback — exchange code, issue JWT
GET /auth/me       — return current user info from JWT
"""
from __future__ import annotations

import os
from datetime import datetime, timedelta, timezone
from urllib.parse import urlencode

import httpx
import jwt
from fastapi import APIRouter, HTTPException, Request, status
from fastapi.responses import JSONResponse, RedirectResponse

router = APIRouter(prefix="/auth", tags=["auth"])

# ---------------------------------------------------------------------------
# Config helpers
# ---------------------------------------------------------------------------

DISCORD_API_BASE = "https://discord.com/api/v10"
DISCORD_OAUTH_URL = "https://discord.com/oauth2/authorize"
DISCORD_TOKEN_URL = "https://discord.com/api/oauth2/token"

SCOPES = "identify guilds"


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
    """Issue a signed JWT with 24-hour expiry."""
    payload = {
        "sub": user_id,
        "guilds": [{"id": g["id"], "name": g["name"], "icon": g.get("icon")} for g in guilds],
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
    params = urlencode(
        {
            "client_id": _client_id(),
            "redirect_uri": _redirect_uri(),
            "response_type": "code",
            "scope": SCOPES,
        }
    )
    return RedirectResponse(url=f"{DISCORD_OAUTH_URL}?{params}")


@router.get("/callback")
async def callback(code: str | None = None, error: str | None = None) -> JSONResponse:
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


# asyncio needed for gather — import at module level to avoid NameError
import asyncio  # noqa: E402
