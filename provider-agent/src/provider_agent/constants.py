"""WS 프로토콜 상수 — Kotlin 중앙 서버(central-server)와 동일 계약 (api.md §8).

와이어 포맷은 Kotlin 의 Jackson 기본 직렬화를 따른다: **camelCase 키** + ``type`` 디스크리미네이터.
이 상수/필드명은 중앙 서버 `relay/protocol/Frame.kt` 와 글자 그대로 일치해야 한다.
"""
from __future__ import annotations

from typing import Final

PROTOCOL_VERSION: Final[str] = "1.0"
MAX_FRAME_BYTES: Final[int] = 1_000_000
MAX_PROMPT_CHARS: Final[int] = 100_000

AGENT_VERSION: Final[str] = "0.1.1"


class FrameType:
    """WS 프레임 ``type`` 값(중앙 서버 FrameType 과 동일)."""

    AUTH: Final[str] = "auth"
    AUTH_OK: Final[str] = "auth_ok"
    AUTH_ERR: Final[str] = "auth_err"
    INFER: Final[str] = "infer"
    RESULT: Final[str] = "result"
    ERROR: Final[str] = "error"
    CHUNK: Final[str] = "chunk"
    PING: Final[str] = "ping"
    PONG: Final[str] = "pong"
    CANCEL: Final[str] = "cancel"
    PROVIDER_HELLO: Final[str] = "provider_hello"
    PROVIDER_STATUS: Final[str] = "provider_status"


class ErrorCode:
    """``error`` 프레임 코드(중앙 서버 ErrorCode 와 동일)."""

    OFFLINE: Final[str] = "OFFLINE"
    TIMEOUT: Final[str] = "TIMEOUT"
    OLLAMA_ERROR: Final[str] = "OLLAMA_ERROR"
    AUTH_FAILED: Final[str] = "AUTH_FAILED"
    BUSY: Final[str] = "BUSY"
    PROTOCOL_ERROR: Final[str] = "PROTOCOL_ERROR"


# 추론 옵션 화이트리스트(중앙 서버 ALLOWED_OPTION_KEYS 와 동일).
ALLOWED_OPTION_KEYS: Final[frozenset[str]] = frozenset(
    {"temperature", "num_predict", "num_ctx", "top_p", "top_k", "stop", "seed"}
)
