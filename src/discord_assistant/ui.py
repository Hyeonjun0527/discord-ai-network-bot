"""Discord UI components: settings panel, provider selection, model management."""
from __future__ import annotations

import asyncio
import json
from dataclasses import dataclass
from typing import TYPE_CHECKING, Any
from urllib import error as urllib_error
from urllib import request as urllib_request

import discord
from discord import ui

from .crypto import encrypt_api_key
from .llm import (
    AnthropicError,
    CircuitBreakerOpenError,
    GeminiError,
    LLMError,
    OllamaError,
    OllamaManager,
    OpenAIError,
)
from .messages import t
from .models import GuildConfig, LLMProvider, OllamaModel
from .prompts import _LANGUAGE_LABELS
from .prompts import language_label as _language_label_from_prompts
from .storage import ConfigStore

if TYPE_CHECKING:
    from collections.abc import Awaitable, Callable


# ---------------------------------------------------------------------------
# Constants
# ---------------------------------------------------------------------------

OPENAI_MODELS: list[tuple[str, str, str]] = [
    ("gpt-4o",        "GPT-4o",        "최신 · 멀티모달"),
    ("gpt-4o-mini",   "GPT-4o mini",   "빠르고 저렴"),
    ("gpt-4-turbo",   "GPT-4 Turbo",   "고성능"),
    ("gpt-3.5-turbo", "GPT-3.5 Turbo", "경제적"),
]

ANTHROPIC_MODELS: list[tuple[str, str, str]] = [
    ("claude-opus-4-7",           "Claude Opus 4.7",    "최고 성능"),
    ("claude-sonnet-4-6",         "Claude Sonnet 4.6",  "균형"),
    ("claude-haiku-4-5-20251001", "Claude Haiku 4.5",   "빠름 · 경량"),
    ("claude-3-5-sonnet-20241022","Claude 3.5 Sonnet",  "안정 · 검증"),
    ("claude-3-haiku-20240307",   "Claude 3 Haiku",     "경제적"),
]

GEMINI_MODELS: list[tuple[str, str, str]] = [
    ("gemini-1.5-pro",    "Gemini 1.5 Pro",    "고성능 · 긴 컨텍스트"),
    ("gemini-1.5-flash",  "Gemini 1.5 Flash",  "빠름 · 경제적"),
    ("gemini-2.0-flash",  "Gemini 2.0 Flash",  "최신 · 빠름"),
]

PROVIDER_DEFAULT_MODELS = {
    LLMProvider.OLLAMA:    "llama3.1:8b",
    LLMProvider.OPENAI:    "gpt-4o-mini",
    LLMProvider.ANTHROPIC: "claude-3-haiku-20240307",
    LLMProvider.GEMINI:    "gemini-1.5-flash",
}

COLORS = {
    "main":    discord.Color.from_str("#5865F2"),
    "success": discord.Color.green(),
    "warning": discord.Color.yellow(),
    "error":   discord.Color.red(),
    "install": discord.Color.from_str("#57F287"),
}


def _external_models_for(provider: LLMProvider) -> list[tuple[str, str, str]]:
    """외부(API 키 기반) 제공자의 모델 목록을 반환한다 (#15).

    OPENAI/ANTHROPIC/GEMINI 분기를 한곳에서 관리해 호출부 중복을 줄인다.
    Ollama 등 목록이 없는 제공자는 빈 리스트를 돌려준다.
    """
    if provider == LLMProvider.OPENAI:
        return OPENAI_MODELS
    if provider == LLMProvider.ANTHROPIC:
        return ANTHROPIC_MODELS
    if provider == LLMProvider.GEMINI:
        return GEMINI_MODELS
    return []


# ---------------------------------------------------------------------------
# Shared context
# ---------------------------------------------------------------------------


@dataclass(frozen=True)
class ViewCtx:
    store: ConfigStore
    ollama_manager: OllamaManager
    secret_key: str


# ---------------------------------------------------------------------------
# Embed builders
# ---------------------------------------------------------------------------


# 지원 언어 코드 → 사람이 읽는 라벨. prompts._LANGUAGE_LABELS + 'auto' 자동 감지.
# 자유 텍스트 입력 대신 이 목록으로만 언어를 선택하게 해 오타/미지원 코드를 차단한다.
_AUTO_LANGUAGE_LABEL = "자동 감지 (Auto-detect)"


def _language_label(code: str) -> str:
    normalized = code.strip().lower()
    if normalized == "auto":
        return _AUTO_LANGUAGE_LABEL
    return _language_label_from_prompts(code)


def _supported_language_options() -> list[discord.SelectOption]:
    """지원 언어 코드 7개 + 'auto'를 Select 옵션으로 반환한다.

    prompts._LANGUAGE_LABELS 를 단일 출처(SSOT)로 사용해 prompts 쪽에 언어가
    추가되면 자동으로 드롭다운에도 반영되게 한다.
    """
    options = [
        discord.SelectOption(label="🌐  " + _AUTO_LANGUAGE_LABEL, value="auto"),
    ]
    for code, label in _LANGUAGE_LABELS.items():
        options.append(discord.SelectOption(label=label, value=code))
    return options[:25]


def _api_key_status(config: GuildConfig, lang: str = "ko") -> str:
    if config.provider == LLMProvider.OLLAMA:
        return t("settings.api_key.na", lang)
    if config.api_key_encrypted:
        return t("settings.api_key.registered", lang)
    return t("settings.api_key.missing", lang)


def settings_embed(config: GuildConfig, guild_name: str, lang: str = "ko") -> discord.Embed:
    embed = discord.Embed(
        title=t("settings.title", lang),
        description=f"-# {guild_name}",
        color=COLORS["main"],
    )
    embed.add_field(
        name=t("settings.field.provider", lang), value=config.provider.display_name(), inline=True
    )
    embed.add_field(name=t("settings.field.model", lang), value=f"`{config.model}`", inline=True)
    embed.add_field(
        name=t("settings.field.api_key", lang), value=_api_key_status(config, lang), inline=True
    )
    embed.add_field(
        name=t("settings.field.language", lang),
        value=f"{_language_label(config.language)} ({config.language})",
        inline=True,
    )
    embed.add_field(
        name=t("settings.field.summary_limit", lang),
        value=t("settings.summary_limit.value", lang, count=config.summary_limit),
        inline=True,
    )
    embed.add_field(name="​", value="​", inline=True)
    embed.set_footer(text=t("settings.footer", lang))
    return embed


