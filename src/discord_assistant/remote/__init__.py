"""원격 에이전트(BYO-LLM) 패키지 (ADR 0002/0003).

유저·방장·커뮤니티 프로바이더 PC 의 로컬 LLM 을 중앙 봇 하나로 사용하기 위한
리버스 터널 에이전트 구성요소를 담는다.

- ``constants`` — 프로토콜 프레임 타입·에러 코드·상한 (구현됨, 차수 1)
- ``protocol`` — 프레임 dataclass·직렬화 (차수 2 예정)
- ``registry`` — 연결 레지스트리·라우팅 (차수 3 예정)
- ``relay`` — aiohttp WebSocket 릴레이 서버 (차수 4 예정)
- ``client`` — ``RemoteAgentClient(BaseLLMClient)`` (차수 5 예정)
- ``tokens`` — 페어링/호스트 토큰 (차수 6 예정)

로깅 컨벤션: 모든 하위 모듈은 ``logging.getLogger(__name__)`` 를 사용한다
(``constants.LOGGER_NAMESPACE`` 참고).
"""

from __future__ import annotations

from .constants import (
    LOGGER_NAMESPACE,
    MAX_FRAME_BYTES,
    MAX_PROMPT_CHARS,
    PROTOCOL_VERSION,
    ErrorCode,
    FrameType,
)

__all__ = [
    "PROTOCOL_VERSION",
    "LOGGER_NAMESPACE",
    "MAX_FRAME_BYTES",
    "MAX_PROMPT_CHARS",
    "FrameType",
    "ErrorCode",
]
