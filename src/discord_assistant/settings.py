"""Runtime settings loaded from environment variables."""

from __future__ import annotations

import logging
import math
import os
from dataclasses import dataclass

_settings_log = logging.getLogger(__name__)

# 운영(production) 환경에서 SECRET_KEY 로 허용할 최소 길이(문자 수). 너무 짧은 키는
# 사실상 약한 암호화로 이어지므로 거부한다.
_MIN_PROD_SECRET_KEY_LENGTH = 32
# .env.example 에 들어 있는 정확한 기본 placeholder. 이 값은 어떤 환경에서도(개발 포함)
# 절대 정당한 키가 아니므로, 운영 여부 판정(fail-open 가능)과 무관하게 항상 거부한다.
_DEFAULT_SECRET_KEY = "change-me-in-production"
# 정확 기본값 외에 운영 환경에서 거부할 잘 알려진 약한 SECRET_KEY 변형들(소문자 비교).
_WEAK_SECRET_KEYS = frozenset(
    {
        "change-me-in-production",
        "changeme",
        "change-me",
        "secret",
        "secret-key",
        "secretkey",
        "password",
        "default",
    }
)

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
        if raw and raw.strip().lower() in {"production", "prod", "live"}:
            return True
    return False


_TRUE_BOOL_VALUES = frozenset({"1", "true", "yes", "y", "on"})
_FALSE_BOOL_VALUES = frozenset({"0", "false", "no", "n", "off"})


def _get_bool(name: str, default: bool) -> bool:
    raw = os.getenv(name)
    if raw is None or raw.strip() == "":
        return default
    normalized = raw.strip().lower()
    if normalized in _TRUE_BOOL_VALUES:
        return True
    if normalized in _FALSE_BOOL_VALUES:
        return False
    # 화이트리스트 밖 값(오타·비표준 표기)은 조용히 False 로 떨어뜨리지 않고 경고를 남긴다.
    # _get_int/_get_float 가 잘못된 입력을 거부하는 것과 정책을 맞추기 위함이며,
    # 하위 호환을 위해 반환값은 기존과 동일하게 False 로 둔다.
    _settings_log.warning(
        "%s=%r is not a recognized boolean value; treating it as False. "
        "Use one of %s for true or %s for false.",
        name,
        raw,
        sorted(_TRUE_BOOL_VALUES),
        sorted(_FALSE_BOOL_VALUES),
    )
    return False


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
    # OpenAI/Anthropic/Gemini 클라이언트가 주입받을 수 있는 파라미터 (기본값은 기존 하드코딩 값 유지)
    openai_temperature: float = 0.2
    anthropic_max_tokens: int = 4096
    gemini_temperature: float = 0.2
    llm_system_prompt: str = "You are a helpful Discord bot assistant."
    # 관측성(선택적). metrics_port=0 이면 헬스/메트릭 서버 비활성, sentry_dsn 이
    # 빈 문자열이면 Sentry 비활성. 둘 다 의존성 미설치 시에도 안전하게 무시된다.
    metrics_port: int = 0
    sentry_dsn: str = ""

    @classmethod
    def from_env(cls, *, load_env_file: bool = True) -> "AppSettings":
        if load_env_file and load_dotenv is not None:
            load_dotenv()

        token = os.getenv("DISCORD_BOT_TOKEN", "").strip()
        if not token or token.startswith("replace-with"):
            raise RuntimeError("DISCORD_BOT_TOKEN is required. Copy .env.example to .env first.")

        secret_key = os.getenv("SECRET_KEY", _DEFAULT_SECRET_KEY).strip()
        # 정확한 기본 placeholder 는 API 키 암복호화에 쓰이는 키를 공개 기본값으로 두는 것이라
        # 운영 여부 판정(미설정 시 fail-open 가능)에 의존하지 않고 항상 기동을 거부한다.
        # 이 한 줄로 env 변수 누락으로 인한 fail-open(기본 SECRET_KEY 허용)을 막는다.
        if secret_key.lower() == _DEFAULT_SECRET_KEY:
            raise RuntimeError(
                "기본 SECRET_KEY('change-me-in-production')는 사용할 수 없습니다. "
                f"최소 {_MIN_PROD_SECRET_KEY_LENGTH}자 이상의 안전한 임의 값을 "
                ".env 파일의 SECRET_KEY에 설정해 주세요."
            )
        # 약한 SECRET_KEY 판정: 비어 있거나, 알려진 약한 값이거나, 최소 길이 미만이면 약하다.
        # 정확 일치(기본값)뿐 아니라 빈 값·짧은 값·약한 변형을 모두 포함해 가드 우회를 막는다.
        secret_is_weak = (
            not secret_key
            or secret_key.lower() in _WEAK_SECRET_KEYS
            or len(secret_key) < _MIN_PROD_SECRET_KEY_LENGTH
        )
        if secret_is_weak:
            # 운영 환경(production)에서는 약한 SECRET_KEY 사용을 금지하고 기동을 거부한다.
            if _is_production_env():
                raise RuntimeError(
                    "운영 환경(production)에서는 약한 SECRET_KEY(빈 값·기본값·짧은 값)를 "
                    f"사용할 수 없습니다. 최소 {_MIN_PROD_SECRET_KEY_LENGTH}자 이상의 안전한 "
                    "임의 값을 .env 파일의 SECRET_KEY에 설정해 주세요."
                )
            _settings_log.warning(
                "SECRET_KEY is empty or using a weak/insecure value. "
                "Set a strong SECRET_KEY in your .env file for production use."
            )

        ollama_base_url = os.getenv("OLLAMA_BASE_URL", "http://localhost:11434").strip().rstrip("/")
        if not ollama_base_url:
            _settings_log.warning("OLLAMA_BASE_URL is empty; defaulting to http://localhost:11434")
            ollama_base_url = "http://localhost:11434"

        return cls(
            discord_bot_token=token,
            ollama_base_url=ollama_base_url,
            ollama_model=os.getenv("OLLAMA_MODEL", "llama3.1:8b").strip() or "llama3.1:8b",
            database_url=(
                os.getenv("DATABASE_URL", "sqlite:///./data/discord_assistant.db").strip()
                or "sqlite:///./data/discord_assistant.db"
            ),
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
            gemini_temperature=_get_float("GEMINI_TEMPERATURE", 0.2, minimum=0.0, maximum=2.0),
            llm_system_prompt=(
                os.getenv("LLM_SYSTEM_PROMPT", "").strip()
                or "You are a helpful Discord bot assistant."
            ),
            # #48/#55 관측성. 0/빈값이면 비활성(기본). 음수 포트는 0(비활성)으로 취급.
            metrics_port=_get_int("METRICS_PORT", 0, minimum=0),
            sentry_dsn=os.getenv("SENTRY_DSN", "").strip(),
        )
