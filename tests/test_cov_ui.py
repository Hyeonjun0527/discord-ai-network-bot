"""Coverage tests for ui.py — uncovered View/Modal/Select/Button callbacks and helpers.

기존 tests/test_ui.py 의 경량 스텁 패턴(_RichInteraction / _RichStore)을 모방한다.
네트워크(urllib)·LLM 호출은 전부 mock 한다. ConfigStore 는 직접 만들지 않고
동작 기록용 스텁으로 대체하므로 aiosqlite 워커 누수 위험이 없다.
"""
from __future__ import annotations

import asyncio
import unittest
from dataclasses import replace
from unittest import mock
from urllib import error as urllib_error

import discord

from discord_assistant.llm import GeminiError, OllamaError, OllamaManager
from discord_assistant.models import GuildConfig, LLMProvider, OllamaModel
from discord_assistant.ui import (
    ANTHROPIC_MODELS,
    GEMINI_MODELS,
    OPENAI_MODELS,
    ChannelSelectView,
    ExternalModelView,
    FollowUpView,
    GeneralSettingsView,
    HelpView,
    LanguageSelectView,
    LongResponseView,
    ModelInstallView,
    OllamaModelView,
    RetryView,
    SettingsView,
    SummarizeResultView,
    ViewCtx,
    _APIKeyModal,
    _BackButton,
    _BackOnlyView,
    _CustomModelModal,
    _external_models_for,
    _FollowUpModal,
    _go_to_main,
    _install_embed,
    _ollama_model_embed,
    _SummaryLimitModal,
    _validate_anthropic_key,
    _validate_gemini_key,
    _validate_openai_key,
    error_hint,
)

# ---------------------------------------------------------------------------
# Test doubles (test_ui.py 의 _Rich* 패턴을 그대로 따른다)
# ---------------------------------------------------------------------------


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


class _RichStore:
    """get/set_* 호출을 기록하는 스텁 ConfigStore (실제 DB 없음)."""

    def __init__(self, config: GuildConfig | None = None) -> None:
        self.config = config or _make_config()
        self.set_model_calls: list[tuple[int, str]] = []
        self.set_summary_limit_calls: list[tuple[int, int]] = []
        self.clear_api_key_calls: list[int] = []
        self.set_language_calls: list[tuple[int, str]] = []
        self.set_provider_calls: list[dict] = []
        self.set_summary_limit_error: Exception | None = None
        self.set_model_error: Exception | None = None

    async def get_guild_config(self, guild_id: int) -> GuildConfig:
        return self.config

    async def set_model(self, guild_id: int, model: str) -> GuildConfig:
        if self.set_model_error is not None:
            raise self.set_model_error
        self.set_model_calls.append((guild_id, model))
        self.config = replace(self.config, model=model)
        return self.config

    async def set_summary_limit(self, guild_id: int, limit: int) -> GuildConfig:
        if self.set_summary_limit_error is not None:
            raise self.set_summary_limit_error
        self.set_summary_limit_calls.append((guild_id, limit))
        self.config = replace(self.config, summary_limit=limit)
        return self.config

    async def clear_api_key(self, guild_id: int) -> GuildConfig:
        self.clear_api_key_calls.append(guild_id)
        self.config = replace(self.config, api_key_encrypted=None)
        return self.config

    async def set_language(self, guild_id: int, language: str) -> GuildConfig:
        self.set_language_calls.append((guild_id, language))
        self.config = replace(self.config, language=language)
        return self.config

    async def set_provider_config(
        self, guild_id: int, *, provider, model, api_key_encrypted
    ) -> GuildConfig:
        self.set_provider_calls.append(
            {"guild_id": guild_id, "provider": provider, "model": model, "key": api_key_encrypted}
        )
        self.config = replace(
            self.config, provider=provider, model=model, api_key_encrypted=api_key_encrypted
        )
        return self.config


class _FakeOllamaManager:
    """list_models / pull_model 을 흉내내는 스텁(네트워크 없음)."""

    def __init__(
        self,
        models: list[OllamaModel] | None = None,
        *,
        pull_error: Exception | None = None,
    ) -> None:
        self._models = models or []
        self.pull_error = pull_error
        self.pull_calls: list[str] = []

    async def list_models(self) -> list[OllamaModel]:
        return self._models

    async def pull_model(self, model_name: str) -> None:
        self.pull_calls.append(model_name)
        if self.pull_error is not None:
            raise self.pull_error


class _RichInteraction:
    """edit_message / defer / send_modal / followup / edit_original_response 기록 스텁."""

    def __init__(self, value: str | list[str] | None = None) -> None:
        if value is None:
            self.data: dict = {}
        elif isinstance(value, list):
            self.data = {"values": value}
        else:
            self.data = {"values": [value]}
        self.guild = None
        self.edit_message_kwargs: dict | None = None
        self.deferred = False
        self.defer_kwargs: dict | None = None
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
                outer.defer_kwargs = kwargs

            async def send_modal(self, modal) -> None:
                outer.sent_modals.append(modal)

            async def send_message(self, content="", **kwargs) -> None:
                outer.sent_messages.append((content, kwargs))

        class _Followup:
            async def send(self, content="", **kwargs) -> None:
                outer.followup_messages.append((content, kwargs))

        self.response = _Resp()
        self.followup = _Followup()

        # LongResponseView 가 interaction.user.send 를 쓴다.
        self.user = mock.MagicMock()
        self.user.send = mock.AsyncMock()

    async def edit_original_response(self, **kwargs) -> None:
        self.original_edits.append(kwargs)


def _make_ctx(store, ollama_manager=None) -> ViewCtx:
    return ViewCtx(
        store=store,
        ollama_manager=ollama_manager or _FakeOllamaManager(),  # type: ignore[arg-type]
        secret_key="test-secret-key",
    )


