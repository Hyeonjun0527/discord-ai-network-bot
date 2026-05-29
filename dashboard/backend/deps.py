"""Shared FastAPI dependencies."""
from __future__ import annotations

from typing import Annotated

from fastapi import Depends, HTTPException, Request, status

from .auth import _token_from_request, decode_jwt


async def get_current_user(request: Request) -> dict:
    """FastAPI dependency that validates the JWT and returns the payload.

    #34: 토큰을 httpOnly 쿠키(우선)에서 읽고, 없으면 ``Authorization: Bearer``
    헤더로 폴백한다(한동안 병행 허용 — 백워드 호환). 쿠키/헤더 어디에도 토큰이
    없으면 401, 위조/만료/무효화(#44)된 토큰도 401 로 거절한다.

    Usage::

        @app.get("/protected")
        async def protected(user: CurrentUser):
            return {"user_id": user["sub"]}
    """
    token = _token_from_request(request)
    if not token:
        raise HTTPException(
            status_code=status.HTTP_401_UNAUTHORIZED,
            detail="Not authenticated",
            headers={"WWW-Authenticate": "Bearer"},
        )
    payload = decode_jwt(token)
    if payload is None:
        raise HTTPException(
            status_code=status.HTTP_401_UNAUTHORIZED,
            detail="Invalid or expired token",
            headers={"WWW-Authenticate": "Bearer"},
        )
    return payload


# Convenience type alias for route signatures
CurrentUser = Annotated[dict, Depends(get_current_user)]
