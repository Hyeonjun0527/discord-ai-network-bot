"""Runtime settings loaded from environment variables."""

from __future__ import annotations

import logging
import math
import os
from dataclasses import dataclass

_settings_log = logging.getLogger(__name__)

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


def _is_production_env() -> bool:
    """ENVIRONMENT 또는 APP_ENV 환경 변수가 production 계열이면 True를 반환한다."""
    for name in ("ENVIRONMENT", "APP_ENV"):
        raw = os.getenv(name)
        if raw and raw.strip().lower() in {"production", "prod"}:
            return True
    return False


def _get_bool(name: str, default: bool) -> bool:
    raw = os.getenv(name)
    if raw is None or raw.strip() == "":
        return default
    return raw.strip().lower() in {"1", "true", "yes", "y", "on"}


def _get_float(
    name: str, default: float, *, minimum: float | None = None, maximum: float | None = None
) -> float:
    raw = os.getenv(name)
    if raw is None or raw.strip() == "":
        return default
    try:
        value = float(raw)
    except ValueError as exc:
        raise ValueError(f"{name} must be a number") from exc
    if not math.isfinite(value):
        raise ValueError(f"{name} must be a finite number")
    if minimum is not None and value < minimum:
        raise ValueError(f"{name} must be >= {minimum}")
    if maximum is not None and value > maximum:
        raise ValueError(f"{name} must be <= {maximum}")
    return value


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
    ollama_temperature: float = 0.2
    ollama_num_ctx: int = 8192
    # OpenAI/Anthropic 클라이언트가 주입받을 수 있는 파라미터 (기본값은 기존 하드코딩 값 유지)
    openai_temperature: float = 0.2
    anthropic_max_tokens: int = 4096
    llm_system_prompt: str = "You are a helpful Discord bot assistant."

    @classmethod
    def from_env(cls, *, load_env_file: bool = True) -> "AppSettings":
        if load_env_file and load_dotenv is not None:
            load_dotenv()

        token = os.getenv("DISCORD_BOT_TOKEN", "").strip()
        if not token or token.startswith("replace-with"):
            raise RuntimeError("DISCORD_BOT_TOKEN is required. Copy .env.example to .env first.")

        secret_key = os.getenv("SECRET_KEY", "change-me-in-production").strip()
        if secret_key == "change-me-in-production":
            # 운영 환경(production)에서는 기본 SECRET_KEY 사용을 금지하고 기동을 거부한다.
            if _is_production_env():
                raise RuntimeError(
                    "운영 환경(production)에서는 기본 SECRET_KEY('change-me-in-production')를 "
                    "사용할 수 없습니다. .env 파일에 SECRET_KEY를 안전한 임의 값으로 설정해 주세요."
                )
            _settings_log.warning(
                "SECRET_KEY is using the default insecure value. "
                "Set SECRET_KEY in your .env file for production use."
            )

        ollama_base_url = os.getenv("OLLAMA_BASE_URL", "http://localhost:11434").strip().rstrip("/")
        if not ollama_base_url:
            _settings_log.warning("OLLAMA_BASE_URL is empty; defaulting to http://localhost:11434")
            ollama_base_url = "http://localhost:11434"

        return cls(
            discord_bot_token=token,
            ollama_base_url=ollama_base_url,
            ollama_model=os.getenv("OLLAMA_MODEL", "llama3.1:8b").strip(),
            database_url=os.getenv("DATABASE_URL", "sqlite:///./data/discord_assistant.db").strip(),
            default_summary_limit=_get_int("DEFAULT_SUMMARY_LIMIT", 50, minimum=1),
            max_context_chars=_get_int("MAX_CONTEXT_CHARS", 12_000, minimum=1_000),
            default_language=os.getenv("DEFAULT_LANGUAGE", "ko").strip() or "ko",
            ollama_timeout_seconds=_get_int("OLLAMA_TIMEOUT_SECONDS", 60, minimum=1),
            auto_sync_commands=_get_bool("AUTO_SYNC_COMMANDS", True),
            secret_key=secret_key,
            ollama_keep_alive=os.getenv("OLLAMA_KEEP_ALIVE", "10m").strip() or "10m",
            ollama_temperature=_get_float("OLLAMA_TEMPERATURE", 0.2, minimum=0.0, maximum=2.0),
            ollama_num_ctx=_get_int("OLLAMA_NUM_CTX", 8192, minimum=256),
            openai_temperature=_get_float("OPENAI_TEMPERATURE", 0.2, minimum=0.0, maximum=2.0),
            anthropic_max_tokens=_get_int("ANTHROPIC_MAX_TOKENS", 4096, minimum=1),
            llm_system_prompt=(
                os.getenv("LLM_SYSTEM_PROMPT", "").strip()
                or "You are a helpful Discord bot assistant."
            ),
        )
