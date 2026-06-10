"""DO NOT EDIT — `protocol/wire-contract.json` 에서 `scripts/gen_wire_contract.py` 로 생성됨."""
from __future__ import annotations

from typing import Final

PROTOCOL_VERSION: Final[str] = "1.0"
MAX_FRAME_BYTES: Final[int] = 1000000
MAX_PROMPT_CHARS: Final[int] = 100000


class FrameType:
    """WS 프레임 type 값(중앙 서버 FrameType 과 동일)."""

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
    IMAGE_BROADCAST: Final[str] = "image_broadcast"


class ErrorCode:
    """error 프레임 코드(중앙 서버 ErrorCode 와 동일)."""

    OFFLINE: Final[str] = "OFFLINE"
    TIMEOUT: Final[str] = "TIMEOUT"
    OLLAMA_ERROR: Final[str] = "OLLAMA_ERROR"
    AUTH_FAILED: Final[str] = "AUTH_FAILED"
    BUSY: Final[str] = "BUSY"
    PROTOCOL_ERROR: Final[str] = "PROTOCOL_ERROR"


ALLOWED_OPTION_KEYS: Final[frozenset[str]] = frozenset(
    {"temperature", "num_predict", "num_ctx", "top_p", "top_k", "stop", "seed"}
)
