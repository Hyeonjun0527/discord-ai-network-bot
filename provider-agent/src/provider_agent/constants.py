"""WS 프로토콜 상수 — Kotlin 중앙 서버(central-server)와 동일 계약 (api.md §8).

와이어 포맷은 Kotlin 의 Jackson 기본 직렬화를 따른다: **camelCase 키** + ``type`` 디스크리미네이터.
이 상수/필드명은 중앙 서버 `relay/protocol/Frame.kt` 와 글자 그대로 일치해야 한다.
"""
from __future__ import annotations

from typing import Final

PROTOCOL_VERSION: Final[str] = "1.0"
MAX_FRAME_BYTES: Final[int] = 1_000_000
MAX_PROMPT_CHARS: Final[int] = 100_000

# 안전 기본값(차수: 일반 사용자 배포). 0 = 무제한이지만, 무제한은 --allow-unlimited 로만 가능.
DEFAULT_DAILY_LIMIT: Final[int] = 15
# 단일 응답 텍스트 상한(문자). 무거운/폭주 응답이 끝없이 커지지 않게 에이전트가 자른다.
MAX_RESPONSE_CHARS: Final[int] = 24_000
# 응답 토큰 상한(서버 옵션 num_predict 의 하드 캡). 서버가 더 큰 값을 줘도 이 값으로 클램프.
MAX_NUM_PREDICT: Final[int] = 2_048
# 이미지(base64) 전송 시 ChunkFrame 한 조각의 최대 문자 수(1MB 프레임 한계 내, SD Phase 2).
IMAGE_CHUNK_CHARS: Final[int] = 600_000

AGENT_VERSION: Final[str] = "0.18.1"


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
