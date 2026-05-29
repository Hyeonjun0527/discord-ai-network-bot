"""discord.py entrypoint and command handlers."""
from __future__ import annotations

import asyncio
import contextvars
import io
import json
import logging
import os
import re
import signal
import sys
from datetime import datetime, timedelta, timezone
from time import perf_counter
from typing import Any

import discord
from discord import app_commands
from discord.ext import commands, tasks

from .cache import get_translation, set_translation, summarize_cache
from .context import build_transcript, from_discord_message, normalize_content
from .crypto import CryptoError, decrypt_api_key
from .llm import (
    AnthropicClient,
    BaseLLMClient,
    LLMError,
    OllamaClient,
    OllamaManager,
    OpenAIClient,
)
from .models import GuildConfig, LLMProvider, Reminder, UsageLog
from .monitor import format_disconnect_message, format_error_message, notify_developer
from .prompts import (
    _LANGUAGE_LABELS,
    build_ask_prompt,
    build_chat_prompt,
    build_chat_with_history_prompt,
    build_image_analysis_prompt,
    build_search_result_prompt,
    build_summarize_prompt,
    build_translate_prompt,
    detect_language_from_transcript,
)
from .settings import AppSettings
from .storage import ConfigStore
from .ui import (
    ChannelSelectView,
    FollowUpView,
    HelpView,
    SettingsView,
    ViewCtx,
    settings_embed,
)

logger = logging.getLogger(__name__)

_SLOW_RESPONSE_THRESHOLD_MS = 30_000

# --- #46 correlation id ---
# 명령마다 interaction.id 를 바인딩해 로그에 cid 를 끼워 넣는다. contextvars 는
# asyncio 태스크 경계를 넘어도 값을 안전하게 전파하므로 명령 핸들러 단위로 격리된다.
_correlation_id: contextvars.ContextVar[str] = contextvars.ContextVar(
    "correlation_id", default="-"
)


def get_correlation_id() -> str:
    """현재 컨텍스트에 바인딩된 correlation id 를 반환한다(없으면 '-')."""
    return _correlation_id.get()


def set_correlation_id(cid: str | int | None) -> None:
    """현재 컨텍스트에 correlation id 를 바인딩한다(_record_usage 등 핵심 경로용)."""
    _correlation_id.set(str(cid) if cid is not None else "-")


class CorrelationIdFilter(logging.Filter):
    """로그 레코드에 ``cid`` 속성을 주입하는 필터 (#46).

    포매터가 ``%(cid)s`` 를 참조할 수 있도록 모든 레코드에 현재 컨텍스트의
    correlation id 를 채운다. 이미 설정된 레코드는 덮어쓰지 않는다.
    """

    def filter(self, record: logging.LogRecord) -> bool:
        if not hasattr(record, "cid"):
            record.cid = get_correlation_id()
        return True


# --- #51 fire-and-forget 태스크 추적 ---
# create_task 로 띄운 태스크를 강한 참조로 보관해 GC 로 인한 조용한 소실을 막고,
# 완료 시 예외를 로깅한다(삼킴 방지).
_background_tasks: set[asyncio.Task[Any]] = set()


def _on_task_done(task: asyncio.Task[Any]) -> None:
    """추적 집합에서 태스크를 제거하고, 취소가 아닌 예외는 로깅한다 (#51)."""
    _background_tasks.discard(task)
    if task.cancelled():
        return
    exc = task.exception()
    if exc is not None:
        logger.exception(
            "백그라운드 태스크에서 예외 발생: %s", task.get_name(), exc_info=exc
        )


def _track_task(coro: Any, *, name: str | None = None) -> asyncio.Task[Any]:
    """코루틴을 태스크로 띄우고 추적 집합에 등록한다 (#51).

    asyncio.create_task 직접 호출을 대체해, 강한 참조 유지 + 예외 로깅을 한다.
    """
    task = asyncio.create_task(coro, name=name)
    _background_tasks.add(task)
    task.add_done_callback(_on_task_done)
    return task


# --- #27 retention 보존일 기본값 ---
# settings 에 별도 항목이 없으므로 상수로 둔다. usage_log 90일 / chat_history 30일.
RETENTION_USAGE_DAYS = 90
RETENTION_CHAT_DAYS = 30


def _truncate(text: str, limit: int = 1024) -> str:
    """Truncate text to embed field limit with ellipsis."""
    if len(text) <= limit:
        return text
    return text[: limit - 1] + "…"
MAX_DISCORD_MESSAGE_CHARS = 1900
MAX_EXPORT_BYTES = 8 * 1024 * 1024  # 8 MB Discord file limit
MAX_SEARCH_MATCHES = 20  # max matching messages shown in /search (#74)

# Reaction emojis for feedback tracking
THUMBS_UP = "\U0001f44d"   # 👍
THUMBS_DOWN = "\U0001f44e"  # 👎

# --- #9 리액션 트리거 이모지 ---
# 메시지에 아래 이모지를 달면 해당 메시지를 요약/번역해 답장한다. 👍/👎 피드백
# 경로(on_reaction_add)와는 별개로 on_raw_reaction_add 에서 처리한다.
REACTION_SUMMARIZE = "\U0001f4dd"  # 📝 요약
REACTION_TRANSLATE = "\U0001f310"  # 🌐 번역

# Message IDs that correspond to bot command results (for reaction tracking)
# guild_id -> {message_id -> command_name}
_tracked_messages: dict[int, dict[int, str]] = {}
_MAX_TRACKED_PER_GUILD = 500

# Last summarize results per user (for /remind)
# user_id -> (summary_text, guild_id)
_last_summaries: dict[int, tuple[str, int | None]] = {}
_MAX_LAST_SUMMARIES = 1000

# Auto-summary tracking: guild_id -> last_run_time
_auto_summary_last_run: dict[int, datetime] = {}

# Cooldown tracking: (guild_id, user_id) -> last used timestamp (task 25)
_cooldowns: dict[tuple[int, int], float] = {}
COOLDOWN_SECONDS = 10
# Sentinel "guild" bucket for DM cooldowns (real guilds never use id 0). Passing
# None as guild_id short-circuits _check_cooldown, so DMs need a concrete key (#20).
_DM_COOLDOWN_GUILD = 0


class UserFacingError(RuntimeError):
    """Raised for errors that should be shown plainly to Discord users."""


def _sanitize_persona(text: str) -> str:
    """Mitigate prompt injection in admin-set persona text (#42).

    Strips control characters and collapses newlines so the persona cannot
    inject fake role delimiters (e.g. a forged "User:"/"System:" block) into
    the prompt. Returns single-line, trimmed text.
    """
    # Map any whitespace (incl. newlines/tabs) to a space, drop other control
    # chars, then collapse runs of whitespace into single spaces.
    chars: list[str] = []
    for ch in text:
        if ch.isspace():
            chars.append(" ")
        elif ord(ch) < 32 or ord(ch) == 127:
            continue
        else:
            chars.append(ch)
    return re.sub(r"\s+", " ", "".join(chars)).strip()


def _parse_since(since_str: str) -> datetime:
    """Parse a duration string like '1h', '30m', '2d' into a UTC datetime in the past.

    Raises UserFacingError on invalid format.
    """
    since_str = since_str.strip().lower()
    match = re.fullmatch(r"(\d+)([mhd])", since_str)
    if not match:
        raise UserFacingError("올바른 형식: 1h, 30m, 2d (숫자 + m/h/d)")
    value = int(match.group(1))
    if value == 0:
        raise UserFacingError("0은 허용되지 않습니다. 예: 1h, 30m, 1d")
    unit = match.group(2)
    if unit == "m":
        delta = timedelta(minutes=value)
    elif unit == "h":
        delta = timedelta(hours=value)
    else:
        delta = timedelta(days=value)
    return datetime.now(timezone.utc) - delta


# --- #7 자동완성 후보 ---
# 자유 텍스트 인자(언어/기간/프롬프트 유형)에 슬래시 명령 자동완성을 붙여
# 오타·미지원 값을 줄인다. discord.py 의 app_commands.autocomplete 가 호출하는
# 콜백은 최대 25개의 Choice 만 반환할 수 있으므로 항상 슬라이스한다.

# 자동완성에 노출할 기간(since) 후보. _parse_since 가 받아들이는 형식과 일치한다.
_SINCE_CHOICES: list[tuple[str, str]] = [
    ("최근 30분", "30m"),
    ("최근 1시간", "1h"),
    ("최근 6시간", "6h"),
    ("최근 12시간", "12h"),
    ("최근 1일", "1d"),
    ("최근 3일", "3d"),
    ("최근 7일", "7d"),
]

# 커스텀 프롬프트 유형 후보 (set_custom_prompt 가 받는 값과 일치).
_PROMPT_TYPE_CHOICES: list[tuple[str, str]] = [
    ("summarize (요약)", "summarize"),
    ("ask (질문)", "ask"),
]


def _filter_choices(
    pairs: list[tuple[str, str]], current: str
) -> list[app_commands.Choice[str]]:
    """(라벨, 값) 목록을 현재 입력으로 필터링해 Choice 리스트(최대 25개)로 변환한다 (#7)."""
    needle = (current or "").strip().lower()
    out: list[app_commands.Choice[str]] = []
    for label, value in pairs:
        if not needle or needle in label.lower() or needle in value.lower():
            out.append(app_commands.Choice(name=label, value=value))
        if len(out) >= 25:
            break
    return out


async def _language_autocomplete(
    interaction: discord.Interaction, current: str
) -> list[app_commands.Choice[str]]:
    """언어 코드 자동완성: _LANGUAGE_LABELS + 'auto' 자동 감지 (#7)."""
    pairs: list[tuple[str, str]] = [("자동 감지 (auto)", "auto")]
    pairs.extend((f"{label} ({code})", code) for code, label in _LANGUAGE_LABELS.items())
    return _filter_choices(pairs, current)


async def _since_autocomplete(
    interaction: discord.Interaction, current: str
) -> list[app_commands.Choice[str]]:
    """기간(since) 자동완성: 30m/1h/6h/1d 등 (#7)."""
    return _filter_choices(_SINCE_CHOICES, current)


async def _prompt_type_autocomplete(
    interaction: discord.Interaction, current: str
) -> list[app_commands.Choice[str]]:
    """프롬프트 유형 자동완성: summarize/ask (#7)."""
    return _filter_choices(_PROMPT_TYPE_CHOICES, current)


_MAX_REMIND_DELAY = timedelta(days=30)


def _parse_remind_delay(when: str) -> timedelta:
    """'10', '30m', '2h', '1d' 형태의 지연 시간을 timedelta 로 파싱한다 (#2).

    단위가 없으면 분으로 해석한다(기존 N분 입력과의 호환). 1초 미만이거나 최대
    허용치(30일)를 넘으면 UserFacingError 를 발생시킨다.
    """
    text = when.strip().lower()
    match = re.fullmatch(r"(\d+)\s*([mhd]?)", text)
    if not match:
        raise UserFacingError("올바른 형식: 30m, 2h, 1d 또는 분 단위 숫자 (예: 10)")
    value = int(match.group(1))
    if value == 0:
        raise UserFacingError("0은 허용되지 않습니다. 예: 30m, 2h, 1d")
    unit = match.group(2) or "m"
    if unit == "m":
        delta = timedelta(minutes=value)
    elif unit == "h":
        delta = timedelta(hours=value)
    else:
        delta = timedelta(days=value)
    if delta > _MAX_REMIND_DELAY:
        raise UserFacingError("알림은 최대 30일 후까지만 예약할 수 있어요.")
    return delta


def _has_allowed_role(interaction: discord.Interaction, allowed_role_id: int | None) -> bool:
    """Return True if no role restriction, or user has the required role."""
    if allowed_role_id is None:
        return True
    roles = getattr(interaction.user, "roles", [])
    return any(getattr(role, "id", None) == allowed_role_id for role in roles)


_COOLDOWN_CLEANUP_INTERVAL = 300  # clean up expired entries every 5 minutes
_cooldown_last_cleanup: float = 0.0


def reset_cooldowns() -> None:
    """Clear all cooldown state. Intended for test isolation (#83)."""
    global _cooldown_last_cleanup
    _cooldowns.clear()
    _cooldown_last_cleanup = 0.0


def _check_cooldown(guild_id: int | None, user_id: int | None) -> float | None:
    """Return remaining cooldown seconds if on cooldown, else None. Updates last-used time."""
    global _cooldown_last_cleanup
    if guild_id is None or user_id is None:
        return None
    key = (guild_id, user_id)
    now = perf_counter()
    # Periodically evict stale entries to prevent unbounded growth
    if now - _cooldown_last_cleanup > _COOLDOWN_CLEANUP_INTERVAL:
        expired = [k for k, t in _cooldowns.items() if now - t > COOLDOWN_SECONDS * 10]
        for k in expired:
            del _cooldowns[k]
        _cooldown_last_cleanup = now
    last = _cooldowns.get(key)
    if last is not None:
        elapsed = now - last
        if elapsed < COOLDOWN_SECONDS:
            return COOLDOWN_SECONDS - elapsed
    _cooldowns[key] = now
    return None


async def _track_for_feedback(
    guild_id: int | None,
    msg: discord.Message | None,
    command: str,
    *,
    add_reactions: bool = True,
) -> None:
    """Register a result message for 👍/👎 feedback and seed the reactions.

    Shared by /summarize (cache + live paths) and /ask so feedback tracking is
    consistent across commands (#71). ``msg`` is only non-None when the send used
    ``wait=True``; otherwise tracking is skipped (no message id to key on).
    """
    if guild_id is None or msg is None:
        return
    guild_tracking = _tracked_messages.setdefault(guild_id, {})
    guild_tracking[msg.id] = command
    if len(guild_tracking) > _MAX_TRACKED_PER_GUILD:
        oldest = sorted(guild_tracking)[: len(guild_tracking) - _MAX_TRACKED_PER_GUILD]
        for k in oldest:
            del guild_tracking[k]
    if add_reactions:
        try:
            await msg.add_reaction(THUMBS_UP)
            await msg.add_reaction(THUMBS_DOWN)
        except discord.HTTPException:
            pass