def _provider_embed(current: LLMProvider, lang: str = "ko") -> discord.Embed:
    embed = discord.Embed(
        title=t("provider.title", lang),
        description=t("provider.description", lang),
        color=COLORS["main"],
    )
    embed.add_field(
        name=t("provider.field.current", lang), value=current.display_name(), inline=False
    )
    embed.set_footer(text=t("provider.footer", lang))
    return embed


def _ollama_model_embed(installed: list[OllamaModel], current_model: str) -> discord.Embed:
    embed = discord.Embed(
        title="📦  Ollama 모델 관리",
        color=COLORS["main"],
    )
    if installed:
        lines = []
        for m in installed:
            mark = "✅" if m.name == current_model else "◦"
            lines.append(f"{mark}  `{m.name}` ({m.size_display()})")
        embed.add_field(name=f"설치된 모델 ({len(installed)}개)", value="\n".join(lines), inline=False)
    else:
        embed.description = "⚠️  Ollama에 설치된 모델이 없습니다. 새 모델을 설치해 주세요."
    embed.add_field(name="현재 사용 중", value=f"`{current_model}`", inline=False)
    return embed


def _install_embed(model_name: str, status: str) -> discord.Embed:
    embed = discord.Embed(
        title="⬇️  모델 설치",
        description=f"`{model_name}`",
        color=COLORS["install"],
    )
    embed.add_field(name="상태", value=status, inline=False)
    return embed


def _external_model_embed(
    provider: LLMProvider, current_model: str, has_key: bool, lang: str = "ko"
) -> discord.Embed:
    embed = discord.Embed(
        title=t("external.title", lang, emoji=provider.emoji(), provider=provider.display_name()),
        color=COLORS["main"],
    )
    embed.add_field(
        name=t("external.field.current_model", lang), value=f"`{current_model}`", inline=True
    )
    embed.add_field(
        name=t("settings.field.api_key", lang),
        value=t("external.api_key.registered", lang)
        if has_key
        else t("external.api_key.missing", lang),
        inline=True,
    )
    if not has_key:
        embed.color = COLORS["warning"]
    return embed


def _general_settings_embed(config: GuildConfig, lang: str = "ko") -> discord.Embed:
    embed = discord.Embed(title=t("general.title", lang), color=COLORS["main"])
    embed.add_field(
        name=t("general.field.language", lang),
        value=f"{_language_label(config.language)} (`{config.language}`)",
        inline=True,
    )
    embed.add_field(
        name=t("general.field.summary_limit", lang),
        value=t("settings.summary_limit.value", lang, count=config.summary_limit),
        inline=True,
    )
    embed.set_footer(text=t("general.footer", lang))
    return embed


def _language_select_embed(config: GuildConfig, lang: str = "ko") -> discord.Embed:
    embed = discord.Embed(
        title=t("language_select.title", lang),
        description=t("language_select.description", lang),
        color=COLORS["main"],
    )
    embed.add_field(
        name=t("language_select.field.current", lang),
        value=f"{_language_label(config.language)} (`{config.language}`)",
        inline=False,
    )
    embed.set_footer(text=t("language_select.footer", lang))
    return embed


# ---------------------------------------------------------------------------
# API key validation helpers
# ---------------------------------------------------------------------------


def _validate_openai_key(api_key: str) -> bool:
    """Return True if the OpenAI key passes a basic API check."""
    req = urllib_request.Request(
        "https://api.openai.com/v1/models",
        headers={"Authorization": f"Bearer {api_key}"},
        method="GET",
    )
    try:
        with urllib_request.urlopen(req, timeout=10) as resp:  # noqa: S310
            return bool(resp.status == 200)
    except urllib_error.HTTPError as exc:
        return exc.code not in (401, 403)
    except Exception:
        return False


def _validate_anthropic_key(api_key: str) -> bool:
    """Return True if the Anthropic key passes a basic API check."""
    body = json.dumps(
        {
            "model": "claude-3-haiku-20240307",
            "max_tokens": 1,
            "messages": [{"role": "user", "content": "hi"}],
        }
    ).encode("utf-8")
    req = urllib_request.Request(
        "https://api.anthropic.com/v1/messages",
        data=body,
        headers={
            "Content-Type": "application/json",
            "x-api-key": api_key,
            "anthropic-version": "2023-06-01",
        },
        method="POST",
    )
    try:
        with urllib_request.urlopen(req, timeout=10) as resp:  # noqa: S310
            return resp.status in (200, 201)
    except urllib_error.HTTPError as exc:
        return exc.code not in (401, 403)
    except Exception:
        return False


def _validate_gemini_key(api_key: str) -> bool:
    """Return True if the Gemini key passes a basic API check (#15).

    가벼운 ``GET /v1beta/models`` 호출로 키 유효성만 확인한다. 키는 보안상 URL
    쿼리가 아니라 ``x-goog-api-key`` 헤더로 전달한다(노출 방지). 401/403 은
    무효 키로 간주하고, 그 외 응답/오류는 (네트워크 문제 등) 통과시켜 등록 자체를
    막지 않는다(OpenAI/Anthropic 검증과 동일한 관대 정책).
    """
    req = urllib_request.Request(
        "https://generativelanguage.googleapis.com/v1beta/models",
        headers={"x-goog-api-key": api_key},
        method="GET",
    )
    try:
        with urllib_request.urlopen(req, timeout=10) as resp:  # noqa: S310
            return bool(resp.status == 200)
    except urllib_error.HTTPError as exc:
        return exc.code not in (401, 403)
    except Exception:
        return False


# ---------------------------------------------------------------------------
# Modals
# ---------------------------------------------------------------------------


