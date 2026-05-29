"""Small data models used by core modules."""
from __future__ import annotations

from dataclasses import dataclass
from datetime import datetime
from enum import Enum

# Minimum auto-summary interval enforced consistently by the model and storage.
MIN_AUTO_SUMMARY_INTERVAL_MINUTES = 5


class LLMProvider(str, Enum):
    OLLAMA = "ollama"
    OPENAI = "openai"
    ANTHROPIC = "anthropic"
    GEMINI = "gemini"

    def display_name(self) -> str:
        return {
            "ollama": "Ollama (로컬)",
            "openai": "OpenAI (GPT)",
            "anthropic": "Anthropic (Claude)",
            "gemini": "Google (Gemini)",
        }[self.value]

    def emoji(self) -> str:
        return {
            "ollama": "🖥️",
            "openai": "🤖",
            "anthropic": "🧠",
            "gemini": "✨",
        }[self.value]


@dataclass(frozen=True, slots=True)
class ChatMessage:
    """Normalized channel message independent of discord.py objects."""

    author: str
    content: str
    created_at: datetime | None = None
    is_bot: bool = False


@dataclass(frozen=True, slots=True)
class GuildConfig:
    """Per-server bot configuration."""

    guild_id: int
    model: str
    summary_limit: int
    language: str
    admin_role_id: int | None = None
    provider: LLMProvider = LLMProvider.OLLAMA
    api_key_encrypted: str | None = None
    auto_summary_interval: int | None = None  # minutes, None = disabled
    persona: str | None = None
    custom_summarize_prompt: str | None = None
    custom_ask_prompt: str | None = None
    allowed_role_id: int | None = None

    def __post_init__(self) -> None:
        if not self.model:
            raise ValueError("GuildConfig.model must not be empty")
        if (
            self.auto_summary_interval is not None
            and self.auto_summary_interval < MIN_AUTO_SUMMARY_INTERVAL_MINUTES
        ):
            raise ValueError(
                f"auto_summary_interval must be >= {MIN_AUTO_SUMMARY_INTERVAL_MINUTES} minutes"
            )


@dataclass(frozen=True, slots=True)
class OllamaModel:
    """Installed Ollama model info."""

    name: str
    size_bytes: int

    def size_display(self) -> str:
        gb = self.size_bytes / (1024**3)
        return f"{gb:.1f}GB"


@dataclass(frozen=True, slots=True)
class UsageLog:
    """Command usage log entry.

    #17: ``prompt_tokens``/``completion_tokens`` 는 LLM 응답의 usage 메타데이터에서
    파싱한 토큰 수다. 기본값 0 으로 두어 기존 호출부(토큰을 넘기지 않는 경로)와
    100% 백워드 호환된다. 토큰 정보가 없는 제공자/응답이면 0 으로 기록된다.
    """

    guild_id: int | None
    channel_id: int | None
    user_id: int | None
    command: str
    status: str
    latency_ms: int = 0
    error: str | None = None
    prompt_tokens: int = 0
    completion_tokens: int = 0


@dataclass(frozen=True, slots=True)
class Reminder:
    """예약된 리마인더 한 건 (#26).

    ``due_at`` 은 ISO8601 문자열(UTC 권장)이며, 비교는 문자열 사전식 비교로
    처리한다. ``payload`` 는 사용자에게 보낼 내용. ``id``/``sent``/``created_at``
    은 저장 시점에 채워지므로 신규 작성 시 기본값을 둔다(백워드 호환).
    """

    user_id: int
    guild_id: int | None
    channel_id: int | None
    due_at: str
    payload: str
    id: int | None = None
    sent: bool = False
    created_at: str | None = None


@dataclass(frozen=True, slots=True)
class AuditEntry:
    """감사 로그 한 건 (#39).

    ``before``/``after`` 는 변경 전후 값을 담는 선택적 문자열(예: JSON 직렬화).
    ``id``/``created_at`` 은 저장 시점에 채워지므로 기본값을 둔다.
    """

    guild_id: int | None
    user_id: int | None
    action: str
    target: str | None = None
    before: str | None = None
    after: str | None = None
    id: int | None = None
    created_at: str | None = None