def _make_error_embed(exc: Exception) -> discord.Embed:
    if isinstance(exc, UserFacingError):
        description = str(exc)
    else:
        description = "예기치 않은 오류가 발생했습니다. 잠시 후 다시 시도해주세요."
        logger.debug("Suppressed error detail from user: %s", exc)
    return discord.Embed(
        title="오류",
        description=description,
        color=discord.Color.red(),
    )


async def _send_error_embed(interaction: discord.Interaction, exc: Exception) -> None:
    embed = _make_error_embed(exc)
    if interaction.response.is_done():
        await interaction.followup.send(embed=embed, ephemeral=True)
    else:
        await interaction.response.send_message(embed=embed, ephemeral=True)


def _split_discord_text(text: str, *, max_chars: int = MAX_DISCORD_MESSAGE_CHARS) -> list[str]:
    """Split a long bot response into Discord-safe chunks.

    Avoids breaking inside code blocks (``` fences) by closing/reopening them.
    """
    text = text.strip() or "(empty response)"
    chunks: list[str] = []
    current = ""
    in_code_block = False
    code_lang = ""

    for line in text.splitlines():
        if line.startswith("```"):
            if not in_code_block:
                in_code_block = True
                code_lang = line[3:].strip()
            else:
                in_code_block = False
                code_lang = ""

        if len(line) > max_chars:
            if in_code_block:
                # Flush any buffered content first, closing the fence — but drop
                # a buffer that is only the bare opening fence (no content yet)
                # to avoid emitting an empty code block.
                if current and current.rstrip() != f"```{code_lang}":
                    chunks.append((current + "\n```").rstrip())
                current = ""
                # Wrap each fragment in its own fence so the code block stays
                # valid markdown across the split (#80). Reserve room for fences.
                fence_open = f"```{code_lang}\n"
                fence_close = "\n```"
                budget = max(1, max_chars - len(fence_open) - len(fence_close))
                starts = list(range(0, len(line), budget))
                for idx, start in enumerate(starts):
                    fragment = line[start : start + budget]
                    if idx < len(starts) - 1:
                        chunks.append(f"{fence_open}{fragment}{fence_close}")
                    else:
                        # Leave the final fragment's fence open so the source's
                        # own closing ``` (or the end-of-text flush) closes it
                        # exactly once — no stray lone fence.
                        current = f"{fence_open}{fragment}"
            else:
                if current:
                    chunks.append(current.rstrip())
                    current = ""
                for start in range(0, len(line), max_chars):
                    chunks.append(line[start : start + max_chars])
            continue

        candidate = f"{current}\n{line}" if current else line
        if len(candidate) > max_chars:
            if in_code_block:
                current += "\n```"
            chunks.append(current.rstrip())
            current = (f"```{code_lang}\n{line}" if in_code_block else line)
        else:
            current = candidate

    if current:
        if in_code_block:
            current += "\n```"
        chunks.append(current.rstrip())
    return chunks


async def _send_interaction_chunks(
    interaction: discord.Interaction,
    text: str,
    *,
    ephemeral: bool = False,
) -> None:
    chunks = _split_discord_text(text)
    first, *rest = chunks
    if interaction.response.is_done():
        await interaction.followup.send(first, ephemeral=ephemeral)
    else:
        await interaction.response.send_message(first, ephemeral=ephemeral)
    for chunk in rest:
        await interaction.followup.send(chunk, ephemeral=ephemeral)


async def _send_channel_chunks(channel: discord.abc.Messageable, text: str) -> None:
    chunks = _split_discord_text(text)
    for i, chunk in enumerate(chunks):
        await channel.send(chunk)
        if i < len(chunks) - 1:
            await asyncio.sleep(0.5)  # avoid Discord rate limit on bulk sends


def _effective_limit(limit: int | None, default: int) -> int:
    if limit is None:
        return default
    return max(1, min(int(limit), 200))


def _ids_from_interaction(
    interaction: discord.Interaction,
) -> tuple[int | None, int | None, int | None]:
    # #46: 명령 실행 컨텍스트에 interaction.id 를 correlation id 로 바인딩한다.
    # 거의 모든 슬래시 명령이 진입부에서 이 헬퍼를 호출하므로 자연스러운 바인딩
    # 지점이 된다. 이후 같은 컨텍스트의 로그에는 cid 가 따라붙는다.
    set_correlation_id(getattr(interaction, "id", None))
    guild_id = interaction.guild.id if interaction.guild else None
    channel_id = interaction.channel.id if interaction.channel else None  # type: ignore[union-attr]
    user_id = interaction.user.id if interaction.user else None
    return guild_id, channel_id, user_id


def _has_config_permission(interaction: discord.Interaction, admin_role_id: int | None) -> bool:
    permissions = getattr(interaction.user, "guild_permissions", None)
    if permissions and (permissions.administrator or permissions.manage_guild):
        return True
    if admin_role_id is not None:
        roles = getattr(interaction.user, "roles", [])
        return any(getattr(role, "id", None) == admin_role_id for role in roles)
    return False


def _get_llm(config: GuildConfig, settings: AppSettings) -> BaseLLMClient:
    """Return the correct LLM client for the guild's provider setting."""
    if config.provider == LLMProvider.OPENAI:
        if not config.api_key_encrypted:
            raise UserFacingError(
                "OpenAI API 키가 설정되지 않았습니다. `/settings` → 📦 모델 관리 → 🔑 API 키 등록"
            )
        try:
            api_key = decrypt_api_key(config.api_key_encrypted, settings.secret_key)
        except CryptoError as exc:
            raise UserFacingError(f"API 키 복호화 실패: {exc}") from exc
        return OpenAIClient(
            api_key=api_key,
            default_model=config.model,
            timeout_seconds=settings.ollama_timeout_seconds,
        )

    if config.provider == LLMProvider.ANTHROPIC:
        if not config.api_key_encrypted:
            raise UserFacingError(
                "Anthropic API 키가 설정되지 않았습니다. `/settings` → 📦 모델 관리 → 🔑 API 키 등록"
            )
        try:
            api_key = decrypt_api_key(config.api_key_encrypted, settings.secret_key)
        except CryptoError as exc:
            raise UserFacingError(f"API 키 복호화 실패: {exc}") from exc
        return AnthropicClient(
            api_key=api_key,
            default_model=config.model,
            timeout_seconds=settings.ollama_timeout_seconds,
        )

    return OllamaClient(
        base_url=settings.ollama_base_url,
        default_model=config.model,
        timeout_seconds=settings.ollama_timeout_seconds,
        keep_alive=settings.ollama_keep_alive,
        temperature=settings.ollama_temperature,
        num_ctx=settings.ollama_num_ctx,
    )


async def _collect_transcript(
    channel: Any,
    *,
    before: datetime,
    limit: int,
    max_context_chars: int,
    after: datetime | None = None,
) -> str:
    if channel is None or not hasattr(channel, "history"):
        raise UserFacingError("이 명령은 메시지 기록을 읽을 수 있는 채널에서만 사용할 수 있어요.")
    messages = []
    try:
        kwargs: dict[str, Any] = {"limit": limit, "before": before}
        if after is not None:
            kwargs["after"] = after
        async for message in channel.history(**kwargs):
            messages.append(from_discord_message(message))
    except discord.Forbidden as exc:
        raise UserFacingError("봇에 Read Message History 권한이 없어 최근 대화를 읽을 수 없어요.") from exc
    except discord.HTTPException as exc:
        raise UserFacingError(f"Discord 메시지 기록 조회에 실패했어요: {exc}") from exc
    messages.reverse()
    return build_transcript(messages, max_chars=max_context_chars)


async def _record_usage(
    store: ConfigStore,
    *,
    guild_id: int | None,
    channel_id: int | None,
    user_id: int | None,
    command: str,
    status: str,
    started_at: float,
    error: str | None = None,
) -> None:
    latency_ms = int((perf_counter() - started_at) * 1000)
    if latency_ms > _SLOW_RESPONSE_THRESHOLD_MS:
        # cid 는 CorrelationIdFilter 가 레코드에 주입하지만, 메시지에도 직접 실어
        # 필터 미구성 환경(테스트 등)에서도 추적 가능하게 한다 (#46).
        logger.warning(
            "느린 응답 감지: %s %dms (cid=%s)", command, latency_ms, get_correlation_id()
        )
    await store.log_usage(
        UsageLog(
            guild_id=guild_id,
            channel_id=channel_id,
            user_id=user_id,
            command=command,
            status=status,
            latency_ms=latency_ms,
            error=error,
        )
    )


# --- #1/#2 리마인더 payload 직렬화 ---
# DB 에는 payload 를 JSON 문자열로 저장해, 표시 텍스트/종류/반복여부를 함께 담는다.
# 과거(비-JSON) payload 도 안전하게 평문 메시지로 취급한다(백워드 호환).
_REMIND_KIND_SUMMARY = "summary"
_REMIND_KIND_TEXT = "text"


def _encode_remind_payload(text: str, *, kind: str, repeat: str | None = None) -> str:
    """리마인더 payload 를 JSON 문자열로 직렬화한다 (#1/#2)."""
    data: dict[str, Any] = {"v": 1, "kind": kind, "text": text}
    if repeat:
        data["repeat"] = repeat
    return json.dumps(data, ensure_ascii=False)


def _decode_remind_payload(payload: str) -> dict[str, Any]:
    """payload 를 디코딩한다. 비-JSON(레거시)은 평문 텍스트로 취급한다 (#1)."""
    try:
        data = json.loads(payload)
        if isinstance(data, dict) and "text" in data:
            return {
                "kind": str(data.get("kind", _REMIND_KIND_TEXT)),
                "text": str(data["text"]),
                "repeat": data.get("repeat"),
            }
    except (json.JSONDecodeError, TypeError):
        pass
    return {"kind": _REMIND_KIND_TEXT, "text": payload, "repeat": None}


