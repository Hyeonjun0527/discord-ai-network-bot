"""Unit tests for ui.py — settings_embed output validation."""
from __future__ import annotations

import asyncio
import unittest

import discord

from discord_assistant.llm import (
    AnthropicError,
    CircuitBreakerOpenError,
    LLMError,
    OllamaError,
    OpenAIError,
)
from discord_assistant.models import GuildConfig, LLMProvider
from discord_assistant.prompts import _LANGUAGE_LABELS
from discord_assistant.ui import (
    LanguageSelectView,
    RetryView,
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
        self.assertIn("Ollama", error_hint(OllamaError("down")))

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


if __name__ == "__main__":
    unittest.main()
