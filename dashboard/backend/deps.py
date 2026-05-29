"""Shared FastAPI dependencies."""
from __future__ import annotations

import os
from typing import Annotated

from fastapi import Depends, HTTPException, status
from fastapi.security import HTTPAuthorizationCredentials, HTTPBearer

from .auth import decode_jwt

_bearer = HTTPBearer(auto_error=True)


async def get_current_user(
    credentials: Annotated[HTTPAuthorizationCredentials, Depends(_bearer)],
) -> dict:
    """FastAPI dependency that validates a Bearer JWT and returns the payload.

    Usage::

        @app.get("/protected")
        async def protected(user: CurrentUser):
            return {"user_id": user["sub"]}
    """
    token = credentials.credentials
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