def _button_by_label(view: discord.ui.View, label: str) -> discord.ui.Button:
    for child in view.children:
        if isinstance(child, discord.ui.Button) and child.label == label:
            return child
    raise AssertionError(f"button {label!r} not found")


# ---------------------------------------------------------------------------
# _external_models_for — GEMINI 분기 + 폴백 (85-87)
# ---------------------------------------------------------------------------


class TestExternalModelsFor(unittest.TestCase):
    def test_openai(self) -> None:
        self.assertIs(_external_models_for(LLMProvider.OPENAI), OPENAI_MODELS)

    def test_anthropic(self) -> None:
        self.assertIs(_external_models_for(LLMProvider.ANTHROPIC), ANTHROPIC_MODELS)

    def test_gemini(self) -> None:
        self.assertIs(_external_models_for(LLMProvider.GEMINI), GEMINI_MODELS)

    def test_ollama_returns_empty(self) -> None:
        self.assertEqual(_external_models_for(LLMProvider.OLLAMA), [])


# ---------------------------------------------------------------------------
# _ollama_model_embed empty branch (194) + _install_embed (200-206)
# ---------------------------------------------------------------------------


class TestEmbedHelpers(unittest.TestCase):
    def test_ollama_embed_with_installed_marks_current(self) -> None:
        installed = [
            OllamaModel(name="llama3.1:8b", size_bytes=10**9),
            OllamaModel(name="qwen2.5:7b", size_bytes=2 * 10**9),
        ]
        embed = _ollama_model_embed(installed, "llama3.1:8b")
        listing = next(f.value for f in embed.fields if "설치된 모델" in f.name)
        self.assertIn("✅", listing)  # 현재 모델 표시
        self.assertIn("◦", listing)  # 비현재 모델 표시

    def test_ollama_embed_empty_sets_warning_description(self) -> None:
        embed = _ollama_model_embed([], "llama3.1:8b")
        self.assertIsNotNone(embed.description)
        self.assertIn("설치된 모델이 없습니다", embed.description or "")
        # 현재 사용 중 필드는 항상 존재.
        self.assertIn("현재 사용 중", [f.name for f in embed.fields])

    def test_install_embed_shows_model_and_status(self) -> None:
        embed = _install_embed("llama3.1:8b", "⏳ 설치 중")
        self.assertIn("llama3.1:8b", embed.description or "")
        status_field = next(f for f in embed.fields if f.name == "상태")
        self.assertEqual(status_field.value, "⏳ 설치 중")


# ---------------------------------------------------------------------------
# 키 검증 헬퍼 (269-280, 285-330) — urllib 전부 mock
# ---------------------------------------------------------------------------


def _http_error(code: int) -> urllib_error.HTTPError:
    return urllib_error.HTTPError(
        url="http://x", code=code, msg="err", hdrs=None, fp=None  # type: ignore[arg-type]
    )


class _FakeResp:
    def __init__(self, status: int) -> None:
        self.status = status

    def __enter__(self):
        return self

    def __exit__(self, *exc) -> None:
        return None


class TestValidateOpenAIKey(unittest.TestCase):
    def test_200_is_valid(self) -> None:
        with mock.patch("discord_assistant.ui.urllib_request.urlopen", return_value=_FakeResp(200)):
            self.assertTrue(_validate_openai_key("sk-good"))

    def test_non_200_is_invalid(self) -> None:
        with mock.patch("discord_assistant.ui.urllib_request.urlopen", return_value=_FakeResp(500)):
            self.assertFalse(_validate_openai_key("sk-x"))

    def test_http_401_is_invalid(self) -> None:
        with mock.patch(
            "discord_assistant.ui.urllib_request.urlopen", side_effect=_http_error(401)
        ):
            self.assertFalse(_validate_openai_key("sk-bad"))

    def test_http_500_is_treated_as_valid(self) -> None:
        # 429/5xx 등 일시 장애는 통과시키는 관대 정책(등록을 막지 않음).
        with mock.patch(
            "discord_assistant.ui.urllib_request.urlopen", side_effect=_http_error(500)
        ):
            self.assertTrue(_validate_openai_key("sk-x"))

    def test_http_400_is_invalid(self) -> None:
        # finding #93: 400(잘못된 요청)도 명시적 무효 키 신호로 거부한다.
        with mock.patch(
            "discord_assistant.ui.urllib_request.urlopen", side_effect=_http_error(400)
        ):
            self.assertFalse(_validate_openai_key("sk-x"))

    def test_http_404_is_invalid(self) -> None:
        with mock.patch(
            "discord_assistant.ui.urllib_request.urlopen", side_effect=_http_error(404)
        ):
            self.assertFalse(_validate_openai_key("sk-x"))

    def test_generic_exception_is_invalid(self) -> None:
        with mock.patch(
            "discord_assistant.ui.urllib_request.urlopen", side_effect=OSError("net down")
        ):
            self.assertFalse(_validate_openai_key("sk-x"))


class TestValidateAnthropicKey(unittest.TestCase):
    def test_201_is_valid(self) -> None:
        with mock.patch("discord_assistant.ui.urllib_request.urlopen", return_value=_FakeResp(201)):
            self.assertTrue(_validate_anthropic_key("sk-ant-good"))

    def test_200_is_valid(self) -> None:
        with mock.patch("discord_assistant.ui.urllib_request.urlopen", return_value=_FakeResp(200)):
            self.assertTrue(_validate_anthropic_key("sk-ant-good"))

    def test_403_is_invalid(self) -> None:
        with mock.patch(
            "discord_assistant.ui.urllib_request.urlopen", side_effect=_http_error(403)
        ):
            self.assertFalse(_validate_anthropic_key("sk-ant-bad"))

    def test_http_429_treated_as_valid(self) -> None:
        with mock.patch(
            "discord_assistant.ui.urllib_request.urlopen", side_effect=_http_error(429)
        ):
            self.assertTrue(_validate_anthropic_key("sk-ant-x"))

    def test_generic_exception_is_invalid(self) -> None:
        with mock.patch(
            "discord_assistant.ui.urllib_request.urlopen", side_effect=RuntimeError("boom")
        ):
            self.assertFalse(_validate_anthropic_key("sk-ant-x"))


