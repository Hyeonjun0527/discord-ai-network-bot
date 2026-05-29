"""discord.py entrypoint and command handlers."""
from __future__ import annotations

import asyncio
from datetime import datetime, timedelta, timezone
import logging
import re
import tempfile
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
    OllamaError,
    OllamaManager,
    OpenAIClient,
)
from .models import GuildConfig, LLMProvider, UsageLog
from .prompts import (
    build_ask_prompt,
    build_chat_prompt,
    build_image_analysis_prompt,
    build_search_result_prompt,
    build_summarize_prompt,
    build_translate_prompt,
    detect_language_from_transcript,
)
from .settings import AppSettings
from .storage import ConfigStore
from .ui import ChannelSelectView, FollowUpView, SettingsView, ViewCtx, settings_embed

logger = logging.getLogger(__name__)

_SLOW_RESPONSE_THRESHOLD_MS = 30_000
MAX_DISCORD_MESSAGE_CHARS = 1900
MAX_EXPORT_BYTES = 8 * 1024 * 1024  # 8 MB Discord file limit

# Reaction emojis for feedback tracking
THUMBS_UP = "\U0001f44d"   # 👍
THUMBS_DOWN = "\U0001f44e"  # 👎

# Message IDs that correspond to bot command results (for reaction tracking)
# guild_id -> {message_id -> command_name}
_tracked_messages: dict[int, dict[int, str]] = {}

# Last summarize results per user (for /remind)
# user_id -> (summary_text, guild_id)
_last_summaries: dict[int, tuple[str, int | None]] = {}

# Auto-summary tracking: guild_id -> last_run_time
_auto_summary_last_run: dict[int, datetime] = {}


class UserFacingError(RuntimeError):
    """Raised for errors that should be shown plainly to Discord users."""


def _parse_since(since_str: str) -> datetime:
    """Parse a duration string like '1h', '30m', '2d' into a UTC datetime in the past.

    Raises UserFacingError on invalid format.
    """
    since_str = since_str.strip().lower()
    match = re.fullmatch(r"(\d+)([mhd])", since_str)
    if not match:
        raise UserFacingError("올바른 형식: 1h, 30m, 2d (숫자 + m/h/d)")
    value = int(match.group(1))
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


def _split_discord_text(text: str, *, max_chars: int = MAX_DISCORD_MESSAGE_CHARS) -> list[str]:
    """Split a long bot response into Discord-safe chunks."""
    text = text.strip() or "(empty response)"
    chunks: list[str] = []
    current = ""
    for line in text.splitlines() or [text]:
        if len(line) > max_chars:
            if current:
                chunks.append(current.rstrip())
                current = ""
            for start in range(0, len(line), max_chars):
                chunks.append(line[start : start + max_chars])
            continue
        candidate = f"{current}\n{line}" if current else line
        if len(candidate) > max_chars:
            chunks.append(current.rstrip())
            current = line
        else:
            current = candidate
    if current:
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
    for chunk in _split_discord_text(text):
        await channel.send(chunk)


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
    )


