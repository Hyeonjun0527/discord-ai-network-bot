"""Fernet symmetric encryption for storing API keys in SQLite.

#81/#86: SECRET_KEY 로부터 Fernet 키를 유도할 때, 과거에는 단일 SHA-256 다이제스트를
썼다(salt·KDF 없음). 이는 SECRET_KEY 에 대한 무차별/사전 공격을 거의 늦추지 못한다.
이제 **버전 태그**로 암호화 스킴을 구분한다:

- **v2(신규, 기본)**: ``v2:`` 프리픽스 + Fernet 토큰. 키는 PBKDF2-HMAC-SHA256(고반복)
  로 유도해 SECRET_KEY 추측 비용을 크게 올린다.
- **v1(레거시)**: 프리픽스 없는 Fernet 토큰. 키 = base64(sha256(secret)). 기존 DB 에
  저장된 토큰을 **마이그레이션 없이 그대로 복호화**하기 위해 유지한다(하위호환).

새로 암호화하면 항상 v2 로 저장되며, 관리자가 키를 다시 저장하면 자연히 v2 로 승격된다.
키 유도는 SECRET_KEY 당 1회만 계산되도록 캐싱한다(복호화는 LLM 호출마다 일어나므로
PBKDF2 반복 비용이 매 호출에 들지 않게 한다).
"""
from __future__ import annotations

import base64
import hashlib
from functools import lru_cache

from cryptography.fernet import Fernet, InvalidToken
from cryptography.hazmat.primitives import hashes
from cryptography.hazmat.primitives.kdf.pbkdf2 import PBKDF2HMAC

__all__ = ["encrypt_api_key", "decrypt_api_key", "CryptoError"]

# v2 스킴 식별 프리픽스. Fernet 토큰 자체는 base64url 알파벳이라 ':' 를 포함하지
# 않으므로, 프리픽스 유무로 v1/v2 를 모호함 없이 구분할 수 있다.
_V2_PREFIX = "v2:"

# 앱 고정 salt(도메인 분리용). 레코드별 salt 를 저장하지 않는 대신, 높은 반복수와
# (가드로 강제되는) 충분히 긴 SECRET_KEY 로 안전성을 확보한다. 값 변경 시 기존 v2
# 토큰이 복호화 불가가 되므로 절대 바꾸지 않는다.
_PBKDF2_SALT = b"discord-assistant::api-key-encryption::v2"
_PBKDF2_ITERATIONS = 200_000


class CryptoError(ValueError):
    """Raised when decryption fails (wrong key or tampered data)."""


@lru_cache(maxsize=8)
def _v1_key(secret: str) -> bytes:
    """레거시 v1 키: base64(sha256(secret)). 기존 토큰 복호화 전용."""
    return base64.urlsafe_b64encode(hashlib.sha256(secret.encode("utf-8")).digest())


@lru_cache(maxsize=8)
def _v2_key(secret: str) -> bytes:
    """v2 키: PBKDF2-HMAC-SHA256(고반복)로 유도한 Fernet 키. SECRET_KEY 당 1회 계산."""
    kdf = PBKDF2HMAC(
        algorithm=hashes.SHA256(),
        length=32,
        salt=_PBKDF2_SALT,
        iterations=_PBKDF2_ITERATIONS,
    )
    return base64.urlsafe_b64encode(kdf.derive(secret.encode("utf-8")))


def encrypt_api_key(api_key: str, secret: str) -> str:
    """API 키를 v2 스킴으로 암호화한다(``v2:`` 프리픽스 + Fernet 토큰)."""
    token = Fernet(_v2_key(secret)).encrypt(api_key.encode("utf-8")).decode("utf-8")
    return _V2_PREFIX + token


def decrypt_api_key(token: str, secret: str) -> str:
    """저장된 토큰을 복호화한다. v2(프리픽스) 우선, 없으면 레거시 v1 로 처리한다."""
    if not isinstance(token, str):
        raise CryptoError(
            f"API 키 복호화 실패 — 토큰 형식이 올바르지 않습니다(type={type(token).__name__})."
        )
    try:
        if token.startswith(_V2_PREFIX):
            raw = token[len(_V2_PREFIX):].encode("utf-8")
            return Fernet(_v2_key(secret)).decrypt(raw).decode("utf-8")
        # 레거시 v1(프리픽스 없음): 기존 DB 토큰 하위호환.
        return Fernet(_v1_key(secret)).decrypt(token.encode("utf-8")).decode("utf-8")
    except InvalidToken as exc:
        raise CryptoError("API 키 복호화 실패 — SECRET_KEY가 변경됐을 수 있습니다.") from exc