class TestValidateGeminiKey(unittest.TestCase):
    def test_200_is_valid(self) -> None:
        with mock.patch("discord_assistant.ui.urllib_request.urlopen", return_value=_FakeResp(200)):
            self.assertTrue(_validate_gemini_key("AIza-good"))

    def test_403_is_invalid(self) -> None:
        with mock.patch(
            "discord_assistant.ui.urllib_request.urlopen", side_effect=_http_error(403)
        ):
            self.assertFalse(_validate_gemini_key("AIza-bad"))

    def test_http_500_treated_as_valid(self) -> None:
        with mock.patch(
            "discord_assistant.ui.urllib_request.urlopen", side_effect=_http_error(503)
        ):
            self.assertTrue(_validate_gemini_key("AIza-x"))

    def test_http_400_is_invalid(self) -> None:
        # finding #93: Gemini 는 무효 키에 400 API_KEY_INVALID 를 돌려주므로
        # 400 은 무효로 거부해야 한다(잘못된 키가 저장되는 것을 방지).
        with mock.patch(
            "discord_assistant.ui.urllib_request.urlopen", side_effect=_http_error(400)
        ):
            self.assertFalse(_validate_gemini_key("AIza-bad"))

    def test_generic_exception_is_invalid(self) -> None:
        with mock.patch(
            "discord_assistant.ui.urllib_request.urlopen", side_effect=Exception("x")
        ):
            self.assertFalse(_validate_gemini_key("AIza-x"))


# ---------------------------------------------------------------------------
# _APIKeyModal — GEMINI 검증 분기 (393) + on_error (397)
# ---------------------------------------------------------------------------


class TestAPIKeyModalBranches(unittest.TestCase):
    def test_validate_key_gemini_uses_gemini_validator(self) -> None:
        ctx = _make_ctx(_RichStore())
        modal = _APIKeyModal(
            provider=LLMProvider.GEMINI, model="gemini-1.5-flash", ctx=ctx, guild_id=9
        )
        with mock.patch(
            "discord_assistant.ui._validate_gemini_key", return_value=True
        ) as gem:
            self.assertTrue(modal._validate_key("AIza-x"))
        gem.assert_called_once_with("AIza-x")

    def test_gemini_valid_key_saves(self) -> None:
        store = _RichStore(_make_config(provider=LLMProvider.GEMINI))
        ctx = _make_ctx(store)
        modal = _APIKeyModal(
            provider=LLMProvider.GEMINI, model="gemini-1.5-flash", ctx=ctx, guild_id=9
        )
        modal.api_key_input._value = "AIza-good"  # type: ignore[attr-defined]
        interaction = _RichInteraction()
        with mock.patch("discord_assistant.ui._validate_gemini_key", return_value=True), \
             mock.patch("discord_assistant.ui.encrypt_api_key", return_value="ENC-G"):
            asyncio.run(modal.on_submit(interaction))  # type: ignore[arg-type]
        self.assertEqual(store.set_provider_calls[-1]["key"], "ENC-G")
        self.assertEqual(store.set_provider_calls[-1]["provider"], LLMProvider.GEMINI)

    def test_on_error_reports_error(self) -> None:
        ctx = _make_ctx(_RichStore())
        modal = _APIKeyModal(
            provider=LLMProvider.OPENAI, model="gpt-4o-mini", ctx=ctx, guild_id=9
        )
        interaction = _RichInteraction()
        asyncio.run(modal.on_error(interaction, ValueError("boom")))  # type: ignore[arg-type]
        self.assertTrue(interaction.sent_messages)
        self.assertIn("오류", interaction.sent_messages[0][0])
        self.assertIn("boom", interaction.sent_messages[0][0])


# ---------------------------------------------------------------------------
# _SummaryLimitModal.on_submit (414-427) + _CustomModelModal.on_submit (444-455)
# ---------------------------------------------------------------------------


class TestSummaryLimitModal(unittest.TestCase):
    def test_non_numeric_input_warns(self) -> None:
        store = _RichStore()
        modal = _SummaryLimitModal(ctx=_make_ctx(store), guild_id=1)
        modal.limit_input._value = "abc"  # type: ignore[attr-defined]
        interaction = _RichInteraction()
        asyncio.run(modal.on_submit(interaction))  # type: ignore[arg-type]
        self.assertEqual(len(store.set_summary_limit_calls), 0)
        self.assertIn("숫자", interaction.sent_messages[0][0])

    def test_out_of_range_value_error_warns(self) -> None:
        store = _RichStore()
        store.set_summary_limit_error = ValueError("1~200 사이여야 합니다")
        modal = _SummaryLimitModal(ctx=_make_ctx(store), guild_id=1)
        modal.limit_input._value = "999"  # type: ignore[attr-defined]
        interaction = _RichInteraction()
        asyncio.run(modal.on_submit(interaction))  # type: ignore[arg-type]
        self.assertTrue(interaction.sent_messages)
        self.assertIn("1~200", interaction.sent_messages[0][0])

    def test_valid_limit_saves_and_returns_to_general(self) -> None:
        store = _RichStore()
        modal = _SummaryLimitModal(ctx=_make_ctx(store), guild_id=5)
        modal.limit_input._value = "120"  # type: ignore[attr-defined]
        interaction = _RichInteraction()
        asyncio.run(modal.on_submit(interaction))  # type: ignore[arg-type]
        self.assertEqual(store.set_summary_limit_calls, [(5, 120)])
        self.assertIsInstance(
            interaction.edit_message_kwargs["view"], GeneralSettingsView
        )


