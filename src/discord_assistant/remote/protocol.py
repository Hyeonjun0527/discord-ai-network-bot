"""원격 에이전트 WebSocket 프로토콜 — 프레임 정의·직렬화·검증 (ADR 0002, 차수 2).

중앙 봇 릴레이와 유저/프로바이더 에이전트가 주고받는 JSON 프레임을 정의한다. 모든 프레임은
``{"type": <FrameType>, ...}`` 형태이며, ``frame_to_dict``/``frame_from_dict`` 가 양방향 변환을,
``dumps_frame``/``loads_frame`` 이 JSON 직렬화를 담당한다.

직렬화 불변식: 임의 프레임 ``f`` 에 대해 ``frame_from_dict(frame_to_dict(f)) == f`` 가 성립한다
(round-trip 동치). JSON 경로도 ``loads_frame(dumps_frame(f)) == f`` 를 만족한다(한국어 등
비ASCII 보존 — ``ensure_ascii=False``).

보안: ``AuthFrame.token`` 같은 비밀 값은 ``__repr__``/로그에 마스킹된다(항목 47).
"""

from __future__ import annotations

import json
import uuid
from dataclasses import dataclass, field
from typing import Any, Final

from .constants import (
    MAX_FRAME_BYTES,
    MAX_PROMPT_CHARS,
    PROTOCOL_VERSION,
    ErrorCode,
    FrameType,
)

__all__ = [
    "ProtocolError",
    "Usage",
    "AuthFrame",
    "AuthOkFrame",
    "AuthErrFrame",
    "InferRequest",
    "InferResult",
    "InferError",
    "ChunkFrame",
    "PingFrame",
    "PongFrame",
    "CancelFrame",
    "Frame",
    "new_request_id",
    "frame_to_dict",
    "frame_from_dict",
    "dumps_frame",
    "loads_frame",
    "filter_options",
    "ALLOWED_OPTION_KEYS",
]


class ProtocolError(Exception):
    """프로토콜 위반(알 수 없는 타입·필수 필드 누락·크기 초과 등)."""


def new_request_id() -> str:
    """봇/에이전트 양쪽에서 일관되게 쓰는 요청 식별자를 만든다(항목 41)."""
    return uuid.uuid4().hex


# 추론 옵션 화이트리스트(항목 45). Ollama 가 이해하는 안전한 샘플링 파라미터만 통과시킨다.
ALLOWED_OPTION_KEYS: Final[frozenset[str]] = frozenset(
    {"temperature", "num_predict", "num_ctx", "top_p", "top_k", "stop", "seed"}
)


def filter_options(options: dict[str, Any] | None) -> dict[str, Any]:
    """화이트리스트에 있는 옵션 키만 남긴다(알 수 없는 키 제거)."""
    if not options:
        return {}
    return {k: v for k, v in options.items() if k in ALLOWED_OPTION_KEYS}


def _require(d: dict[str, Any], key: str, frame_type: str) -> Any:
    """필수 필드 검증(항목 40). 없으면 ProtocolError."""
    if key not in d:
        raise ProtocolError(f"{frame_type} 프레임에 필수 필드 '{key}' 가 없습니다")
    return d[key]


@dataclass(frozen=True, slots=True)
class Usage:
    """LLM 응답 토큰 사용량(항목 48). 정보가 없으면 0."""

    prompt_tokens: int = 0
    completion_tokens: int = 0

    def to_dict(self) -> dict[str, int]:
        return {"prompt_tokens": self.prompt_tokens, "completion_tokens": self.completion_tokens}

    @classmethod
    def from_dict(cls, d: dict[str, Any] | None) -> "Usage":
        if not d:
            return cls()
        return cls(
            prompt_tokens=int(d.get("prompt_tokens", 0) or 0),
            completion_tokens=int(d.get("completion_tokens", 0) or 0),
        )


@dataclass(frozen=True, slots=True)
class AuthFrame:
    """에이전트 → 릴레이: 연결 직후 인증(항목 28). 토큰은 마스킹된다."""

    type: Final[str] = field(default=FrameType.AUTH, init=False)
    token: str = ""
    protocol_version: str = PROTOCOL_VERSION
    agent_version: str = ""
    platform: str = ""

    def to_dict(self) -> dict[str, Any]:
        return {
            "type": self.type,
            "token": self.token,
            "protocol_version": self.protocol_version,
            "agent_version": self.agent_version,
            "platform": self.platform,
        }

    @classmethod
    def from_dict(cls, d: dict[str, Any]) -> "AuthFrame":
        return cls(
            token=str(_require(d, "token", FrameType.AUTH)),
            protocol_version=str(d.get("protocol_version", PROTOCOL_VERSION)),
            agent_version=str(d.get("agent_version", "")),
            platform=str(d.get("platform", "")),
        )

    def __repr__(self) -> str:  # 토큰 마스킹(항목 47)
        masked = "***" if self.token else ""
        return (
            f"AuthFrame(token={masked!r}, protocol_version={self.protocol_version!r}, "
            f"agent_version={self.agent_version!r}, platform={self.platform!r})"
        )


