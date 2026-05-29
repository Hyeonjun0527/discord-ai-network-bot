"""Unit tests for ui.py — settings_embed output validation."""
from __future__ import annotations

import asyncio
import unittest
from unittest import mock

import discord

from discord_assistant.llm import (
    AnthropicError,
    CircuitBreakerOpenError,
    LLMError,
    OllamaError,
    OpenAIError,
)
from discord_assistant.models import GuildConfig, LLMProvider, OllamaModel
from discord_assistant.prompts import _LANGUAGE_LABELS
from discord_assistant.ui import (
    ExternalModelView,
    GeneralSettingsView,
    HelpView,
    LanguageSelectView,
    ProviderView,
    RetryView,
    SettingsView,
    ViewCtx,
    _APIKeyModal,
    _external_model_embed,
    _general_settings_embed,
    _provider_embed,
    _supported_language_options,
    error_hint,
    settings_embed,
)


def _make_config(**overrides) -> GuildConfig:
    defaults = dict(
        guild_id=123,
        model="llama3.1:8b",
        summary_limit=50,
        language="ko",
        admin_role_id=None,
        provider=LLMProvider.OLLAMA,
        api_key_encrypted=None,
    )
    defaults.update(overrides)
    return GuildConfig(**defaults)


class TestSettingsEmbed(unittest.TestCase):
    def test_returns_discord_embed_instance(self):
        config = _make_config()
        embed = settings_embed(config, "Test Server")
        self.assertIsInstance(embed, discord.Embed)

    def test_embed_title(self):
        config = _make_config()
        embed = settings_embed(config, "My Server")
        self.assertEqual(embed.title, "서버 AI 설정")

    def test_embed_has_six_fields(self):
        """The embed is defined with exactly 6 fields (제공자, 모델, API 키, 언어, 요약 범위, blank)."""
        config = _make_config()
        embed = settings_embed(config, "Test Server")
        self.assertEqual(len(embed.fields), 6)

    def test_provider_field_present(self):
        config = _make_config(provider=LLMProvider.OLLAMA)
        embed = settings_embed(config, "Test Server")
        names = [f.name for f in embed.fields]
        self.assertIn("제공자", names)

    def test_model_field_present(self):
        config = _make_config(model="gpt-4o-mini")
        embed = settings_embed(config, "Test Server")
        names = [f.name for f in embed.fields]
        self.assertIn("모델", names)

    def test_api_key_absent_shows_unregistered(self):
        # Use OPENAI provider so _api_key_status returns a real status (not N/A)
        config = _make_config(provider=LLMProvider.OPENAI, api_key_encrypted=None)
        embed = settings_embed(config, "Test Server")
        api_key_field = next(f for f in embed.fields if f.name == "API 키")
        self.assertIn("미등록", api_key_field.value)

    def test_api_key_present_shows_registered(self):
        # Use OPENAI provider so _api_key_status returns a real status (not N/A)
        config = _make_config(provider=LLMProvider.OPENAI, api_key_encrypted="gAAAAABxxxxENcrypted")
        embed = settings_embed(config, "Test Server")
        api_key_field = next(f for f in embed.fields if f.name == "API 키")
        self.assertIn("등록됨", api_key_field.value)

    def test_guild_name_appears_in_description(self):
        config = _make_config()
        embed = settings_embed(config, "My Test Guild")
        self.assertIn("My Test Guild", embed.description or "")

    def test_language_field_present(self):
        config = _make_config(language="en")
        embed = settings_embed(config, "Test Server")
        names = [f.name for f in embed.fields]
        self.assertIn("언어", names)

    def test_summary_limit_field_present(self):
        config = _make_config(summary_limit=100)
        embed = settings_embed(config, "Test Server")
        names = [f.name for f in embed.fields]
        self.assertIn("요약 범위", names)

    def test_summary_limit_value_is_correct(self):
        config = _make_config(summary_limit=75)
        embed = settings_embed(config, "Test Server")
        summary_field = next(f for f in embed.fields if f.name == "요약 범위")
        self.assertIn("75", summary_field.value)

    def test_openai_provider_name_displayed(self):
        config = _make_config(provider=LLMProvider.OPENAI)
        embed = settings_embed(config, "Test Server")
        provider_field = next(f for f in embed.fields if f.name == "제공자")
        self.assertIn("OpenAI", provider_field.value)

    def test_anthropic_provider_name_displayed(self):
        config = _make_config(provider=LLMProvider.ANTHROPIC)
        embed = settings_embed(config, "Test Server")
        provider_field = next(f for f in embed.fields if f.name == "제공자")
        self.assertIn("Anthropic", provider_field.value)