class TestCustomModelModal(unittest.TestCase):
    def test_empty_model_name_warns(self) -> None:
        store = _RichStore()
        modal = _CustomModelModal(ctx=_make_ctx(store), guild_id=1)
        modal.model_input._value = "   "  # type: ignore[attr-defined]
        interaction = _RichInteraction()
        asyncio.run(modal.on_submit(interaction))  # type: ignore[arg-type]
        self.assertIn("모델명", interaction.sent_messages[0][0])

    def test_valid_model_name_starts_install(self) -> None:
        store = _RichStore()
        ollama = _FakeOllamaManager()
        modal = _CustomModelModal(ctx=_make_ctx(store, ollama), guild_id=1)
        modal.model_input._value = "qwen2.5:14b"  # type: ignore[attr-defined]
        interaction = _RichInteraction()
        # asyncio.create_task 로 백그라운드 설치가 뜨지 않게 막아 동기 단언만 한다.
        created: list[object] = []

        def _fake_create_task(coro):
            coro.close()  # 코루틴 미실행 경고 방지
            created.append(coro)
            return mock.MagicMock()

        with mock.patch("discord_assistant.ui.asyncio.create_task", side_effect=_fake_create_task):
            asyncio.run(modal.on_submit(interaction))  # type: ignore[arg-type]
        self.assertTrue(interaction.deferred)
        self.assertTrue(interaction.original_edits)
        self.assertIsInstance(interaction.original_edits[-1]["view"], ModelInstallView)
        self.assertEqual(len(created), 1)


# ---------------------------------------------------------------------------
# SettingsView.on_timeout (521-522)
# ---------------------------------------------------------------------------


class TestSettingsViewTimeout(unittest.TestCase):
    def test_on_timeout_disables_all_children(self) -> None:
        view = SettingsView(ctx=_make_ctx(_RichStore()), guild_id=1)
        for child in view.children:
            child.disabled = False  # type: ignore[attr-defined]
        asyncio.run(view.on_timeout())
        self.assertTrue(all(c.disabled for c in view.children))  # type: ignore[attr-defined]


# ---------------------------------------------------------------------------
# ExternalModelView buttons (633-645)
# ---------------------------------------------------------------------------


class TestExternalModelViewButtons(unittest.TestCase):
    def _view(self, store) -> ExternalModelView:
        return ExternalModelView(
            ctx=_make_ctx(store),
            guild_id=7,
            provider=LLMProvider.OPENAI,
            models=OPENAI_MODELS,
        )

    def test_enter_api_key_sends_modal(self) -> None:
        view = self._view(_RichStore(_make_config(provider=LLMProvider.OPENAI)))
        btn = _button_by_label(view, "API 키 등록 / 변경")
        interaction = _RichInteraction()
        asyncio.run(btn.callback(interaction))  # type: ignore[arg-type]
        self.assertEqual(len(interaction.sent_modals), 1)
        self.assertIsInstance(interaction.sent_modals[0], _APIKeyModal)
        self.assertEqual(interaction.sent_modals[0].provider, LLMProvider.OPENAI)

    def test_clear_api_key_clears_and_refreshes_embed(self) -> None:
        store = _RichStore(
            _make_config(provider=LLMProvider.OPENAI, api_key_encrypted="ENC")
        )
        view = self._view(store)
        btn = _button_by_label(view, "API 키 삭제")
        interaction = _RichInteraction()
        asyncio.run(btn.callback(interaction))  # type: ignore[arg-type]
        self.assertEqual(store.clear_api_key_calls, [7])
        self.assertIs(interaction.edit_message_kwargs["view"], view)
        # 키 미등록 상태 임베드 — warning 색.
        embed = interaction.edit_message_kwargs["embed"]
        self.assertEqual(embed.color, discord.Color.yellow())


# ---------------------------------------------------------------------------
# OllamaModelView (679-705) + ModelInstallView (741-803)
# ---------------------------------------------------------------------------


class TestOllamaModelView(unittest.TestCase):
    def _installed(self) -> list[OllamaModel]:
        return [
            OllamaModel(name="llama3.1:8b", size_bytes=10**9),
            OllamaModel(name="qwen2.5:7b", size_bytes=2 * 10**9),
        ]

    def test_select_updates_selected_and_edits(self) -> None:
        store = _RichStore(_make_config(model="llama3.1:8b"))
        view = OllamaModelView(ctx=_make_ctx(store), guild_id=1, installed=self._installed())
        interaction = _RichInteraction("qwen2.5:7b")
        asyncio.run(view._on_select(interaction))  # type: ignore[arg-type]
        self.assertEqual(view._selected, "qwen2.5:7b")
        self.assertIs(interaction.edit_message_kwargs["view"], view)

    def test_use_selected_without_selection_warns(self) -> None:
        store = _RichStore()
        view = OllamaModelView(ctx=_make_ctx(store), guild_id=1, installed=[])
        self.assertIsNone(view._selected)
        btn = _button_by_label(view, "이 모델 사용")
        interaction = _RichInteraction()
        asyncio.run(btn.callback(interaction))  # type: ignore[arg-type]
        self.assertIn("먼저 모델을 선택", interaction.sent_messages[0][0])
        self.assertEqual(len(store.set_model_calls), 0)

    def test_use_selected_saves_model_and_returns_to_settings(self) -> None:
        store = _RichStore(_make_config(provider=LLMProvider.OLLAMA))
        view = OllamaModelView(ctx=_make_ctx(store), guild_id=3, installed=self._installed())
        btn = _button_by_label(view, "이 모델 사용")
        interaction = _RichInteraction()
        asyncio.run(btn.callback(interaction))  # type: ignore[arg-type]
        # installed[0].name 이 기본 선택값.
        self.assertEqual(store.set_model_calls, [(3, "llama3.1:8b")])
        self.assertIsInstance(interaction.edit_message_kwargs["view"], SettingsView)

    def test_install_new_opens_install_view(self) -> None:
        store = _RichStore()
        view = OllamaModelView(ctx=_make_ctx(store), guild_id=1, installed=self._installed())
        btn = _button_by_label(view, "새 모델 설치")
        interaction = _RichInteraction()
        asyncio.run(btn.callback(interaction))  # type: ignore[arg-type]
        self.assertIsInstance(interaction.edit_message_kwargs["view"], ModelInstallView)