def create_bot(settings: AppSettings) -> commands.Bot:
    """Create a configured discord.py bot instance."""

    intents = discord.Intents.default()
    intents.guilds = True
    intents.messages = True
    intents.message_content = True

    store = ConfigStore(
        settings.database_url,
        default_model=settings.ollama_model,
        default_summary_limit=settings.default_summary_limit,
        default_language=settings.default_language,
    )
    ollama_manager = OllamaManager(settings.ollama_base_url)
    view_ctx = ViewCtx(store=store, ollama_manager=ollama_manager, secret_key=settings.secret_key)

    class AssistantBot(commands.Bot):
        async def setup_hook(self) -> None:
            await store.initialize()
            if settings.auto_sync_commands:
                synced = await self.tree.sync()
                logger.info("Synced %d application command(s).", len(synced))

    bot = AssistantBot(command_prefix="!", intents=intents)

    # ------------------------------------------------------------------
    # Reminders — 영속 예약 전송 (#1/#2/#3)
    # ------------------------------------------------------------------
    #
    # storage 계층(add_reminder/list_due/list_by_user/delete_reminder/mark_sent)을
    # 그대로 재사용한다. 봇 재시작에도 미발송 reminder 가 살아남도록 on_ready 에서
    # 미발송 항목을 다시 예약한다.

    async def _deliver_reminder(reminder: Reminder) -> None:
        """단일 reminder 를 DM 으로 전송하고 발송 완료 표시한다 (#1)."""
        decoded = _decode_remind_payload(reminder.payload)
        text = decoded["text"]
        if decoded["kind"] == _REMIND_KIND_SUMMARY:
            body = f"⏰ 알림: 예약했던 요약 결과입니다.\n\n{text[:1800]}"
        else:
            body = f"⏰ 알림: {text[:1900]}"
        try:
            user = bot.get_user(reminder.user_id) or await bot.fetch_user(reminder.user_id)
            await user.send(body)
        except discord.Forbidden:
            # DM 차단 등으로 실패해도 무한 재시도하지 않도록 발송 완료로 표시한다.
            logger.info("리마인더 DM 전송 실패(차단): user=%s id=%s", reminder.user_id, reminder.id)
        except discord.HTTPException as exc:
            logger.warning("리마인더 DM 전송 실패: id=%s %s", reminder.id, exc)
        if reminder.id is not None:
            await store.mark_sent(reminder.id)

    async def _schedule_reminder(reminder: Reminder) -> None:
        """due_at 까지 대기한 뒤 reminder 를 전송한다 (#1).

        due_at 은 ISO8601(UTC 권장) 문자열이다. 이미 지났으면 즉시 전송한다.
        """
        try:
            due = datetime.fromisoformat(reminder.due_at)
        except ValueError:
            logger.warning("리마인더 due_at 파싱 실패: id=%s %r", reminder.id, reminder.due_at)
            due = datetime.now(timezone.utc)
        if due.tzinfo is None:
            due = due.replace(tzinfo=timezone.utc)
        delay = (due - datetime.now(timezone.utc)).total_seconds()
        if delay > 0:
            await asyncio.sleep(delay)
        await _deliver_reminder(reminder)

    async def _reschedule_pending_reminders() -> None:
        """봇 시작 시 미발송 reminder 를 모두 다시 예약한다 (#1).

        이미 만기인 항목은 즉시 전송되며, 미래 항목은 due_at 까지 대기한다.
        """
        try:
            # 충분히 먼 미래 시각을 넘겨 '미발송' 전부를 가져온 뒤 각각 재예약한다.
            far_future = (datetime.now(timezone.utc) + _MAX_REMIND_DELAY).isoformat()
            pending = await store.list_due(now=far_future)
        except Exception as exc:  # pragma: no cover — 기동 경로 방어
            logger.exception("미발송 리마인더 조회 실패: %s", exc)
            return
        for reminder in pending:
            _track_task(_schedule_reminder(reminder), name=f"reminder-{reminder.id}")
        if pending:
            logger.info("미발송 리마인더 %d건을 재예약했습니다.", len(pending))

    # ------------------------------------------------------------------
    # /settings — interactive admin panel
    # ------------------------------------------------------------------

    @bot.tree.command(name="settings", description="봇 설정 패널을 엽니다. (관리자 전용)")
    async def settings_command(interaction: discord.Interaction) -> None:
        if interaction.guild is None:
            await interaction.response.send_message("⚠️ 이 명령은 서버 안에서만 사용할 수 있어요.", ephemeral=True)
            return
        config = await store.get_guild_config(interaction.guild.id)
        if not _has_config_permission(interaction, config.admin_role_id):
            await interaction.response.send_message(
                "⚠️ 이 명령을 사용하려면 Manage Server 또는 관리자 권한이 필요해요.", ephemeral=True
            )
            return
        embed = settings_embed(config, interaction.guild.name)
        view = SettingsView(ctx=view_ctx, guild_id=interaction.guild.id, provider=config.provider)
        await interaction.response.send_message(embed=embed, view=view, ephemeral=True)

    # ------------------------------------------------------------------
    # /summarize
    # ------------------------------------------------------------------

    async def _deliver_summary_to_thread(
        interaction: discord.Interaction, title: str, body: str
    ) -> bool:
        """요약 결과를 채널에 새 스레드를 만들어 게시한다 (#5).

        create_thread 권한이 없거나 스레드를 만들 수 없는 채널이면 False 를
        반환해 호출 측이 일반(채널) 전송으로 폴백하게 한다.
        """
        channel = interaction.channel
        # 일반 텍스트 채널에서만 새 스레드를 만든다. 스레드/포럼/DM 등은 폴백한다.
        if not isinstance(channel, discord.TextChannel):
            return False
        # 스레드 이름은 100자 제한 + 개행 불가. 안전하게 잘라 정리한다.
        thread_name = title.replace("\n", " ").strip()[:90] or "요약"
        try:
            thread = await channel.create_thread(
                name=thread_name,
                type=discord.ChannelType.public_thread,
            )
        except discord.Forbidden:
            return False
        except (discord.HTTPException, TypeError):
            # 권한이 있어도 일시적 실패가 있을 수 있어 폴백한다.
            return False
        await _send_channel_chunks(thread, body)
        return True

    async def run_summarize(
        interaction: discord.Interaction,
        limit: int | None,
        since: str | None = None,
        thread: bool = False,
    ) -> None:
        started = perf_counter()
        guild_id, channel_id, user_id = _ids_from_interaction(interaction)
        remaining = _check_cooldown(guild_id, user_id)
        if remaining is not None:
            await interaction.response.send_message(
                f"⏳ {remaining:.0f}초 후에 다시 시도해주세요.", ephemeral=True
            )
            return
        await interaction.response.defer(thinking=True)
        try:
            config = await store.get_guild_config(guild_id or 0)
            # Role restriction check (#49)
            if not _has_allowed_role(interaction, config.allowed_role_id):
                raise UserFacingError("이 명령을 사용할 권한이 없어요. 서버 관리자에게 문의하세요.")
            message_limit = _effective_limit(limit, config.summary_limit)

            # Parse `since` parameter (#31)
            since_dt: datetime | None = None
            if since:
                since_dt = _parse_since(since)

            # Language auto-detect support (#44)
            effective_language = config.language

            # Skip cache when since or limit is explicitly specified
            use_cache = (limit is None and since is None)
            cache_key = f"{guild_id}:{channel_id}"
            cached = summarize_cache.get(cache_key) if use_cache else None
            if cached is not None:
                if user_id is not None:
                    _last_summaries[user_id] = (cached, guild_id)
                    if len(_last_summaries) > _MAX_LAST_SUMMARIES:
                        del _last_summaries[next(iter(_last_summaries))]
                header = f"**최근 {message_limit}개 메시지 요약** *(캐시)*\n"
                # #5: thread=True 면 새 스레드에 게시하고, 권한이 없으면 폴백한다.
                if thread and await _deliver_summary_to_thread(
                    interaction, f"요약: 최근 {message_limit}개 메시지", header + cached
                ):
                    await interaction.followup.send("🧵 새 스레드에 요약을 게시했어요.", ephemeral=True)
                    await _record_usage(
                        store, guild_id=guild_id, channel_id=channel_id, user_id=user_id,
                        command="summarize", status="ok", started_at=started,
                    )
                    return
                if thread:
                    await interaction.followup.send(
                        "⚠️ 스레드를 만들 권한이 없어 여기에 표시할게요.", ephemeral=True
                    )
                first, *rest = _split_discord_text(header + cached)
                msg = await interaction.followup.send(first, wait=True)
                for chunk in rest:
                    await interaction.followup.send(chunk)
                # Track cached results too, for parity with the live path (#71)
                await _track_for_feedback(guild_id, msg, "summarize")
                await _record_usage(
                    store, guild_id=guild_id, channel_id=channel_id, user_id=user_id,
                    command="summarize", status="ok", started_at=started,
                )
                return

            transcript = await _collect_transcript(
                interaction.channel,
                before=interaction.created_at,
                limit=message_limit,
                max_context_chars=settings.max_context_chars,
                after=since_dt,
            )
            if not transcript:
                raise UserFacingError("요약할 메시지가 없어요. 채널에 대화가 있어야 합니다.")

            # Auto-detect language if set to 'auto' (#44)
            if effective_language == "auto":
                effective_language = detect_language_from_transcript(transcript)

            # Use custom prompt if set (#40)
            if config.custom_summarize_prompt:
                prompt = config.custom_summarize_prompt.replace("{transcript}", transcript)
            else:
                prompt = build_summarize_prompt(transcript, language=effective_language)

            llm = _get_llm(config, settings)
            answer = await llm.generate(prompt, model=config.model)

            # Cache the result for default queries
            if use_cache:
                summarize_cache.set(cache_key, answer)

            # Store last summary for /remind (#32)
            if user_id is not None:
                _last_summaries[user_id] = (answer, guild_id)
                if len(_last_summaries) > _MAX_LAST_SUMMARIES:
                    del _last_summaries[next(iter(_last_summaries))]

            since_label = f" (since: {since})" if since else ""
            header = f"**최근 {message_limit}개 메시지 요약{since_label}**\n"
            # #5: thread=True 면 새 스레드에 게시하고, 권한이 없으면 폴백한다.
            if thread and await _deliver_summary_to_thread(
                interaction, f"요약: 최근 {message_limit}개 메시지", header + answer
            ):
                await interaction.followup.send("🧵 새 스레드에 요약을 게시했어요.", ephemeral=True)
                await _record_usage(
                    store, guild_id=guild_id, channel_id=channel_id, user_id=user_id,
                    command="summarize", status="ok", started_at=started,
                )
                return
            if thread:
                await interaction.followup.send(
                    "⚠️ 스레드를 만들 권한이 없어 여기에 표시할게요.", ephemeral=True
                )
            first, *rest = _split_discord_text(header + answer)
            msg = await interaction.followup.send(first, wait=True)
            for chunk in rest:
                await interaction.followup.send(chunk)
            # Track this message for reaction feedback (consistent with /ask)
            await _track_for_feedback(guild_id, msg, "summarize")
            await _record_usage(
                store, guild_id=guild_id, channel_id=channel_id, user_id=user_id,
                command="summarize", status="ok", started_at=started,
            )
        except (UserFacingError, LLMError) as exc:
            await _send_interaction_chunks(interaction, f"⚠️ {exc}", ephemeral=True)
            await _record_usage(
                store, guild_id=guild_id, channel_id=channel_id, user_id=user_id,
                command="summarize", status="error", started_at=started, error=str(exc),
            )

    @bot.tree.command(name="summarize", description="최근 채널 대화를 로컬 LLM으로 요약합니다.")
    @app_commands.describe(
        limit="최근 몇 개 메시지를 읽을지 지정합니다. 기본값은 서버 설정입니다.",
        since="시간 기반 필터. 예: 1h, 30m, 2d",
        thread="True면 요약 결과를 채널에 새 스레드를 만들어 게시합니다.",
    )
    @app_commands.autocomplete(since=_since_autocomplete)
    async def summarize_command(
        interaction: discord.Interaction,
        limit: int | None = None,
        since: str | None = None,
        thread: bool = False,
    ) -> None:
        await run_summarize(interaction, limit, since, thread)

    # ------------------------------------------------------------------
    # /ask
    # ------------------------------------------------------------------

    async def run_ask(
        interaction: discord.Interaction,
        question: str,
        limit: int | None,
        _transcript_override: str | None = None,
    ) -> None:
        started = perf_counter()
        guild_id, channel_id, user_id = _ids_from_interaction(interaction)
        remaining = _check_cooldown(guild_id, user_id)
        if remaining is not None:
            if not interaction.response.is_done():
                await interaction.response.send_message(
                    f"⏳ {remaining:.0f}초 후에 다시 시도해주세요.", ephemeral=True
                )
            return
        if not interaction.response.is_done():
            await interaction.response.defer(thinking=True)
        try:
            config = await store.get_guild_config(guild_id or 0)
            # Role restriction check (#49)
            if not _has_allowed_role(interaction, config.allowed_role_id):
                raise UserFacingError("이 명령을 사용할 권한이 없어요.")
            message_limit = _effective_limit(limit, config.summary_limit)

            if _transcript_override is not None:
                transcript = _transcript_override
            else:
                transcript = await _collect_transcript(
                    interaction.channel,
                    before=interaction.created_at,
                    limit=message_limit,
                    max_context_chars=settings.max_context_chars,
                )
            if not transcript:
                raise UserFacingError("질문에 참고할 최근 메시지가 없어요.")

            effective_language = config.language
            if effective_language == "auto":
                effective_language = detect_language_from_transcript(transcript)

            # Use custom prompt if set (#40)
            if config.custom_ask_prompt:
                prompt = (
                    config.custom_ask_prompt
                    .replace("{transcript}", transcript)
                    .replace("{question}", question)
                )
            else:
                prompt = build_ask_prompt(transcript, question, language=effective_language)

            llm = _get_llm(config, settings)
            answer = await llm.generate(prompt, model=config.model)

            # Follow-up view (#36) — capture transcript for follow-up
            transcript_snapshot = transcript

            async def _handle_follow_up(follow_interaction: discord.Interaction, follow_q: str) -> None:
                await follow_interaction.response.defer(thinking=True)
                await run_ask(follow_interaction, follow_q, limit, _transcript_override=transcript_snapshot)

            follow_view = FollowUpView(on_follow_up=_handle_follow_up)

            chunks = _split_discord_text(f"**질문:** {question}\n\n{answer}")
            first, *rest = chunks
            # run_ask always defers before reaching here (both call sites defer),
            # so the response is done — send via followup (#23: removed dead else).
            msg = await interaction.followup.send(first, view=follow_view, wait=True)
            for chunk in rest:
                await interaction.followup.send(chunk)

            # Track this message for reaction feedback (#42, #71)
            await _track_for_feedback(guild_id, msg, "ask")

            await _record_usage(
                store, guild_id=guild_id, channel_id=channel_id, user_id=user_id,
                command="ask", status="ok", started_at=started,
            )
        except (UserFacingError, LLMError) as exc:
            await _send_interaction_chunks(interaction, f"⚠️ {exc}", ephemeral=True)
            await _record_usage(
                store, guild_id=guild_id, channel_id=channel_id, user_id=user_id,
                command="ask", status="error", started_at=started, error=str(exc),
            )

    @bot.tree.command(name="ask", description="최근 채널 대화 맥락으로 질문에 답합니다.")
    @app_commands.describe(
        question="최근 대화에 대해 물어볼 질문입니다.",
        limit="최근 몇 개 메시지를 읽을지 지정합니다. 기본값은 서버 설정입니다.",
    )
    async def ask_command(
        interaction: discord.Interaction,
        question: str,
        limit: int | None = None,
    ) -> None:
        await run_ask(interaction, question, limit)

    # ------------------------------------------------------------------
    # /translate
    # ------------------------------------------------------------------

    @bot.tree.command(name="translate", description="선택 기능: 짧은 텍스트를 지정 언어로 번역합니다.")
    @app_commands.describe(text="번역할 텍스트", target_language="목표 언어입니다. 예: ko, en, ja")
    @app_commands.autocomplete(target_language=_language_autocomplete)
    async def translate_command(
        interaction: discord.Interaction,
        text: str,
        target_language: str = "ko",
    ) -> None:
        started = perf_counter()
        guild_id, channel_id, user_id = _ids_from_interaction(interaction)
        remaining = _check_cooldown(guild_id, user_id)
        if remaining is not None:
            await interaction.response.send_message(
                f"⏳ {remaining:.0f}초 후에 다시 시도해주세요.", ephemeral=True
            )
            return
        await interaction.response.defer(thinking=True, ephemeral=True)
        try:
            # Translation cache check (#38)
            cached_translation = get_translation(text, target_language)
            if cached_translation is not None:
                embed = discord.Embed(color=discord.Color.from_str("#5865F2"))
                embed.add_field(name="원문", value=_truncate(text), inline=False)
                embed.add_field(name=f"번역 ({target_language}) *(캐시)*", value=_truncate(cached_translation), inline=False)
                await interaction.followup.send(embed=embed, ephemeral=True)
                await _record_usage(
                    store, guild_id=guild_id, channel_id=channel_id, user_id=user_id,
                    command="translate", status="ok", started_at=started,
                )
                return
            config = await store.get_guild_config(guild_id or 0)
            prompt = build_translate_prompt(text, target_language=target_language)
            llm = _get_llm(config, settings)
            answer = await llm.generate(prompt, model=config.model)
            # Cache the result (#38)
            set_translation(text, target_language, answer)
            embed = discord.Embed(color=discord.Color.from_str("#5865F2"))
            embed.add_field(name="원문", value=_truncate(text), inline=False)
            embed.add_field(name=f"번역 ({target_language})", value=_truncate(answer), inline=False)
            await interaction.followup.send(embed=embed, ephemeral=True)
            await _record_usage(
                store, guild_id=guild_id, channel_id=channel_id, user_id=user_id,
                command="translate", status="ok", started_at=started,
            )
        except (UserFacingError, LLMError) as exc:
            await _send_interaction_chunks(interaction, f"⚠️ {exc}", ephemeral=True)
            await _record_usage(
                store, guild_id=guild_id, channel_id=channel_id, user_id=user_id,
                command="translate", status="error", started_at=started, error=str(exc),
            )

    # ------------------------------------------------------------------
    # /chat — free-form AI conversation without channel context
    # ------------------------------------------------------------------

    @bot.tree.command(name="chat", description="채널 맥락 없이 AI에게 자유롭게 질문합니다.")
    @app_commands.describe(
        message="AI에게 보낼 메시지입니다.",
        public="True로 설정하면 채널에 공개 메시지로 표시됩니다. 기본값은 비공개입니다.",
    )
    async def chat_command(
        interaction: discord.Interaction,
        message: str,
        public: bool = False,
    ) -> None:
        started = perf_counter()
        guild_id, channel_id, user_id = _ids_from_interaction(interaction)
        remaining = _check_cooldown(guild_id, user_id)
        if remaining is not None:
            await interaction.response.send_message(
                f"⏳ {remaining:.0f}초 후에 다시 시도해주세요.", ephemeral=True
            )
            return
        ephemeral = not public
        await interaction.response.defer(thinking=True, ephemeral=ephemeral)
        try:
            config = await store.get_guild_config(guild_id or 0)
            history: list[dict[str, str]] = []
            if user_id is not None:
                history = await store.get_chat_history(
                    user_id, guild_id=guild_id, channel_id=channel_id, limit=10
                )
            if history:
                prompt = build_chat_with_history_prompt(message, history, language=config.language)
            else:
                # Apply persona if set (#37)
                prompt = build_chat_prompt(message, language=config.language, persona=config.persona)
            llm = _get_llm(config, settings)
            answer = await llm.generate(prompt, model=config.model)
            if len(answer) > MAX_DISCORD_MESSAGE_CHARS:
                from .ui import LongResponseView as _LRV
                view = _LRV(full_text=answer)
                preview = answer[:MAX_DISCORD_MESSAGE_CHARS]
                await interaction.followup.send(preview, view=view, ephemeral=ephemeral)
            else:
                await _send_interaction_chunks(interaction, answer, ephemeral=ephemeral)
            if user_id is not None:
                await store.save_chat_message(
                    user_id, "user", message, guild_id=guild_id, channel_id=channel_id
                )
                await store.save_chat_message(
                    user_id, "assistant", answer, guild_id=guild_id, channel_id=channel_id
                )
            await _record_usage(
                store, guild_id=guild_id, channel_id=channel_id, user_id=user_id,
                command="chat", status="ok", started_at=started,
            )
        except (UserFacingError, LLMError) as exc:
            await _send_interaction_chunks(interaction, f"⚠️ {exc}", ephemeral=True)
            await _record_usage(
                store, guild_id=guild_id, channel_id=channel_id, user_id=user_id,
                command="chat", status="error", started_at=started, error=str(exc),
            )

    # ------------------------------------------------------------------
    # /help — command reference
    # ------------------------------------------------------------------

    @bot.tree.command(name="help", description="봇 명령어 사용법을 안내합니다.")
    async def help_command(interaction: discord.Interaction) -> None:
        embed = discord.Embed(
            title="명령어 안내",
            color=discord.Color.from_str("#5865F2"),
        )
        embed.add_field(
            name="/summarize",
            value=(
                "채널의 최근 대화를 AI가 요약합니다.\n"
                "```\n"
                "/summarize\n"
                "/summarize limit:100\n"
                "```"
            ),
            inline=False,
        )
        embed.add_field(
            name="/ask",
            value=(
                "채널의 최근 대화에서 근거를 찾아 질문에 답합니다.\n"
                "대화 내용에 없는 내용은 답하지 않습니다.\n"
                "```\n"
                "/ask question:오늘 회의 결론이 뭐야?\n"
                "/ask question:누가 담당자야? limit:100\n"
                "```"
            ),
            inline=False,
        )
        embed.add_field(
            name="/chat",
            value=(
                "채널 맥락 없이 AI에게 자유롭게 질문합니다.\n"
                "```\n"
                "/chat message:파이썬 리스트 컴프리헨션 설명해줘\n"
                "/chat message:영어 이메일 초안 작성해줘\n"
                "```"
            ),
            inline=False,
        )
        embed.add_field(
            name="/translate",
            value=(
                "텍스트를 지정 언어로 번역합니다.\n"
                "```\n"
                "/translate text:Hello target_language:ko\n"
                "```"
            ),
            inline=False,
        )
        embed.add_field(
            name="@ 멘션",
            value=(
                "봇을 멘션하면 채널 대화를 요약합니다.\n"
                "멘션 뒤에 질문을 쓰면 `/ask` 처럼 동작합니다.\n"
                "```\n"
                "@ai-assistant\n"
                "@ai-assistant 어제 무슨 얘기 했어?\n"
                "```"
            ),
            inline=False,
        )
        embed.add_field(
            name="/settings  (관리자 전용)",
            value="AI 제공자, 모델, 언어, 요약 범위 등 서버 설정을 변경합니다.",
            inline=False,
        )
        embed.set_footer(text="버튼을 눌러 섹션별 상세 안내를 볼 수 있습니다.")
        dashboard_url = os.getenv("DASHBOARD_URL", "").strip()
        view = HelpView()
        if dashboard_url:
            view.add_item(
                discord.ui.Button(
                    label="대시보드 열기",
                    url=dashboard_url,
                    style=discord.ButtonStyle.link,
                    emoji="🖥️",
                    row=1,
                )
            )
        await interaction.response.send_message(embed=embed, view=view, ephemeral=True)

    # ------------------------------------------------------------------
    # /config — legacy CLI-style setters (kept for backward compat)
    # ------------------------------------------------------------------

    config_group = app_commands.Group(name="config", description="서버별 봇 설정을 관리합니다.")

    async def require_guild_admin(interaction: discord.Interaction) -> int:
        if interaction.guild is None:
            raise UserFacingError("/config 명령은 서버 안에서만 사용할 수 있어요.")
        config = await store.get_guild_config(interaction.guild.id)
        if not _has_config_permission(interaction, config.admin_role_id):
            raise UserFacingError("이 설정을 바꾸려면 Manage Server 또는 관리자 권한이 필요해요.")
        return interaction.guild.id

    async def _audit_config_change(
        interaction: discord.Interaction,
        guild_id: int,
        action: str,
        before: Any,
        after: Any,
    ) -> None:
        """설정 변경을 감사 로그에 기록한다 (#39).

        before/after 는 문자열로 정규화해 저장한다. 감사 로깅 실패가 명령 자체를
        막아서는 안 되므로 예외는 삼키고 경고만 남긴다.
        """
        user_id = interaction.user.id if interaction.user else None
        try:
            await store.record_audit(
                guild_id=guild_id,
                user_id=user_id,
                action=action,
                before=None if before is None else str(before),
                after=None if after is None else str(after),
            )
        except Exception as exc:  # pragma: no cover — 감사 로깅 방어
            logger.warning("감사 로그 기록 실패(action=%s): %s", action, exc)

    @config_group.command(name="model", description="서버 기본 Ollama 모델명을 저장합니다.")
    @app_commands.describe(model="예: llama3.1:8b, qwen2.5:7b, gemma2:9b")
    async def config_model(interaction: discord.Interaction, model: str) -> None:
        try:
            guild_id = await require_guild_admin(interaction)
            before = (await store.get_guild_config(guild_id)).model
            config = await store.set_model(guild_id, model)
            await _audit_config_change(interaction, guild_id, "set_model", before, config.model)
            await _send_interaction_chunks(
                interaction, f"✅ 기본 모델을 `{config.model}`로 저장했어요.", ephemeral=True,
            )
        except (UserFacingError, ValueError) as exc:
            await _send_interaction_chunks(interaction, f"⚠️ {exc}", ephemeral=True)

    @config_group.command(name="summary_limit", description="기본 메시지 요약 범위를 저장합니다.")
    @app_commands.describe(limit="1~200 사이의 메시지 개수")
    async def config_summary_limit(interaction: discord.Interaction, limit: int) -> None:
        try:
            guild_id = await require_guild_admin(interaction)
            before = (await store.get_guild_config(guild_id)).summary_limit
            config = await store.set_summary_limit(guild_id, limit)
            await _audit_config_change(
                interaction, guild_id, "set_summary_limit", before, config.summary_limit
            )
            await _send_interaction_chunks(
                interaction,
                f"✅ 기본 요약 범위를 최근 {config.summary_limit}개 메시지로 저장했어요.",
                ephemeral=True,
            )
        except (UserFacingError, ValueError) as exc:
            await _send_interaction_chunks(interaction, f"⚠️ {exc}", ephemeral=True)

    @config_group.command(name="language", description="기본 응답 언어를 저장합니다.")
    @app_commands.describe(language="예: ko, en, ja")
    @app_commands.autocomplete(language=_language_autocomplete)
    async def config_language(interaction: discord.Interaction, language: str) -> None:
        try:
            guild_id = await require_guild_admin(interaction)
            before = (await store.get_guild_config(guild_id)).language
            config = await store.set_language(guild_id, language)
            await _audit_config_change(
                interaction, guild_id, "set_language", before, config.language
            )
            await _send_interaction_chunks(
                interaction, f"✅ 기본 응답 언어를 `{config.language}`로 저장했어요.", ephemeral=True,
            )
        except (UserFacingError, ValueError) as exc:
            await _send_interaction_chunks(interaction, f"⚠️ {exc}", ephemeral=True)


    # ------------------------------------------------------------------
    # Phase 3 new commands
    # ------------------------------------------------------------------

    # --- #1/#2 /remind --- 영속화된 리마인더 (요약 결과 또는 임의 텍스트)
    @bot.tree.command(
        name="remind",
        description="지정한 시간 뒤 DM으로 알림을 보냅니다. (메시지 미지정 시 마지막 요약)",
    )
    @app_commands.describe(
        when="언제 보낼지. 예: 30m, 2h, 1d (단위 없으면 분). 최대 30일.",
        message="알림으로 받을 임의 텍스트. 비우면 마지막 /summarize 결과를 사용합니다.",
        repeat="(선택) 반복 표시용 라벨. 예: daily, weekly (실제 반복 없이 표시만)",
    )
    async def remind_command(
        interaction: discord.Interaction,
        when: str,
        message: str = "",
        repeat: str = "",
    ) -> None:
        user_id = interaction.user.id if interaction.user else None
        if user_id is None:
            await interaction.response.send_message(
                "⚠️ 사용자 정보를 확인할 수 없어요.", ephemeral=True
            )
            return
        try:
            delay = _parse_remind_delay(when)
        except UserFacingError as exc:
            await interaction.response.send_message(f"⚠️ {exc}", ephemeral=True)
            return

        # 메시지가 비어 있으면 마지막 요약 결과(_last_summaries)를 사용한다(#2 호환 경로).
        text = message.strip()
        if text:
            kind = _REMIND_KIND_TEXT
        else:
            cached = _last_summaries.get(user_id)
            if cached is None:
                await interaction.response.send_message(
                    "⚠️ 보낼 내용이 없어요. 메시지를 입력하거나 먼저 /summarize를 실행해 주세요.",
                    ephemeral=True,
                )
                return
            text, _ = cached
            kind = _REMIND_KIND_SUMMARY

        guild_id, channel_id, _ = _ids_from_interaction(interaction)
        due_at = (datetime.now(timezone.utc) + delay).isoformat()
        repeat_label = repeat.strip() or None
        payload = _encode_remind_payload(text, kind=kind, repeat=repeat_label)
        reminder_id = await store.add_reminder(user_id, guild_id, channel_id, due_at, payload)

        # 방금 저장한 행을 기준으로 예약한다(봇 재시작 시에도 on_ready 가 재예약).
        scheduled = Reminder(
            user_id=user_id,
            guild_id=guild_id,
            channel_id=channel_id,
            due_at=due_at,
            payload=payload,
            id=reminder_id,
        )
        _track_task(_schedule_reminder(scheduled), name=f"reminder-{reminder_id}")

        # 사람이 읽기 좋은 지연 표기.
        total_minutes = int(delay.total_seconds() // 60)
        if total_minutes >= 1440:
            when_label = f"{total_minutes // 1440}일"
        elif total_minutes >= 60:
            when_label = f"{total_minutes // 60}시간"
        else:
            when_label = f"{max(total_minutes, 1)}분"
        repeat_note = f" (반복: {repeat_label})" if repeat_label else ""
        await interaction.response.send_message(
            f"⏰ {when_label} 후에 DM으로 알림을 보내드릴게요!{repeat_note}", ephemeral=True
        )

    # --- #3 /reminders --- 본인 예약 목록 표시 + 취소
    @bot.tree.command(name="reminders", description="내 예약 알림 목록을 보고 취소합니다.")
    @app_commands.describe(cancel="취소할 알림의 ID. 비우면 목록만 표시합니다.")
    async def reminders_command(
        interaction: discord.Interaction, cancel: int | None = None
    ) -> None:
        user_id = interaction.user.id if interaction.user else None
        if user_id is None:
            await interaction.response.send_message(
                "⚠️ 사용자 정보를 확인할 수 없어요.", ephemeral=True
            )
            return

        # 취소 요청: 본인 소유 + 미발송 항목만 삭제 가능.
        if cancel is not None:
            mine = await store.list_by_user(user_id)
            owned = next((r for r in mine if r.id == cancel), None)
            if owned is None:
                await interaction.response.send_message(
                    "⚠️ 해당 ID의 예약 알림이 없거나 본인 것이 아니에요.", ephemeral=True
                )
                return
            await store.delete_reminder(cancel)
            await interaction.response.send_message(
                f"✅ 예약 알림 #{cancel}을(를) 취소했어요.", ephemeral=True
            )
            return

        reminders = await store.list_by_user(user_id)
        if not reminders:
            await interaction.response.send_message(
                "예약된 알림이 없어요. `/remind`로 새 알림을 만들 수 있어요.", ephemeral=True
            )
            return

        embed = discord.Embed(
            title="내 예약 알림",
            description="취소하려면 `/reminders cancel:<ID>` 를 사용하세요.",
            color=discord.Color.from_str("#5865F2"),
        )
        for r in reminders[:20]:
            decoded = _decode_remind_payload(r.payload)
            preview = decoded["text"].replace("\n", " ")[:80]
            kind_label = "요약" if decoded["kind"] == _REMIND_KIND_SUMMARY else "메시지"
            repeat_note = f" · 반복: {decoded['repeat']}" if decoded.get("repeat") else ""
            embed.add_field(
                name=f"#{r.id} · {kind_label}{repeat_note}",
                value=f"예정: {r.due_at}\n{preview or '(내용 없음)'}",
                inline=False,
            )
        await interaction.response.send_message(embed=embed, ephemeral=True)

    # --- #40 /forget-me --- 본인 데이터 전체 삭제 (GDPR)
    @bot.tree.command(name="forget-me", description="내 데이터를 모두 삭제합니다. (되돌릴 수 없음)")
    async def forget_me_command(interaction: discord.Interaction) -> None:
        maybe_user_id = interaction.user.id if interaction.user else None
        if maybe_user_id is None:
            await interaction.response.send_message(
                "⚠️ 사용자 정보를 확인할 수 없어요.", ephemeral=True
            )
            return
        user_id: int = maybe_user_id  # None 검사 후의 non-None 로컬(클로저 캡처용)

        # 확인 단계: 버튼으로 한 번 더 동의를 받은 뒤에만 삭제한다(#40).
        class _ForgetConfirmView(discord.ui.View):
            def __init__(self) -> None:
                super().__init__(timeout=60)
                self._owner_id = user_id

            async def interaction_check(self, inner: discord.Interaction) -> bool:
                # 명령을 실행한 본인만 버튼을 누를 수 있게 한다.
                if inner.user and inner.user.id == self._owner_id:
                    return True
                await inner.response.send_message(
                    "⚠️ 본인만 이 작업을 확인할 수 있어요.", ephemeral=True
                )
                return False

            @discord.ui.button(label="삭제 확인", style=discord.ButtonStyle.danger)
            async def confirm(
                self, btn_interaction: discord.Interaction, _button: discord.ui.Button
            ) -> None:
                deleted = await store.delete_user_data(user_id)
                total = sum(deleted.values())
                detail = ", ".join(f"{k}: {v}건" for k, v in deleted.items())
                # 인메모리 캐시(_last_summaries)에서도 흔적을 제거한다.
                _last_summaries.pop(user_id, None)
                for child in self.children:
                    if isinstance(child, discord.ui.Button):
                        child.disabled = True
                self.stop()
                await btn_interaction.response.edit_message(
                    content=f"✅ 데이터를 삭제했어요. (총 {total}건 — {detail})",
                    view=self,
                )

            @discord.ui.button(label="취소", style=discord.ButtonStyle.secondary)
            async def cancel(
                self, btn_interaction: discord.Interaction, _button: discord.ui.Button
            ) -> None:
                for child in self.children:
                    if isinstance(child, discord.ui.Button):
                        child.disabled = True
                self.stop()
                await btn_interaction.response.edit_message(
                    content="취소했어요. 데이터는 그대로 유지됩니다.", view=self
                )

        await interaction.response.send_message(
            "⚠️ 이 작업은 되돌릴 수 없어요. 당신의 채팅 기록, 피드백, 사용 기록, 예약 알림이 "
            "모두 삭제됩니다. 정말 삭제하시겠어요?",
            view=_ForgetConfirmView(),
            ephemeral=True,
        )

    # --- #34 /pin-summary --- pin the summarize result
    @bot.tree.command(name="pin-summary", description="요약을 실행하고 결과를 채널에 고정합니다.")
    @app_commands.describe(limit="최근 몇 개 메시지를 요약할지 지정합니다.")
    async def pin_summary_command(interaction: discord.Interaction, limit: int | None = None) -> None:
        if interaction.guild is None:
            await interaction.response.send_message("⚠️ 이 명령은 서버 안에서만 사용할 수 있어요.", ephemeral=True)
            return
        permissions = getattr(interaction.user, "guild_permissions", None)
        if not (permissions and (permissions.administrator or permissions.manage_messages)):
            await interaction.response.send_message(
                "⚠️ 메시지 관리 또는 관리자 권한이 필요해요.", ephemeral=True
            )
            return
        started = perf_counter()
        guild_id, channel_id, user_id = _ids_from_interaction(interaction)
        await interaction.response.defer(thinking=True)
        try:
            config = await store.get_guild_config(guild_id or 0)
            message_limit = _effective_limit(limit, config.summary_limit)
            transcript = await _collect_transcript(
                interaction.channel,
                before=interaction.created_at,
                limit=message_limit,
                max_context_chars=settings.max_context_chars,
            )
            if not transcript:
                raise UserFacingError("요약할 메시지가 없어요.")
            prompt = build_summarize_prompt(transcript, language=config.language)
            llm = _get_llm(config, settings)
            answer = await llm.generate(prompt, model=config.model)
            sent_msg = await interaction.followup.send(
                f"📌 **요약 (고정됨)**\n{answer}", wait=True
            )
            try:
                await sent_msg.pin()
                await interaction.followup.send("✅ 요약이 채널에 고정됐어요.", ephemeral=True)
            except discord.Forbidden:
                await interaction.followup.send("⚠️ 메시지를 고정할 권한이 없어요.", ephemeral=True)
            except discord.HTTPException as exc:
                await interaction.followup.send(f"⚠️ 고정 실패: {exc}", ephemeral=True)
            await _record_usage(
                store, guild_id=guild_id, channel_id=channel_id, user_id=user_id,
                command="pin_summary", status="ok", started_at=started,
            )
        except (UserFacingError, LLMError) as exc:
            await _send_interaction_chunks(interaction, f"⚠️ {exc}", ephemeral=True)
            await _record_usage(
                store, guild_id=guild_id, channel_id=channel_id, user_id=user_id,
                command="pin_summary", status="error", started_at=started, error=str(exc),
            )

    # --- #35 /summarize-channels --- multi-channel summary
    @bot.tree.command(name="summarize-channels", description="여러 채널을 선택해 통합 요약합니다.")
    async def summarize_channels_command(interaction: discord.Interaction) -> None:
        guild = interaction.guild
        if guild is None:
            await interaction.response.send_message("⚠️ 이 명령은 서버 안에서만 사용할 수 있어요.", ephemeral=True)
            return
        text_channels = [
            ch for ch in guild.text_channels
            if ch.permissions_for(guild.me).read_message_history
        ]
        if not text_channels:
            await interaction.response.send_message("⚠️ 읽기 가능한 텍스트 채널이 없어요.", ephemeral=True)
            return

        async def _on_confirm(confirm_interaction: discord.Interaction, channel_ids: list[str]) -> None:
            await confirm_interaction.response.defer(thinking=True)
            if not channel_ids:
                await confirm_interaction.followup.send("⚠️ 선택된 채널이 없습니다.", ephemeral=True)
                return
            started = perf_counter()
            guild_id, channel_id, user_id = _ids_from_interaction(confirm_interaction)
            try:
                config = await store.get_guild_config(guild_id or 0)
                message_limit = config.summary_limit
                # Build the LLM client once and reuse it across all channels (#35)
                llm = _get_llm(config, settings)

                async def _summarize_one(ch_id: str) -> tuple[str, str]:
                    ch = guild.get_channel(int(ch_id))
                    if ch is None or not hasattr(ch, "history"):
                        return ch_id, "(채널을 찾을 수 없음)"
                    try:
                        transcript = await _collect_transcript(
                            ch,
                            before=confirm_interaction.created_at,
                            limit=message_limit,
                            max_context_chars=settings.max_context_chars // len(channel_ids),
                        )
                        if not transcript:
                            return getattr(ch, "name", ch_id), "(메시지 없음)"
                        prompt = build_summarize_prompt(transcript, language=config.language)
                        answer = await llm.generate(prompt, model=config.model)
                        return getattr(ch, "name", ch_id), answer
                    except Exception as e:
                        return getattr(ch, "name", ch_id), f"(오류: {e})"

                results = await asyncio.gather(*[_summarize_one(cid) for cid in channel_ids])

                embed = discord.Embed(
                    title="멀티 채널 통합 요약",
                    color=discord.Color.from_str("#5865F2"),
                )
                for ch_name, summary in results:
                    embed.add_field(
                        name=f"#{ch_name}",
                        value=_truncate(summary),
                        inline=False,
                    )
                await confirm_interaction.followup.send(embed=embed)
                await _record_usage(
                    store, guild_id=guild_id, channel_id=channel_id, user_id=user_id,
                    command="summarize_channels", status="ok", started_at=started,
                )
            except (UserFacingError, LLMError) as exc:
                await confirm_interaction.followup.send(f"⚠️ {exc}", ephemeral=True)
                await _record_usage(
                    store, guild_id=guild_id, channel_id=channel_id, user_id=user_id,
                    command="summarize_channels", status="error", started_at=started, error=str(exc),
                )

        view = ChannelSelectView(channels=text_channels, on_confirm=_on_confirm)
        await interaction.response.send_message("요약할 채널을 선택하세요:", view=view, ephemeral=True)

    # --- #41 /export --- export channel messages as markdown file
    @bot.tree.command(name="export", description="채널 메시지를 마크다운 파일로 내보내기 (DM 전송)")
    @app_commands.describe(limit="내보낼 메시지 수 (기본값: 서버 설정)")
    async def export_command(interaction: discord.Interaction, limit: int | None = None) -> None:
        started = perf_counter()
        guild_id, channel_id, user_id = _ids_from_interaction(interaction)
        await interaction.response.defer(thinking=True, ephemeral=True)
        try:
            config = await store.get_guild_config(guild_id or 0)
            message_limit = _effective_limit(limit, config.summary_limit)
            messages = []
            try:
                async for msg in interaction.channel.history(  # type: ignore[union-attr]
                    limit=message_limit, before=interaction.created_at
                ):
                    messages.append(msg)
            except discord.Forbidden as exc:
                raise UserFacingError("봇에 Read Message History 권한이 없어요.") from exc
            messages.reverse()

            lines = [
                f"# {getattr(interaction.channel, 'name', 'channel')} 내보내기",
                "",
            ]
            for msg in messages:
                ts = msg.created_at.strftime("%Y-%m-%d %H:%M") if msg.created_at else ""
                lines.append(f"**{msg.author.display_name}** [{ts}]")
                if msg.content:
                    lines.append(msg.content)
                # Include attachments and embeds, not just text (#27)
                for att in msg.attachments:
                    lines.append(f"- [첨부] {att.filename}: {att.url}")
                for emb in msg.embeds:
                    emb_title = emb.title or "(제목 없음)"
                    lines.append(f"- [임베드] {emb_title}")
                    if emb.description:
                        lines.append(f"  > {emb.description}")
                lines.append("")

            md_text = "\n".join(lines)
            md_bytes = md_text.encode("utf-8")
            if len(md_bytes) > MAX_EXPORT_BYTES:
                raise UserFacingError("파일 크기가 8MB를 초과해 전송할 수 없어요. limit을 줄여서 시도해 주세요.")

            file_obj = io.BytesIO(md_bytes)
            discord_file = discord.File(file_obj, filename="export.md")
            try:
                await interaction.user.send(
                    f"📄 {getattr(interaction.channel, 'name', 'channel')} 채널 내보내기",
                    file=discord_file,
                )
                await _send_interaction_chunks(interaction, "✅ DM으로 마크다운 파일을 전송했어요!", ephemeral=True)
            except discord.Forbidden:
                await _send_interaction_chunks(
                    interaction, "⚠️ DM을 보낼 수 없어요. 개인 메시지 설정을 확인해 주세요.", ephemeral=True
                )
            await _record_usage(
                store, guild_id=guild_id, channel_id=channel_id, user_id=user_id,
                command="export", status="ok", started_at=started,
            )
        except (UserFacingError, LLMError) as exc:
            await _send_interaction_chunks(interaction, f"⚠️ {exc}", ephemeral=True)
            await _record_usage(
                store, guild_id=guild_id, channel_id=channel_id, user_id=user_id,
                command="export", status="error", started_at=started, error=str(exc),
            )

    # --- #43 /stats --- server usage statistics
    @bot.tree.command(name="stats", description="서버 봇 사용 통계를 표시합니다.")
    async def stats_command(interaction: discord.Interaction) -> None:
        if interaction.guild is None:
            await interaction.response.send_message("⚠️ 이 명령은 서버 안에서만 사용할 수 있어요.", ephemeral=True)
            return
        await interaction.response.defer(thinking=True)
        stats = await store.get_stats(interaction.guild.id)
        now_str = datetime.now(timezone.utc).strftime("%Y-%m-%d %H:%M UTC")

        # Build a human-readable date range from the actual first/last activity (#69)
        def _fmt_day(iso_ts: str | None) -> str | None:
            if not iso_ts:
                return None
            try:
                return datetime.fromisoformat(iso_ts).strftime("%Y-%m-%d")
            except ValueError:
                return iso_ts[:10]

        first_day = _fmt_day(stats.get("first_at"))
        last_day = _fmt_day(stats.get("last_at"))
        if first_day and last_day:
            period = first_day if first_day == last_day else f"{first_day} ~ {last_day}"
            description = f"집계 기간: {period} · 조회 시각: {now_str}"
        else:
            description = f"집계된 사용 기록 없음 · 조회 시각: {now_str}"

        embed = discord.Embed(
            title="서버 사용 통계",
            description=description,
            color=discord.Color.from_str("#5865F2"),
        )
        embed.add_field(name="총 사용 횟수", value=str(stats["total"]), inline=True)
        embed.add_field(name="평균 응답 시간", value=f"{stats['avg_latency_ms']}ms", inline=True)
        embed.add_field(name="에러율", value=f"{stats['error_rate']}%", inline=True)
        if stats["by_command"]:
            cmd_lines = [f"`{r['command']}`: {r['count']}회" for r in stats["by_command"][:10]]
            embed.add_field(name="명령어별 사용 횟수", value="\n".join(cmd_lines), inline=False)
        await interaction.followup.send(embed=embed)

    # --- #47 /search --- keyword search + LLM summary
    @bot.tree.command(name="search", description="채널에서 키워드로 메시지를 검색하고 요약합니다.")
    @app_commands.describe(
        query="검색할 키워드입니다.",
        limit="최대 몇 개 메시지를 검색할지 지정합니다. 기본값: 200",
    )
    async def search_command(
        interaction: discord.Interaction,
        query: str,
        limit: int | None = None,
    ) -> None:
        started = perf_counter()
        guild_id, channel_id, user_id = _ids_from_interaction(interaction)
        await interaction.response.defer(thinking=True)
        try:
            config = await store.get_guild_config(guild_id or 0)
            search_limit = _effective_limit(limit, 200)
            query_lower = query.lower()
            matching: list[str] = []
            try:
                async for msg in interaction.channel.history(  # type: ignore[union-attr]
                    limit=search_limit, before=interaction.created_at
                ):
                    if query_lower in msg.content.lower():
                        ts = msg.created_at.strftime("%H:%M") if msg.created_at else ""
                        matching.append(f"[{ts}] {msg.author.display_name}: {msg.content[:200]}")
                        if len(matching) >= MAX_SEARCH_MATCHES:
                            break
            except discord.Forbidden as exc:
                raise UserFacingError("봇에 Read Message History 권한이 없어요.") from exc

            if not matching:
                await _send_interaction_chunks(
                    interaction, f"검색 결과 없음: `{query}`에 일치하는 메시지가 없어요."
                )
                return

            transcript = "\n".join(matching)
            prompt = build_search_result_prompt(transcript, query, language=config.language)
            llm = _get_llm(config, settings)
            answer = await llm.generate(prompt, model=config.model)

            embed = discord.Embed(
                title=f"검색 결과: {query}",
                color=discord.Color.from_str("#5865F2"),
            )
            embed.add_field(name=f"일치 메시지 수 (최대 {MAX_SEARCH_MATCHES}개 표시)", value=f"{len(matching)}개", inline=True)
            embed.add_field(name="검색 범위", value=f"최근 {search_limit}개 메시지", inline=True)
            embed.add_field(name="요약", value=_truncate(answer), inline=False)
            await interaction.followup.send(embed=embed)
            await _record_usage(
                store, guild_id=guild_id, channel_id=channel_id, user_id=user_id,
                command="search", status="ok", started_at=started,
            )
        except (UserFacingError, LLMError) as exc:
            await _send_interaction_chunks(interaction, f"⚠️ {exc}", ephemeral=True)
            await _record_usage(
                store, guild_id=guild_id, channel_id=channel_id, user_id=user_id,
                command="search", status="error", started_at=started, error=str(exc),
            )

    # --- #11 /digest --- 기간 기반 '오늘의 정리'
    @bot.tree.command(name="digest", description="지정한 기간의 대화를 핵심·결정·액션으로 정리합니다.")
    @app_commands.describe(since="정리할 기간. 예: 30m, 1h, 6h, 1d (기본: 1d)")
    @app_commands.autocomplete(since=_since_autocomplete)
    async def digest_command(
        interaction: discord.Interaction,
        since: str = "1d",
    ) -> None:
        started = perf_counter()
        guild_id, channel_id, user_id = _ids_from_interaction(interaction)
        remaining = _check_cooldown(guild_id, user_id)
        if remaining is not None:
            await interaction.response.send_message(
                f"⏳ {remaining:.0f}초 후에 다시 시도해주세요.", ephemeral=True
            )
            return
        await interaction.response.defer(thinking=True)
        try:
            since_dt = _parse_since(since)
            config = await store.get_guild_config(guild_id or 0)
            # 역할 제한 검사 (#49 와 동일 정책).
            if not _has_allowed_role(interaction, config.allowed_role_id):
                raise UserFacingError("이 명령을 사용할 권한이 없어요. 서버 관리자에게 문의하세요.")
            # 기간 내 메시지를 모은다. summary_limit 를 상한으로 두되 200까지 허용한다.
            digest_limit = _effective_limit(config.summary_limit, config.summary_limit)
            transcript = await _collect_transcript(
                interaction.channel,
                before=interaction.created_at,
                limit=digest_limit,
                max_context_chars=settings.max_context_chars,
                after=since_dt,
            )
            if not transcript:
                raise UserFacingError("정리할 메시지가 없어요. 해당 기간에 대화가 있어야 합니다.")

            effective_language = config.language
            if effective_language == "auto":
                effective_language = detect_language_from_transcript(transcript)
            # build_summarize_prompt 를 재사용해 '핵심·결정·액션' 구조 요약을 만든다 (#11).
            prompt = build_summarize_prompt(transcript, language=effective_language)
            llm = _get_llm(config, settings)
            answer = await llm.generate(prompt, model=config.model)

            header = f"📋 **오늘의 정리** (최근 {since})\n"
            first, *rest = _split_discord_text(header + answer)
            msg = await interaction.followup.send(first, wait=True)
            for chunk in rest:
                await interaction.followup.send(chunk)
            await _track_for_feedback(guild_id, msg, "digest")
            await _record_usage(
                store, guild_id=guild_id, channel_id=channel_id, user_id=user_id,
                command="digest", status="ok", started_at=started,
            )
        except (UserFacingError, LLMError) as exc:
            await _send_interaction_chunks(interaction, f"⚠️ {exc}", ephemeral=True)
            await _record_usage(
                store, guild_id=guild_id, channel_id=channel_id, user_id=user_id,
                command="digest", status="error", started_at=started, error=str(exc),
            )

    # --- #94 /usage --- 본인 사용량 + 쿨다운 + 서버 한도 안내
    @bot.tree.command(name="usage", description="내 사용량과 쿨다운, 서버 한도를 확인합니다.")
    async def usage_command(interaction: discord.Interaction) -> None:
        guild_id, _channel_id, user_id = _ids_from_interaction(interaction)
        config = await store.get_guild_config(guild_id or 0)

        # 남은 쿨다운은 상태를 갱신하지 않고 조회만 한다(_check_cooldown 은 갱신하므로
        # 사용하지 않는다). _cooldowns 의 마지막 사용 시각으로 직접 계산한다.
        cooldown_note = "없음 (바로 사용 가능)"
        if guild_id is not None and user_id is not None:
            last = _cooldowns.get((guild_id, user_id))
            if last is not None:
                elapsed = perf_counter() - last
                if elapsed < COOLDOWN_SECONDS:
                    cooldown_note = f"{COOLDOWN_SECONDS - elapsed:.0f}초 남음"

        # 서버 전체 사용 통계에서 본인 명령 수를 별도 집계할 헬퍼가 없으므로,
        # get_stats(서버 단위)로 전체 사용 현황을 보여주고 서버 한도를 함께 안내한다 (#94).
        embed = discord.Embed(
            title="내 사용량 / 서버 한도",
            color=discord.Color.from_str("#5865F2"),
        )
        if guild_id is not None:
            stats = await store.get_stats(guild_id)
            embed.add_field(name="서버 총 사용 횟수", value=str(stats["total"]), inline=True)
            embed.add_field(name="평균 응답 시간", value=f"{stats['avg_latency_ms']}ms", inline=True)
            embed.add_field(name="에러율", value=f"{stats['error_rate']}%", inline=True)
        embed.add_field(name="남은 쿨다운", value=cooldown_note, inline=True)
        embed.add_field(
            name="쿨다운 간격", value=f"{COOLDOWN_SECONDS}초", inline=True
        )
        # _effective_limit 가 200 초과를 조용히 깎으므로, 서버 요약 한도와 함께
        # 실제 적용 상한을 명시해 사용자가 혼동하지 않게 안내한다 (#94).
        applied_limit = _effective_limit(config.summary_limit, config.summary_limit)
        limit_note = f"{config.summary_limit}개"
        if config.summary_limit > 200:
            limit_note = f"{config.summary_limit}개 → 실제 {applied_limit}개로 제한 적용"
        embed.add_field(name="서버 요약 범위(summary_limit)", value=limit_note, inline=True)
        embed.set_footer(text="요약·질문 명령은 위 쿨다운 간격으로 제한됩니다.")
        await interaction.response.send_message(embed=embed, ephemeral=True)

    # --- #4 컨텍스트 메뉴 --- 우클릭 → 메시지 번역/요약/질문
    # 메시지 대상 컨텍스트 메뉴 3개. 우클릭한 메시지 내용을 기존 build_* 프롬프트에
    # 그대로 넣어(인젝션 방어 내장) ephemeral 로 응답한다. create_bot 안에서
    # bot.tree.add_command 로 등록한다.

    async def _ctx_menu_guard(
        interaction: discord.Interaction, content: str
    ) -> tuple[GuildConfig, BaseLLMClient] | None:
        """컨텍스트 메뉴 공통 가드: 쿨다운·빈 메시지 확인 후 (config, llm) 반환.

        가드에 걸리면 사용자에게 ephemeral 안내를 보내고 None 을 반환한다.
        """
        guild_id, _channel_id, user_id = _ids_from_interaction(interaction)
        remaining = _check_cooldown(guild_id, user_id)
        if remaining is not None:
            await interaction.response.send_message(
                f"⏳ {remaining:.0f}초 후에 다시 시도해주세요.", ephemeral=True
            )
            return None
        if not content.strip():
            await interaction.response.send_message(
                "⚠️ 대상 메시지에 처리할 텍스트가 없어요.", ephemeral=True
            )
            return None
        config = await store.get_guild_config(guild_id or 0)
        llm = _get_llm(config, settings)
        return config, llm

    async def _translate_message_ctx(
        interaction: discord.Interaction, message: discord.Message
    ) -> None:
        started = perf_counter()
        guild_id, channel_id, user_id = _ids_from_interaction(interaction)
        try:
            guard = await _ctx_menu_guard(interaction, message.content)
            if guard is None:
                return
            config, llm = guard
            await interaction.response.defer(thinking=True, ephemeral=True)
            # 컨텍스트 메뉴 번역은 서버 언어 설정으로 번역한다(auto 면 한국어로 폴백).
            target = config.language if config.language != "auto" else "ko"
            prompt = build_translate_prompt(message.content, target_language=target)
            answer = await llm.generate(prompt, model=config.model)
            await _send_interaction_chunks(interaction, f"🌐 **번역 ({target})**\n{answer}", ephemeral=True)
            await _record_usage(
                store, guild_id=guild_id, channel_id=channel_id, user_id=user_id,
                command="ctx_translate", status="ok", started_at=started,
            )
        except (UserFacingError, LLMError) as exc:
            await _send_interaction_chunks(interaction, f"⚠️ {exc}", ephemeral=True)
            await _record_usage(
                store, guild_id=guild_id, channel_id=channel_id, user_id=user_id,
                command="ctx_translate", status="error", started_at=started, error=str(exc),
            )

    async def _summarize_message_ctx(
        interaction: discord.Interaction, message: discord.Message
    ) -> None:
        started = perf_counter()
        guild_id, channel_id, user_id = _ids_from_interaction(interaction)
        try:
            guard = await _ctx_menu_guard(interaction, message.content)
            if guard is None:
                return
            config, llm = guard
            await interaction.response.defer(thinking=True, ephemeral=True)
            language = config.language
            if language == "auto":
                language = detect_language_from_transcript(message.content)
            prompt = build_summarize_prompt(message.content, language=language)
            answer = await llm.generate(prompt, model=config.model)
            await _send_interaction_chunks(interaction, f"📝 **메시지 요약**\n{answer}", ephemeral=True)
            await _record_usage(
                store, guild_id=guild_id, channel_id=channel_id, user_id=user_id,
                command="ctx_summarize", status="ok", started_at=started,
            )
        except (UserFacingError, LLMError) as exc:
            await _send_interaction_chunks(interaction, f"⚠️ {exc}", ephemeral=True)
            await _record_usage(
                store, guild_id=guild_id, channel_id=channel_id, user_id=user_id,
                command="ctx_summarize", status="error", started_at=started, error=str(exc),
            )

    async def _ask_message_ctx(
        interaction: discord.Interaction, message: discord.Message
    ) -> None:
        started = perf_counter()
        guild_id, channel_id, user_id = _ids_from_interaction(interaction)
        try:
            guard = await _ctx_menu_guard(interaction, message.content)
            if guard is None:
                return
            config, llm = guard
            await interaction.response.defer(thinking=True, ephemeral=True)
            language = config.language
            if language == "auto":
                language = detect_language_from_transcript(message.content)
            # 우클릭한 메시지를 트랜스크립트로, 고정 질문으로 build_ask_prompt 를 호출한다.
            question = "이 메시지의 핵심 내용을 설명하고, 궁금한 점에 답해줘."
            prompt = build_ask_prompt(message.content, question, language=language)
            answer = await llm.generate(prompt, model=config.model)
            await _send_interaction_chunks(interaction, f"💬 **이 메시지로 질문**\n{answer}", ephemeral=True)
            await _record_usage(
                store, guild_id=guild_id, channel_id=channel_id, user_id=user_id,
                command="ctx_ask", status="ok", started_at=started,
            )
        except (UserFacingError, LLMError) as exc:
            await _send_interaction_chunks(interaction, f"⚠️ {exc}", ephemeral=True)
            await _record_usage(
                store, guild_id=guild_id, channel_id=channel_id, user_id=user_id,
                command="ctx_ask", status="error", started_at=started, error=str(exc),
            )

    bot.tree.add_command(
        app_commands.ContextMenu(name="메시지 번역", callback=_translate_message_ctx)
    )
    bot.tree.add_command(
        app_commands.ContextMenu(name="메시지 요약", callback=_summarize_message_ctx)
    )
    bot.tree.add_command(
        app_commands.ContextMenu(name="이 메시지로 질문", callback=_ask_message_ctx)
    )

    # --- Phase 3 /config subcommands ---

    @config_group.command(name="admin_role", description="봇 설정 권한을 가진 역할을 지정합니다.")
    @app_commands.describe(role="설정 권한을 부여할 역할입니다.")
    async def config_admin_role(interaction: discord.Interaction, role: discord.Role) -> None:
        try:
            guild_id = await require_guild_admin(interaction)
            before = (await store.get_guild_config(guild_id)).admin_role_id
            await store.set_admin_role(guild_id, role.id)
            await _audit_config_change(interaction, guild_id, "set_admin_role", before, role.id)
            await _send_interaction_chunks(
                interaction, f"✅ 관리 역할을 `{role.name}`으로 설정했어요.", ephemeral=True,
            )
        except (UserFacingError, ValueError) as exc:
            await _send_interaction_chunks(interaction, f"⚠️ {exc}", ephemeral=True)

    _MAX_PERSONA_CHARS = 500
    _MAX_CUSTOM_PROMPT_CHARS = 2000

    @config_group.command(name="persona", description="/chat 페르소나를 설정합니다.")
    @app_commands.describe(description="봇의 페르소나 설명입니다. 비워두면 초기화합니다.")
    async def config_persona(interaction: discord.Interaction, description: str = "") -> None:
        try:
            guild_id = await require_guild_admin(interaction)
            persona = _sanitize_persona(description) or None
            if persona and len(persona) > _MAX_PERSONA_CHARS:
                raise UserFacingError(f"페르소나는 {_MAX_PERSONA_CHARS}자 이하여야 합니다.")
            before = (await store.get_guild_config(guild_id)).persona
            await store.set_persona(guild_id, persona)
            # 감사 로그에는 긴 본문 대신 길이가 제한된 요약만 남긴다(#39).
            await _audit_config_change(
                interaction,
                guild_id,
                "set_persona",
                None if before is None else before[:100],
                None if persona is None else persona[:100],
            )
            if persona:
                await _send_interaction_chunks(
                    interaction, f"✅ 페르소나를 설정했어요: `{persona[:100]}`", ephemeral=True,
                )
            else:
                await _send_interaction_chunks(interaction, "✅ 페르소나를 초기화했어요.", ephemeral=True)
        except (UserFacingError, ValueError) as exc:
            await _send_interaction_chunks(interaction, f"⚠️ {exc}", ephemeral=True)

    @config_group.command(name="auto_summary", description="자동 요약 간격을 설정합니다. (최소 5분, 0이면 비활성화)")
    @app_commands.describe(interval="자동 요약 간격 (분, 최소 5). 0이면 비활성화.")
    async def config_auto_summary(interaction: discord.Interaction, interval: int) -> None:
        try:
            guild_id = await require_guild_admin(interaction)
            effective_interval = None if interval <= 0 else interval
            before = (await store.get_guild_config(guild_id)).auto_summary_interval
            await store.set_auto_summary_interval(guild_id, effective_interval)
            await _audit_config_change(
                interaction, guild_id, "set_auto_summary", before, effective_interval
            )
            if effective_interval:
                await _send_interaction_chunks(
                    interaction, f"✅ 자동 요약 간격을 {effective_interval}분으로 설정했어요.", ephemeral=True,
                )
            else:
                await _send_interaction_chunks(interaction, "✅ 자동 요약을 비활성화했어요.", ephemeral=True)
        except (UserFacingError, ValueError) as exc:
            await _send_interaction_chunks(interaction, f"⚠️ {exc}", ephemeral=True)

    @config_group.command(name="custom_prompt", description="커스텀 프롬프트를 설정합니다.")
    @app_commands.describe(
        prompt_type="프롬프트 유형: summarize 또는 ask",
        text="커스텀 프롬프트 내용. 비워두면 초기화.",
    )
    @app_commands.autocomplete(prompt_type=_prompt_type_autocomplete)
    async def config_custom_prompt(
        interaction: discord.Interaction, prompt_type: str, text: str = ""
    ) -> None:
        try:
            guild_id = await require_guild_admin(interaction)
            effective_text = text.strip() or None
            if effective_text and len(effective_text) > _MAX_CUSTOM_PROMPT_CHARS:
                raise UserFacingError(f"커스텀 프롬프트는 {_MAX_CUSTOM_PROMPT_CHARS}자 이하여야 합니다.")
            prev_config = await store.get_guild_config(guild_id)
            before = (
                prev_config.custom_summarize_prompt
                if prompt_type == "summarize"
                else prev_config.custom_ask_prompt
            )
            await store.set_custom_prompt(guild_id, prompt_type, effective_text)
            # 긴 프롬프트 본문 대신 길이 제한 요약만 감사 로그에 남긴다(#39).
            await _audit_config_change(
                interaction,
                guild_id,
                f"set_custom_prompt_{prompt_type}",
                None if before is None else before[:100],
                None if effective_text is None else effective_text[:100],
            )
            if effective_text:
                await _send_interaction_chunks(
                    interaction, f"✅ `{prompt_type}` 커스텀 프롬프트를 저장했어요.", ephemeral=True,
                )
            else:
                await _send_interaction_chunks(
                    interaction, f"✅ `{prompt_type}` 커스텀 프롬프트를 초기화했어요.", ephemeral=True
                )
        except (UserFacingError, ValueError) as exc:
            await _send_interaction_chunks(interaction, f"⚠️ {exc}", ephemeral=True)

    @config_group.command(name="allowed_role", description="명령어 사용 가능 역할을 설정합니다.")
    @app_commands.describe(role="명령어를 사용할 수 있는 역할입니다.")
    async def config_allowed_role(interaction: discord.Interaction, role: discord.Role | None = None) -> None:
        try:
            guild_id = await require_guild_admin(interaction)
            role_id = role.id if role else None
            before = (await store.get_guild_config(guild_id)).allowed_role_id
            await store.set_allowed_role(guild_id, role_id)
            await _audit_config_change(
                interaction, guild_id, "set_allowed_role", before, role_id
            )
            if role:
                await _send_interaction_chunks(
                    interaction, f"✅ `{role.name}` 역할만 명령어를 사용할 수 있어요.", ephemeral=True,
                )
            else:
                await _send_interaction_chunks(interaction, "✅ 역할 제한을 해제했어요.", ephemeral=True)
        except (UserFacingError, ValueError) as exc:
            await _send_interaction_chunks(interaction, f"⚠️ {exc}", ephemeral=True)

    bot.tree.add_command(config_group)

    # ------------------------------------------------------------------
    # Events
    # ------------------------------------------------------------------

    @bot.event
    async def on_ready() -> None:
        assert bot.user is not None
        logger.info("Logged in as %s (id=%s)", bot.user, bot.user.id)
        for guild in bot.guilds:
            bot.tree.copy_global_to(guild=guild)
            synced = await bot.tree.sync(guild=guild)
            logger.info("Guild-synced %d command(s) to %s", len(synced), guild.name)
        # fire-and-forget 태스크는 _track_task 로 추적해 조용한 소실/예외 삼킴 방지 (#51).
        _track_task(_memory_monitor(), name="memory-monitor")
        # 봇 재시작에도 미발송 reminder 가 살아남도록 다시 예약한다 (#1).
        _track_task(_reschedule_pending_reminders(), name="reschedule-reminders")
        # Start auto-summary background task (#33)
        if not auto_summary_task.is_running():
            auto_summary_task.start()
        # Start retention 정리 백그라운드 태스크 (#27)
        if not retention_task.is_running():
            retention_task.start()
        await bot.change_presence(
            activity=discord.Activity(
                type=discord.ActivityType.watching,
                name=f"{len(bot.guilds)}개 서버",
            )
        )

    async def _memory_monitor() -> None:
        """Log process memory usage every hour."""
        try:
            import psutil  # type: ignore[import-untyped]
            while True:
                await asyncio.sleep(3600)
                proc = psutil.Process(os.getpid())
                mem_mb = proc.memory_info().rss / 1024 / 1024
                logger.info("메모리 사용량: %.1f MB", mem_mb)
        except ImportError:
            logger.debug("psutil not installed; memory monitoring disabled.")

    @bot.event
    async def on_message(message: discord.Message) -> None:
        if message.author.bot or bot.user is None:
            await bot.process_commands(message)
            return

        # Invalidate summarize cache for this channel on every new non-bot message
        guild_id_raw = message.guild.id if message.guild else 0
        channel_id_raw = message.channel.id if hasattr(message.channel, "id") else 0
        summarize_cache.invalidate_prefix(f"{guild_id_raw}:{channel_id_raw}")

        await bot.process_commands(message)

        # --- DM support (#48) ---
        if message.guild is None:
            # DM mode: treat as /chat without channel context
            user_id = message.author.id
            dm_remaining = _check_cooldown(_DM_COOLDOWN_GUILD, user_id)
            if dm_remaining is not None:
                try:
                    await message.channel.send(f"⏳ {dm_remaining:.0f}초 후에 다시 시도해주세요.")
                except Exception:
                    pass
                return
            started = perf_counter()
            try:
                config = await store.get_guild_config(0)  # use default config
                # #10: DM 대화 기억 — 직전 대화를 history 로 이어 붙인다(guild_id=None).
                history = await store.get_chat_history(user_id, guild_id=None, limit=10)
                if history:
                    prompt = build_chat_with_history_prompt(
                        message.content, history, language=config.language
                    )
                else:
                    prompt = build_chat_prompt(
                        message.content, language=config.language, persona=config.persona
                    )
                llm = _get_llm(config, settings)
                async with message.channel.typing():
                    answer = await llm.generate(prompt, model=config.model)
                await _send_channel_chunks(message.channel, answer)
                # 대화를 저장해 다음 DM 에서 맥락을 이어가게 한다 (#10).
                await store.save_chat_message(user_id, "user", message.content, guild_id=None)
                await store.save_chat_message(user_id, "assistant", answer, guild_id=None)
                await _record_usage(
                    store, guild_id=None, channel_id=None, user_id=user_id,
                    command="dm_chat", status="ok", started_at=started,
                )
            except Exception as exc:
                # Mirror the guild path: surface only user-facing/LLM detail,
                # keep internal exceptions generic (consistent with #64).
                if isinstance(exc, (UserFacingError, LLMError)):
                    user_msg = f"⚠️ {exc}"
                else:
                    logger.exception("DM chat handler error: %s", exc)
                    user_msg = "⚠️ 예기치 않은 오류가 발생했습니다. 잠시 후 다시 시도해주세요."
                try:
                    await message.channel.send(user_msg)
                except Exception:
                    pass
                await _record_usage(
                    store, guild_id=None, channel_id=None, user_id=user_id,
                    command="dm_chat", status="error", started_at=started, error=str(exc),
                )
            return

        # --- #8 답장 맥락 --- 봇의 이전 메시지에 답장하면 대화를 이어간다.
        # message.reference 가 봇이 보낸 메시지를 가리키면, 그 내용을 직전
        # assistant 턴으로 삼아 build_chat_with_history_prompt 로 이어 대화한다.
        # (message.author.bot 가드는 상단에서 이미 처리되어 자기 답장 루프는 없다.)
        ref = message.reference
        replied_to_bot = False
        referenced_text = ""
        if ref is not None:
            resolved = ref.resolved
            if isinstance(resolved, discord.Message):
                referenced = resolved
            elif ref.message_id is not None and hasattr(message.channel, "fetch_message"):
                try:
                    referenced = await message.channel.fetch_message(ref.message_id)
                except (discord.HTTPException, discord.NotFound, discord.Forbidden):
                    referenced = None
            else:
                referenced = None
            if (
                referenced is not None
                and bot.user is not None
                and referenced.author.id == bot.user.id
            ):
                replied_to_bot = True
                referenced_text = referenced.content

        if replied_to_bot:
            started = perf_counter()
            guild_id = message.guild.id if message.guild else None
            channel_id = message.channel.id if hasattr(message.channel, "id") else None
            user_id = message.author.id
            reply_remaining = _check_cooldown(guild_id, user_id)
            if reply_remaining is not None:
                return
            # 멘션 토큰은 질문 본문에서 제거한다(답장은 자동 멘션을 포함할 수 있음).
            cleaned = message.content.replace(f"<@{bot.user.id}>", "").replace(
                f"<@!{bot.user.id}>", ""
            )
            follow_question = normalize_content(cleaned)
            if not follow_question:
                return
            try:
                config = await store.get_guild_config(guild_id or 0)
                # 봇 직전 응답을 assistant 턴으로 넣어 맥락을 잇는다 (#8).
                history = [{"role": "assistant", "content": referenced_text}]
                prompt = build_chat_with_history_prompt(
                    follow_question, history, language=config.language
                )
                llm = _get_llm(config, settings)
                async with message.channel.typing():
                    answer = await llm.generate(prompt, model=config.model)
                await _send_channel_chunks(message.channel, answer)
                await _record_usage(
                    store, guild_id=guild_id, channel_id=channel_id, user_id=user_id,
                    command="reply_chat", status="ok", started_at=started,
                )
            except (UserFacingError, LLMError) as exc:
                await _send_channel_chunks(message.channel, f"⚠️ {exc}")
                await _record_usage(
                    store, guild_id=guild_id, channel_id=channel_id, user_id=user_id,
                    command="reply_chat", status="error", started_at=started, error=str(exc),
                )
            return

        if bot.user not in message.mentions:
            return

        started = perf_counter()
        guild_id = message.guild.id if message.guild else None
        channel_id = message.channel.id if hasattr(message.channel, "id") else None
        user_id = message.author.id
        command_name = "mention_ask"

        raw_query = message.content
        raw_query = raw_query.replace(f"<@{bot.user.id}>", "").replace(f"<@!{bot.user.id}>", "")
        question = normalize_content(raw_query)

        try:
            config = await store.get_guild_config(guild_id or 0)

            # Image analysis (#46) — if attachments contain an image and model looks multimodal
            if message.attachments and config.model.lower().startswith(("llava", "bakllava")):
                image_urls = [
                    att.url for att in message.attachments
                    if att.content_type and att.content_type.startswith("image/")
                ]
                if image_urls:
                    llm = _get_llm(config, settings)
                    results = []
                    async with message.channel.typing():
                        for img_url in image_urls[:3]:
                            img_prompt = build_image_analysis_prompt(img_url, language=config.language)
                            img_answer = await llm.generate(img_prompt, model=config.model)
                            results.append(img_answer)
                    combined = "\n\n".join(results)
                    await _send_channel_chunks(message.channel, f"**이미지 분석**\n{combined}")
                    await _record_usage(
                        store, guild_id=guild_id, channel_id=channel_id, user_id=user_id,
                        command="image_analysis", status="ok", started_at=started,
                    )
                    return

            # #6 스레드 맥락: 멘션이 스레드 안에서 발생하면 그 스레드의 메시지만
            # transcript 로 모은다. message.channel 은 스레드일 때 스레드 자신을
            # 가리키므로 thread.history 만 읽혀 부모 채널이 섞이지 않는다.
            context_channel = message.channel
            in_thread = isinstance(context_channel, discord.Thread)
            transcript = await _collect_transcript(
                context_channel,
                before=message.created_at,
                limit=config.summary_limit,
                max_context_chars=settings.max_context_chars,
            )
            if not transcript:
                raise UserFacingError("참고할 최근 메시지가 없어요.")

            llm = _get_llm(config, settings)

            if question:
                prompt = build_ask_prompt(transcript, question, language=config.language)
                heading = f"**질문:** {question}\n\n"
            else:
                command_name = "mention_summarize"
                prompt = build_summarize_prompt(transcript, language=config.language)
                heading = "🧵 **스레드 대화 요약**\n" if in_thread else "**최근 대화 요약**\n"

            async with message.channel.typing():
                answer = await llm.generate(prompt, model=config.model)
            await _send_channel_chunks(message.channel, heading + answer)
            await _record_usage(
                store, guild_id=guild_id, channel_id=channel_id, user_id=user_id,
                command=command_name, status="ok", started_at=started,
            )
        except (UserFacingError, LLMError) as exc:
            await _send_channel_chunks(message.channel, f"⚠️ {exc}")
            await _record_usage(
                store, guild_id=guild_id, channel_id=channel_id, user_id=user_id,
                command=command_name, status="error", started_at=started, error=str(exc),
            )

    # ------------------------------------------------------------------
    # Developer notifications — on_disconnect and on_error
    # ------------------------------------------------------------------

    # #54: on_disconnect 오탐 제거.
    # discord.py 는 일상적인 재연결(게이트웨이 리밸런싱 등)에도 on_disconnect 를
    # 자주 발생시킨다. 즉시 DM 을 보내면 도배가 되므로, 끊김을 감지하면 유예 시간
    # 동안 기다렸다가 그동안 재연결(on_resumed/on_connect)되지 않은 경우에만 알린다.
    _DISCONNECT_GRACE_SECONDS = 30.0
    _disconnect_state: dict[str, asyncio.Task[Any] | None] = {"pending": None}

    async def _delayed_disconnect_alert() -> None:
        """유예 시간 대기 후에도 재연결이 없으면 개발자에게 알린다 (#54)."""
        try:
            await asyncio.sleep(_DISCONNECT_GRACE_SECONDS)
        except asyncio.CancelledError:
            # 유예 시간 내 재연결 → 알림 취소(정상 동작).
            return
        # 여전히 연결되지 않은 경우에만 알린다.
        if bot.is_closed() or not bot.is_ready():
            logger.warning(
                "Bot still disconnected after %.0fs grace; notifying developer.",
                _DISCONNECT_GRACE_SECONDS,
            )
            msg = format_disconnect_message(shard_id=None)
            await notify_developer(msg, bot)
        _disconnect_state["pending"] = None

    def _cancel_pending_disconnect_alert() -> None:
        """대기 중인 끊김 알림을 취소한다(재연결 시 호출) (#54)."""
        pending = _disconnect_state.get("pending")
        if pending is not None and not pending.done():
            pending.cancel()
        _disconnect_state["pending"] = None

    @bot.event
    async def on_disconnect() -> None:
        logger.warning("Bot disconnected from Discord (grace period before alert).")
        # 이미 대기 중인 알림이 있으면 중복 예약하지 않는다.
        pending = _disconnect_state.get("pending")
        if pending is not None and not pending.done():
            return
        _disconnect_state["pending"] = _track_task(
            _delayed_disconnect_alert(), name="disconnect-alert"
        )

    @bot.event
    async def on_resumed() -> None:
        # 세션 재개 → 진행 중인 끊김 알림이 있으면 취소해 DM 도배를 막는다 (#54).
        logger.info("Bot session resumed.")
        _cancel_pending_disconnect_alert()

    @bot.event
    async def on_connect() -> None:
        # 재연결(신규 세션) 시에도 대기 중인 끊김 알림을 취소한다 (#54).
        _cancel_pending_disconnect_alert()

    @bot.event
    async def on_error(event: str, *args: object, **kwargs: object) -> None:  # type: ignore[override]
        exc_info = sys.exc_info()
        exc = exc_info[1]
        if exc is not None:
            logger.exception("Unhandled error in event '%s'.", event, exc_info=exc_info)
            msg = format_error_message(event, exc)
        else:
            logger.error("Unhandled error in event '%s' (no exception info).", event)
            msg = f"[discord-assistant] Unhandled error in event `{event}` (no exception details)."
        await notify_developer(msg, bot)


    # ------------------------------------------------------------------
    # Phase 3 — Auto summary background task (#33)
    # ------------------------------------------------------------------

    @tasks.loop(minutes=1)
    async def auto_summary_task() -> None:
        """Check each guild for pending auto-summary and post if interval elapsed."""
        try:
            # Only consider guilds that actually enabled auto-summary (#25)
            configured = await store.get_guilds_with_auto_summary()
            if not configured:
                return
            now = datetime.now(timezone.utc)
            for gid, interval in configured:
                last_run = _auto_summary_last_run.get(gid)
                if last_run is not None:
                    elapsed_minutes = (now - last_run).total_seconds() / 60
                    if elapsed_minutes < interval:
                        continue
                _auto_summary_last_run[gid] = now

                config = await store.get_guild_config(gid)
                guild = bot.get_guild(gid)
                if guild is None:
                    continue
                # Find the first text channel we can post to
                for ch in guild.text_channels:
                    if ch.permissions_for(guild.me).send_messages and ch.permissions_for(guild.me).read_message_history:
                        try:
                            transcript = await _collect_transcript(
                                ch,
                                before=now,
                                limit=config.summary_limit,
                                max_context_chars=settings.max_context_chars,
                            )
                            if not transcript:
                                break
                            prompt = build_summarize_prompt(transcript, language=config.language)
                            llm = _get_llm(config, settings)
                            answer = await llm.generate(prompt, model=config.model)
                            await ch.send(f"**자동 요약** (매 {config.auto_summary_interval}분)\n{answer[:1800]}")
                        except Exception as e:
                            logger.warning("Auto summary failed for guild %d: %s", gid, e)
                        break
        except Exception as e:
            logger.exception("auto_summary_task error: %s", e)

    # ------------------------------------------------------------------
    # #27 — Retention 정리 백그라운드 태스크 (하루 1회)
    # ------------------------------------------------------------------

    @tasks.loop(hours=24)
    async def retention_task() -> None:
        """오래된 usage_log/chat_history 를 주기적으로 정리한다 (#27).

        보존일은 상수(RETENTION_USAGE_DAYS / RETENTION_CHAT_DAYS)를 사용한다.
        purge 후 VACUUM 으로 디스크 사용을 회수한다(인메모리 DB 는 자동 skip).
        """
        try:
            deleted = await store.purge_old(
                usage_days=RETENTION_USAGE_DAYS, chat_days=RETENTION_CHAT_DAYS
            )
            if deleted.get("usage_log") or deleted.get("chat_history"):
                logger.info(
                    "Retention 정리 완료: usage_log %d건, chat_history %d건 삭제.",
                    deleted.get("usage_log", 0),
                    deleted.get("chat_history", 0),
                )
                await store.vacuum()
        except Exception as exc:
            logger.exception("retention_task error: %s", exc)

    # ------------------------------------------------------------------
    # Phase 3 — Reaction feedback tracker (#42)
    # ------------------------------------------------------------------

    @bot.event
    async def on_reaction_add(reaction: discord.Reaction, user: discord.User | discord.Member) -> None:
        if user.bot:
            return
        msg = reaction.message
        guild_id = msg.guild.id if msg.guild else None
        if guild_id is None:
            return
        command_name = _tracked_messages.get(guild_id, {}).get(msg.id)
        if command_name is None:
            return
        emoji_str = str(reaction.emoji)
        if emoji_str == THUMBS_UP:
            rating = 1
        elif emoji_str == THUMBS_DOWN:
            rating = -1
        else:
            return
        try:
            await store.save_feedback(
                guild_id=guild_id,
                message_id=msg.id,
                user_id=user.id,
                rating=rating,
                command=command_name,
            )
        except Exception as e:
            logger.warning("Failed to save feedback: %s", e)

    # ------------------------------------------------------------------
    # #9 — Reaction-triggered summarize/translate (📝 / 🌐)
    # ------------------------------------------------------------------

    @bot.event
    async def on_raw_reaction_add(payload: discord.RawReactionActionEvent) -> None:
        """📝/🌐 리액션을 메시지에 달면 그 메시지를 요약/번역해 답장한다 (#9).

        on_raw 를 쓰는 이유: 캐시되지 않은(오래된) 메시지에도 동작해야 하기
        때문이다. 봇 자신/쿨다운 가드를 두고, 👍/👎 피드백 경로와 공존한다.
        """
        emoji_str = str(payload.emoji)
        if emoji_str not in (REACTION_SUMMARIZE, REACTION_TRANSLATE):
            return
        if bot.user is not None and payload.user_id == bot.user.id:
            return  # 봇 자신의 리액션은 무시
        guild_id = payload.guild_id
        # 쿨다운: 리액션을 단 사용자 기준. DM(guild_id None)은 센티넬 버킷 사용.
        cd_guild = guild_id if guild_id is not None else _DM_COOLDOWN_GUILD
        if _check_cooldown(cd_guild, payload.user_id) is not None:
            return

        channel = bot.get_channel(payload.channel_id)
        if channel is None or not hasattr(channel, "fetch_message"):
            return
        try:
            target = await channel.fetch_message(payload.message_id)  # type: ignore[union-attr]
        except (discord.HTTPException, discord.NotFound, discord.Forbidden):
            return
        if not target.content.strip():
            return  # 처리할 텍스트가 없는 메시지(첨부만 등)는 건너뛴다.

        started = perf_counter()
        config = await store.get_guild_config(guild_id or 0)
        command_name = "reaction_summarize" if emoji_str == REACTION_SUMMARIZE else "reaction_translate"
        try:
            llm = _get_llm(config, settings)
            if emoji_str == REACTION_SUMMARIZE:
                language = config.language
                if language == "auto":
                    language = detect_language_from_transcript(target.content)
                prompt = build_summarize_prompt(target.content, language=language)
                heading = "📝 **메시지 요약**\n"
            else:
                target_lang = config.language if config.language != "auto" else "ko"
                prompt = build_translate_prompt(target.content, target_language=target_lang)
                heading = f"🌐 **번역 ({target_lang})**\n"
            answer = await llm.generate(prompt, model=config.model)
            for i, chunk in enumerate(_split_discord_text(heading + answer)):
                if i == 0:
                    await target.reply(chunk, mention_author=False)
                else:
                    await _send_channel_chunks(channel, chunk)  # type: ignore[arg-type]
            await _record_usage(
                store, guild_id=guild_id, channel_id=payload.channel_id, user_id=payload.user_id,
                command=command_name, status="ok", started_at=started,
            )
        except (UserFacingError, LLMError) as exc:
            try:
                await target.reply(f"⚠️ {exc}", mention_author=False)
            except discord.HTTPException:
                pass
            await _record_usage(
                store, guild_id=guild_id, channel_id=payload.channel_id, user_id=payload.user_id,
                command=command_name, status="error", started_at=started, error=str(exc),
            )

    # ------------------------------------------------------------------
    # Phase 3 — Guild join welcome message (#50)
    # ------------------------------------------------------------------

    @bot.event
    async def on_guild_join(guild: discord.Guild) -> None:
        logger.info("Joined guild: %s (id=%s, members=%s)", guild.name, guild.id, guild.member_count)
        help_embed = discord.Embed(
            title="Discord AI Assistant에 오신 것을 환영합니다!",
            description="저는 채널 대화를 요약하고 질문에 답하는 AI 어시스턴트입니다.",
            color=discord.Color.from_str("#5865F2"),
        )
        help_embed.add_field(name="/summarize", value="채널 대화 요약", inline=True)
        help_embed.add_field(name="/ask question:...", value="채널 대화 기반 Q&A", inline=True)
        help_embed.add_field(name="/chat message:...", value="자유 대화", inline=True)
        help_embed.add_field(name="/settings", value="서버 설정 (관리자 전용) — `/settings`로 AI 제공자와 모델을 설정하세요.", inline=False)
        help_embed.set_footer(text="/help 명령어로 전체 안내를 볼 수 있어요.")
        sent = False
        for channel in guild.text_channels:
            if channel.permissions_for(guild.me).send_messages:
                try:
                    await channel.send(embed=help_embed)
                    sent = True
                except Exception:
                    pass
                break
        if not sent:
            logger.warning("Could not send welcome message to guild %s — no accessible text channel", guild.id)

    return bot


async def _cancel_background_tasks() -> None:
    """추적 중인 fire-and-forget 태스크를 모두 취소하고 정리를 기다린다 (#49/#51)."""
    pending = [t for t in list(_background_tasks) if not t.done()]
    for task in pending:
        task.cancel()
    if pending:
        # 취소 예외를 모두 흡수하며 정리가 끝날 때까지 대기한다.
        await asyncio.gather(*pending, return_exceptions=True)


async def run_bot(settings: AppSettings, bot: commands.Bot | None = None) -> None:
    """SIGTERM/SIGINT 에 반응하는 graceful shutdown 기반 봇 실행 루틴 (#49).

    기존 ``bot.run`` 과 동등하게 봇을 기동하되, 종료 시그널을 받으면 추적 중인
    백그라운드 태스크를 취소하고 ``bot.close()`` 로 깔끔하게 정리한다.
    """
    if bot is None:
        bot = create_bot(settings)

    loop = asyncio.get_running_loop()
    stop_event = asyncio.Event()

    def _request_stop() -> None:
        logger.info("종료 시그널 수신 — graceful shutdown 시작.")
        stop_event.set()

    # 일부 플랫폼(Windows 등)은 loop.add_signal_handler 를 지원하지 않으므로 방어한다.
    for sig in (signal.SIGTERM, signal.SIGINT):
        try:
            loop.add_signal_handler(sig, _request_stop)
        except (NotImplementedError, RuntimeError):  # pragma: no cover — 플랫폼 의존
            pass

    # 봇 시작과 종료 시그널 대기를 동시에 돌린다. 둘 중 하나가 끝나면 정리한다.
    start_task = asyncio.ensure_future(bot.start(settings.discord_bot_token))
    stop_task = asyncio.ensure_future(stop_event.wait())
    try:
        done, _ = await asyncio.wait(
            {start_task, stop_task}, return_when=asyncio.FIRST_COMPLETED
        )
        # start_task 가 예외로 끝났다면 그대로 표면화한다.
        if start_task in done:
            start_task.result()
    finally:
        stop_task.cancel()
        # 백그라운드 추적 태스크(리마인더/모니터 등) 취소 (#51).
        await _cancel_background_tasks()
        if not bot.is_closed():
            await bot.close()
        if not start_task.done():
            start_task.cancel()
        await asyncio.gather(start_task, return_exceptions=True)


def main() -> None:
    settings = AppSettings.from_env()
    bot = create_bot(settings)
    # 기존 ``bot.run`` 동작과 동등하되, SIGTERM/SIGINT 시 graceful shutdown 한다 (#49).
    try:
        asyncio.run(run_bot(settings, bot))
    except KeyboardInterrupt:  # pragma: no cover — Ctrl-C 보조 처리
        logger.info("KeyboardInterrupt — 종료합니다.")