class _APIKeyModal(ui.Modal, title="🔑  API 키 등록"):
    api_key_input: ui.TextInput = ui.TextInput(
        label="API 키",
        placeholder="sk-...  /  sk-ant-...  /  AIza...",
        style=discord.TextStyle.short,
        required=True,
        max_length=300,
    )

    def __init__(
        self,
        provider: LLMProvider,
        model: str,
        ctx: ViewCtx,
        guild_id: int,
        timeout: float = 300,
    ) -> None:
        super().__init__(timeout=timeout)
        self.provider = provider
        self.model = model
        self.ctx = ctx
        self.guild_id = guild_id

    async def on_submit(self, interaction: discord.Interaction) -> None:
        raw_key = self.api_key_input.value.strip()
        if not raw_key:
            await interaction.response.send_message("⚠️ API 키를 입력해 주세요.", ephemeral=True)
            return

        # Validate the key before saving
        await interaction.response.defer(ephemeral=True, thinking=True)
        is_valid = await asyncio.to_thread(self._validate_key, raw_key)
        if not is_valid:
            await interaction.followup.send("⚠️ 유효하지 않은 API 키입니다.", ephemeral=True)
            return

        encrypted = encrypt_api_key(raw_key, self.ctx.secret_key)
        config = await self.ctx.store.set_provider_config(
            self.guild_id,
            provider=self.provider,
            model=self.model,
            api_key_encrypted=encrypted,
        )
        embed = settings_embed(
            config, interaction.guild.name if interaction.guild else "서버", config.language
        )
        view = SettingsView(ctx=self.ctx, guild_id=self.guild_id, provider=config.provider)
        await interaction.edit_original_response(embed=embed, view=view)

    def _validate_key(self, raw_key: str) -> bool:
        if self.provider == LLMProvider.OPENAI:
            return _validate_openai_key(raw_key)
        if self.provider == LLMProvider.ANTHROPIC:
            return _validate_anthropic_key(raw_key)
        if self.provider == LLMProvider.GEMINI:
            return _validate_gemini_key(raw_key)
        return True

    async def on_error(self, interaction: discord.Interaction, error: Exception) -> None:  # type: ignore[override]
        await interaction.response.send_message(f"⚠️ 오류: {error}", ephemeral=True)


class _SummaryLimitModal(ui.Modal, title="📊  요약 범위 변경"):
    limit_input: ui.TextInput = ui.TextInput(
        label="메시지 수 (1 ~ 200)",
        placeholder="50",
        style=discord.TextStyle.short,
        required=True,
        max_length=3,
    )

    def __init__(self, ctx: ViewCtx, guild_id: int) -> None:
        super().__init__()
        self.ctx = ctx
        self.guild_id = guild_id

    async def on_submit(self, interaction: discord.Interaction) -> None:
        try:
            limit = int(self.limit_input.value.strip())
        except ValueError:
            await interaction.response.send_message("⚠️ 숫자를 입력해 주세요.", ephemeral=True)
            return
        try:
            config = await self.ctx.store.set_summary_limit(self.guild_id, limit)
        except ValueError as exc:
            await interaction.response.send_message(f"⚠️ {exc}", ephemeral=True)
            return
        embed = _general_settings_embed(config, config.language)
        view = GeneralSettingsView(ctx=self.ctx, guild_id=self.guild_id)
        await interaction.response.edit_message(embed=embed, view=view)


class _CustomModelModal(ui.Modal, title="📦  모델 직접 입력"):
    model_input: ui.TextInput = ui.TextInput(
        label="Ollama 모델명",
        placeholder="llama3.1:8b  /  qwen2.5:14b  …",
        style=discord.TextStyle.short,
        required=True,
        max_length=100,
    )

    def __init__(self, ctx: ViewCtx, guild_id: int) -> None:
        super().__init__()
        self.ctx = ctx
        self.guild_id = guild_id

    async def on_submit(self, interaction: discord.Interaction) -> None:
        model_name = self.model_input.value.strip()
        if not model_name:
            await interaction.response.send_message("⚠️ 모델명을 입력해 주세요.", ephemeral=True)
            return
        await interaction.response.defer()
        view = ModelInstallView(ctx=self.ctx, guild_id=self.guild_id, model_name=model_name)
        await interaction.edit_original_response(
            embed=_install_embed(model_name, "⏳ 설치 준비 중..."),
            view=view,
        )
        asyncio.create_task(view.run_install(interaction))


# ---------------------------------------------------------------------------
# Views
# ---------------------------------------------------------------------------


class SettingsView(ui.View):
    """Main settings panel — shown by /settings command."""

    def __init__(self, *, ctx: ViewCtx, guild_id: int, provider: LLMProvider | None = None) -> None:
        super().__init__(timeout=300)
        self.ctx = ctx
        self.guild_id = guild_id

        # Determine model button label dynamically based on provider
        _external = (LLMProvider.OPENAI, LLMProvider.ANTHROPIC, LLMProvider.GEMINI)
        model_btn_label = "모델 선택" if provider in _external else "모델 관리"

        change_provider_btn: ui.Button[Any] = ui.Button(
            label="제공자 변경", style=discord.ButtonStyle.primary, row=0
        )
        change_provider_btn.callback = self._change_provider  # type: ignore[method-assign]
        self.add_item(change_provider_btn)

        manage_models_btn: ui.Button[Any] = ui.Button(
            label=model_btn_label, style=discord.ButtonStyle.secondary, row=0
        )
        manage_models_btn.callback = self._manage_models  # type: ignore[method-assign]
        self.add_item(manage_models_btn)

        general_settings_btn: ui.Button[Any] = ui.Button(
            label="일반 설정", style=discord.ButtonStyle.secondary, row=0
        )
        general_settings_btn.callback = self._general_settings  # type: ignore[method-assign]
        self.add_item(general_settings_btn)

    async def _change_provider(self, interaction: discord.Interaction) -> None:
        config = await self.ctx.store.get_guild_config(self.guild_id)
        embed = _provider_embed(config.provider, config.language)
        view = ProviderView(ctx=self.ctx, guild_id=self.guild_id)
        await interaction.response.edit_message(embed=embed, view=view)

    async def _manage_models(self, interaction: discord.Interaction) -> None:
        config = await self.ctx.store.get_guild_config(self.guild_id)
        await interaction.response.defer()
        if config.provider == LLMProvider.OLLAMA:
            installed = await self.ctx.ollama_manager.list_models()
            embed = _ollama_model_embed(installed, config.model)
            view: ui.View = OllamaModelView(ctx=self.ctx, guild_id=self.guild_id, installed=installed)
        else:
            models = _external_models_for(config.provider)
            embed = _external_model_embed(
                config.provider, config.model, bool(config.api_key_encrypted), config.language
            )
            view = ExternalModelView(ctx=self.ctx, guild_id=self.guild_id, provider=config.provider, models=models)
        await interaction.edit_original_response(embed=embed, view=view)

    async def _general_settings(self, interaction: discord.Interaction) -> None:
        config = await self.ctx.store.get_guild_config(self.guild_id)
        embed = _general_settings_embed(config, config.language)
        view = GeneralSettingsView(ctx=self.ctx, guild_id=self.guild_id)
        await interaction.response.edit_message(embed=embed, view=view)

    async def on_timeout(self) -> None:
        for item in self.children:
            item.disabled = True  # type: ignore[attr-defined]