class TestModelInstallView(unittest.TestCase):
    def test_select_sets_chosen_model(self) -> None:
        view = ModelInstallView(ctx=_make_ctx(_RichStore()), guild_id=1)
        # POPULAR_MODELS 의 첫 모델 id 를 선택값으로 사용.
        chosen = OllamaManager.POPULAR_MODELS[0][0]
        interaction = _RichInteraction(chosen)
        asyncio.run(view._on_select(interaction))  # type: ignore[arg-type]
        self.assertEqual(view._selected, chosen)
        self.assertIn(chosen, interaction.edit_message_kwargs["embed"].description)

    def test_installed_models_get_check_suffix(self) -> None:
        installed = {OllamaManager.POPULAR_MODELS[0][0]}
        view = ModelInstallView(ctx=_make_ctx(_RichStore()), guild_id=1, installed_names=installed)
        select = next(c for c in view.children if isinstance(c, discord.ui.Select))
        marked = [o for o in select.options if o.label.endswith("✅")]
        self.assertEqual(len(marked), 1)

    def test_custom_input_button_sends_modal(self) -> None:
        view = ModelInstallView(ctx=_make_ctx(_RichStore()), guild_id=1)
        btn = _button_by_label(view, "직접 입력")
        interaction = _RichInteraction()
        asyncio.run(btn.callback(interaction))  # type: ignore[arg-type]
        self.assertIsInstance(interaction.sent_modals[0], _CustomModelModal)

    def test_start_install_without_selection_warns(self) -> None:
        view = ModelInstallView(ctx=_make_ctx(_RichStore()), guild_id=1)
        view._selected = None
        btn = _button_by_label(view, "설치 시작")
        interaction = _RichInteraction()
        asyncio.run(btn.callback(interaction))  # type: ignore[arg-type]
        self.assertIn("먼저 모델을 선택", interaction.sent_messages[0][0])

    def test_start_install_with_selection_defers_and_schedules(self) -> None:
        view = ModelInstallView(
            ctx=_make_ctx(_RichStore(), _FakeOllamaManager()), guild_id=1, model_name="qwen2.5:7b"
        )
        btn = _button_by_label(view, "설치 시작")
        interaction = _RichInteraction()
        created: list[object] = []

        def _fake_create_task(coro):
            coro.close()
            created.append(coro)
            return mock.MagicMock()

        with mock.patch("discord_assistant.ui.asyncio.create_task", side_effect=_fake_create_task):
            asyncio.run(btn.callback(interaction))  # type: ignore[arg-type]
        self.assertTrue(interaction.deferred)
        self.assertTrue(interaction.original_edits)
        self.assertIsNone(interaction.original_edits[-1]["view"])
        self.assertEqual(len(created), 1)

    def test_run_install_success_sets_model(self) -> None:
        store = _RichStore(_make_config(provider=LLMProvider.OLLAMA))
        ollama = _FakeOllamaManager()
        view = ModelInstallView(
            ctx=_make_ctx(store, ollama), guild_id=8, model_name="qwen2.5:7b"
        )
        interaction = _RichInteraction()
        asyncio.run(view.run_install(interaction))  # type: ignore[arg-type]
        self.assertEqual(ollama.pull_calls, ["qwen2.5:7b"])
        self.assertEqual(store.set_model_calls, [(8, "qwen2.5:7b")])
        # 성공 임베드 + _BackOnlyView.
        self.assertIn("설치 완료", interaction.original_edits[-1]["embed"].fields[0].value)
        self.assertIsInstance(interaction.original_edits[-1]["view"], _BackOnlyView)

    def test_run_install_ollama_error_reports_failure(self) -> None:
        # run_install 은 pull 후 set_model 결과에 대해 OllamaError 를 처리한다.
        store = _RichStore()
        store.set_model_error = OllamaError("set failed")
        ollama = _FakeOllamaManager()
        view = ModelInstallView(
            ctx=_make_ctx(store, ollama), guild_id=8, model_name="bad-model"
        )
        interaction = _RichInteraction()
        asyncio.run(view.run_install(interaction))  # type: ignore[arg-type]
        self.assertEqual(ollama.pull_calls, ["bad-model"])
        self.assertIn("설치 실패", interaction.original_edits[-1]["embed"].fields[0].value)
        self.assertIsInstance(interaction.original_edits[-1]["view"], _BackOnlyView)

    def test_run_install_unknown_error_reports_failure(self) -> None:
        store = _RichStore()
        store.set_model_error = RuntimeError("boom")
        ollama = _FakeOllamaManager()
        view = ModelInstallView(
            ctx=_make_ctx(store, ollama), guild_id=8, model_name="x"
        )
        interaction = _RichInteraction()
        asyncio.run(view.run_install(interaction))  # type: ignore[arg-type]
        self.assertIn("알 수 없는 오류", interaction.original_edits[-1]["embed"].fields[0].value)

    def test_run_install_pull_failure_reports_failure_and_skips_set_model(self) -> None:
        # finding #63: pull(다운로드) 실패가 회수되지 않아 '설치 완료' 로 잘못
        # 표시되던 버그의 회귀 테스트. pull 이 OllamaError 면 '설치 실패' 로 표시되고
        # set_model 은 호출되지 않아야 한다(없는 모델로 설정되는 정합성 위반 방지).
        store = _RichStore(_make_config(provider=LLMProvider.OLLAMA))
        ollama = _FakeOllamaManager(pull_error=OllamaError("pull failed"))
        view = ModelInstallView(
            ctx=_make_ctx(store, ollama), guild_id=8, model_name="bad-model"
        )
        interaction = _RichInteraction()
        asyncio.run(view.run_install(interaction))  # type: ignore[arg-type]
        self.assertEqual(ollama.pull_calls, ["bad-model"])
        self.assertEqual(store.set_model_calls, [])
        self.assertIn("설치 실패", interaction.original_edits[-1]["embed"].fields[0].value)
        self.assertIsInstance(interaction.original_edits[-1]["view"], _BackOnlyView)

    def test_run_install_swallows_expired_token_on_final_edit(self) -> None:
        # finding #64: 대형 다운로드가 인터랙션 토큰(15분)을 넘기면 최종 상태를 쓰는
        # edit_original_response 가 NotFound(만료된 웹훅 토큰)를 던진다. 이 예외가
        # run_install 밖으로 전파되면 'Task exception was never retrieved' 로 끝난다.
        # 최종 edit 은 best-effort 로 삼켜야 한다.
        store = _RichStore(_make_config(provider=LLMProvider.OLLAMA))
        ollama = _FakeOllamaManager()
        view = ModelInstallView(
            ctx=_make_ctx(store, ollama), guild_id=8, model_name="qwen2.5:7b"
        )
        interaction = _RichInteraction()
        resp = mock.MagicMock(status=401)
        interaction.edit_original_response = mock.AsyncMock(  # type: ignore[method-assign]
            side_effect=discord.NotFound(resp, "Invalid Webhook Token")
        )
        # 예외를 던지지 않고 정상 반환해야 한다(설치 자체는 성공 처리).
        asyncio.run(view.run_install(interaction))  # type: ignore[arg-type]
        self.assertEqual(store.set_model_calls, [(8, "qwen2.5:7b")])


