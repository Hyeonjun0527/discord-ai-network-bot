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
from .llm import OllamaError, OllamaManager
from .models import GuildConfig, LLMProvider, OllamaModel
from .storage import ConfigStore

if TYPE_CHECKING:
    pass


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

PROVIDER_DEFAULT_MODELS = {
    LLMProvider.OLLAMA:    "llama3.1:8b",
    LLMProvider.OPENAI:    "gpt-4o-mini",
    LLMProvider.ANTHROPIC: "claude-3-haiku-20240307",
}

COLORS = {
    "main":    discord.Color.from_str("#5865F2"),
    "success": discord.Color.green(),
    "warning": discord.Color.yellow(),
    "error":   discord.Color.red(),
    "install": discord.Color.from_str("#57F287"),
}


# ---------------------------------------------------------------------------
# Shared context
# ---------------------------------------------------------------------------


@dataclass
class ViewCtx:
    store: ConfigStore
    ollama_manager: OllamaManager
    secret_key: str


# ---------------------------------------------------------------------------
# Embed builders
# ---------------------------------------------------------------------------


def _language_label(code: str) -> str:
    labels = {
        "ko": "한국어",
        "kr": "한국어",
        "en": "English",
        "ja": "日本語",
        "jp": "日本語",
        "zh": "中文",
        "fr": "Français",
        "de": "Deutsch",
        "es": "Español",
    }
    return labels.get(code.lower(), code.upper())


def _api_key_status(config: GuildConfig) -> str:
    if config.provider == LLMProvider.OLLAMA:
        return "N/A"
    if config.api_key_encrypted:
        return "✅ 등록됨 (●●●●●●)"
    return "⚠️ 미등록"


def settings_embed(config: GuildConfig, guild_name: str) -> discord.Embed:
    embed = discord.Embed(
        title="서버 AI 설정",
        description=f"-# {guild_name}",
        color=COLORS["main"],
    )
    embed.add_field(name="제공자", value=config.provider.display_name(), inline=True)
    embed.add_field(name="모델", value=f"`{config.model}`", inline=True)
    embed.add_field(name="API 키", value=_api_key_status(config), inline=True)
    embed.add_field(
        name="언어",
        value=f"{_language_label(config.language)} ({config.language})",
        inline=True,
    )
    embed.add_field(name="요약 범위", value=f"{config.summary_limit}개 메시지", inline=True)
    embed.add_field(name="​", value="​", inline=True)
    embed.set_footer(text="이 서버에만 적용 • 관리자 전용")
    return embed


def _provider_embed(current: LLMProvider) -> discord.Embed:
    embed = discord.Embed(
        title="AI 제공자 변경",
        description=(
            "**Ollama (로컬)** — 인터넷 불필요, 내 PC에서 직접 실행\n"
            "**OpenAI (GPT)** — ChatGPT API 키 필요\n"
            "**Anthropic (Claude)** — Claude API 키 필요"
        ),
        color=COLORS["main"],
    )
    embed.add_field(name="현재", value=current.display_name(), inline=False)
    embed.set_footer(text="OpenAI / Anthropic 선택 시 API 키 입력이 필요합니다")
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


def _external_model_embed(provider: LLMProvider, current_model: str, has_key: bool) -> discord.Embed:
    embed = discord.Embed(
        title=f"{provider.emoji()}  {provider.display_name()} 설정",
        color=COLORS["main"],
    )
    embed.add_field(name="현재 모델", value=f"`{current_model}`", inline=True)
    embed.add_field(
        name="API 키",
        value="✅ 등록됨" if has_key else "⚠️ 미등록 — 아래 버튼으로 등록하세요",
        inline=True,
    )
    if not has_key:
        embed.color = COLORS["warning"]
    return embed


def _general_settings_embed(config: GuildConfig) -> discord.Embed:
    embed = discord.Embed(title="⚙️  일반 설정", color=COLORS["main"])
    embed.add_field(
        name="🌐  응답 언어",
        value=f"{_language_label(config.language)} (`{config.language}`)",
        inline=True,
    )
    embed.add_field(name="📊  요약 범위", value=f"{config.summary_limit}개 메시지", inline=True)
    embed.set_footer(text="ko · en · ja · zh · fr · de · es 등 언어 코드를 사용하세요")
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
            return resp.status == 200
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


# ---------------------------------------------------------------------------
# Modals
# ---------------------------------------------------------------------------