class ProviderView(ui.View):
    """Provider selection: Ollama / OpenAI / Anthropic."""

    def __init__(self, *, ctx: ViewCtx, guild_id: int) -> None:
        super().__init__(timeout=120)
        self.ctx = ctx
        self.guild_id = guild_id

        select: ui.Select[Any] = ui.Select(
            placeholder="AI 제공자를 선택하세요",
            options=[
                discord.SelectOption(
                    label="🖥️  Ollama (로컬)",
                    value="ollama",
                    description="인터넷 없이 로컬 PC에서 실행",
                ),
                discord.SelectOption(
                    label="🤖  OpenAI (GPT)",
                    value="openai",
                    description="ChatGPT API 키 필요",
                ),
                discord.SelectOption(
                    label="🧠  Anthropic (Claude)",
                    value="anthropic",
                    description="Claude API 키 필요",
                ),
                discord.SelectOption(
                    label="✨  Google (Gemini)",
                    value="gemini",
                    description="Gemini API 키 필요",
                ),
            ],
            row=0,
        )
        select.callback = self._on_select  # type: ignore[method-assign]
        self.add_item(select)
        self.add_item(_BackButton(ctx=ctx, guild_id=guild_id, row=1))

    async def _on_select(self, interaction: discord.Interaction) -> None:
        selected = LLMProvider(interaction.data["values"][0])  # type: ignore[index,typeddict-item]

        if selected == LLMProvider.OLLAMA:
            installed = await self.ctx.ollama_manager.list_models()
            default_model = installed[0].name if installed else PROVIDER_DEFAULT_MODELS[selected]
            config = await self.ctx.store.set_provider_config(
                self.guild_id,
                provider=selected,
                model=default_model,
                api_key_encrypted=None,
            )
            embed = settings_embed(
                config, interaction.guild.name if interaction.guild else "서버", config.language
            )
            await interaction.response.edit_message(embed=embed, view=SettingsView(ctx=self.ctx, guild_id=self.guild_id, provider=selected))

        else:
            models = _external_models_for(selected)
            current = await self.ctx.store.get_guild_config(self.guild_id)
            default_model = PROVIDER_DEFAULT_MODELS[selected]
            embed = _external_model_embed(
                selected, default_model, bool(current.api_key_encrypted), current.language
            )
            view = ExternalModelView(ctx=self.ctx, guild_id=self.guild_id, provider=selected, models=models)
            await interaction.response.edit_message(embed=embed, view=view)


class ExternalModelView(ui.View):
    """Model selector + API key button for OpenAI or Anthropic."""

    def __init__(
        self,
        *,
        ctx: ViewCtx,
        guild_id: int,
        provider: LLMProvider,
        models: list[tuple[str, str, str]],
    ) -> None:
        super().__init__(timeout=120)
        self.ctx = ctx
        self.guild_id = guild_id
        self.provider = provider
        self._selected_model: str = models[0][0]

        options = [
            discord.SelectOption(label=name, value=model_id, description=desc)
            for model_id, name, desc in models[:25]
        ]
        select: ui.Select[Any] = ui.Select(placeholder="모델을 선택하세요", options=options, row=0)
        select.callback = self._on_model_select  # type: ignore[method-assign]
        self.add_item(select)
        self.add_item(_BackButton(ctx=ctx, guild_id=guild_id, row=2))

    async def _on_model_select(self, interaction: discord.Interaction) -> None:
        self._selected_model = interaction.data["values"][0]  # type: ignore[index,typeddict-item]
        config = await self.ctx.store.get_guild_config(self.guild_id)
        updated = await self.ctx.store.set_provider_config(
            self.guild_id,
            provider=self.provider,
            model=self._selected_model,
            api_key_encrypted=config.api_key_encrypted,
        )
        embed = _external_model_embed(
            self.provider, self._selected_model, bool(updated.api_key_encrypted), updated.language
        )
        await interaction.response.edit_message(embed=embed, view=self)

    @ui.button(label="API 키 등록 / 변경", style=discord.ButtonStyle.success, row=1)
    async def enter_api_key(self, interaction: discord.Interaction, button: ui.Button) -> None:
        modal = _APIKeyModal(
            provider=self.provider,
            model=self._selected_model,
            ctx=self.ctx,
            guild_id=self.guild_id,
        )
        await interaction.response.send_modal(modal)

    @ui.button(label="API 키 삭제", style=discord.ButtonStyle.danger, row=1)
    async def clear_api_key(self, interaction: discord.Interaction, button: ui.Button) -> None:
        config = await self.ctx.store.clear_api_key(self.guild_id)
        embed = _external_model_embed(self.provider, config.model, False, config.language)
        await interaction.response.edit_message(embed=embed, view=self)


class OllamaModelView(ui.View):
    """Shows installed Ollama models and install option."""

    def __init__(
        self,
        *,
        ctx: ViewCtx,
        guild_id: int,
        installed: list[OllamaModel],
    ) -> None:
        super().__init__(timeout=120)
        self.ctx = ctx
        self.guild_id = guild_id
        self.installed = installed
        self._selected: str | None = installed[0].name if installed else None

        if installed:
            options = [
                discord.SelectOption(
                    label=m.name[:100],
                    value=m.name[:100],
                    description=m.size_display(),
                )
                for m in installed[:25]
            ]
            select: ui.Select[Any] = ui.Select(placeholder="사용할 모델을 선택하세요", options=options, row=0)
            select.callback = self._on_select  # type: ignore[method-assign]
            self.add_item(select)

        self.add_item(_BackButton(ctx=ctx, guild_id=guild_id, row=2))

    async def _on_select(self, interaction: discord.Interaction) -> None:
        self._selected = interaction.data["values"][0]  # type: ignore[index,typeddict-item]
        config = await self.ctx.store.get_guild_config(self.guild_id)
        embed = _ollama_model_embed(self.installed, config.model)
        await interaction.response.edit_message(embed=embed, view=self)

    @ui.button(label="이 모델 사용", style=discord.ButtonStyle.success, row=1)
    async def use_selected(self, interaction: discord.Interaction, button: ui.Button) -> None:
        if not self._selected:
            await interaction.response.send_message("⚠️ 먼저 모델을 선택하세요.", ephemeral=True)
            return
        config = await self.ctx.store.set_model(self.guild_id, self._selected)
        embed = settings_embed(
            config, interaction.guild.name if interaction.guild else "서버", config.language
        )
        await interaction.response.edit_message(embed=embed, view=SettingsView(ctx=self.ctx, guild_id=self.guild_id, provider=config.provider))

    @ui.button(label="새 모델 설치", style=discord.ButtonStyle.primary, row=1)
    async def install_new(self, interaction: discord.Interaction, button: ui.Button) -> None:
        installed_names = {m.name for m in self.installed}
        view = ModelInstallView(ctx=self.ctx, guild_id=self.guild_id, installed_names=installed_names)
        embed = discord.Embed(
            title="⬇️  새 모델 설치",
            description="설치할 모델을 선택하거나 직접 이름을 입력하세요.",
            color=COLORS["main"],
        )
        await interaction.response.edit_message(embed=embed, view=view)