class _StubStore:
    """set_language 호출을 기록하는 최소 스텁 ConfigStore."""

    def __init__(self) -> None:
        self.set_language_calls: list[tuple[int, str]] = []
        self.config = _make_config(language="ko")

    async def set_language(self, guild_id: int, language: str) -> GuildConfig:
        self.set_language_calls.append((guild_id, language))
        from dataclasses import replace

        self.config = replace(self.config, language=language)
        return self.config


class _StubInteraction:
    """edit_message / data 만 흉내내는 최소 Interaction 스텁."""

    def __init__(self, value: str) -> None:
        self.data = {"values": [value]}
        self.edit_called = False
        self.guild = None

        class _Resp:
            def __init__(self, outer: "_StubInteraction") -> None:
                self._outer = outer

            async def edit_message(self, **kwargs) -> None:
                self._outer.edit_called = True
                self._outer.edit_kwargs = kwargs

        self.response = _Resp(self)


def _make_ctx(store):
    # ViewCtx 는 frozen dataclass — store 만 실제로 쓰이므로 나머지는 더미.
    from discord_assistant.ui import ViewCtx

    return ViewCtx(store=store, ollama_manager=object(), secret_key="x")  # type: ignore[arg-type]


class TestSupportedLanguageOptions(unittest.TestCase):
    def test_includes_auto_plus_all_supported_codes(self):
        opts = _supported_language_options()
        values = [o.value for o in opts]
        self.assertIn("auto", values)
        for code in _LANGUAGE_LABELS:
            self.assertIn(code, values)

    def test_within_discord_25_option_limit(self):
        self.assertLessEqual(len(_supported_language_options()), 25)

    def test_all_options_are_select_options(self):
        for o in _supported_language_options():
            self.assertIsInstance(o, discord.SelectOption)


class TestLanguageSelectView(unittest.TestCase):
    def test_view_contains_select_with_options(self):
        ctx = _make_ctx(_StubStore())
        view = LanguageSelectView(ctx=ctx, guild_id=123, current="ko")
        selects = [c for c in view.children if isinstance(c, discord.ui.Select)]
        self.assertEqual(len(selects), 1)
        self.assertGreater(len(selects[0].options), 0)

    def test_select_options_match_supported_languages(self):
        ctx = _make_ctx(_StubStore())
        view = LanguageSelectView(ctx=ctx, guild_id=123, current="ko")
        select = next(c for c in view.children if isinstance(c, discord.ui.Select))
        values = {o.value for o in select.options}
        # 자유 텍스트가 아니라 화이트리스트 코드만 노출되는지 확인.
        self.assertEqual(values, set(_LANGUAGE_LABELS) | {"auto"})

    def test_current_language_marked_default(self):
        ctx = _make_ctx(_StubStore())
        view = LanguageSelectView(ctx=ctx, guild_id=123, current="ja")
        select = next(c for c in view.children if isinstance(c, discord.ui.Select))
        defaults = [o.value for o in select.options if o.default]
        self.assertEqual(defaults, ["ja"])

    def test_unsupported_current_marks_no_default(self):
        ctx = _make_ctx(_StubStore())
        view = LanguageSelectView(ctx=ctx, guild_id=123, current="zz")
        select = next(c for c in view.children if isinstance(c, discord.ui.Select))
        self.assertEqual([o for o in select.options if o.default], [])

    def test_select_callback_persists_language(self):
        store = _StubStore()
        ctx = _make_ctx(store)
        view = LanguageSelectView(ctx=ctx, guild_id=777, current="ko")
        interaction = _StubInteraction("ja")
        asyncio.run(view._on_select(interaction))  # type: ignore[arg-type]
        self.assertEqual(store.set_language_calls, [(777, "ja")])
        self.assertTrue(interaction.edit_called)

    def test_select_callback_auto_value(self):
        store = _StubStore()
        ctx = _make_ctx(store)
        view = LanguageSelectView(ctx=ctx, guild_id=1, current="ko")
        interaction = _StubInteraction("auto")
        asyncio.run(view._on_select(interaction))  # type: ignore[arg-type]
        self.assertEqual(store.set_language_calls, [(1, "auto")])

    def test_has_back_button(self):
        ctx = _make_ctx(_StubStore())
        view = LanguageSelectView(ctx=ctx, guild_id=123, current="ko")
        labels = [getattr(c, "label", None) for c in view.children]
        self.assertIn("← 뒤로", labels)


