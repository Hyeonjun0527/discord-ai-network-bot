"""WS 프레임 정의·직렬화 — Kotlin 중앙 서버와 동일 와이어 포맷(camelCase + type).

직렬화 불변식: ``loads_frame(dumps_frame(f)) == f``. 한국어 등 비ASCII 보존(ensure_ascii=False).
Kotlin Jackson 이 모르는 키를 무시(FAIL_ON_UNKNOWN_PROPERTIES=false)하므로, 누락 키는 기본값으로 채운다.
"""
from __future__ import annotations

import json
import uuid
from dataclasses import dataclass, field
from typing import Any, Final

from .constants import (
    ALLOWED_OPTION_KEYS,
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
    "ProviderHelloFrame",
    "ProviderStatusFrame",
    "Frame",
    "new_request_id",
    "filter_options",
    "frame_to_dict",
    "frame_from_dict",
    "dumps_frame",
    "loads_frame",
]


class ProtocolError(Exception):
    """프로토콜 위반(알 수 없는 타입·필수 필드 누락·크기 초과·JSON 오류)."""


def new_request_id() -> str:
    return uuid.uuid4().hex


def filter_options(options: dict[str, Any] | None) -> dict[str, Any]:
    if not options:
        return {}
    return {k: v for k, v in options.items() if k in ALLOWED_OPTION_KEYS}


def _require(d: dict[str, Any], key: str, ftype: str) -> Any:
    if key not in d:
        raise ProtocolError(f"{ftype} 프레임에 필수 필드 '{key}' 가 없습니다")
    return d[key]


@dataclass(frozen=True, slots=True)
class Usage:
    prompt_tokens: int = 0
    completion_tokens: int = 0

    def to_dict(self) -> dict[str, int]:
        return {"promptTokens": self.prompt_tokens, "completionTokens": self.completion_tokens}

    @classmethod
    def from_dict(cls, d: dict[str, Any] | None) -> "Usage":
        if not d:
            return cls()
        return cls(int(d.get("promptTokens", 0) or 0), int(d.get("completionTokens", 0) or 0))


@dataclass(frozen=True, slots=True)
class AuthFrame:
    token: str = ""
    protocol_version: str = PROTOCOL_VERSION
    agent_version: str = ""
    platform: str = ""

    @property
    def type(self) -> str:
        return FrameType.AUTH

    def to_dict(self) -> dict[str, Any]:
        return {
            "type": FrameType.AUTH,
            "token": self.token,
            "protocolVersion": self.protocol_version,
            "agentVersion": self.agent_version,
            "platform": self.platform,
        }

    @classmethod
    def from_dict(cls, d: dict[str, Any]) -> "AuthFrame":
        return cls(
            token=str(_require(d, "token", FrameType.AUTH)),
            protocol_version=str(d.get("protocolVersion", PROTOCOL_VERSION)),
            agent_version=str(d.get("agentVersion", "")),
            platform=str(d.get("platform", "")),
        )

    def __repr__(self) -> str:
        masked = "***" if self.token else ""
        return (
            f"AuthFrame(token={masked!r}, protocol_version={self.protocol_version!r}, "
            f"agent_version={self.agent_version!r}, platform={self.platform!r})"
        )


@dataclass(frozen=True, slots=True)
class AuthOkFrame:
    protocol_version: str = PROTOCOL_VERSION
    session_id: str = ""
    provider_token: str = ""  # 재연결·재시작 재사용용 durable 토큰(비면 미발급)
    # 인증된 토큰이 묶인 길드. 토큰-연결 시 '이름 미상' 수동 라벨링 없이 서버명을 바로 표시.
    guild_id: int | None = None
    guild_name: str | None = None

    @property
    def type(self) -> str:
        return FrameType.AUTH_OK

    def to_dict(self) -> dict[str, Any]:
        return {
            "type": FrameType.AUTH_OK,
            "protocolVersion": self.protocol_version,
            "sessionId": self.session_id,
            "providerToken": self.provider_token,
            "guildId": self.guild_id,
            "guildName": self.guild_name,
        }

    @classmethod
    def from_dict(cls, d: dict[str, Any]) -> "AuthOkFrame":
        gid = d.get("guildId")
        gname = d.get("guildName")
        return cls(
            str(d.get("protocolVersion", PROTOCOL_VERSION)),
            str(d.get("sessionId", "")),
            str(d.get("providerToken", "")),
            int(gid) if gid is not None else None,
            str(gname) if gname is not None else None,
        )