class ModelInstallView(ui.View):
    """Popular-model picker + custom model input + install runner."""

    def __init__(
        self,
        *,
        ctx: ViewCtx,
        guild_id: int,
        installed_names: set[str] | None = None,
        model_name: str | None = None,
    ) -> None:
        super().__init__(timeout=1800)  # 30 min — downloads take time
        self.ctx = ctx
        self.guild_id = guild_id
        self._selected: str | None = model_name
        installed_names = installed_names or set()

        options = []
        for model_id, label, desc in OllamaManager.POPULAR_MODELS:
            suffix = " ✅" if model_id in installed_names else ""
            options.append(
                discord.SelectOption(
                    label=f"{label}{suffix}"[:100],
                    value=model_id,
                    description=desc,
                )
            )

        select: ui.Select[Any] = ui.Select(placeholder="설치할 모델을 선택하세요", options=options[:25], row=0)
        select.callback = self._on_select  # type: ignore[method-assign]
        self.add_item(select)
        self.add_item(_BackButton(ctx=ctx, guild_id=guild_id, row=2))

    async def _on_select(self, interaction: discord.Interaction) -> None:
        self._selected = interaction.data["values"][0]  # type: ignore[index,typeddict-item]
        embed = discord.Embed(
            title="⬇️  새 모델 설치",
            description=f"선택: `{self._selected}`\n\n아래 **설치 시작** 버튼을 누르면 다운로드가 시작됩니다.",
            color=COLORS["main"],
        )
        await interaction.response.edit_message(embed=embed, view=self)

    @ui.button(label="직접 입력", style=discord.ButtonStyle.secondary, row=1)
    async def custom_input(self, interaction: discord.Interaction, button: ui.Button) -> None:
        await interaction.response.send_modal(_CustomModelModal(ctx=self.ctx, guild_id=self.guild_id))

    @ui.button(label="설치 시작", style=discord.ButtonStyle.success, row=1)
    async def start_install(self, interaction: discord.Interaction, button: ui.Button) -> None:
        if not self._selected:
            await interaction.response.send_message("⚠️ 먼저 모델을 선택하세요.", ephemeral=True)
            return
        await interaction.response.defer()
        await interaction.edit_original_response(
            embed=_install_embed(self._selected, "⏳ 설치 시작 중..."),
            view=None,
        )
        asyncio.create_task(self.run_install(interaction))

    async def run_install(self, interaction: discord.Interaction) -> None:
        """Background task: pull model, update Discord message with spinner."""
        assert self._selected is not None
        model_name = self._selected
        spinner = ["⣾", "⣽", "⣻", "⢿", "⡿", "⣟", "⣯", "⣷"]
        i = 0

        pull_task = asyncio.create_task(self.ctx.ollama_manager.pull_model(model_name))

        while not pull_task.done():
            await asyncio.sleep(6)
            if pull_task.done():
                break
            try:
                await interaction.edit_original_response(
                    embed=_install_embed(model_name, f"{spinner[i % len(spinner)]}  다운로드 중..."),
                )
            except Exception:
                pass
            i += 1

        try:
            config = await self.ctx.store.set_model(self.guild_id, model_name)
            _ = config  # provider info available if needed
            await interaction.edit_original_response(
                embed=_install_embed(model_name, "✅  설치 완료! 현재 모델로 설정됐습니다."),
                view=_BackOnlyView(ctx=self.ctx, guild_id=self.guild_id),
            )
        except OllamaError as exc:
            await interaction.edit_original_response(
                embed=_install_embed(model_name, f"❌  설치 실패\n```{exc}```"),
                view=_BackOnlyView(ctx=self.ctx, guild_id=self.guild_id),
            )
        except Exception as exc:
            await interaction.edit_original_response(
                embed=_install_embed(model_name, f"❌  알 수 없는 오류\n```{exc}```"),
                view=_BackOnlyView(ctx=self.ctx, guild_id=self.guild_id),
            )


