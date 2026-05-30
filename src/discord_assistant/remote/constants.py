"""원격 에이전트(BYO-LLM) 공통 상수 (ADR 0002/0003).

프로토콜 프레임 타입·에러 코드·프로토콜 버전·크기 상한 등 중앙 봇 릴레이와 유저
에이전트가 공유하는 단일 출처. 문자열 리터럴을 코드 곳곳에 흩뿌리지 않기 위해 여기에 모은다.
"""

from __future__ import annotations

from typing import Final

# 프로토콜 버전. 핸드셰이크에서 협상하며, major 가 다르면 호환되지 않는다.
PROTOCOL_VERSION: Final[str] = "1.0"

# remote 패키지 로거 네이밍 컨벤션(항목 22): 모든 하위 모듈은
# ``logging.getLogger(__name__)`` 를 쓰며, 그 결과는 ``discord_assistant.remote.<module>``
# 으로 통일된다. 이 prefix 로 핸들러/레벨을 일괄 제어할 수 있다.
LOGGER_NAMESPACE: Final[str] = "discord_assistant.remote"


class FrameType:
    """WebSocket 프레임 ``type`` 필드 값 (ADR 0002 메시지 프로토콜).

    Phase B(Provider Pool)에서 ``PROVIDER_HELLO``/``PROVIDER_STATUS`` 로 확장된다.
    """

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
    # Phase B 확장(차수 17)
    PROVIDER_HELLO: Final[str] = "provider_hello"
    PROVIDER_STATUS: Final[str] = "provider_status"


class ErrorCode:
    """``error`` 프레임 및 클라이언트 변환에서 쓰는 에러 코드."""

    OFFLINE: Final[str] = "OFFLINE"
    TIMEOUT: Final[str] = "TIMEOUT"
    OLLAMA_ERROR: Final[str] = "OLLAMA_ERROR"
    AUTH_FAILED: Final[str] = "AUTH_FAILED"
    BUSY: Final[str] = "BUSY"
    PROTOCOL_ERROR: Final[str] = "PROTOCOL_ERROR"


# 단일 프레임의 최대 직렬화 크기(바이트). 과대 프레임으로 인한 메모리 남용을 막는다.
MAX_FRAME_BYTES: Final[int] = 1_000_000

# 프롬프트 최대 길이(문자). 릴레이/에이전트 양쪽에서 방어적으로 적용한다.
MAX_PROMPT_CHARS: Final[int] = 100_000