@dataclass(frozen=True, slots=True)
class AuthErrFrame:
    code: str = ErrorCode.AUTH_FAILED
    message: str = ""

    @property
    def type(self) -> str:
        return FrameType.AUTH_ERR

    def to_dict(self) -> dict[str, Any]:
        return {"type": FrameType.AUTH_ERR, "code": self.code, "message": self.message}

    @classmethod
    def from_dict(cls, d: dict[str, Any]) -> "AuthErrFrame":
        return cls(str(d.get("code", ErrorCode.AUTH_FAILED)), str(d.get("message", "")))


@dataclass(frozen=True, slots=True)
class InferRequest:
    request_id: str
    model: str | None = None
    prompt: str = ""
    options: dict[str, Any] = field(default_factory=dict)
    stream: bool = False  # true 면 ChunkFrame 으로 점진 응답(#142)
    task: str = "text"  # "text" | "image"(로컬 SD 이미지 생성, SD Phase 2)

    def __post_init__(self) -> None:
        if len(self.prompt) > MAX_PROMPT_CHARS:
            raise ProtocolError(f"프롬프트가 너무 깁니다({len(self.prompt)} > {MAX_PROMPT_CHARS}자)")
        filtered = filter_options(self.options)
        if filtered != self.options:
            object.__setattr__(self, "options", filtered)

    @property
    def type(self) -> str:
        return FrameType.INFER

    def to_dict(self) -> dict[str, Any]:
        return {
            "type": FrameType.INFER,
            "requestId": self.request_id,
            "model": self.model,
            "prompt": self.prompt,
            "options": filter_options(self.options),
            "stream": self.stream,
            "task": self.task,
        }

    @classmethod
    def from_dict(cls, d: dict[str, Any]) -> "InferRequest":
        return cls(
            request_id=str(_require(d, "requestId", FrameType.INFER)),
            model=(str(d["model"]) if d.get("model") is not None else None),
            prompt=str(d.get("prompt", "")),
            options=filter_options(d.get("options")),
            stream=bool(d.get("stream", False)),
            task=str(d.get("task", "text")),
        )


@dataclass(frozen=True, slots=True)
class InferResult:
    request_id: str
    text: str = ""
    usage: Usage = field(default_factory=Usage)

    @property
    def type(self) -> str:
        return FrameType.RESULT

    def to_dict(self) -> dict[str, Any]:
        return {
            "type": FrameType.RESULT,
            "requestId": self.request_id,
            "text": self.text,
            "usage": self.usage.to_dict(),
        }

    @classmethod
    def from_dict(cls, d: dict[str, Any]) -> "InferResult":
        return cls(
            request_id=str(_require(d, "requestId", FrameType.RESULT)),
            text=str(d.get("text", "")),
            usage=Usage.from_dict(d.get("usage")),
        )


@dataclass(frozen=True, slots=True)
class InferError:
    request_id: str
    code: str = ErrorCode.OLLAMA_ERROR
    message: str = ""

    @property
    def type(self) -> str:
        return FrameType.ERROR

    def to_dict(self) -> dict[str, Any]:
        return {
            "type": FrameType.ERROR,
            "requestId": self.request_id,
            "code": self.code,
            "message": self.message,
        }

    @classmethod
    def from_dict(cls, d: dict[str, Any]) -> "InferError":
        return cls(
            request_id=str(_require(d, "requestId", FrameType.ERROR)),
            code=str(d.get("code", ErrorCode.OLLAMA_ERROR)),
            message=str(d.get("message", "")),
        )


@dataclass(frozen=True, slots=True)
class ChunkFrame:
    request_id: str
    delta: str = ""
    done: bool = False
    # 진행률(0~100). -1 = 진행률 아님(이미지 데이터/텍스트 청크). 이미지 생성 중 SD 진행률을 중앙으로
    # 흘려보내 디스코드 '생각 중' 메시지를 N% 로 편집하게 한다(delta="" 인 상태 전용 청크).
    progress: int = -1

    @property
    def type(self) -> str:
        return FrameType.CHUNK

    def to_dict(self) -> dict[str, Any]:
        return {
            "type": FrameType.CHUNK,
            "requestId": self.request_id,
            "delta": self.delta,
            "done": self.done,
            "progress": self.progress,
        }

    @classmethod
    def from_dict(cls, d: dict[str, Any]) -> "ChunkFrame":
        return cls(
            request_id=str(_require(d, "requestId", FrameType.CHUNK)),
            delta=str(d.get("delta", "")),
            done=bool(d.get("done", False)),
            progress=int(d.get("progress", -1)),
        )