class HelpView(ui.View):
    """Section navigation for /help command.

    #87: 길드 언어(``lang``)를 받아 임베드 텍스트와 버튼 라벨을 현지화한다. 기본값은
    'ko' 로 두어 기존 호출부/테스트(한국어 단언)와 100% 호환된다. 섹션 임베드 빌더는
    정적 메서드를 유지하되 ``lang`` 인자를 받도록 확장했다(기본 'ko'). 버튼은 라벨을
    동적으로 지정해야 하므로 ``@ui.button`` 데코레이터 대신 ``add_item`` 으로 구성한다.
    """

    _HELP_COLOR = discord.Color.from_str("#5865F2")

    def __init__(self, lang: str = "ko") -> None:
        super().__init__(timeout=180)
        self.lang = lang

        ai_btn: ui.Button[Any] = ui.Button(
            label=t("help.button.ai", lang), style=discord.ButtonStyle.primary, row=0
        )
        ai_btn.callback = self.show_ai  # type: ignore[method-assign]
        self.add_item(ai_btn)

        analysis_btn: ui.Button[Any] = ui.Button(
            label=t("help.button.analysis", lang), style=discord.ButtonStyle.primary, row=0
        )
        analysis_btn.callback = self.show_analysis  # type: ignore[method-assign]
        self.add_item(analysis_btn)

        settings_btn: ui.Button[Any] = ui.Button(
            label=t("help.button.settings", lang), style=discord.ButtonStyle.primary, row=0
        )
        settings_btn.callback = self.show_settings  # type: ignore[method-assign]
        self.add_item(settings_btn)

        close_btn: ui.Button[Any] = ui.Button(
            label=t("help.button.close", lang), style=discord.ButtonStyle.danger, row=0
        )
        close_btn.callback = self.close_help  # type: ignore[method-assign]
        self.add_item(close_btn)

    @staticmethod
    def main_embed(lang: str = "ko") -> discord.Embed:
        embed = discord.Embed(
            title=t("help.title", lang),
            color=HelpView._HELP_COLOR,
        )
        embed.add_field(name="/summarize", value=t("help.field.summarize.value", lang), inline=False)
        embed.add_field(name="/ask", value=t("help.field.ask.value", lang), inline=False)
        embed.add_field(name="/chat", value=t("help.field.chat.value", lang), inline=False)
        embed.add_field(name="/translate", value=t("help.field.translate.value", lang), inline=False)
        embed.add_field(name="@ 멘션", value=t("help.field.mention.value", lang), inline=False)
        embed.add_field(
            name=t("help.field.settings.name", lang),
            value=t("help.field.settings.value", lang),
            inline=False,
        )
        embed.set_footer(text=t("help.footer", lang))
        return embed

    @staticmethod
    def ai_embed(lang: str = "ko") -> discord.Embed:
        embed = discord.Embed(
            title=t("help.section.ai.title", lang),
            color=HelpView._HELP_COLOR,
        )
        embed.add_field(
            name="/chat",
            value=(
                "채널 맥락 없이 AI에게 자유롭게 질문합니다.\n"
                "최근 5턴의 대화 기록을 기억합니다.\n"
                "```\n/chat message:파이썬 리스트 컴프리헨션 설명해줘\n"
                "/chat message:영어 이메일 초안 작성해줘\n"
                "/chat message:... public:True  (공개 응답)\n```"
            ) if lang == "ko" else (
                "Chat freely with the AI without channel context.\n"
                "Remembers the last 5 conversation turns.\n"
                "```\n/chat message:Explain Python list comprehensions\n"
                "/chat message:Draft an email in English\n"
                "/chat message:... public:True  (public reply)\n```"
            ),
            inline=False,
        )
        embed.add_field(name="/translate", value=t("help.field.translate.value", lang), inline=False)
        embed.add_field(
            name="@ 멘션",
            value=(
                "봇을 멘션하면 채널 대화를 요약합니다.\n"
                "멘션 뒤에 질문을 쓰면 `/ask` 처럼 동작합니다.\n"
                "```\n@ai-assistant\n@ai-assistant 어제 무슨 얘기 했어?\n```"
            ) if lang == "ko" else (
                "Mention the bot to summarize the channel conversation.\n"
                "Add a question after the mention to act like `/ask`.\n"
                "```\n@ai-assistant\n@ai-assistant What did we talk about yesterday?\n```"
            ),
            inline=False,
        )
        return embed

    @staticmethod
    def analysis_embed(lang: str = "ko") -> discord.Embed:
        embed = discord.Embed(
            title=t("help.section.analysis.title", lang),
            color=HelpView._HELP_COLOR,
        )
        embed.add_field(
            name="/summarize",
            value=(
                "채널의 최근 대화를 섹션별로 요약합니다.\n"
                "핵심 요약 · 결정/합의 · 액션 아이템 · 놓치면 안 되는 맥락\n"
                "```\n/summarize\n/summarize limit:100\n```"
            ) if lang == "ko" else (
                "Summarizes the channel's recent conversation by section.\n"
                "Key points · decisions/agreements · action items · context\n"
                "```\n/summarize\n/summarize limit:100\n```"
            ),
            inline=False,
        )
        embed.add_field(
            name="/ask",
            value=(
                "채널의 최근 대화에서 근거를 찾아 질문에 답합니다.\n"
                "대화 내용에 없는 내용은 답하지 않습니다.\n"
                "```\n/ask question:오늘 회의 결론이 뭐야?\n"
                "/ask question:누가 담당자야? limit:100\n```"
            ) if lang == "ko" else (
                "Answers questions using evidence from the recent conversation.\n"
                "Won't answer anything not present in the conversation.\n"
                "```\n/ask question:What was today's meeting conclusion?\n"
                "/ask question:Who is the owner? limit:100\n```"
            ),
            inline=False,
        )
        return embed

    @staticmethod
    def settings_section_embed(lang: str = "ko") -> discord.Embed:
        embed = discord.Embed(
            title=t("help.section.settings.title", lang),
            color=HelpView._HELP_COLOR,
        )
        embed.add_field(
            name=t("help.field.settings.name", lang),
            value=(
                "AI 제공자 변경 — Ollama(로컬) / OpenAI / Anthropic\n"
                "모델 선택 및 관리\n"
                "응답 언어 변경\n"
                "요약 범위 설정"
            ) if lang == "ko" else (
                "Change AI provider — Ollama (local) / OpenAI / Anthropic\n"
                "Select and manage models\n"
                "Change response language\n"
                "Set summary range"
            ),
            inline=False,
        )
        embed.add_field(
            name="지원 언어 코드" if lang == "ko" else "Supported language codes",
            value="ko · en · ja · zh · fr · de · es",
            inline=False,
        )
        return embed

    async def show_ai(self, interaction: discord.Interaction) -> None:
        await interaction.response.edit_message(embed=self.ai_embed(self.lang), view=self)

    async def show_analysis(self, interaction: discord.Interaction) -> None:
        await interaction.response.edit_message(embed=self.analysis_embed(self.lang), view=self)

    async def show_settings(self, interaction: discord.Interaction) -> None:
        await interaction.response.edit_message(
            embed=self.settings_section_embed(self.lang), view=self
        )

    async def close_help(self, interaction: discord.Interaction) -> None:
        await interaction.response.edit_message(
            embed=discord.Embed(
                title=t("help.closed.title", self.lang),
                description=t("help.closed.description", self.lang),
                color=COLORS["main"],
            ),
            view=None,
        )


class LongResponseView(ui.View):
    """Provides a 'View full response via DM' button for long responses."""

    def __init__(self, full_text: str) -> None:
        super().__init__(timeout=300)
        self.full_text = full_text

    @ui.button(label="전체 응답 보기 (DM으로 받기)", style=discord.ButtonStyle.secondary, row=0)
    async def send_dm(self, interaction: discord.Interaction, button: ui.Button) -> None:
        try:
            chunks = [self.full_text[i:i + 1900] for i in range(0, len(self.full_text), 1900)]
            await interaction.user.send(chunks[0])
            for chunk in chunks[1:]:
                await interaction.user.send(chunk)
            await interaction.response.send_message("✅ DM으로 전체 응답을 전송했어요.", ephemeral=True)
        except discord.Forbidden:
            await interaction.response.send_message(
                "⚠️ DM을 보낼 수 없어요. 개인 메시지 설정을 확인해 주세요.", ephemeral=True
            )
        except discord.HTTPException as exc:
            await interaction.response.send_message(
                f"⚠️ DM 전송 중 오류가 발생했어요. ({exc.status})", ephemeral=True
            )