# ---------------------------------------------------------------------------
# HelpView show_analysis / show_settings (970-976)
# ---------------------------------------------------------------------------


class TestHelpViewCallbacks(unittest.TestCase):
    def test_show_analysis_renders_localized_embed(self) -> None:
        view = HelpView("en")
        interaction = _RichInteraction()
        asyncio.run(view.show_analysis(interaction))  # type: ignore[arg-type]
        self.assertEqual(
            interaction.edit_message_kwargs["embed"].title, "Channel Analysis"
        )
        self.assertIs(interaction.edit_message_kwargs["view"], view)

    def test_show_settings_renders_localized_embed(self) -> None:
        view = HelpView("en")
        interaction = _RichInteraction()
        asyncio.run(view.show_settings(interaction))  # type: ignore[arg-type]
        self.assertEqual(interaction.edit_message_kwargs["embed"].title, "Settings")

    def test_show_settings_ko_default(self) -> None:
        view = HelpView()
        interaction = _RichInteraction()
        asyncio.run(view.show_settings(interaction))  # type: ignore[arg-type]
        self.assertEqual(interaction.edit_message_kwargs["embed"].title, "설정")


# ---------------------------------------------------------------------------
# LongResponseView.send_dm (996-1011)
# ---------------------------------------------------------------------------


class TestLongResponseView(unittest.TestCase):
    def test_send_dm_chunks_and_confirms(self) -> None:
        text = "A" * 4000  # 1900 단위로 3개 청크.
        view = LongResponseView(full_text=text)
        btn = _button_by_label(view, "전체 응답 보기 (DM으로 받기)")
        interaction = _RichInteraction()
        asyncio.run(btn.callback(interaction))  # type: ignore[arg-type]
        self.assertEqual(interaction.user.send.await_count, 3)
        self.assertIn("DM으로 전체 응답", interaction.sent_messages[0][0])

    def test_send_dm_forbidden_warns(self) -> None:
        view = LongResponseView(full_text="hello")
        btn = _button_by_label(view, "전체 응답 보기 (DM으로 받기)")
        interaction = _RichInteraction()
        interaction.user.send = mock.AsyncMock(
            side_effect=discord.Forbidden(mock.MagicMock(status=403), "no dm")
        )
        asyncio.run(btn.callback(interaction))  # type: ignore[arg-type]
        self.assertIn("DM을 보낼 수 없", interaction.sent_messages[0][0])

    def test_send_dm_http_exception_warns(self) -> None:
        view = LongResponseView(full_text="hello")
        btn = _button_by_label(view, "전체 응답 보기 (DM으로 받기)")
        interaction = _RichInteraction()
        resp = mock.MagicMock(status=500)
        interaction.user.send = mock.AsyncMock(
            side_effect=discord.HTTPException(resp, "server error")
        )
        asyncio.run(btn.callback(interaction))  # type: ignore[arg-type]
        self.assertIn("DM 전송 중 오류", interaction.sent_messages[0][0])

    def test_interaction_check_allows_all_when_no_author(self) -> None:
        # finding #65: author_id 미지정 시 하위 호환 — 모두 허용.
        view = LongResponseView(full_text="hello")
        interaction = _RichInteraction()
        interaction.user.id = 999
        self.assertTrue(asyncio.run(view.interaction_check(interaction)))  # type: ignore[arg-type]

    def test_interaction_check_blocks_other_user(self) -> None:
        # finding #65: author_id 지정 시 다른 사용자는 거부(ephemeral 안내 + False).
        view = LongResponseView(full_text="hello", author_id=42)
        interaction = _RichInteraction()
        interaction.user.id = 999
        self.assertFalse(asyncio.run(view.interaction_check(interaction)))  # type: ignore[arg-type]
        self.assertTrue(interaction.sent_messages)
        self.assertEqual(interaction.sent_messages[0][1].get("ephemeral"), True)

    def test_interaction_check_allows_author(self) -> None:
        view = LongResponseView(full_text="hello", author_id=42)
        interaction = _RichInteraction()
        interaction.user.id = 42
        self.assertTrue(asyncio.run(view.interaction_check(interaction)))  # type: ignore[arg-type]