class _APIKeyModal(ui.Modal, title="🔑  API 키 등록"):
    api_key_input: ui.TextInput = ui.TextInput(
        label="API 키",
        placeholder="sk-...  또는  sk-ant-...",
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
    ) -> None:
        super().__init__()
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
        embed = settings_embed(config, interaction.guild.name if interaction.guild else "서버")
        view = SettingsView(ctx=self.ctx, guild_id=self.guild_id, provider=config.provider)
        await interaction.edit_original_response(embed=embed, view=view)

    def _validate_key(self, raw_key: str) -> bool:
        if self.provider == LLMProvider.OPENAI:
            return _validate_openai_key(raw_key)
        if self.provider == LLMProvider.ANTHROPIC:
            return _validate_anthropic_key(raw_key)
        return True

    async def on_error(self, interaction: discord.Interaction, error: Exception) -> None:
        await interaction.response.send_message(f"⚠️ 오류: {error}", ephemeral=True)


class _LanguageModal(ui.Modal, title="🌐  응답 언어 변경"):
    language_input: ui.TextInput = ui.TextInput(
        label="언어 코드",
        placeholder="ko  /  en  /  ja  /  zh  /  fr …",
        style=discord.TextStyle.short,
        required=True,
        max_length=10,
    )

    def __init__(self, ctx: ViewCtx, guild_id: int) -> None:
        super().__init__()
        self.ctx = ctx
        self.guild_id = guild_id

    async def on_submit(self, interaction: discord.Interaction) -> None:
        lang = self.language_input.value.strip().lower()
        config = await self.ctx.store.set_language(self.guild_id, lang)
        embed = _general_settings_embed(config)
        view = GeneralSettingsView(ctx=self.ctx, guild_id=self.guild_id)
        await interaction.response.edit_message(embed=embed, view=view)


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
        embed = _general_settings_embed(config)
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
        model_btn_label = "모델 선택" if provider in (LLMProvider.OPENAI, LLMProvider.ANTHROPIC) else "모델 관리"

        change_provider_btn = ui.Button(
            label="제공자 변경", style=discord.ButtonStyle.primary, row=0
        )
        change_provider_btn.callback = self._change_provider
        self.add_item(change_provider_btn)

        manage_models_btn = ui.Button(
            label=model_btn_label, style=discord.ButtonStyle.secondary, row=0
        )
        manage_models_btn.callback = self._manage_models
        self.add_item(manage_models_btn)

        general_settings_btn = ui.Button(
            label="일반 설정", style=discord.ButtonStyle.secondary, row=0
        )
        general_settings_btn.callback = self._general_settings
        self.add_item(general_settings_btn)

    async def _change_provider(self, interaction: discord.Interaction) -> None:
        config = await self.ctx.store.get_guild_config(self.guild_id)
        embed = _provider_embed(config.provider)
        view = ProviderView(ctx=self.ctx, guild_id=self.guild_id)
        await interaction.response.edit_message(embed=embed, view=view)

    async def _manage_models(self, interaction: discord.Interaction) -> None:
        config = await self.ctx.store.get_guild_config(self.guild_id)
        await interaction.response.defer()
        if config.provider == LLMProvider.OLLAMA:
            installed = await self.ctx.ollama_manager.list_models()
            embed = _ollama_model_embed(installed, config.model)
            view = OllamaModelView(ctx=self.ctx, guild_id=self.guild_id, installed=installed)
        else:
            models = OPENAI_MODELS if config.provider == LLMProvider.OPENAI else ANTHROPIC_MODELS
            embed = _external_model_embed(config.provider, config.model, bool(config.api_key_encrypted))
            view = ExternalModelView(ctx=self.ctx, guild_id=self.guild_id, provider=config.provider, models=models)
        await interaction.edit_original_response(embed=embed, view=view)

    async def _general_settings(self, interaction: discord.Interaction) -> None:
        config = await self.ctx.store.get_guild_config(self.guild_id)
        embed = _general_settings_embed(config)
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

        select = ui.Select(
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
            ],
            row=0,
        )
        select.callback = self._on_select
        self.add_item(select)
        self.add_item(_BackButton(ctx=ctx, guild_id=guild_id, row=1))

    async def _on_select(self, interaction: discord.Interaction) -> None:
        selected = LLMProvider(interaction.data["values"][0])  # type: ignore[index]

        if selected == LLMProvider.OLLAMA:
            installed = await self.ctx.ollama_manager.list_models()
            default_model = installed[0].name if installed else PROVIDER_DEFAULT_MODELS[selected]
            config = await self.ctx.store.set_provider_config(
                self.guild_id,
                provider=selected,
                model=default_model,
                api_key_encrypted=None,
            )
            embed = settings_embed(config, interaction.guild.name if interaction.guild else "서버")
            await interaction.response.edit_message(embed=embed, view=SettingsView(ctx=self.ctx, guild_id=self.guild_id, provider=selected))

        else:
            models = OPENAI_MODELS if selected == LLMProvider.OPENAI else ANTHROPIC_MODELS
            current = await self.ctx.store.get_guild_config(self.guild_id)
            default_model = PROVIDER_DEFAULT_MODELS[selected]
            embed = _external_model_embed(selected, default_model, bool(current.api_key_encrypted))
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
        select = ui.Select(placeholder="모델을 선택하세요", options=options, row=0)
        select.callback = self._on_model_select
        self.add_item(select)
        self.add_item(_BackButton(ctx=ctx, guild_id=guild_id, row=2))

    async def _on_model_select(self, interaction: discord.Interaction) -> None:
        self._selected_model = interaction.data["values"][0]  # type: ignore[index]
        config = await self.ctx.store.get_guild_config(self.guild_id)
        updated = await self.ctx.store.set_provider_config(
            self.guild_id,
            provider=self.provider,
            model=self._selected_model,
            api_key_encrypted=config.api_key_encrypted,
        )
        embed = _external_model_embed(self.provider, self._selected_model, bool(updated.api_key_encrypted))
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
        embed = _external_model_embed(self.provider, config.model, False)
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
            select = ui.Select(placeholder="사용할 모델을 선택하세요", options=options, row=0)
            select.callback = self._on_select
            self.add_item(select)

        self.add_item(_BackButton(ctx=ctx, guild_id=guild_id, row=2))

    async def _on_select(self, interaction: discord.Interaction) -> None:
        self._selected = interaction.data["values"][0]  # type: ignore[index]
        config = await self.ctx.store.get_guild_config(self.guild_id)
        embed = _ollama_model_embed(self.installed, config.model)
        await interaction.response.edit_message(embed=embed, view=self)

    @ui.button(label="이 모델 사용", style=discord.ButtonStyle.success, row=1)
    async def use_selected(self, interaction: discord.Interaction, button: ui.Button) -> None:
        if not self._selected:
            await interaction.response.send_message("⚠️ 먼저 모델을 선택하세요.", ephemeral=True)
            return
        config = await self.ctx.store.set_model(self.guild_id, self._selected)
        embed = settings_embed(config, interaction.guild.name if interaction.guild else "서버")
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

        select = ui.Select(placeholder="설치할 모델을 선택하세요", options=options[:25], row=0)
        select.callback = self._on_select
        self.add_item(select)
        self.add_item(_BackButton(ctx=ctx, guild_id=guild_id, row=2))

    async def _on_select(self, interaction: discord.Interaction) -> None:
        self._selected = interaction.data["values"][0]  # type: ignore[index]
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
    """Section navigation for /help command."""

    _SECTIONS = {
        "ai": "AI 기능",
        "analysis": "채널 분석",
        "settings": "설정",
    }

    def __init__(self) -> None:
        super().__init__(timeout=180)

    @staticmethod
    def main_embed() -> discord.Embed:
        embed = discord.Embed(
            title="명령어 안내",
            color=discord.Color.from_str("#5865F2"),
        )
        embed.add_field(
            name="/summarize",
            value=(
                "채널의 최근 대화를 AI가 요약합니다.\n"
                "```\n/summarize\n/summarize limit:100\n```"
            ),
            inline=False,
        )
        embed.add_field(
            name="/ask",
            value=(
                "채널의 최근 대화에서 근거를 찾아 질문에 답합니다.\n"
                "```\n/ask question:오늘 회의 결론이 뭐야?\n```"
            ),
            inline=False,
        )
        embed.add_field(
            name="/chat",
            value=(
                "채널 맥락 없이 AI에게 자유롭게 질문합니다.\n"
                "```\n/chat message:파이썬 리스트 컴프리헨션 설명해줘\n```"
            ),
            inline=False,
        )
        embed.add_field(
            name="/translate",
            value=(
                "텍스트를 지정 언어로 번역합니다.\n"
                "```\n/translate text:Hello target_language:ko\n```"
            ),
            inline=False,
        )
        embed.add_field(
            name="@ 멘션",
            value=(
                "봇을 멘션하면 채널 대화를 요약합니다.\n"
                "멘션 뒤에 질문을 쓰면 `/ask` 처럼 동작합니다."
            ),
            inline=False,
        )
        embed.add_field(
            name="/settings  (관리자 전용)",
            value="AI 제공자, 모델, 언어, 요약 범위 등 서버 설정을 변경합니다.",
            inline=False,
        )
        embed.set_footer(text="버튼을 눌러 섹션별 상세 안내를 볼 수 있습니다.")
        return embed

    @staticmethod
    def ai_embed() -> discord.Embed:
        embed = discord.Embed(
            title="AI 기능",
            color=discord.Color.from_str("#5865F2"),
        )
        embed.add_field(
            name="/chat",
            value=(
                "채널 맥락 없이 AI에게 자유롭게 질문합니다.\n"
                "최근 5턴의 대화 기록을 기억합니다.\n"
                "```\n/chat message:파이썬 리스트 컴프리헨션 설명해줘\n"
                "/chat message:영어 이메일 초안 작성해줘\n"
                "/chat message:... public:True  (공개 응답)\n```"
            ),
            inline=False,
        )
        embed.add_field(
            name="/translate",
            value=(
                "텍스트를 지정 언어로 번역합니다.\n"
                "```\n/translate text:Hello target_language:ko\n```"
            ),
            inline=False,
        )
        embed.add_field(
            name="@ 멘션",
            value=(
                "봇을 멘션하면 채널 대화를 요약합니다.\n"
                "멘션 뒤에 질문을 쓰면 `/ask` 처럼 동작합니다.\n"
                "```\n@ai-assistant\n@ai-assistant 어제 무슨 얘기 했어?\n```"
            ),
            inline=False,
        )
        return embed

    @staticmethod
    def analysis_embed() -> discord.Embed:
        embed = discord.Embed(
            title="채널 분석",
            color=discord.Color.from_str("#5865F2"),
        )
        embed.add_field(
            name="/summarize",
            value=(
                "채널의 최근 대화를 섹션별로 요약합니다.\n"
                "핵심 요약 · 결정/합의 · 액션 아이템 · 놓치면 안 되는 맥락\n"
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
            ),
            inline=False,
        )
        return embed

    @staticmethod
    def settings_section_embed() -> discord.Embed:
        embed = discord.Embed(
            title="설정",
            color=discord.Color.from_str("#5865F2"),
        )
        embed.add_field(
            name="/settings  (관리자 전용)",
            value=(
                "AI 제공자 변경 — Ollama(로컬) / OpenAI / Anthropic\n"
                "모델 선택 및 관리\n"
                "응답 언어 변경\n"
                "요약 범위 설정"
            ),
            inline=False,
        )
        embed.add_field(
            name="지원 언어 코드",
            value="ko · en · ja · zh · fr · de · es",
            inline=False,
        )
        return embed

    @ui.button(label="AI 기능", style=discord.ButtonStyle.primary, row=0)
    async def show_ai(self, interaction: discord.Interaction, button: ui.Button) -> None:
        await interaction.response.edit_message(embed=self.ai_embed(), view=self)

    @ui.button(label="채널 분석", style=discord.ButtonStyle.primary, row=0)
    async def show_analysis(self, interaction: discord.Interaction, button: ui.Button) -> None:
        await interaction.response.edit_message(embed=self.analysis_embed(), view=self)

    @ui.button(label="설정", style=discord.ButtonStyle.primary, row=0)
    async def show_settings(self, interaction: discord.Interaction, button: ui.Button) -> None:
        await interaction.response.edit_message(embed=self.settings_section_embed(), view=self)

    @ui.button(label="닫기", style=discord.ButtonStyle.danger, row=0)
    async def close_help(self, interaction: discord.Interaction, button: ui.Button) -> None:
        await interaction.response.edit_message(
            embed=discord.Embed(
                title="도움말 닫힘",
                description="다시 보려면 `/help`를 입력하세요.",
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
        await interaction.response.send_modal(_LanguageModal(ctx=self.ctx, guild_id=self.guild_id))

    @ui.button(label="요약 범위 변경", style=discord.ButtonStyle.primary, row=0)
    async def change_limit(self, interaction: discord.Interaction, button: ui.Button) -> None:
        await interaction.response.send_modal(_SummaryLimitModal(ctx=self.ctx, guild_id=self.guild_id))

    @ui.button(label="← 뒤로", style=discord.ButtonStyle.secondary, row=1)
    async def go_back(self, interaction: discord.Interaction, button: ui.Button) -> None:
        await _go_to_main(interaction, ctx=self.ctx, guild_id=self.guild_id)


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
    embed = settings_embed(config, interaction.guild.name if interaction.guild else "서버")
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

    async def on_error(self, interaction: discord.Interaction, error: Exception) -> None:
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
            select = ui.Select(
                placeholder="요약할 채널을 선택하세요 (복수 가능)",
                options=options,
                min_values=1,
                max_values=min(len(options), 10),
                row=0,
            )
            select.callback = self._on_select
            self.add_item(select)

    async def _on_select(self, interaction: discord.Interaction) -> None:
        self._selected = interaction.data["values"]  # type: ignore[index]
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