class TestErrorHint(unittest.TestCase):
    def test_auth_error_status_code(self):
        exc = OpenAIError("bad key", status_code=401)
        self.assertIn("API 키", error_hint(exc))

    def test_forbidden_status_code(self):
        exc = AnthropicError("forbidden", status_code=403)
        self.assertIn("API 키", error_hint(exc))

    def test_rate_limit_status_code(self):
        exc = OpenAIError("rate", status_code=429)
        self.assertIn("기다", error_hint(exc))

    def test_server_error_status_code(self):
        exc = AnthropicError("server", status_code=503)
        self.assertIn("서버", error_hint(exc))

    def test_circuit_breaker(self):
        self.assertIn("차단", error_hint(CircuitBreakerOpenError("open")))

    def test_ollama_error(self):
        # 사용자용 문구는 개발자 용어('Ollama'/'ollama serve') 대신 서버 내에서
        # 버튼으로 해결 가능한 경로(모델 관리/관리자 안내)를 가리킨다.
        hint = error_hint(OllamaError("down"))
        self.assertIn("모델", hint)
        self.assertNotIn("ollama serve", hint)

    def test_openai_error_no_status(self):
        self.assertIn("OpenAI", error_hint(OpenAIError("oops")))

    def test_anthropic_error_no_status(self):
        self.assertIn("Anthropic", error_hint(AnthropicError("oops")))

    def test_timeout_error(self):
        self.assertIn("시간", error_hint(TimeoutError()))

    def test_generic_llm_error(self):
        self.assertIn("실패", error_hint(LLMError("?")))

    def test_unknown_error_fallback(self):
        hint = error_hint(ValueError("???"))
        self.assertTrue(hint)
        self.assertIn("오류", hint)

    def test_always_returns_nonempty_string(self):
        for exc in [OpenAIError("x"), ValueError("y"), RuntimeError("z")]:
            self.assertIsInstance(error_hint(exc), str)
            self.assertTrue(error_hint(exc))


class TestRetryView(unittest.TestCase):
    def test_default_label(self):
        async def _cb(_interaction):
            return None

        view = RetryView(on_retry=_cb)
        labels = [getattr(c, "label", None) for c in view.children]
        self.assertIn("다시 시도", labels)

    def test_custom_label(self):
        async def _cb(_interaction):
            return None

        view = RetryView(on_retry=_cb, label="재요약")
        labels = [getattr(c, "label", None) for c in view.children]
        self.assertIn("재요약", labels)

    def test_retry_invokes_callback(self):
        called: list[object] = []

        async def _cb(interaction):
            called.append(interaction)

        view = RetryView(on_retry=_cb)
        button = next(
            c for c in view.children if isinstance(c, discord.ui.Button)
        )
        interaction = _StubInteraction("x")
        # discord.py 는 button.callback(interaction) 형태로 호출한다.
        asyncio.run(button.callback(interaction))  # type: ignore[arg-type]
        self.assertEqual(len(called), 1)
        self.assertTrue(button.disabled)


# ---------------------------------------------------------------------------
# #62: View / Modal 콜백 상호작용 테스트
#
# MagicMock 대신 동작을 기록하는 경량 스텁으로 interaction 을 흉내내고,
# 콜백 분기(_change_provider / _manage_models / _general_settings,
# ProviderView._on_select, _APIKeyModal.on_submit + 키 검증)와 RetryView 동작을
# 검증한다. urllib 호출(키 검증)은 mock 으로 막아 실제 네트워크를 타지 않게 한다.
# ---------------------------------------------------------------------------


