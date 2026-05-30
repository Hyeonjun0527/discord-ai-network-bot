"""원격 에이전트 추론 경로 예외 (ADR 0002, 차수 4/5).

릴레이의 ``send_infer`` 가 던지고, ``RemoteAgentClient`` 가 사용자 친화 ``LLMError`` 로
변환한다(차수 5). 프로토콜/연결 계층 예외(``ProtocolError``)와 구분한다.
"""

from __future__ import annotations

from .constants import ErrorCode


class RemoteError(Exception):
    """원격 추론 경로의 기본 예외."""


class AgentBusyError(RemoteError):
    """대기 큐가 가득 차 요청을 받을 수 없음(항목 107/108). code=BUSY."""

    code: str = ErrorCode.BUSY


class RemoteTimeoutError(RemoteError):
    """원격 에이전트 응답이 타임아웃됨(항목 101). code=TIMEOUT."""

    code: str = ErrorCode.TIMEOUT


class RemoteInferError(RemoteError):
    """에이전트가 ``error`` 프레임으로 보고한 실패(항목 97). code 는 에이전트가 준 값."""

    def __init__(self, code: str, message: str) -> None:
        super().__init__(message)
        self.code = code
        self.message = message


class ConnectionClosedError(RemoteError):
    """대기 중 연결이 끊겨 요청을 완료할 수 없음(항목 105). code=OFFLINE."""

    code: str = ErrorCode.OFFLINE