# ---------------------------------------------------------------------------
# SummarizeResultView.re_summarize (1037-1040)
# ---------------------------------------------------------------------------


class TestSummarizeResultView(unittest.TestCase):
    def test_re_summarize_defers_and_invokes_callback(self) -> None:
        called: list[object] = []

        async def _cb(interaction):
            called.append(interaction)

        view = SummarizeResultView(
            store=_RichStore(),  # type: ignore[arg-type]
            guild_id=1,
            channel=mock.MagicMock(),
            message_limit=50,
            max_context_chars=1000,
            language="ko",
            llm_callback=_cb,
        )
        btn = _button_by_label(view, "다시 요약하기")
        interaction = _RichInteraction()
        asyncio.run(btn.callback(interaction))  # type: ignore[arg-type]
        self.assertTrue(interaction.deferred)
        self.assertEqual(len(called), 1)


# ---------------------------------------------------------------------------
# GeneralSettingsView.go_back (1065) + LanguageSelectView.on_timeout (1108-1109)
# + _BackButton/_BackOnlyView/_go_to_main (1117-1139)
# ---------------------------------------------------------------------------


class TestNavigationHelpers(unittest.TestCase):
    def test_general_settings_go_back_returns_to_main(self) -> None:
        store = _RichStore(_make_config(provider=LLMProvider.OLLAMA))
        view = GeneralSettingsView(ctx=_make_ctx(store), guild_id=1)
        btn = _button_by_label(view, "← 뒤로")
        interaction = _RichInteraction()
        asyncio.run(btn.callback(interaction))  # type: ignore[arg-type]
        self.assertIsInstance(interaction.edit_message_kwargs["view"], SettingsView)

    def test_language_select_view_on_timeout_disables(self) -> None:
        view = LanguageSelectView(ctx=_make_ctx(_RichStore()), guild_id=1, current="ko")
        for child in view.children:
            child.disabled = False  # type: ignore[attr-defined]
        asyncio.run(view.on_timeout())
        self.assertTrue(all(c.disabled for c in view.children))  # type: ignore[attr-defined]

    def test_back_button_callback_goes_to_main(self) -> None:
        store = _RichStore(_make_config(provider=LLMProvider.OPENAI))
        btn = _BackButton(ctx=_make_ctx(store), guild_id=1)
        interaction = _RichInteraction()
        asyncio.run(btn.callback(interaction))  # type: ignore[arg-type]
        self.assertIsInstance(interaction.edit_message_kwargs["view"], SettingsView)

    def test_back_only_view_contains_back_button(self) -> None:
        view = _BackOnlyView(ctx=_make_ctx(_RichStore()), guild_id=1)
        labels = [getattr(c, "label", None) for c in view.children]
        self.assertIn("← 뒤로", labels)

    def test_go_to_main_uses_guild_name_when_present(self) -> None:
        store = _RichStore(_make_config())
        interaction = _RichInteraction()
        interaction.guild = mock.MagicMock(name="Guildy")
        interaction.guild.name = "Guildy"
        asyncio.run(_go_to_main(interaction, ctx=_make_ctx(store), guild_id=1))  # type: ignore[arg-type]
        self.assertIn("Guildy", interaction.edit_message_kwargs["embed"].description)

    def test_go_to_main_falls_back_when_no_guild(self) -> None:
        store = _RichStore(_make_config())
        interaction = _RichInteraction()
        interaction.guild = None
        asyncio.run(_go_to_main(interaction, ctx=_make_ctx(store), guild_id=1))  # type: ignore[arg-type]
        self.assertIn("서버", interaction.edit_message_kwargs["embed"].description)


# ---------------------------------------------------------------------------
# _FollowUpModal / FollowUpView (1158-1183) + ChannelSelectView (1191-1236)
# ---------------------------------------------------------------------------