class _RichStore:
    """get_guild_config / set_provider_config 등을 기록하는 스텁 ConfigStore."""

    def __init__(self, config: GuildConfig | None = None) -> None:
        self.config = config or _make_config()
        self.set_provider_calls: list[dict] = []

    async def get_guild_config(self, guild_id: int) -> GuildConfig:
        return self.config

    async def set_provider_config(
        self,
        guild_id: int,
        *,
        provider: LLMProvider,
        model: str,
        api_key_encrypted: str | None,
    ) -> GuildConfig:
        from dataclasses import replace

        self.set_provider_calls.append(
            {"guild_id": guild_id, "provider": provider, "model": model, "key": api_key_encrypted}
        )
        self.config = replace(
            self.config,
            provider=provider,
            model=model,
            api_key_encrypted=api_key_encrypted,
        )
        return self.config


class _FakeOllamaManager:
    """list_models 만 흉내내는 스텁(네트워크 없음)."""

    def __init__(self, models: list[OllamaModel] | None = None) -> None:
        self._models = models or []
        self.list_called = 0

    async def list_models(self) -> list[OllamaModel]:
        self.list_called += 1
        return self._models


class _RichInteraction:
    """edit_message / defer / followup / send_modal / edit_original_response 기록 스텁."""

    def __init__(self, value: str | None = None) -> None:
        self.data = {"values": [value]} if value is not None else {}
        self.guild = None
        self.edit_message_kwargs: dict | None = None
        self.deferred = False
        self.followup_messages: list[tuple[str, dict]] = []
        self.original_edits: list[dict] = []
        self.sent_modals: list[object] = []
        self.sent_messages: list[tuple[str, dict]] = []

        outer = self

        class _Resp:
            async def edit_message(self, **kwargs) -> None:
                outer.edit_message_kwargs = kwargs

            async def defer(self, **kwargs) -> None:
                outer.deferred = True

            async def send_modal(self, modal) -> None:
                outer.sent_modals.append(modal)

            async def send_message(self, content="", **kwargs) -> None:
                outer.sent_messages.append((content, kwargs))

        class _Followup:
            async def send(self, content="", **kwargs) -> None:
                outer.followup_messages.append((content, kwargs))

        self.response = _Resp()
        self.followup = _Followup()

    async def edit_original_response(self, **kwargs) -> None:
        self.original_edits.append(kwargs)


def _make_rich_ctx(store, ollama_manager=None) -> ViewCtx:
    return ViewCtx(
        store=store,
        ollama_manager=ollama_manager or _FakeOllamaManager(),  # type: ignore[arg-type]
        secret_key="test-secret-key",
    )