@dataclass(frozen=True, slots=True)
class PingFrame:
    @property
    def type(self) -> str:
        return FrameType.PING

    def to_dict(self) -> dict[str, Any]:
        return {"type": FrameType.PING}

    @classmethod
    def from_dict(cls, d: dict[str, Any]) -> "PingFrame":
        return cls()


@dataclass(frozen=True, slots=True)
class PongFrame:
    @property
    def type(self) -> str:
        return FrameType.PONG

    def to_dict(self) -> dict[str, Any]:
        return {"type": FrameType.PONG}

    @classmethod
    def from_dict(cls, d: dict[str, Any]) -> "PongFrame":
        return cls()


@dataclass(frozen=True, slots=True)
class CancelFrame:
    request_id: str

    @property
    def type(self) -> str:
        return FrameType.CANCEL

    def to_dict(self) -> dict[str, Any]:
        return {"type": FrameType.CANCEL, "requestId": self.request_id}

    @classmethod
    def from_dict(cls, d: dict[str, Any]) -> "CancelFrame":
        return cls(str(_require(d, "requestId", FrameType.CANCEL)))


@dataclass(frozen=True, slots=True)
class ProviderHelloFrame:
    models: list[str] = field(default_factory=list)
    max_concurrency: int = 1
    remaining_daily_requests: int = 0
    # 제공 능력. 기본 ["text"]; 로컬 SD 이미지 생성 가능 시 "image" 추가(SD Phase 1).
    # 서버는 모르는 키를 무시(하위호환) — Phase 2 에서 라우팅에 사용.
    capabilities: list[str] = field(default_factory=lambda: ["text"])

    @property
    def type(self) -> str:
        return FrameType.PROVIDER_HELLO

    def to_dict(self) -> dict[str, Any]:
        return {
            "type": FrameType.PROVIDER_HELLO,
            "models": list(self.models),
            "maxConcurrency": self.max_concurrency,
            "remainingDailyRequests": self.remaining_daily_requests,
            "capabilities": list(self.capabilities),
        }

    @classmethod
    def from_dict(cls, d: dict[str, Any]) -> "ProviderHelloFrame":
        return cls(
            models=[str(m) for m in (d.get("models") or [])],
            max_concurrency=int(d.get("maxConcurrency", 1) or 1),
            remaining_daily_requests=int(d.get("remainingDailyRequests", 0) or 0),
            capabilities=[str(c) for c in (d.get("capabilities") or ["text"])],
        )


@dataclass(frozen=True, slots=True)
class ProviderStatusFrame:
    load: str = "idle"
    battery: str = ""
    online: bool = True
    busy: bool = False

    @property
    def type(self) -> str:
        return FrameType.PROVIDER_STATUS

    def to_dict(self) -> dict[str, Any]:
        return {
            "type": FrameType.PROVIDER_STATUS,
            "load": self.load,
            "battery": self.battery,
            "online": self.online,
            "busy": self.busy,
        }

    @classmethod
    def from_dict(cls, d: dict[str, Any]) -> "ProviderStatusFrame":
        return cls(
            load=str(d.get("load", "idle")),
            battery=str(d.get("battery", "")),
            online=bool(d.get("online", True)),
            busy=bool(d.get("busy", False)),
        )


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
    | ProviderHelloFrame
    | ProviderStatusFrame
)

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
    FrameType.PROVIDER_HELLO: ProviderHelloFrame.from_dict,
    FrameType.PROVIDER_STATUS: ProviderStatusFrame.from_dict,
}


def frame_to_dict(frame: Frame) -> dict[str, Any]:
    return frame.to_dict()


def frame_from_dict(d: dict[str, Any]) -> Frame:
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
    text = json.dumps(frame_to_dict(frame), ensure_ascii=False, separators=(",", ":"))
    if len(text.encode("utf-8")) > MAX_FRAME_BYTES:
        raise ProtocolError("프레임이 너무 큽니다")
    return text


def loads_frame(raw: str | bytes) -> Frame:
    if isinstance(raw, bytes):
        if len(raw) > MAX_FRAME_BYTES:
            raise ProtocolError("프레임이 너무 큽니다")
        raw = raw.decode("utf-8")
    elif len(raw.encode("utf-8")) > MAX_FRAME_BYTES:
        raise ProtocolError("프레임이 너무 큽니다")
    try:
        parsed = json.loads(raw)
    except (json.JSONDecodeError, ValueError) as exc:
        raise ProtocolError(f"JSON 파싱 실패: {exc}") from exc
    return frame_from_dict(parsed)