class TestFollowUp(unittest.TestCase):
    def test_modal_on_submit_invokes_callback_with_value(self) -> None:
        seen: list[tuple[object, str]] = []

        async def _cb(interaction, text):
            seen.append((interaction, text))

        modal = _FollowUpModal(callback=_cb)
        modal.question_input._value = "  추가 질문  "  # type: ignore[attr-defined]
        interaction = _RichInteraction()
        asyncio.run(modal.on_submit(interaction))  # type: ignore[arg-type]
        self.assertEqual(seen[0][1], "추가 질문")  # strip 됨

    def test_modal_on_error_reports(self) -> None:
        async def _cb(interaction, text):
            return None

        modal = _FollowUpModal(callback=_cb)
        interaction = _RichInteraction()
        asyncio.run(modal.on_error(interaction, ValueError("oops")))  # type: ignore[arg-type]
        self.assertIn("오류", interaction.sent_messages[0][0])

    def test_view_button_sends_modal(self) -> None:
        async def _cb(interaction, text):
            return None

        view = FollowUpView(on_follow_up=_cb)
        btn = _button_by_label(view, "후속 질문")
        interaction = _RichInteraction()
        asyncio.run(btn.callback(interaction))  # type: ignore[arg-type]
        self.assertIsInstance(interaction.sent_modals[0], _FollowUpModal)

    def test_view_on_timeout_disables(self) -> None:
        async def _cb(interaction, text):
            return None

        view = FollowUpView(on_follow_up=_cb)
        for child in view.children:
            child.disabled = False  # type: ignore[attr-defined]
        asyncio.run(view.on_timeout())
        self.assertTrue(all(c.disabled for c in view.children))  # type: ignore[attr-defined]

    def test_interaction_check_allows_all_when_no_author(self) -> None:
        # finding #65: author_id 미지정 시 하위 호환 — 모두 허용.
        async def _cb(interaction, text):
            return None

        view = FollowUpView(on_follow_up=_cb)
        interaction = _RichInteraction()
        interaction.user.id = 7
        self.assertTrue(asyncio.run(view.interaction_check(interaction)))  # type: ignore[arg-type]

    def test_interaction_check_blocks_other_user(self) -> None:
        # finding #65: 타 사용자가 원작성자 맥락으로 후속 LLM 호출을 일으키지 못하게 거부.
        async def _cb(interaction, text):
            return None

        view = FollowUpView(on_follow_up=_cb, author_id=42)
        interaction = _RichInteraction()
        interaction.user.id = 7
        self.assertFalse(asyncio.run(view.interaction_check(interaction)))  # type: ignore[arg-type]
        self.assertTrue(interaction.sent_messages)
        self.assertEqual(interaction.sent_messages[0][1].get("ephemeral"), True)

    def test_interaction_check_allows_author(self) -> None:
        async def _cb(interaction, text):
            return None

        view = FollowUpView(on_follow_up=_cb, author_id=42)
        interaction = _RichInteraction()
        interaction.user.id = 42
        self.assertTrue(asyncio.run(view.interaction_check(interaction)))  # type: ignore[arg-type]


def _make_channel(ch_id: int, name: str, topic: str | None = "topic") -> mock.MagicMock:
    ch = mock.MagicMock(spec=discord.TextChannel)
    ch.id = ch_id
    ch.name = name
    ch.topic = topic
    return ch


class TestChannelSelectView(unittest.TestCase):
    def test_builds_options_from_channels(self) -> None:
        channels = [_make_channel(1, "general"), _make_channel(2, "random")]

        async def _confirm(interaction, selected):
            return None

        view = ChannelSelectView(channels=channels, on_confirm=_confirm)
        select = next(c for c in view.children if isinstance(c, discord.ui.Select))
        self.assertEqual({o.value for o in select.options}, {"1", "2"})

    def test_select_records_selection_and_defers(self) -> None:
        channels = [_make_channel(1, "general"), _make_channel(2, "random")]

        async def _confirm(interaction, selected):
            return None

        view = ChannelSelectView(channels=channels, on_confirm=_confirm)
        interaction = _RichInteraction(["1", "2"])
        asyncio.run(view._on_select(interaction))  # type: ignore[arg-type]
        self.assertEqual(view._selected, ["1", "2"])
        self.assertTrue(interaction.deferred)

    def test_confirm_without_selection_warns(self) -> None:
        async def _confirm(interaction, selected):
            raise AssertionError("should not be called")

        view = ChannelSelectView(channels=[_make_channel(1, "g")], on_confirm=_confirm)
        view._selected = []
        btn = _button_by_label(view, "요약 시작")
        interaction = _RichInteraction()
        asyncio.run(btn.callback(interaction))  # type: ignore[arg-type]
        self.assertIn("채널을 먼저 선택", interaction.sent_messages[0][0])

    def test_confirm_with_selection_invokes_callback(self) -> None:
        seen: list[list[str]] = []

        async def _confirm(interaction, selected):
            seen.append(selected)

        view = ChannelSelectView(channels=[_make_channel(1, "g")], on_confirm=_confirm)
        view._selected = ["1"]
        btn = _button_by_label(view, "요약 시작")
        interaction = _RichInteraction()
        asyncio.run(btn.callback(interaction))  # type: ignore[arg-type]
        self.assertEqual(seen, [["1"]])

    def test_on_timeout_disables(self) -> None:
        async def _confirm(interaction, selected):
            return None

        view = ChannelSelectView(channels=[_make_channel(1, "g")], on_confirm=_confirm)
        for child in view.children:
            child.disabled = False  # type: ignore[attr-defined]
        asyncio.run(view.on_timeout())
        self.assertTrue(all(c.disabled for c in view.children))  # type: ignore[attr-defined]

    def test_no_channels_means_no_select(self) -> None:
        async def _confirm(interaction, selected):
            return None

        view = ChannelSelectView(channels=[], on_confirm=_confirm)
        selects = [c for c in view.children if isinstance(c, discord.ui.Select)]
        self.assertEqual(selects, [])


# ---------------------------------------------------------------------------
# error_hint Gemini 분기 (1269-1270) + RetryView.on_timeout (1304-1306)
# ---------------------------------------------------------------------------


class TestErrorHintGemini(unittest.TestCase):
    def test_gemini_error_hint(self) -> None:
        self.assertIn("Gemini", error_hint(GeminiError("down")))


class TestRetryViewTimeout(unittest.TestCase):
    def test_on_timeout_disables_children(self) -> None:
        async def _cb(interaction):
            return None

        view = RetryView(on_retry=_cb)
        for child in view.children:
            child.disabled = False  # type: ignore[attr-defined]
        asyncio.run(view.on_timeout())
        self.assertTrue(all(c.disabled for c in view.children))  # type: ignore[attr-defined]


if __name__ == "__main__":
    unittest.main()