class TestSettingsViewCallbacks(unittest.TestCase):
    def test_change_provider_opens_provider_view(self) -> None:
        store = _RichStore(_make_config(provider=LLMProvider.OLLAMA))
        ctx = _make_rich_ctx(store)
        view = SettingsView(ctx=ctx, guild_id=1, provider=LLMProvider.OLLAMA)
        interaction = _RichInteraction()
        asyncio.run(view._change_provider(interaction))  # type: ignore[arg-type]
        self.assertIsNotNone(interaction.edit_message_kwargs)
        self.assertIsInstance(interaction.edit_message_kwargs["view"], ProviderView)

    def test_manage_models_ollama_branch_lists_models(self) -> None:
        store = _RichStore(_make_config(provider=LLMProvider.OLLAMA, model="llama3.1:8b"))
        ollama = _FakeOllamaManager([OllamaModel(name="llama3.1:8b", size_bytes=10**9)])
        ctx = _make_rich_ctx(store, ollama)
        view = SettingsView(ctx=ctx, guild_id=1, provider=LLMProvider.OLLAMA)
        interaction = _RichInteraction()
        asyncio.run(view._manage_models(interaction))  # type: ignore[arg-type]
        self.assertTrue(interaction.deferred)
        self.assertEqual(ollama.list_called, 1)
        self.assertTrue(interaction.original_edits)

    def test_manage_models_external_branch_no_ollama_call(self) -> None:
        store = _RichStore(_make_config(provider=LLMProvider.OPENAI, model="gpt-4o-mini"))
        ollama = _FakeOllamaManager()
        ctx = _make_rich_ctx(store, ollama)
        view = SettingsView(ctx=ctx, guild_id=1, provider=LLMProvider.OPENAI)
        interaction = _RichInteraction()
        asyncio.run(view._manage_models(interaction))  # type: ignore[arg-type]
        # 외부 제공자에서는 ollama 모델 목록을 조회하지 않는다.
        self.assertEqual(ollama.list_called, 0)
        self.assertTrue(interaction.original_edits)
        self.assertIsInstance(interaction.original_edits[-1]["view"], ExternalModelView)

    def test_general_settings_opens_general_view(self) -> None:
        store = _RichStore(_make_config())
        ctx = _make_rich_ctx(store)
        view = SettingsView(ctx=ctx, guild_id=1)
        interaction = _RichInteraction()
        asyncio.run(view._general_settings(interaction))  # type: ignore[arg-type]
        self.assertIsInstance(interaction.edit_message_kwargs["view"], GeneralSettingsView)

    def test_model_button_label_for_external_provider(self) -> None:
        ctx = _make_rich_ctx(_RichStore())
        view = SettingsView(ctx=ctx, guild_id=1, provider=LLMProvider.OPENAI)
        labels = [getattr(c, "label", None) for c in view.children]
        self.assertIn("모델 선택", labels)

    def test_model_button_label_for_ollama(self) -> None:
        ctx = _make_rich_ctx(_RichStore())
        view = SettingsView(ctx=ctx, guild_id=1, provider=LLMProvider.OLLAMA)
        labels = [getattr(c, "label", None) for c in view.children]
        self.assertIn("모델 관리", labels)


class TestProviderViewSelect(unittest.TestCase):
    def test_select_ollama_persists_and_returns_to_settings(self) -> None:
        store = _RichStore(_make_config(provider=LLMProvider.OPENAI))
        ollama = _FakeOllamaManager([OllamaModel(name="qwen2.5:7b", size_bytes=10**9)])
        ctx = _make_rich_ctx(store, ollama)
        view = ProviderView(ctx=ctx, guild_id=42)
        interaction = _RichInteraction("ollama")
        asyncio.run(view._on_select(interaction))  # type: ignore[arg-type]
        # ollama 선택 시 set_provider_config 가 호출되고 SettingsView 로 복귀.
        self.assertEqual(len(store.set_provider_calls), 1)
        self.assertEqual(store.set_provider_calls[0]["provider"], LLMProvider.OLLAMA)
        # 설치된 모델이 있으면 첫 모델을 기본 모델로 사용.
        self.assertEqual(store.set_provider_calls[0]["model"], "qwen2.5:7b")
        self.assertIsInstance(interaction.edit_message_kwargs["view"], SettingsView)

    def test_select_ollama_uses_default_when_no_models(self) -> None:
        store = _RichStore(_make_config(provider=LLMProvider.OPENAI))
        ctx = _make_rich_ctx(store, _FakeOllamaManager([]))
        view = ProviderView(ctx=ctx, guild_id=42)
        interaction = _RichInteraction("ollama")
        asyncio.run(view._on_select(interaction))  # type: ignore[arg-type]
        self.assertEqual(store.set_provider_calls[0]["model"], "llama3.1:8b")

    def test_select_openai_opens_external_model_view_without_persisting(self) -> None:
        store = _RichStore(_make_config(provider=LLMProvider.OLLAMA))
        ctx = _make_rich_ctx(store)
        view = ProviderView(ctx=ctx, guild_id=42)
        interaction = _RichInteraction("openai")
        asyncio.run(view._on_select(interaction))  # type: ignore[arg-type]
        # OpenAI 선택은 모델/키 입력 화면만 열고 아직 저장하지 않는다.
        self.assertEqual(len(store.set_provider_calls), 0)
        self.assertIsInstance(interaction.edit_message_kwargs["view"], ExternalModelView)
        self.assertEqual(interaction.edit_message_kwargs["view"].provider, LLMProvider.OPENAI)

    def test_select_anthropic_opens_external_model_view(self) -> None:
        store = _RichStore(_make_config(provider=LLMProvider.OLLAMA))
        ctx = _make_rich_ctx(store)
        view = ProviderView(ctx=ctx, guild_id=42)
        interaction = _RichInteraction("anthropic")
        asyncio.run(view._on_select(interaction))  # type: ignore[arg-type]
        self.assertIsInstance(interaction.edit_message_kwargs["view"], ExternalModelView)
        self.assertEqual(interaction.edit_message_kwargs["view"].provider, LLMProvider.ANTHROPIC)