class SummarizeResultView(ui.View):
    """Actions shown below a /summarize result."""

    def __init__(
        self,
        *,
        store: "ConfigStore",
        guild_id: int,
        channel: discord.abc.Messageable,
        message_limit: int,
        max_context_chars: int,
        language: str,
        llm_callback: "Any",
    ) -> None:
        super().__init__(timeout=300)
        self.store = store
        self.guild_id = guild_id
        self.channel = channel
        self.message_limit = message_limit
        self.max_context_chars = max_context_chars
        self.language = language
        self.llm_callback = llm_callback

    @ui.button(label="다시 요약하기", style=discord.ButtonStyle.secondary, row=0)
    async def re_summarize(self, interaction: discord.Interaction, button: ui.Button) -> None:
        await interaction.response.defer(thinking=True)
        await self.llm_callback(interaction)


class GeneralSettingsView(ui.View):
    """Language and summary-limit settings."""

    def __init__(self, *, ctx: ViewCtx, guild_id: int) -> None:
        super().__init__(timeout=120)
        self.ctx = ctx
        self.guild_id = guild_id

    @ui.button(label="언어 변경", style=discord.ButtonStyle.primary, row=0)
    async def change_language(self, interaction: discord.Interaction, button: ui.Button) -> None:
        # 자유 텍스트 모달 대신 지원 언어 Select 드롭다운 뷰를 연다(오타/미지원 코드 차단).
        config = await self.ctx.store.get_guild_config(self.guild_id)
        embed = _language_select_embed(config)
        view = LanguageSelectView(ctx=self.ctx, guild_id=self.guild_id, current=config.language)
        await interaction.response.edit_message(embed=embed, view=view)

    @ui.button(label="요약 범위 변경", style=discord.ButtonStyle.primary, row=0)
    async def change_limit(self, interaction: discord.Interaction, button: ui.Button) -> None:
        await interaction.response.send_modal(_SummaryLimitModal(ctx=self.ctx, guild_id=self.guild_id))

    @ui.button(label="← 뒤로", style=discord.ButtonStyle.secondary, row=1)
    async def go_back(self, interaction: discord.Interaction, button: ui.Button) -> None:
        await _go_to_main(interaction, ctx=self.ctx, guild_id=self.guild_id)


class LanguageSelectView(ui.View):
    """지원 언어 Select 드롭다운(#89).

    자유 텍스트 입력을 대체해 미지원/오타 언어 코드 입력을 원천 차단한다.
    옵션은 prompts._LANGUAGE_LABELS 7개 + 'auto' 자동 감지로 구성된다.
    선택 즉시 store.set_language 를 호출하고 일반 설정 화면으로 돌아간다.
    """

    def __init__(self, *, ctx: ViewCtx, guild_id: int, current: str | None = None) -> None:
        super().__init__(timeout=120)
        self.ctx = ctx
        self.guild_id = guild_id

        options = _supported_language_options()
        # 현재 설정된 언어를 기본 선택값으로 표시(지원 목록에 있을 때만).
        current_code = (current or "").strip().lower()
        for opt in options:
            if opt.value == current_code:
                opt.default = True
                break

        select: ui.Select[Any] = ui.Select(
            placeholder="응답 언어를 선택하세요",
            options=options,
            min_values=1,
            max_values=1,
            row=0,
        )
        select.callback = self._on_select  # type: ignore[method-assign]
        self.add_item(select)
        self.add_item(_BackButton(ctx=ctx, guild_id=guild_id, row=1))

    async def _on_select(self, interaction: discord.Interaction) -> None:
        lang = interaction.data["values"][0]  # type: ignore[index,typeddict-item]
        config = await self.ctx.store.set_language(self.guild_id, lang)
        embed = _general_settings_embed(config, config.language)
        view = GeneralSettingsView(ctx=self.ctx, guild_id=self.guild_id)
        await interaction.response.edit_message(embed=embed, view=view)

    async def on_timeout(self) -> None:
        for item in self.children:
            item.disabled = True  # type: ignore[attr-defined]


# ---------------------------------------------------------------------------
# Helpers
# ---------------------------------------------------------------------------


class _BackButton(ui.Button):
    def __init__(self, *, ctx: ViewCtx, guild_id: int, row: int = 1) -> None:
        super().__init__(label="← 뒤로", style=discord.ButtonStyle.secondary, row=row)
        self.ctx = ctx
        self.guild_id = guild_id

    async def callback(self, interaction: discord.Interaction) -> None:
        await _go_to_main(interaction, ctx=self.ctx, guild_id=self.guild_id)


class _BackOnlyView(ui.View):
    def __init__(self, *, ctx: ViewCtx, guild_id: int) -> None:
        super().__init__(timeout=120)
        self.add_item(_BackButton(ctx=ctx, guild_id=guild_id))


async def _go_to_main(interaction: discord.Interaction, *, ctx: ViewCtx, guild_id: int) -> None:
    config = await ctx.store.get_guild_config(guild_id)
    embed = settings_embed(
        config, interaction.guild.name if interaction.guild else "서버", config.language
    )
    view = SettingsView(ctx=ctx, guild_id=guild_id, provider=config.provider)
    await interaction.response.edit_message(embed=embed, view=view)


# ---------------------------------------------------------------------------
# Phase 3 — Follow-up View for /ask (#36)
# ---------------------------------------------------------------------------


class _FollowUpModal(ui.Modal, title="후속 질문"):
    """Modal for entering a follow-up question after /ask."""

    question_input: ui.TextInput = ui.TextInput(
        label="후속 질문",
        placeholder="이전 대화를 바탕으로 추가로 궁금한 점을 입력하세요.",
        style=discord.TextStyle.paragraph,
        required=True,
        max_length=500,
    )

    def __init__(self, *, callback) -> None:
        super().__init__()
        self._callback = callback

    async def on_submit(self, interaction: discord.Interaction) -> None:
        await self._callback(interaction, self.question_input.value.strip())

    async def on_error(self, interaction: discord.Interaction, error: Exception) -> None:  # type: ignore[override]
        await interaction.response.send_message(f"⚠️ 오류: {error}", ephemeral=True)


