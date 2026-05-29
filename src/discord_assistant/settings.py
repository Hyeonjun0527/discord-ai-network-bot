"""Runtime settings loaded from environment variables."""

from __future__ import annotations

import os
from dataclasses import dataclass

try:  # python-dotenv is a runtime dependency, but keep local tests resilient.
    from dotenv import load_dotenv
except ImportError:  # pragma: no cover - only relevant before dependencies are installed.
    load_dotenv = None  # type: ignore[assignment]


def _get_int(name: str, default: int, *, minimum: int | None = None) -> int:
    raw = os.getenv(name)
    if raw is None or raw.strip() == "":
        return default
    try:
        value = int(raw)
    except ValueError as exc:
        raise ValueError(f"{name} must be an integer") from exc
    if minimum is not None and value < minimum:
        raise ValueError(f"{name} must be >= {minimum}")
    return value


def _get_bool(name: str, default: bool) -> bool:
    raw = os.getenv(name)
    if raw is None or raw.strip() == "":
        return default
    return raw.strip().lower() in {"1", "true", "yes", "y", "on"}


@dataclass(frozen=True, slots=True)
class AppSettings:
    """Configuration shared by the bot, LLM adapter, and storage."""

    discord_bot_token: str
    ollama_base_url: str = "http://localhost:11434"
    ollama_model: str = "llama3.1:8b"
    database_url: str = "sqlite:///./data/discord_assistant.db"
    default_summary_limit: int = 50
    max_context_chars: int = 12_000
    default_language: str = "ko"
    ollama_timeout_seconds: int = 60
    auto_sync_commands: bool = True
    secret_key: str = "change-me-in-production"
    ollama_keep_alive: str = "10m"

    @classmethod
    def from_env(cls, *, load_env_file: bool = True) -> "AppSettings":
        if load_env_file and load_dotenv is not None:
            load_dotenv()

        token = os.getenv("DISCORD_BOT_TOKEN", "").strip()
        if not token or token.startswith("replace-with"):
            raise RuntimeError("DISCORD_BOT_TOKEN is required. Copy .env.example to .env first.")

        return cls(
            discord_bot_token=token,
            ollama_base_url=os.getenv("OLLAMA_BASE_URL", "http://localhost:11434").strip().rstrip("/"),
            ollama_model=os.getenv("OLLAMA_MODEL", "llama3.1:8b").strip(),
            database_url=os.getenv("DATABASE_URL", "sqlite:///./data/discord_assistant.db").strip(),
            default_summary_limit=_get_int("DEFAULT_SUMMARY_LIMIT", 50, minimum=1),
            max_context_chars=_get_int("MAX_CONTEXT_CHARS", 12_000, minimum=1_000),
            default_language=os.getenv("DEFAULT_LANGUAGE", "ko").strip() or "ko",
            ollama_timeout_seconds=_get_int("OLLAMA_TIMEOUT_SECONDS", 60, minimum=1),
            auto_sync_commands=_get_bool("AUTO_SYNC_COMMANDS", True),
            secret_key=os.getenv("SECRET_KEY", "change-me-in-production").strip(),
            ollama_keep_alive=os.getenv("OLLAMA_KEEP_ALIVE", "10m").strip() or "10m",
        )
