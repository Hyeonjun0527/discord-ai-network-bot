"""Fernet symmetric encryption for storing API keys in SQLite."""
from __future__ import annotations

import base64
import hashlib

from cryptography.fernet import Fernet, InvalidToken

__all__ = ["encrypt_api_key", "decrypt_api_key", "CryptoError"]


class CryptoError(ValueError):
    """Raised when decryption fails (wrong key or tampered data)."""


def _fernet_key(secret: str) -> bytes:
    digest = hashlib.sha256(secret.encode("utf-8")).digest()
    return base64.urlsafe_b64encode(digest)


def encrypt_api_key(api_key: str, secret: str) -> str:
    return Fernet(_fernet_key(secret)).encrypt(api_key.encode("utf-8")).decode("utf-8")


def decrypt_api_key(token: str, secret: str) -> str:
    if not isinstance(token, str):
        raise CryptoError(
            f"API 키 복호화 실패 — 토큰 형식이 올바르지 않습니다(type={type(token).__name__})."
        )
    try:
        return Fernet(_fernet_key(secret)).decrypt(token.encode("utf-8")).decode("utf-8")
    except InvalidToken as exc:
        raise CryptoError("API 키 복호화 실패 — SECRET_KEY가 변경됐을 수 있습니다.") from exc
