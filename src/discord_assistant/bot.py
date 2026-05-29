"""discord.py entrypoint and command handlers."""
from __future__ import annotations

import asyncio
import io
import logging
import os
import re
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
from .models import GuildConfig, LLMProvider, UsageLog
from .monitor import format_disconnect_message, format_error_message, notify_developer
from .prompts import (
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
        logger.warning("느린 응답 감지: %s %dms", command, latency_ms)
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

    async def run_summarize(
        interaction: discord.Interaction,
        limit: int | None,
        since: str | None = None,
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
                first, *rest = _split_discord_text(
                    f"**최근 {message_limit}개 메시지 요약** *(캐시)*\n{cached}"
                )
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
    )
    async def summarize_command(
        interaction: discord.Interaction,
        limit: int | None = None,
        since: str | None = None,
    ) -> None:
        await run_summarize(interaction, limit, since)

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

    @config_group.command(name="model", description="서버 기본 Ollama 모델명을 저장합니다.")
    @app_commands.describe(model="예: llama3.1:8b, qwen2.5:7b, gemma2:9b")
    async def config_model(interaction: discord.Interaction, model: str) -> None:
        try:
            guild_id = await require_guild_admin(interaction)
            config = await store.set_model(guild_id, model)
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
            config = await store.set_summary_limit(guild_id, limit)
            await _send_interaction_chunks(
                interaction,
                f"✅ 기본 요약 범위를 최근 {config.summary_limit}개 메시지로 저장했어요.",
                ephemeral=True,
            )
        except (UserFacingError, ValueError) as exc:
            await _send_interaction_chunks(interaction, f"⚠️ {exc}", ephemeral=True)

    @config_group.command(name="language", description="기본 응답 언어를 저장합니다.")
    @app_commands.describe(language="예: ko, en, ja")
    async def config_language(interaction: discord.Interaction, language: str) -> None:
        try:
            guild_id = await require_guild_admin(interaction)
            config = await store.set_language(guild_id, language)
            await _send_interaction_chunks(
                interaction, f"✅ 기본 응답 언어를 `{config.language}`로 저장했어요.", ephemeral=True,
            )
        except (UserFacingError, ValueError) as exc:
            await _send_interaction_chunks(interaction, f"⚠️ {exc}", ephemeral=True)


    # ------------------------------------------------------------------
    # Phase 3 new commands
    # ------------------------------------------------------------------

    # --- #32 /remind --- reminder for last summarize result
    @bot.tree.command(name="remind", description="마지막 /summarize 결과를 N분 후 DM으로 전송합니다.")
    @app_commands.describe(minutes="몇 분 후에 DM으로 받을지 지정합니다. 최대 60분.")
    async def remind_command(interaction: discord.Interaction, minutes: int) -> None:
        if minutes < 1 or minutes > 60:
            await interaction.response.send_message("⚠️ 분은 1~60 사이여야 해요.", ephemeral=True)
            return
        user_id = interaction.user.id if interaction.user else None
        if user_id is None or user_id not in _last_summaries:
            await interaction.response.send_message(
                "⚠️ 최근 /summarize 결과가 없어요. 먼저 /summarize를 실행해 주세요.", ephemeral=True
            )
            return
        summary_text, _ = _last_summaries[user_id]
        await interaction.response.send_message(
            f"⏰ {minutes}분 후에 DM으로 요약 결과를 보내드릴게요!", ephemeral=True
        )

        async def _send_reminder() -> None:
            await asyncio.sleep(minutes * 60)
            try:
                await interaction.user.send(f"⏰ 알림: {minutes}분 전 요약 결과입니다.\n\n{summary_text[:1800]}")
            except discord.Forbidden:
                pass
        asyncio.create_task(_send_reminder())

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

    # --- Phase 3 /config subcommands ---

    @config_group.command(name="admin_role", description="봇 설정 권한을 가진 역할을 지정합니다.")
    @app_commands.describe(role="설정 권한을 부여할 역할입니다.")
    async def config_admin_role(interaction: discord.Interaction, role: discord.Role) -> None:
        try:
            guild_id = await require_guild_admin(interaction)
            await store.set_admin_role(guild_id, role.id)
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
            await store.set_persona(guild_id, persona)
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
            await store.set_auto_summary_interval(guild_id, effective_interval)
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
    async def config_custom_prompt(
        interaction: discord.Interaction, prompt_type: str, text: str = ""
    ) -> None:
        try:
            guild_id = await require_guild_admin(interaction)
            effective_text = text.strip() or None
            if effective_text and len(effective_text) > _MAX_CUSTOM_PROMPT_CHARS:
                raise UserFacingError(f"커스텀 프롬프트는 {_MAX_CUSTOM_PROMPT_CHARS}자 이하여야 합니다.")
            await store.set_custom_prompt(guild_id, prompt_type, effective_text)
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
            await store.set_allowed_role(guild_id, role_id)
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
        asyncio.create_task(_memory_monitor())
        # Start auto-summary background task (#33)
        if not auto_summary_task.is_running():
            auto_summary_task.start()
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
                prompt = build_chat_prompt(message.content, language=config.language, persona=config.persona)
                llm = _get_llm(config, settings)
                async with message.channel.typing():
                    answer = await llm.generate(prompt, model=config.model)
                await _send_channel_chunks(message.channel, answer)
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

            transcript = await _collect_transcript(
                message.channel,
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
                heading = "**최근 대화 요약**\n"

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

    @bot.event
    async def on_disconnect() -> None:
        logger.warning("Bot disconnected from Discord.")
        msg = format_disconnect_message(shard_id=None)
        await notify_developer(msg, bot)

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


def main() -> None:
    settings = AppSettings.from_env()
    bot = create_bot(settings)
    bot.run(settings.discord_bot_token)