class TestExternalModelViewSelect(unittest.TestCase):
    def test_model_select_persists_chosen_model(self) -> None:
        store = _RichStore(_make_config(provider=LLMProvider.OPENAI, model="gpt-4o-mini"))
        ctx = _make_rich_ctx(store)
        models = [("gpt-4o", "GPT-4o", "최신"), ("gpt-4o-mini", "GPT-4o mini", "저렴")]
        view = ExternalModelView(ctx=ctx, guild_id=7, provider=LLMProvider.OPENAI, models=models)
        interaction = _RichInteraction("gpt-4o")
        asyncio.run(view._on_model_select(interaction))  # type: ignore[arg-type]
        self.assertEqual(view._selected_model, "gpt-4o")
        self.assertEqual(store.set_provider_calls[-1]["model"], "gpt-4o")
        self.assertIsNotNone(interaction.edit_message_kwargs)


def _button_by_label(view: discord.ui.View, label: str) -> discord.ui.Button:
    """주어진 라벨을 가진 버튼 아이템을 찾는다(@ui.button 데코레이터 콜백 호출용)."""
    for child in view.children:
        if isinstance(child, discord.ui.Button) and child.label == label:
            return child
    raise AssertionError(f"button with label {label!r} not found")


class TestGeneralSettingsViewCallbacks(unittest.TestCase):
    def test_change_language_opens_language_select(self) -> None:
        store = _RichStore(_make_config(language="ko"))
        ctx = _make_rich_ctx(store)
        view = GeneralSettingsView(ctx=ctx, guild_id=1)
        interaction = _RichInteraction()
        # @ui.button 데코레이터는 view.children 의 Button 아이템에 콜백을 바인딩한다.
        button = _button_by_label(view, "언어 변경")
        asyncio.run(button.callback(interaction))  # type: ignore[arg-type]
        self.assertIsInstance(interaction.edit_message_kwargs["view"], LanguageSelectView)

    def test_change_limit_sends_modal(self) -> None:
        store = _RichStore(_make_config())
        ctx = _make_rich_ctx(store)
        view = GeneralSettingsView(ctx=ctx, guild_id=1)
        interaction = _RichInteraction()
        button = _button_by_label(view, "요약 범위 변경")
        asyncio.run(button.callback(interaction))  # type: ignore[arg-type]
        self.assertEqual(len(interaction.sent_modals), 1)


class _ModalInteraction(_RichInteraction):
    """on_submit 테스트용: api 키 입력값을 흉내내는 interaction."""