class FollowUpView(ui.View):
    """Attached to /ask responses to allow follow-up questions."""

    def __init__(self, *, on_follow_up) -> None:
        super().__init__(timeout=300)
        self._on_follow_up = on_follow_up

    @ui.button(label="후속 질문", style=discord.ButtonStyle.primary, emoji="💬")
    async def follow_up_btn(self, interaction: discord.Interaction, button: ui.Button) -> None:
        modal = _FollowUpModal(callback=self._on_follow_up)
        await interaction.response.send_modal(modal)

    async def on_timeout(self) -> None:
        for item in self.children:
            item.disabled = True  # type: ignore[attr-defined]


# ---------------------------------------------------------------------------
# Phase 3 — Multi-channel select view for /summarize-channels (#35)
# ---------------------------------------------------------------------------


class ChannelSelectView(ui.View):
    """Lets users pick multiple text channels to summarize."""

    def __init__(
        self,
        *,
        channels: list[discord.TextChannel],
        on_confirm,
    ) -> None:
        super().__init__(timeout=120)
        self._on_confirm = on_confirm
        self._selected: list[str] = []

        options = [
            discord.SelectOption(
                label=f"#{ch.name}"[:100],
                value=str(ch.id),
                description=(ch.topic or "")[:100] if hasattr(ch, "topic") else "",
            )
            for ch in channels[:25]
        ]
        if options:
            select: ui.Select[Any] = ui.Select(
                placeholder="요약할 채널을 선택하세요 (복수 가능)",
                options=options,
                min_values=1,
                max_values=min(len(options), 10),
                row=0,
            )
            select.callback = self._on_select  # type: ignore[method-assign]
            self.add_item(select)

    async def _on_select(self, interaction: discord.Interaction) -> None:
        self._selected = interaction.data["values"]  # type: ignore[index,typeddict-item]
        await interaction.response.defer()

    @ui.button(label="요약 시작", style=discord.ButtonStyle.success, row=1)
    async def confirm_btn(self, interaction: discord.Interaction, button: ui.Button) -> None:
        if not self._selected:
            await interaction.response.send_message("⚠️ 채널을 먼저 선택하세요.", ephemeral=True)
            return
        await self._on_confirm(interaction, self._selected)

    async def on_timeout(self) -> None:
        for item in self.children:
            item.disabled = True  # type: ignore[attr-defined]


# ---------------------------------------------------------------------------
# Error recovery building blocks (배선은 bot.py 소관, 여기선 재사용 블록만 제공)
# ---------------------------------------------------------------------------


def error_hint(exc: BaseException) -> str:
    """오류 원인 → 사용자용 한국어 복구 힌트 문자열로 매핑한다.

    bot.py 가 LLM 호출 실패를 사용자에게 알릴 때 RetryView 와 함께 사용한다.
    구체 예외 타입과 (있다면) HTTP status_code 를 함께 보고 가장 행동 가능한
    안내를 돌려준다. 알 수 없는 오류는 일반적인 재시도 안내로 폴백한다.
    """
    # HTTP 상태 코드 기반 안내(LLMError 계열은 status_code 속성을 가질 수 있다).
    status_code = getattr(exc, "status_code", None)
    if status_code in (401, 403):
        return "API 키가 유효하지 않거나 권한이 없어요. `/settings`에서 API 키를 다시 등록해 주세요."
    if status_code == 429:
        return "요청이 너무 많아 잠시 제한됐어요. 잠깐 기다렸다가 다시 시도해 주세요."
    if status_code is not None and 500 <= status_code < 600:
        return "AI 제공자 서버에 일시적인 문제가 있어요. 잠시 후 다시 시도해 주세요."

    # 예외 타입 기반 안내.
    if isinstance(exc, CircuitBreakerOpenError):
        return "연속 실패로 잠시 요청을 차단했어요. 잠시 후 다시 시도해 주세요."
    if isinstance(exc, OllamaError):
        # 일반 사용자는 봇 호스트의 Ollama 를 만질 수 없다. '터미널에서 실행' 같은
        # 개발자용 안내 대신, 서버 안에서 버튼으로 해결 가능한 경로를 알려 준다.
        return (
            "AI 모델이 아직 준비되지 않았어요. 서버 **관리자**가 `/settings` → "
            "**모델 관리**에서 모델을 선택하거나 **새 모델 설치** 버튼으로 설치하면 "
            "바로 사용할 수 있어요. (관리자가 아니라면 서버 관리자에게 알려 주세요.)"
        )
    if isinstance(exc, OpenAIError):
        return "OpenAI 요청에 실패했어요. API 키와 네트워크 상태를 확인한 뒤 다시 시도해 주세요."
    if isinstance(exc, AnthropicError):
        return "Anthropic 요청에 실패했어요. API 키와 네트워크 상태를 확인한 뒤 다시 시도해 주세요."
    if isinstance(exc, GeminiError):
        return "Gemini 요청에 실패했어요. API 키와 네트워크 상태를 확인한 뒤 다시 시도해 주세요."
    if isinstance(exc, TimeoutError | asyncio.TimeoutError):
        return "응답이 시간 초과됐어요. 잠시 후 다시 시도하거나 요약 범위를 줄여 주세요."
    if isinstance(exc, LLMError):
        return "AI 응답 생성에 실패했어요. 잠시 후 다시 시도해 주세요."

    return "예기치 못한 오류가 발생했어요. 잠시 후 다시 시도해 주세요."


class RetryView(ui.View):
    """주입된 콜백으로 마지막 작업을 재시도하는 재사용 뷰.

    bot.py 가 LLM 호출 실패 시 에러 메시지에 붙여 사용한다. 콜백 시그니처는
    ``async (interaction) -> None`` 이며, 재시도 버튼 클릭 시 그대로 호출된다.
    콜백 내부에서 응답(defer/edit/send)을 처리하도록 위임한다.
    """

    def __init__(
        self,
        *,
        on_retry: "Callable[[discord.Interaction], Awaitable[None]]",
        label: str = "다시 시도",
        timeout: float = 300,
    ) -> None:
        super().__init__(timeout=timeout)
        self._on_retry = on_retry
        self._retry_button.label = label

    @ui.button(label="다시 시도", style=discord.ButtonStyle.primary, emoji="🔄", row=0)
    async def _retry_button(self, interaction: discord.Interaction, button: ui.Button) -> None:
        # 중복 클릭 방지를 위해 버튼을 비활성화한 뒤 콜백에 위임한다.
        button.disabled = True
        await self._on_retry(interaction)

    async def on_timeout(self) -> None:
        for item in self.children:
            item.disabled = True  # type: ignore[attr-defined]
