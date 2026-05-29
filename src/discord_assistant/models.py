"""Small data models used by core modules."""
from __future__ import annotations

from dataclasses import dataclass
from datetime import datetime
from enum import Enum


class LLMProvider(str, Enum):
    OLLAMA = "ollama"
    OPENAI = "openai"
    ANTHROPIC = "anthropic"

    def display_name(self) -> str:
        return {
            "ollama": "Ollama (로컬)",
            "openai": "OpenAI (GPT)",
            "anthropic": "Anthropic (Claude)",
        }[self.value]

    def emoji(self) -> str:
        return {"ollama": "🖥️", "openai": "🤖", "anthropic": "🧠"}[self.value]


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
    # Phase 3 additions
    auto_summary_interval: int | None = None  # minutes, None = disabled
    persona: str | None = None               # custom chat persona
    custom_summarize_prompt: str | None = None
    custom_ask_prompt: str | None = None
    allowed_role_id: int | None = None


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
    """Command usage log entry."""

    guild_id: int | None
    channel_id: int | None
    user_id: int | None
    command: str
    status: str
    latency_ms: int | None = None
    error: str | None = None