class TestAPIKeyModalSubmit(unittest.TestCase):
    def _make_modal(self, provider: LLMProvider, store: _RichStore) -> _APIKeyModal:
        ctx = _make_rich_ctx(store)
        modal = _APIKeyModal(provider=provider, model="gpt-4o-mini", ctx=ctx, guild_id=9)
        return modal

    def test_empty_key_warns_and_does_not_save(self) -> None:
        store = _RichStore(_make_config(provider=LLMProvider.OPENAI))
        modal = self._make_modal(LLMProvider.OPENAI, store)
        modal.api_key_input._value = "   "  # type: ignore[attr-defined]
        interaction = _RichInteraction()
        asyncio.run(modal.on_submit(interaction))  # type: ignore[arg-type]
        self.assertEqual(len(store.set_provider_calls), 0)
        self.assertTrue(interaction.sent_messages)
        self.assertIn("API 키", interaction.sent_messages[0][0])

    def test_invalid_openai_key_not_saved(self) -> None:
        store = _RichStore(_make_config(provider=LLMProvider.OPENAI))
        modal = self._make_modal(LLMProvider.OPENAI, store)
        modal.api_key_input._value = "sk-bad"  # type: ignore[attr-defined]
        interaction = _RichInteraction()
        # 키 검증을 실패로 강제(urllib 미호출 — 네트워크 없음).
        with mock.patch("discord_assistant.ui._validate_openai_key", return_value=False):
            asyncio.run(modal.on_submit(interaction))  # type: ignore[arg-type]
        self.assertTrue(interaction.deferred)
        self.assertEqual(len(store.set_provider_calls), 0)
        self.assertTrue(interaction.followup_messages)
        self.assertIn("유효하지 않은", interaction.followup_messages[0][0])

    def test_valid_openai_key_encrypts_and_saves(self) -> None:
        store = _RichStore(_make_config(provider=LLMProvider.OPENAI))
        modal = self._make_modal(LLMProvider.OPENAI, store)
        modal.api_key_input._value = "sk-good-key"  # type: ignore[attr-defined]
        interaction = _RichInteraction()
        with mock.patch("discord_assistant.ui._validate_openai_key", return_value=True), \
             mock.patch(
                 "discord_assistant.ui.encrypt_api_key", return_value="ENC"
             ) as enc_mock:
            asyncio.run(modal.on_submit(interaction))  # type: ignore[arg-type]
        enc_mock.assert_called_once()
        self.assertEqual(len(store.set_provider_calls), 1)
        self.assertEqual(store.set_provider_calls[0]["key"], "ENC")
        self.assertTrue(interaction.original_edits)
        self.assertIsInstance(interaction.original_edits[-1]["view"], SettingsView)

    def test_anthropic_key_uses_anthropic_validator(self) -> None:
        store = _RichStore(_make_config(provider=LLMProvider.ANTHROPIC))
        ctx = _make_rich_ctx(store)
        modal = _APIKeyModal(
            provider=LLMProvider.ANTHROPIC, model="claude-3-haiku-20240307", ctx=ctx, guild_id=9
        )
        modal.api_key_input._value = "sk-ant-good"  # type: ignore[attr-defined]
        interaction = _RichInteraction()
        with mock.patch(
            "discord_assistant.ui._validate_anthropic_key", return_value=True
        ) as anthropic_validator, mock.patch(
            "discord_assistant.ui._validate_openai_key", return_value=False
        ) as openai_validator, mock.patch(
            "discord_assistant.ui.encrypt_api_key", return_value="ENC2"
        ):
            asyncio.run(modal.on_submit(interaction))  # type: ignore[arg-type]
        # Anthropic 제공자는 anthropic 검증기만 사용해야 한다.
        anthropic_validator.assert_called_once()
        openai_validator.assert_not_called()
        self.assertEqual(store.set_provider_calls[0]["key"], "ENC2")

    def test_validate_key_returns_true_for_non_external_provider(self) -> None:
        # _validate_key 분기: OLLAMA 등은 검증 없이 True.
        store = _RichStore(_make_config(provider=LLMProvider.OLLAMA))
        ctx = _make_rich_ctx(store)
        modal = _APIKeyModal(
            provider=LLMProvider.OLLAMA, model="llama3.1:8b", ctx=ctx, guild_id=9
        )
        self.assertTrue(modal._validate_key("anything"))


# ---------------------------------------------------------------------------
# #87: i18n — 길드 언어 주입 (ko 회귀 0 / en 번역 / 미지원 폴백)
# ---------------------------------------------------------------------------