async def _collect_transcript(
    channel: Any,
    *,
    before: datetime,
    limit: int,
    max_context_chars: int,
) -> str:
    if channel is None or not hasattr(channel, "history"):
        raise UserFacingError("이 명령은 메시지 기록을 읽을 수 있는 채널에서만 사용할 수 있어요.")
    messages = []
    try:
        async for message in channel.history(limit=limit, before=before):
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
        view = SettingsView(ctx=view_ctx, guild_id=interaction.guild.id)
        await interaction.response.send_message(embed=embed, view=view, ephemeral=True)

    # ------------------------------------------------------------------
    # /summarize
    # ------------------------------------------------------------------

    async def run_summarize(interaction: discord.Interaction, limit: int | None) -> None:
        started = perf_counter()
        guild_id, channel_id, user_id = _ids_from_interaction(interaction)
        await interaction.response.defer(thinking=True)
        try:
            config = await store.get_guild_config(guild_id or 0)
            message_limit = _effective_limit(limit, config.summary_limit)
            # Check cache first (only when no explicit limit override)
            cache_key = f"{guild_id}:{channel_id}"
            cached = summarize_cache.get(cache_key) if limit is None else None
            if cached is not None:
                await _send_interaction_chunks(
                    interaction,
                    f"**최근 {message_limit}개 메시지 요약** *(캐시)*\n{cached}",
                )
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
            )
            if not transcript:
                raise UserFacingError("요약할 메시지가 없어요. 채널에 대화가 있어야 합니다.")
            prompt = build_summarize_prompt(transcript, language=config.language)
            llm = _get_llm(config, settings)
            answer = await llm.generate(prompt, model=config.model)
            # Cache the result (only for default limit queries)
            if limit is None:
                summarize_cache.set(cache_key, answer)
            await _send_interaction_chunks(
                interaction,
                f"**최근 {message_limit}개 메시지 요약**\n{answer}",
            )
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
    @app_commands.describe(limit="최근 몇 개 메시지를 읽을지 지정합니다. 기본값은 서버 설정입니다.")
    async def summarize_command(interaction: discord.Interaction, limit: int | None = None) -> None:
        await run_summarize(interaction, limit)

    # ------------------------------------------------------------------
    # /ask
    # ------------------------------------------------------------------

    async def run_ask(interaction: discord.Interaction, question: str, limit: int | None) -> None:
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
                raise UserFacingError("질문에 참고할 최근 메시지가 없어요.")
            prompt = build_ask_prompt(transcript, question, language=config.language)
            llm = _get_llm(config, settings)
            answer = await llm.generate(prompt, model=config.model)
            await _send_interaction_chunks(interaction, f"**질문:** {question}\n\n{answer}")
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
        await interaction.response.defer(thinking=True, ephemeral=True)
        try:
            config = await store.get_guild_config(guild_id or 0)
            prompt = build_translate_prompt(text, target_language=target_language)
            llm = _get_llm(config, settings)
            answer = await llm.generate(prompt, model=config.model)
            await _send_interaction_chunks(interaction, answer, ephemeral=True)
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
    @app_commands.describe(message="AI에게 보낼 메시지입니다.")
    async def chat_command(interaction: discord.Interaction, message: str) -> None:
        started = perf_counter()
        guild_id, channel_id, user_id = _ids_from_interaction(interaction)
        await interaction.response.defer(thinking=True)
        try:
            config = await store.get_guild_config(guild_id or 0)
            prompt = build_chat_prompt(message, language=config.language)
            llm = _get_llm(config, settings)
            answer = await llm.generate(prompt, model=config.model)
            await _send_interaction_chunks(interaction, answer)
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
        embed.set_footer(text="/ask — 채널 대화 기반 Q&A   /chat — 자유 대화")
        await interaction.response.send_message(embed=embed, ephemeral=True)

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
        bot.loop.create_task(_memory_monitor())

    async def _memory_monitor() -> None:
        """Log process memory usage every hour."""
        try:
            import psutil  # type: ignore[import-untyped]
            import os as _os
            while True:
                await asyncio.sleep(3600)
                proc = psutil.Process(_os.getpid())
                mem_mb = proc.memory_info().rss / 1024 / 1024
                logger.info("메모리 사용량: %.1f MB", mem_mb)
        except ImportError:
            logger.debug("psutil not installed; memory monitoring disabled.")

    @bot.event
    async def on_message(message: discord.Message) -> None:
        # Invalidate summarize cache for this channel on every new non-bot message
        if not message.author.bot:
            guild_id_raw = message.guild.id if message.guild else 0
            channel_id_raw = message.channel.id if hasattr(message.channel, "id") else 0
            summarize_cache.invalidate_prefix(f"{guild_id_raw}:{channel_id_raw}")

        await bot.process_commands(message)
        if message.author.bot or bot.user is None or bot.user not in message.mentions:
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

    return bot


def main() -> None:
    settings = AppSettings.from_env()
    bot = create_bot(settings)
    bot.run(settings.discord_bot_token)