@dataclass(frozen=True, slots=True)
class AuthOkFrame:
    """릴레이 → 에이전트: 인증 성공(항목 29)."""

    type: Final[str] = field(default=FrameType.AUTH_OK, init=False)
    protocol_version: str = PROTOCOL_VERSION
    session_id: str = ""

    def to_dict(self) -> dict[str, Any]:
        return {
            "type": self.type,
            "protocol_version": self.protocol_version,
            "session_id": self.session_id,
        }

    @classmethod
    def from_dict(cls, d: dict[str, Any]) -> "AuthOkFrame":
        return cls(
            protocol_version=str(d.get("protocol_version", PROTOCOL_VERSION)),
            session_id=str(d.get("session_id", "")),
        )


@dataclass(frozen=True, slots=True)
class AuthErrFrame:
    """릴레이 → 에이전트: 인증 실패(항목 29)."""

    type: Final[str] = field(default=FrameType.AUTH_ERR, init=False)
    code: str = ErrorCode.AUTH_FAILED
    message: str = ""

    def to_dict(self) -> dict[str, Any]:
        return {"type": self.type, "code": self.code, "message": self.message}

    @classmethod
    def from_dict(cls, d: dict[str, Any]) -> "AuthErrFrame":
        return cls(
            code=str(d.get("code", ErrorCode.AUTH_FAILED)),
            message=str(d.get("message", "")),
        )


@dataclass(frozen=True, slots=True)
class InferRequest:
    """릴레이 → 에이전트: 추론 요청(항목 30). prompt 길이 상한을 검증한다(항목 44)."""

    request_id: str
    model: str | None = None
    prompt: str = ""
    options: dict[str, Any] = field(default_factory=dict)

    def __post_init__(self) -> None:
        if len(self.prompt) > MAX_PROMPT_CHARS:
            raise ProtocolError(
                f"프롬프트가 너무 깁니다({len(self.prompt)} > {MAX_PROMPT_CHARS}자)"
            )
        # 생성 시점에 옵션을 화이트리스트로 정규화한다(항목 45). 이렇게 하면 프레임이
        # 항상 정상화된 상태를 유지해 round-trip 불변식이 성립한다(frozen 이므로
        # object.__setattr__ 사용).
        filtered = filter_options(self.options)
        if filtered != self.options:
            object.__setattr__(self, "options", filtered)

    @property
    def type(self) -> str:
        return FrameType.INFER

    def to_dict(self) -> dict[str, Any]:
        return {
            "type": FrameType.INFER,
            "request_id": self.request_id,
            "model": self.model,
            "prompt": self.prompt,
            "options": filter_options(self.options),
        }

    @classmethod
    def from_dict(cls, d: dict[str, Any]) -> "InferRequest":
        return cls(
            request_id=str(_require(d, "request_id", FrameType.INFER)),
            model=(str(d["model"]) if d.get("model") is not None else None),
            prompt=str(d.get("prompt", "")),
            options=filter_options(d.get("options")),
        )


@dataclass(frozen=True, slots=True)
class InferResult:
    """에이전트 → 릴레이: 추론 성공 결과(항목 31)."""

    request_id: str
    text: str = ""
    usage: Usage = field(default_factory=Usage)

    @property
    def type(self) -> str:
        return FrameType.RESULT

    def to_dict(self) -> dict[str, Any]:
        return {
            "type": FrameType.RESULT,
            "request_id": self.request_id,
            "text": self.text,
            "usage": self.usage.to_dict(),
        }

    @classmethod
    def from_dict(cls, d: dict[str, Any]) -> "InferResult":
        return cls(
            request_id=str(_require(d, "request_id", FrameType.RESULT)),
            text=str(d.get("text", "")),
            usage=Usage.from_dict(d.get("usage")),
        )


@dataclass(frozen=True, slots=True)
class InferError:
    """에이전트 → 릴레이: 추론 실패(항목 32). code 는 ErrorCode."""

    request_id: str
    code: str = ErrorCode.OLLAMA_ERROR
    message: str = ""

    @property
    def type(self) -> str:
        return FrameType.ERROR

    def to_dict(self) -> dict[str, Any]:
        return {
            "type": FrameType.ERROR,
            "request_id": self.request_id,
            "code": self.code,
            "message": self.message,
        }

    @classmethod
    def from_dict(cls, d: dict[str, Any]) -> "InferError":
        return cls(
            request_id=str(_require(d, "request_id", FrameType.ERROR)),
            code=str(d.get("code", ErrorCode.OLLAMA_ERROR)),
            message=str(d.get("message", "")),
        )