class TestEmbedI18n(unittest.TestCase):
    def test_settings_embed_default_ko_unchanged(self) -> None:
        # 기본(ko) 호출은 기존 한국어 문자열을 그대로 유지한다(회귀 0).
        config = _make_config()
        embed = settings_embed(config, "S")
        self.assertEqual(embed.title, "서버 AI 설정")
        names = [f.name for f in embed.fields]
        self.assertIn("제공자", names)
        self.assertIn("언어", names)
        self.assertEqual(embed.footer.text, "이 서버에만 적용 • 관리자 전용")

    def test_settings_embed_korean_explicit(self) -> None:
        config = _make_config()
        embed = settings_embed(config, "S", "ko")
        self.assertEqual(embed.title, "서버 AI 설정")

    def test_settings_embed_english(self) -> None:
        config = _make_config()
        embed = settings_embed(config, "S", "en")
        self.assertEqual(embed.title, "Server AI Settings")
        names = [f.name for f in embed.fields]
        self.assertIn("Provider", names)
        self.assertIn("Language", names)

    def test_settings_embed_unsupported_lang_falls_back_to_ko(self) -> None:
        config = _make_config()
        embed = settings_embed(config, "S", "fr")
        self.assertEqual(embed.title, "서버 AI 설정")

    def test_settings_embed_summary_limit_value_localized(self) -> None:
        config = _make_config(summary_limit=42)
        embed_ko = settings_embed(config, "S", "ko")
        embed_en = settings_embed(config, "S", "en")
        ko_val = next(f.value for f in embed_ko.fields if f.name == "요약 범위")
        en_val = next(f.value for f in embed_en.fields if f.name == "Summary Range")
        self.assertEqual(ko_val, "42개 메시지")
        self.assertEqual(en_val, "42 messages")

    def test_general_settings_embed_en(self) -> None:
        config = _make_config()
        embed = _general_settings_embed(config, "en")
        self.assertEqual(embed.title, "⚙️  General Settings")

    def test_provider_embed_en(self) -> None:
        embed = _provider_embed(LLMProvider.OLLAMA, "en")
        self.assertEqual(embed.title, "Change AI Provider")

    def test_provider_embed_default_ko(self) -> None:
        embed = _provider_embed(LLMProvider.OLLAMA)
        self.assertEqual(embed.title, "AI 제공자 변경")

    def test_external_model_embed_en(self) -> None:
        embed = _external_model_embed(LLMProvider.OPENAI, "gpt-4o", True, "en")
        self.assertIn("Settings", embed.title)
        api_field = next(f for f in embed.fields if f.name == "API Key")
        self.assertIn("Registered", api_field.value)

    def test_external_model_embed_default_ko(self) -> None:
        embed = _external_model_embed(LLMProvider.OPENAI, "gpt-4o", False)
        self.assertIn("설정", embed.title)
        api_field = next(f for f in embed.fields if f.name == "API 키")
        self.assertIn("미등록", api_field.value)


class TestHelpViewI18n(unittest.TestCase):
    def test_main_embed_default_ko(self) -> None:
        embed = HelpView.main_embed()
        self.assertEqual(embed.title, "명령어 안내")
        self.assertEqual(embed.footer.text, "버튼을 눌러 섹션별 상세 안내를 볼 수 있습니다.")

    def test_main_embed_english(self) -> None:
        embed = HelpView.main_embed("en")
        self.assertEqual(embed.title, "Command Guide")

    def test_section_embeds_english(self) -> None:
        self.assertEqual(HelpView.ai_embed("en").title, "AI Features")
        self.assertEqual(HelpView.analysis_embed("en").title, "Channel Analysis")
        self.assertEqual(HelpView.settings_section_embed("en").title, "Settings")

    def test_section_embeds_default_ko(self) -> None:
        self.assertEqual(HelpView.ai_embed().title, "AI 기능")
        self.assertEqual(HelpView.analysis_embed().title, "채널 분석")
        self.assertEqual(HelpView.settings_section_embed().title, "설정")

    def test_button_labels_ko_default(self) -> None:
        view = HelpView()
        labels = [getattr(c, "label", None) for c in view.children]
        self.assertIn("AI 기능", labels)
        self.assertIn("닫기", labels)

    def test_button_labels_english(self) -> None:
        view = HelpView("en")
        labels = [getattr(c, "label", None) for c in view.children]
        self.assertIn("AI Features", labels)
        self.assertIn("Close", labels)

    def test_show_ai_callback_renders_localized_embed(self) -> None:
        view = HelpView("en")
        interaction = _RichInteraction()
        asyncio.run(view.show_ai(interaction))  # type: ignore[arg-type]
        self.assertEqual(interaction.edit_message_kwargs["embed"].title, "AI Features")

    def test_close_callback_localized(self) -> None:
        view = HelpView("en")
        interaction = _RichInteraction()
        asyncio.run(view.close_help(interaction))  # type: ignore[arg-type]
        self.assertEqual(interaction.edit_message_kwargs["embed"].title, "Help Closed")
        self.assertIsNone(interaction.edit_message_kwargs["view"])


if __name__ == "__main__":
    unittest.main()