@dataclass(frozen=True, slots=True)
class ChunkFrame:
    """에이전트 → 릴레이: 스트리밍 부분 텍스트(항목 33)."""

    request_id: str
    delta: str = ""
    done: bool = False

    @property
    def type(self) -> str:
        return FrameType.CHUNK

    def to_dict(self) -> dict[str, Any]:
        return {
            "type": FrameType.CHUNK,
            "request_id": self.request_id,
            "delta": self.delta,
            "done": self.done,
        }

    @classmethod
    def from_dict(cls, d: dict[str, Any]) -> "ChunkFrame":
        return cls(
            request_id=str(_require(d, "request_id", FrameType.CHUNK)),
            delta=str(d.get("delta", "")),
            done=bool(d.get("done", False)),
        )


@dataclass(frozen=True, slots=True)
class PingFrame:
    """heartbeat ping(항목 34)."""

    type: Final[str] = field(default=FrameType.PING, init=False)

    def to_dict(self) -> dict[str, Any]:
        return {"type": self.type}

    @classmethod
    def from_dict(cls, d: dict[str, Any]) -> "PingFrame":
        return cls()


@dataclass(frozen=True, slots=True)
class PongFrame:
    """heartbeat pong(항목 34)."""

    type: Final[str] = field(default=FrameType.PONG, init=False)

    def to_dict(self) -> dict[str, Any]:
        return {"type": self.type}

    @classmethod
    def from_dict(cls, d: dict[str, Any]) -> "PongFrame":
        return cls()


@dataclass(frozen=True, slots=True)
class CancelFrame:
    """릴레이 → 에이전트: 진행 중 요청 취소(항목 35)."""

    request_id: str

    @property
    def type(self) -> str:
        return FrameType.CANCEL

    def to_dict(self) -> dict[str, Any]:
        return {"type": FrameType.CANCEL, "request_id": self.request_id}

    @classmethod
    def from_dict(cls, d: dict[str, Any]) -> "CancelFrame":
        return cls(request_id=str(_require(d, "request_id", FrameType.CANCEL)))


# 모든 프레임의 합집합 타입(정적 타이핑/역직렬화 dispatch 용).
Frame = (
    AuthFrame
    | AuthOkFrame
    | AuthErrFrame
    | InferRequest
    | InferResult
    | InferError
    | ChunkFrame
    | PingFrame
    | PongFrame
    | CancelFrame
)

# type 문자열 → from_dict 디스패치 테이블(항목 37). 단일 출처(FrameType) 재사용(항목 52).
_FROM_DICT: Final[dict[str, Any]] = {
    FrameType.AUTH: AuthFrame.from_dict,
    FrameType.AUTH_OK: AuthOkFrame.from_dict,
    FrameType.AUTH_ERR: AuthErrFrame.from_dict,
    FrameType.INFER: InferRequest.from_dict,
    FrameType.RESULT: InferResult.from_dict,
    FrameType.ERROR: InferError.from_dict,
    FrameType.CHUNK: ChunkFrame.from_dict,
    FrameType.PING: PingFrame.from_dict,
    FrameType.PONG: PongFrame.from_dict,
    FrameType.CANCEL: CancelFrame.from_dict,
}


def frame_to_dict(frame: Frame) -> dict[str, Any]:
    """프레임 → dict(항목 36)."""
    return frame.to_dict()


def frame_from_dict(d: dict[str, Any]) -> Frame:
    """dict → 프레임(항목 37). type 으로 디스패치하며, 알 수 없으면 ProtocolError(항목 39)."""
    if not isinstance(d, dict):
        raise ProtocolError("프레임은 객체(dict)여야 합니다")
    ftype = d.get("type")
    if not isinstance(ftype, str):
        raise ProtocolError("프레임에 문자열 'type' 필드가 필요합니다")
    builder = _FROM_DICT.get(ftype)
    if builder is None:
        raise ProtocolError(f"알 수 없는 프레임 타입: {ftype!r}")
    result: Frame = builder(d)
    return result


def dumps_frame(frame: Frame) -> str:
    """프레임 → JSON 문자열(항목 38). 한국어 보존(ensure_ascii=False, 항목 53)."""
    text = json.dumps(frame_to_dict(frame), ensure_ascii=False, separators=(",", ":"))
    encoded_len = len(text.encode("utf-8"))
    if encoded_len > MAX_FRAME_BYTES:
        raise ProtocolError(f"프레임이 너무 큽니다({encoded_len} > {MAX_FRAME_BYTES} bytes)")
    return text


def loads_frame(raw: str | bytes) -> Frame:
    """JSON 문자열/바이트 → 프레임(항목 38). 크기 상한과 형식을 검증한다(항목 43)."""
    if isinstance(raw, bytes):
        if len(raw) > MAX_FRAME_BYTES:
            raise ProtocolError(f"프레임이 너무 큽니다({len(raw)} > {MAX_FRAME_BYTES} bytes)")
        raw = raw.decode("utf-8")
    elif len(raw.encode("utf-8")) > MAX_FRAME_BYTES:
        raise ProtocolError("프레임이 너무 큽니다")
    try:
        parsed = json.loads(raw)
    except (json.JSONDecodeError, ValueError) as exc:
        raise ProtocolError(f"JSON 파싱 실패: {exc}") from exc
    return frame_from_dict(parsed)
