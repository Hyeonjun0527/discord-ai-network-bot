"""bot.py 이벤트/수명주기/헬퍼 커버리지 보강 테스트 (bot-events).

대상(발췌):
  - 모듈 레벨 헬퍼: _make_error_embed / _is_retryable_error / _send_error_embed,
    _decode_remind_payload / _encode_remind_payload, _parse_remind_delay,
    _filter_choices + 자동완성 콜백, _locale_to_lang / CommandTranslator,
    _split_discord_text(코드블록/긴 줄), _send_channel_answer_with_overflow,
    _needs_provider_setup / _onboarding_embed, _ui_language, _has_config_permission,
    _track_task / _on_task_done / _cancel_background_tasks.
  - 이벤트 핸들러(create_bot 클로저): on_message(@멘션 질문/요약/이미지·답장 맥락·DM),
    on_raw_reaction_add(📝/🌐), on_reaction_add(👍/👎), 컨텍스트 메뉴 3종,
    on_disconnect/on_resumed/on_connect/on_error.
  - 슬래시 핸들러: remind_command(텍스트/마지막 요약/검증 실패) — 이를 통해
    _encode_remind_payload + _schedule_reminder(_track_task) 경로를 태운다.
  - 수명주기: run_bot graceful shutdown(시그널/네트워크 mock), main(observability mock).

규약 준수:
  - ConfigStore 는 tempfile 파일 DB 로 만들고 반드시 close 한다(:memory: 분리 이슈).
  - 네트워크/Discord/LLM 호출은 전부 mock. _get_llm/_collect_transcript 패치.
  - 기존 파일은 수정하지 않고 이 파일만 생성한다.
"""
from __future__ import annotations

import asyncio
import tempfile
import unittest
from pathlib import Path
from types import SimpleNamespace
from typing import Any
from unittest.mock import AsyncMock, MagicMock, patch

import discord

from discord_assistant import bot as bot_module
from discord_assistant.bot import (
    REACTION_SUMMARIZE,
    REACTION_TRANSLATE,
    THUMBS_DOWN,
    THUMBS_UP,
    CommandTranslator,
    UserFacingError,
    _cancel_background_tasks,
    _decode_remind_payload,
    _encode_remind_payload,
    _filter_choices,
    _has_config_permission,
    _is_retryable_error,
    _language_autocomplete,
    _locale_to_lang,
    _make_error_embed,
    _needs_provider_setup,
    _on_task_done,
    _onboarding_embed,
    _parse_remind_delay,
    _prompt_type_autocomplete,
    _send_answer_with_overflow,
    _send_channel_answer_with_overflow,
    _send_error_embed,
    _since_autocomplete,
    _split_discord_text,
    _track_task,
    _ui_language,
    create_bot,
    main,
    reset_cooldowns,
    run_bot,
)
from discord_assistant.llm import LLMError, TokenUsage
from discord_assistant.models import GuildConfig, LLMProvider
from discord_assistant.settings import AppSettings
from discord_assistant.storage import ConfigStore


# ---------------------------------------------------------------------------
# 공용 더블
# ---------------------------------------------------------------------------
class _FakeLLM:
    """generate/generate_stream/generate_with_tools 흉내 — 네트워크 없음."""

    def __init__(self, text: str = "가짜 응답입니다.") -> None:
        self.text = text
        self.last_usage = TokenUsage(prompt_tokens=5, completion_tokens=3)
        self.generate_calls: list[str] = []

    async def generate(self, prompt: str, *, model: str | None = None, **_: Any) -> str:
        self.generate_calls.append(prompt)
        return self.text

    async def generate_stream(self, prompt: str, *, model: str | None = None):
        for piece in (self.text[: len(self.text) // 2], self.text[len(self.text) // 2 :]):
            yield piece

    async def generate_with_tools(
        self, prompt: str, *, tools=None, tool_runner=None, model: str | None = None
    ) -> str:
        return self.text


def _make_settings(database_url: str) -> AppSettings:
    return AppSettings(
        discord_bot_token="test-token",
        ollama_base_url="http://localhost:11434",
        ollama_model="test-model",
        database_url=database_url,
        default_summary_limit=10,
        max_context_chars=12_000,
        default_language="ko",
        ollama_timeout_seconds=60,
        auto_sync_commands=False,
        secret_key="test-secret-key-1234567890",
        metrics_port=0,
    )


# ---------------------------------------------------------------------------
# 모듈 레벨 순수 헬퍼 — 봇/스토어 없이 단언만 한다.
# ---------------------------------------------------------------------------
class PureHelperTest(unittest.TestCase):
    def test_make_error_embed_user_facing(self) -> None:
        embed = _make_error_embed(UserFacingError("권한이 없어요"))
        self.assertEqual(embed.title, "오류")
        self.assertEqual(embed.description, "권한이 없어요")
        self.assertEqual(embed.color, discord.Color.red())

    def test_make_error_embed_llm_error_uses_hint(self) -> None:
        embed = _make_error_embed(LLMError("연결 실패"))
        # LLMError 는 orange + error_hint 기반 친절 문구.
        self.assertEqual(embed.color, discord.Color.orange())
        self.assertTrue(embed.description)

    def test_make_error_embed_generic_hides_detail(self) -> None:
        embed = _make_error_embed(ValueError("내부 비밀 정보"))
        self.assertNotIn("내부 비밀", embed.description)
        self.assertIn("예기치 않은 오류", embed.description)

    def test_is_retryable_error_branches(self) -> None:
        # 일반 예외 → 재시도 불가.
        self.assertFalse(_is_retryable_error(ValueError("x")))
        # 일반 LLM 오류(상태코드 없음) → 재시도 가능.
        self.assertTrue(_is_retryable_error(LLMError("timeout")))
        # 5xx → 재시도 가능.
        self.assertTrue(_is_retryable_error(LLMError("server", status_code=503)))
        # 401/403(키/권한) → 재시도해도 의미 없음.
        self.assertFalse(_is_retryable_error(LLMError("auth", status_code=401)))
        self.assertFalse(_is_retryable_error(LLMError("forbidden", status_code=403)))

    def test_remind_payload_round_trip_text(self) -> None:
        encoded = _encode_remind_payload("할 일 정리", kind="text", repeat="daily")
        decoded = _decode_remind_payload(encoded)
        self.assertEqual(decoded["kind"], "text")
        self.assertEqual(decoded["text"], "할 일 정리")
        self.assertEqual(decoded["repeat"], "daily")

    def test_remind_payload_summary_without_repeat(self) -> None:
        encoded = _encode_remind_payload("요약본", kind="summary")
        decoded = _decode_remind_payload(encoded)
        self.assertEqual(decoded["kind"], "summary")
        self.assertIsNone(decoded["repeat"])

    def test_remind_payload_legacy_plain_text(self) -> None:
        # JSON 이 아닌 레거시 payload 는 평문 텍스트로 취급한다.
        decoded = _decode_remind_payload("그냥 평문 메시지")
        self.assertEqual(decoded["kind"], "text")
        self.assertEqual(decoded["text"], "그냥 평문 메시지")
        self.assertIsNone(decoded["repeat"])

    def test_parse_remind_delay_units(self) -> None:
        from datetime import timedelta

        self.assertEqual(_parse_remind_delay("10"), timedelta(minutes=10))
        self.assertEqual(_parse_remind_delay("30m"), timedelta(minutes=30))
        self.assertEqual(_parse_remind_delay("2h"), timedelta(hours=2))
        self.assertEqual(_parse_remind_delay("1d"), timedelta(days=1))

    def test_parse_remind_delay_rejects_bad_input(self) -> None:
        with self.assertRaises(UserFacingError):
            _parse_remind_delay("abc")
        with self.assertRaises(UserFacingError):
            _parse_remind_delay("0")
        with self.assertRaises(UserFacingError):
            _parse_remind_delay("60d")  # > 30일 상한

    def test_filter_choices_filters_and_caps(self) -> None:
        pairs = [(f"label{i}", f"v{i}") for i in range(40)]
        out = _filter_choices(pairs, "")
        self.assertEqual(len(out), 25)  # 최대 25개로 제한.
        # needle 매칭(값 기준)도 동작한다.
        matched = _filter_choices([("한국어", "ko"), ("영어", "en")], "ko")
        self.assertEqual([c.value for c in matched], ["ko"])

    def test_locale_to_lang(self) -> None:
        self.assertEqual(_locale_to_lang(discord.Locale.american_english), "en")
        self.assertEqual(_locale_to_lang(discord.Locale.korean), "ko")
        self.assertIsNone(_locale_to_lang(discord.Locale.japanese))

    def test_ui_language_auto_falls_back_to_ko(self) -> None:
        cfg_auto = GuildConfig(guild_id=1, model="m", summary_limit=10, language="auto")
        self.assertEqual(_ui_language(cfg_auto), "ko")
        cfg_en = GuildConfig(guild_id=1, model="m", summary_limit=10, language="en")
        self.assertEqual(_ui_language(cfg_en), "en")

    def test_needs_provider_setup_external_and_ollama(self) -> None:
        # 외부 제공자: 키 없으면 설정 필요.
        ext = GuildConfig(
            guild_id=1, model="gpt", summary_limit=10, language="ko",
            provider=LLMProvider.OPENAI, api_key_encrypted=None,
        )
        self.assertTrue(_needs_provider_setup(ext, ollama_has_model=True))
        ext_ok = GuildConfig(
            guild_id=1, model="gpt", summary_limit=10, language="ko",
            provider=LLMProvider.OPENAI, api_key_encrypted="enc",
        )
        self.assertFalse(_needs_provider_setup(ext_ok, ollama_has_model=True))
        # Ollama: 모델 없으면 설정 필요.
        oll = GuildConfig(
            guild_id=1, model="llama", summary_limit=10, language="ko",
            provider=LLMProvider.OLLAMA,
        )
        self.assertTrue(_needs_provider_setup(oll, ollama_has_model=False))
        self.assertFalse(_needs_provider_setup(oll, ollama_has_model=True))

    def test_onboarding_embed_external_vs_ollama(self) -> None:
        ext = GuildConfig(
            guild_id=1, model="gpt", summary_limit=10, language="ko",
            provider=LLMProvider.ANTHROPIC, api_key_encrypted=None,
        )
        e_ext = _onboarding_embed(ext, ollama_has_model=True)
        self.assertIn("API 키", e_ext.fields[0].value)
        oll = GuildConfig(
            guild_id=1, model="llama", summary_limit=10, language="ko",
            provider=LLMProvider.OLLAMA,
        )
        e_oll = _onboarding_embed(oll, ollama_has_model=False)
        self.assertIn("Ollama", e_oll.fields[0].value)

    def test_split_discord_text_long_line_non_code(self) -> None:
        # 한도를 넘는 단일 줄은 max_chars 단위로 쪼갠다.
        line = "x" * 5000
        chunks = _split_discord_text(line, max_chars=1900)
        self.assertTrue(len(chunks) >= 3)
        self.assertTrue(all(len(c) <= 1900 for c in chunks))

    def test_split_discord_text_code_block_preserved(self) -> None:
        # 코드 블록 안의 긴 줄은 fence 를 유지하며 쪼갠다.
        body = "```python\n" + ("a" * 5000) + "\n```"
        chunks = _split_discord_text(body, max_chars=1900)
        self.assertTrue(len(chunks) >= 2)
        # 각 청크는 닫힌 코드블록(짝수 fence)을 유지해야 한다.
        for c in chunks:
            self.assertEqual(c.count("```") % 2, 0)

    def test_split_discord_text_empty(self) -> None:
        self.assertEqual(_split_discord_text("   "), ["(empty response)"])

    def test_has_config_permission_admin_and_role(self) -> None:
        # 관리자 권한 → True.
        admin = MagicMock()
        admin.user.guild_permissions = SimpleNamespace(administrator=True, manage_guild=False)
        admin.user.roles = []
        self.assertTrue(_has_config_permission(admin, None))
        # 권한 없음 + 역할 불일치 → False.
        plain = MagicMock()
        plain.user.guild_permissions = SimpleNamespace(administrator=False, manage_guild=False)
        plain.user.roles = [SimpleNamespace(id=5)]
        self.assertFalse(_has_config_permission(plain, 99))
        # 지정 역할 보유 → True.
        roled = MagicMock()
        roled.user.guild_permissions = SimpleNamespace(administrator=False, manage_guild=False)
        roled.user.roles = [SimpleNamespace(id=99)]
        self.assertTrue(_has_config_permission(roled, 99))


# ---------------------------------------------------------------------------
# _send_error_embed / _send_channel_answer_with_overflow / _send_answer_with_overflow
# — 응답 더블을 직접 만들어 분기를 단언한다.
# ---------------------------------------------------------------------------
class SendHelperTest(unittest.IsolatedAsyncioTestCase):
    def _interaction(self, *, done: bool) -> MagicMock:
        inter = MagicMock(name="Interaction")
        inter.response = MagicMock()
        inter.response.is_done = MagicMock(return_value=done)
        inter.response.send_message = AsyncMock()
        inter.followup = MagicMock()
        inter.followup.send = AsyncMock(return_value=MagicMock(id=1))
        return inter

    async def test_send_error_embed_before_response_uses_send_message(self) -> None:
        inter = self._interaction(done=False)
        await _send_error_embed(inter, UserFacingError("입력 오류"))
        inter.response.send_message.assert_awaited_once()
        inter.followup.send.assert_not_called()
        kwargs = inter.response.send_message.await_args.kwargs
        self.assertTrue(kwargs["ephemeral"])
        self.assertIn("embed", kwargs)
        # 입력성 오류라 RetryView 는 붙지 않는다.
        self.assertNotIn("view", kwargs)

    async def test_send_error_embed_after_response_uses_followup_with_retry(self) -> None:
        inter = self._interaction(done=True)
        retried: list[bool] = []

        async def _retry(_i: discord.Interaction) -> None:
            retried.append(True)

        # 재시도 가능한 LLM 오류 → followup + RetryView.
        await _send_error_embed(inter, LLMError("connection reset"), retry=_retry)
        inter.followup.send.assert_awaited_once()
        kwargs = inter.followup.send.await_args.kwargs
        self.assertIn("view", kwargs)
        self.assertIsNotNone(kwargs["view"])

    async def test_send_error_embed_non_retryable_no_view(self) -> None:
        inter = self._interaction(done=True)

        async def _retry(_i: discord.Interaction) -> None:
            return None

        # 401 → 재시도 무의미 → view 없음.
        await _send_error_embed(inter, LLMError("auth", status_code=401), retry=_retry)
        kwargs = inter.followup.send.await_args.kwargs
        self.assertNotIn("view", kwargs)

    async def test_send_channel_answer_short_sends_plain(self) -> None:
        channel = MagicMock()
        channel.send = AsyncMock()
        await _send_channel_answer_with_overflow(channel, "짧은 답변")
        channel.send.assert_awaited_once_with("짧은 답변")
        # view 인자가 없어야 한다.
        self.assertNotIn("view", channel.send.await_args.kwargs)

    async def test_send_channel_answer_long_uses_preview_view(self) -> None:
        channel = MagicMock()
        channel.send = AsyncMock()
        long_text = "가" * 3000
        await _send_channel_answer_with_overflow(channel, long_text)
        channel.send.assert_awaited_once()
        # 프리뷰 + LongResponseView 로 한 번만 보낸다(메시지 폭탄 방지).
        self.assertIn("view", channel.send.await_args.kwargs)
        sent_text = channel.send.await_args.args[0]
        self.assertTrue(sent_text.endswith("…"))

    async def test_send_answer_with_overflow_before_response_returns_none(self) -> None:
        inter = self._interaction(done=False)
        result = await _send_answer_with_overflow(inter, "본문")
        self.assertIsNone(result)
        inter.response.send_message.assert_awaited_once()

    async def test_send_answer_with_overflow_followup_returns_message(self) -> None:
        inter = self._interaction(done=True)
        msg = await _send_answer_with_overflow(inter, "본문", return_message=True)
        self.assertIsNotNone(msg)
        inter.followup.send.assert_awaited_once()
        self.assertTrue(inter.followup.send.await_args.kwargs.get("wait"))


# ---------------------------------------------------------------------------
# 자동완성 콜백 — interaction 인자는 무시되므로 None 으로 호출 가능.
# ---------------------------------------------------------------------------
class AutocompleteTest(unittest.IsolatedAsyncioTestCase):
    async def test_language_autocomplete_includes_auto(self) -> None:
        choices = await _language_autocomplete(MagicMock(), "")
        values = [c.value for c in choices]
        self.assertIn("auto", values)
        self.assertLessEqual(len(choices), 25)

    async def test_language_autocomplete_filters(self) -> None:
        choices = await _language_autocomplete(MagicMock(), "ko")
        self.assertTrue(any(c.value == "ko" for c in choices))

    async def test_since_autocomplete(self) -> None:
        choices = await _since_autocomplete(MagicMock(), "1h")
        self.assertTrue(any(c.value == "1h" for c in choices))

    async def test_prompt_type_autocomplete(self) -> None:
        choices = await _prompt_type_autocomplete(MagicMock(), "")
        values = {c.value for c in choices}
        self.assertEqual(values, {"summarize", "ask"})


# ---------------------------------------------------------------------------
# CommandTranslator — locale 별 번역/폴백 분기.
# ---------------------------------------------------------------------------
class CommandTranslatorTest(unittest.IsolatedAsyncioTestCase):
    async def test_translate_to_english(self) -> None:
        translator = CommandTranslator()
        s = discord.app_commands.locale_str("봇 명령어 사용법을 안내합니다.")
        out = await translator.translate(s, discord.Locale.american_english, MagicMock())
        self.assertEqual(out, "Show how to use the bot's commands.")

    async def test_translate_korean_falls_back(self) -> None:
        translator = CommandTranslator()
        s = discord.app_commands.locale_str("봇 명령어 사용법을 안내합니다.")
        out = await translator.translate(s, discord.Locale.korean, MagicMock())
        self.assertIsNone(out)

    async def test_translate_unsupported_locale(self) -> None:
        translator = CommandTranslator()
        s = discord.app_commands.locale_str("봇 명령어 사용법을 안내합니다.")
        out = await translator.translate(s, discord.Locale.japanese, MagicMock())
        self.assertIsNone(out)

    async def test_translate_unknown_string(self) -> None:
        translator = CommandTranslator()
        s = discord.app_commands.locale_str("카탈로그에 없는 문자열")
        out = await translator.translate(s, discord.Locale.american_english, MagicMock())
        self.assertIsNone(out)


# ---------------------------------------------------------------------------
# 백그라운드 태스크 추적 — _track_task / _on_task_done / _cancel_background_tasks.
# ---------------------------------------------------------------------------
class BackgroundTaskTest(unittest.IsolatedAsyncioTestCase):
    async def test_track_task_completes_and_removes(self) -> None:
        async def _noop() -> None:
            return None

        task = _track_task(_noop(), name="noop")
        self.assertIn(task, bot_module._background_tasks)
        await task
        await asyncio.sleep(0)  # done 콜백 실행 기회.
        self.assertNotIn(task, bot_module._background_tasks)

    async def test_on_task_done_logs_exception(self) -> None:
        async def _boom() -> None:
            raise RuntimeError("의도된 실패")

        task = _track_task(_boom(), name="boom")
        # 예외가 전파되지 않고 done 콜백이 로깅만 한다.
        with self.assertLogs(bot_module.logger, level="ERROR"):
            await asyncio.gather(task, return_exceptions=True)
            await asyncio.sleep(0)

    async def test_cancel_background_tasks(self) -> None:
        async def _long() -> None:
            await asyncio.sleep(100)

        task = _track_task(_long(), name="long")
        await _cancel_background_tasks()
        self.assertTrue(task.cancelled() or task.done())

    async def test_on_task_done_cancelled_no_log(self) -> None:
        # 취소된 태스크는 예외 로깅 없이 조용히 정리된다.
        async def _long() -> None:
            await asyncio.sleep(100)

        task = asyncio.create_task(_long())
        task.cancel()
        with self.assertRaises(asyncio.CancelledError):
            await task
        # _on_task_done 을 직접 호출해도 예외를 던지지 않아야 한다.
        _on_task_done(task)


# ---------------------------------------------------------------------------
# 이벤트 핸들러 — 파일 DB + create_bot. test_bot_events.py 패턴 모방.
# ---------------------------------------------------------------------------
class _BotCase(unittest.IsolatedAsyncioTestCase):
    async def asyncSetUp(self) -> None:
        reset_cooldowns()
        bot_module._tracked_messages.clear()
        bot_module._last_summaries.clear()

        self._tmp = tempfile.TemporaryDirectory()
        db_path = Path(self._tmp.name) / "cov.db"
        self._db_url = f"sqlite:///{db_path}"

        self.store = ConfigStore(
            self._db_url,
            default_model="test-model",
            default_summary_limit=10,
            default_language="ko",
        )
        await self.store.initialize()

        self.settings = _make_settings(self._db_url)
        self.bot = create_bot(self.settings)
        # 봇 user.id 는 멘션/리액션 가드에서 쓰인다. 고정한다.
        self.bot._connection.user = SimpleNamespace(id=424242)

    async def asyncTearDown(self) -> None:
        await self.store.close()
        await self.bot.close()
        self._tmp.cleanup()
        reset_cooldowns()
        bot_module._tracked_messages.clear()
        bot_module._last_summaries.clear()

    def _patch_llm(self, llm: Any):
        return patch.object(bot_module, "_get_llm", return_value=llm)

    def _patch_transcript(self, text: str):
        return patch.object(
            bot_module, "_collect_transcript", AsyncMock(return_value=text)
        )

    def _ctx_menu_callback(self, name: str):
        for cmd in self.bot.tree.get_commands(type=discord.AppCommandType.message):
            if cmd.name == name:
                return cmd.callback
        raise AssertionError(f"context menu not found: {name}")


def _make_typing_channel() -> MagicMock:
    """async with channel.typing() 을 지원하는 채널 더블."""
    channel = MagicMock(name="Channel")
    channel.id = 333
    channel.send = AsyncMock()
    typing_cm = MagicMock()
    typing_cm.__aenter__ = AsyncMock(return_value=None)
    typing_cm.__aexit__ = AsyncMock(return_value=False)
    channel.typing = MagicMock(return_value=typing_cm)
    return channel


def _make_mention_message(
    bot_user_id: int,
    *,
    content: str,
    is_dm: bool = False,
    attachments: list | None = None,
) -> MagicMock:
    from datetime import datetime, timezone

    msg = MagicMock(name="Message")
    msg.author = SimpleNamespace(bot=False, id=111, display_name="tester")
    msg.attachments = attachments or []
    msg.content = content
    msg.created_at = datetime.now(timezone.utc)
    msg.reference = None
    msg.channel = _make_typing_channel()
    if is_dm:
        msg.guild = None
    else:
        msg.guild = SimpleNamespace(id=222)
    # bot.user 가 mentions 에 포함되도록 한다.
    msg.mentions = [SimpleNamespace(id=bot_user_id)]
    return msg


class OnMessageMentionTest(_BotCase):
    async def test_mention_question_answers(self) -> None:
        # process_commands 가 prefix 명령을 찾지 않도록 mock.
        self.bot.process_commands = AsyncMock()
        msg = _make_mention_message(
            424242, content="<@424242> 이게 무슨 뜻이야?"
        )
        llm = _FakeLLM("질문에 대한 답입니다.")
        with self._patch_llm(llm), self._patch_transcript("a: 안녕\nb: 반가워"):
            await self.bot.on_message(msg)
        # 질문 경로 → 채널로 답변 전송.
        msg.channel.send.assert_awaited()
        sent = msg.channel.send.await_args.args[0]
        self.assertIn("질문에 대한 답입니다.", sent)
        self.assertIn("질문:", sent)

    async def test_mention_without_question_summarizes(self) -> None:
        self.bot.process_commands = AsyncMock()
        msg = _make_mention_message(424242, content="<@424242>")
        llm = _FakeLLM("최근 대화 요약본")
        with self._patch_llm(llm), self._patch_transcript("a: 잡담\nb: 날씨"):
            await self.bot.on_message(msg)
        sent = msg.channel.send.await_args.args[0]
        self.assertIn("최근 대화 요약본", sent)
        self.assertIn("요약", sent)

    async def test_mention_empty_transcript_sends_warning(self) -> None:
        self.bot.process_commands = AsyncMock()
        msg = _make_mention_message(424242, content="<@424242> 질문")
        llm = _FakeLLM()
        with self._patch_llm(llm), self._patch_transcript(""):
            await self.bot.on_message(msg)
        # UserFacingError → 경고 메시지를 채널로 전송.
        sent_texts = [c.args[0] for c in msg.channel.send.await_args_list]
        self.assertTrue(any("참고할 최근 메시지가 없어요" in s for s in sent_texts))

    async def test_no_mention_does_nothing(self) -> None:
        self.bot.process_commands = AsyncMock()
        msg = _make_mention_message(424242, content="안녕하세요")
        msg.mentions = []  # 멘션 없음 → 응답 안 함.
        with self._patch_llm(_FakeLLM()) as get_llm:
            await self.bot.on_message(msg)
        get_llm.assert_not_called()
        msg.channel.send.assert_not_called()

    async def test_mention_disallowed_role_blocked(self) -> None:
        # #24/#90: allowed_role 가 설정되면 역할 없는 사용자는 @멘션으로도 차단된다.
        await self.store.set_allowed_role(222, role_id=999)
        self.bot.process_commands = AsyncMock()
        msg = _make_mention_message(424242, content="<@424242> 질문")
        # 멘션 작성자는 필요한 역할(999)이 없다.
        msg.author = SimpleNamespace(
            bot=False, id=111, display_name="tester", roles=[]
        )
        with self._patch_llm(_FakeLLM()) as get_llm, self._patch_transcript("a: hi"):
            await self.bot.on_message(msg)
        get_llm.assert_not_called()
        msg.channel.send.assert_not_called()

    async def test_mention_allowed_role_passes(self) -> None:
        # allowed_role 가 설정돼도 해당 역할을 가진 사용자는 정상 응답한다.
        await self.store.set_allowed_role(222, role_id=999)
        self.bot.process_commands = AsyncMock()
        msg = _make_mention_message(424242, content="<@424242> 질문이야")
        msg.author = SimpleNamespace(
            bot=False, id=111, display_name="tester",
            roles=[SimpleNamespace(id=999)],
        )
        llm = _FakeLLM("권한있는 응답")
        with self._patch_llm(llm), self._patch_transcript("a: hi\nb: yo"):
            await self.bot.on_message(msg)
        msg.channel.send.assert_awaited()
        sent = msg.channel.send.await_args.args[0]
        self.assertIn("권한있는 응답", sent)

    async def test_bot_author_skipped(self) -> None:
        self.bot.process_commands = AsyncMock()
        msg = _make_mention_message(424242, content="<@424242> 질문")
        msg.author = SimpleNamespace(bot=True, id=999, display_name="other-bot")
        with self._patch_llm(_FakeLLM()) as get_llm:
            await self.bot.on_message(msg)
        # 봇 작성자는 process_commands 만 타고 LLM 미호출.
        self.bot.process_commands.assert_awaited_once()
        get_llm.assert_not_called()

    async def test_mention_image_attachment_analysis(self) -> None:
        self.bot.process_commands = AsyncMock()
        att = SimpleNamespace(
            content_type="image/png",
            filename="a.png",
            size=100,
            read=AsyncMock(return_value=b"imgbytes"),
        )
        msg = _make_mention_message(
            424242, content="<@424242>", attachments=[att]
        )
        llm = _FakeLLM("이미지에는 고양이가 있습니다.")
        with self._patch_llm(llm), patch.object(
            bot_module, "supports_vision", return_value=True
        ):
            await self.bot.on_message(msg)
        sent = msg.channel.send.await_args.args[0]
        self.assertIn("이미지 분석", sent)
        self.assertIn("고양이", sent)

    async def test_mention_image_unsupported_model_falls_back(self) -> None:
        self.bot.process_commands = AsyncMock()
        att = SimpleNamespace(
            content_type="image/png",
            filename="a.png",
            size=100,
            read=AsyncMock(return_value=b"imgbytes"),
        )
        msg = _make_mention_message(
            424242, content="<@424242> 설명해줘", attachments=[att]
        )
        llm = _FakeLLM("텍스트 답변")
        with self._patch_llm(llm), self._patch_transcript("a: 텍스트"), patch.object(
            bot_module, "supports_vision", return_value=False
        ):
            await self.bot.on_message(msg)
        # 비지원 안내 후 텍스트 경로로 진행 → 채널 send 가 여러 번 호출됨.
        sent_texts = [c.args[0] for c in msg.channel.send.await_args_list]
        self.assertTrue(any("이미지 분석을 지원하지 않" in s for s in sent_texts))


class OnMessageDMTest(_BotCase):
    async def test_dm_chat_replies_and_saves_history(self) -> None:
        self.bot.process_commands = AsyncMock()
        msg = _make_mention_message(424242, content="안녕 봇", is_dm=True)
        msg.mentions = []
        llm = _FakeLLM("DM 답변입니다.")
        with self._patch_llm(llm):
            await self.bot.on_message(msg)
        msg.channel.send.assert_awaited()
        sent = msg.channel.send.await_args.args[0]
        self.assertIn("DM 답변입니다.", sent)
        # 대화가 저장됐는지 확인(user/assistant 두 턴).
        history = await self.store.get_chat_history(111, guild_id=None, limit=10)
        roles = [h["role"] for h in history]
        self.assertIn("user", roles)
        self.assertIn("assistant", roles)

    async def test_dm_uses_history_when_present(self) -> None:
        self.bot.process_commands = AsyncMock()
        # 직전 DM 대화를 미리 저장해 history 분기를 탄다.
        await self.store.save_chat_message(111, "user", "이전 질문", guild_id=None)
        await self.store.save_chat_message(111, "assistant", "이전 답변", guild_id=None)
        msg = _make_mention_message(424242, content="다음 질문", is_dm=True)
        msg.mentions = []
        with self._patch_llm(_FakeLLM("후속 답변")):
            await self.bot.on_message(msg)
        msg.channel.send.assert_awaited()

    async def test_dm_llm_error_surfaces_user_message(self) -> None:
        self.bot.process_commands = AsyncMock()
        msg = _make_mention_message(424242, content="질문", is_dm=True)
        msg.mentions = []

        class _Raising(_FakeLLM):
            async def generate(self, prompt, *, model=None, **_):
                raise LLMError("DM 실패")

        with self._patch_llm(_Raising()):
            await self.bot.on_message(msg)
        sent = msg.channel.send.await_args.args[0]
        self.assertIn("DM 실패", sent)


class OnMessageReplyTest(_BotCase):
    async def test_reply_to_bot_continues_conversation(self) -> None:
        self.bot.process_commands = AsyncMock()
        msg = _make_mention_message(424242, content="후속 질문이에요")
        msg.mentions = []  # 멘션 없이 답장만.
        # 봇이 보낸 메시지를 참조한다.
        referenced = MagicMock()
        referenced.author = SimpleNamespace(id=424242)  # 봇 자신.
        referenced.content = "봇의 이전 답변"
        msg.reference = SimpleNamespace(resolved=referenced, message_id=555)

        # discord.Message isinstance 체크를 통과하도록 패치.
        with self._patch_llm(_FakeLLM("이어지는 답변입니다.")), patch.object(
            bot_module.discord, "Message", MagicMock
        ):
            await self.bot.on_message(msg)
        sent = msg.channel.send.await_args.args[0]
        self.assertIn("이어지는 답변입니다.", sent)


class OnRawReactionTest(_BotCase):
    def _wire_channel(self, *, content: str = "요약할 긴 메시지입니다.") -> tuple[MagicMock, MagicMock]:
        target = MagicMock(name="Target")
        target.content = content
        target.reply = AsyncMock()
        channel = MagicMock(name="Channel")
        channel.fetch_message = AsyncMock(return_value=target)
        channel.send = AsyncMock()
        self.bot.get_channel = MagicMock(return_value=channel)
        return channel, target

    def _payload(self, *, emoji: str, user_id: int = 111) -> MagicMock:
        p = MagicMock()
        p.emoji = emoji
        p.message_id = 999
        p.channel_id = 333
        p.guild_id = 222
        p.user_id = user_id
        return p

    async def test_summarize_reaction(self) -> None:
        channel, target = self._wire_channel()
        with self._patch_llm(_FakeLLM("요약 결과")):
            await self.bot.on_raw_reaction_add(self._payload(emoji=REACTION_SUMMARIZE))
        target.reply.assert_awaited()
        self.assertIn("메시지 요약", target.reply.await_args.args[0])

    async def test_translate_reaction(self) -> None:
        channel, target = self._wire_channel()
        with self._patch_llm(_FakeLLM("Translated")):
            await self.bot.on_raw_reaction_add(self._payload(emoji=REACTION_TRANSLATE))
        self.assertIn("번역", target.reply.await_args.args[0])

    async def test_llm_error_replies_warning(self) -> None:
        channel, target = self._wire_channel()

        class _Raising(_FakeLLM):
            async def generate(self, prompt, *, model=None, **_):
                raise LLMError("리액션 LLM 실패")

        with self._patch_llm(_Raising()):
            await self.bot.on_raw_reaction_add(self._payload(emoji=REACTION_SUMMARIZE))
        # 오류 경로 → ⚠️ 답장.
        self.assertIn("리액션 LLM 실패", target.reply.await_args.args[0])

    async def test_channel_without_fetch_skipped(self) -> None:
        self.bot.get_channel = MagicMock(return_value=None)
        with self._patch_llm(_FakeLLM()) as get_llm:
            await self.bot.on_raw_reaction_add(self._payload(emoji=REACTION_SUMMARIZE))
        get_llm.assert_not_called()

    async def test_fetch_message_not_found_skipped(self) -> None:
        channel = MagicMock()
        channel.fetch_message = AsyncMock(side_effect=discord.NotFound(MagicMock(), "x"))
        self.bot.get_channel = MagicMock(return_value=channel)
        with self._patch_llm(_FakeLLM()) as get_llm:
            await self.bot.on_raw_reaction_add(self._payload(emoji=REACTION_SUMMARIZE))
        get_llm.assert_not_called()


class OnReactionFeedbackTest(_BotCase):
    async def test_thumbs_up_records_feedback(self) -> None:
        bot_module._tracked_messages[222] = {999: "summarize"}
        reaction = MagicMock()
        reaction.emoji = THUMBS_UP
        reaction.message = MagicMock()
        reaction.message.id = 999
        reaction.message.guild = SimpleNamespace(id=222)
        user = SimpleNamespace(bot=False, id=111)
        await self.bot.on_reaction_add(reaction, user)
        conn = await self.store._ensure_conn()
        cur = await conn.execute(
            "SELECT rating FROM feedback WHERE message_id = 999"
        )
        rows = await cur.fetchall()
        self.assertEqual(len(rows), 1)
        self.assertEqual(rows[0]["rating"], 1)

    async def test_thumbs_down_records_negative(self) -> None:
        bot_module._tracked_messages[222] = {999: "ask"}
        reaction = MagicMock()
        reaction.emoji = THUMBS_DOWN
        reaction.message = MagicMock()
        reaction.message.id = 999
        reaction.message.guild = SimpleNamespace(id=222)
        user = SimpleNamespace(bot=False, id=222)
        await self.bot.on_reaction_add(reaction, user)
        conn = await self.store._ensure_conn()
        cur = await conn.execute("SELECT rating FROM feedback WHERE message_id = 999")
        rows = await cur.fetchall()
        self.assertEqual(rows[0]["rating"], -1)


class ContextMenuTest(_BotCase):
    def _interaction(self) -> MagicMock:
        inter = MagicMock(name="Interaction")
        inter.id = 4242
        inter.user = SimpleNamespace(id=111, roles=[])
        inter.guild = SimpleNamespace(id=222, name="g")
        inter.channel = SimpleNamespace(id=333)
        resp = MagicMock()
        resp.is_done = MagicMock(return_value=False)

        def _done(*a, **k):
            resp.is_done.return_value = True

        resp.defer = AsyncMock(side_effect=_done)
        resp.send_message = AsyncMock(side_effect=_done)
        inter.response = resp
        inter.followup = MagicMock()
        inter.followup.send = AsyncMock(return_value=MagicMock(id=1))
        return inter

    async def test_translate_message_ctx(self) -> None:
        cb = self._ctx_menu_callback("메시지 번역")
        inter = self._interaction()
        message = SimpleNamespace(content="Hello world")
        with self._patch_llm(_FakeLLM("안녕 세상")):
            await cb(inter, message)
        sent = inter.followup.send.await_args.args[0]
        self.assertIn("번역", sent)
        self.assertIn("안녕 세상", sent)

    async def test_summarize_message_ctx(self) -> None:
        cb = self._ctx_menu_callback("메시지 요약")
        inter = self._interaction()
        message = SimpleNamespace(content="긴 메시지를 요약합니다.")
        with self._patch_llm(_FakeLLM("요약된 내용")):
            await cb(inter, message)
        sent = inter.followup.send.await_args.args[0]
        self.assertIn("메시지 요약", sent)

    async def test_ask_message_ctx(self) -> None:
        cb = self._ctx_menu_callback("이 메시지로 질문")
        inter = self._interaction()
        message = SimpleNamespace(content="설명이 필요한 메시지")
        with self._patch_llm(_FakeLLM("이건 이런 뜻입니다.")):
            await cb(inter, message)
        sent = inter.followup.send.await_args.args[0]
        self.assertIn("이 메시지로 질문", sent)

    async def test_ctx_menu_empty_message_guarded(self) -> None:
        cb = self._ctx_menu_callback("메시지 번역")
        inter = self._interaction()
        message = SimpleNamespace(content="   ")
        with self._patch_llm(_FakeLLM()) as get_llm:
            await cb(inter, message)
        # 빈 메시지 가드 → 안내 후 LLM 미호출.
        inter.response.send_message.assert_awaited()
        get_llm.assert_not_called()

    async def test_ctx_menu_cooldown_guarded(self) -> None:
        cb = self._ctx_menu_callback("메시지 요약")
        inter = self._interaction()
        message = SimpleNamespace(content="내용 있음")
        with self._patch_llm(_FakeLLM("요약")):
            await cb(inter, message)  # 첫 호출 성공.
        inter2 = self._interaction()
        with self._patch_llm(_FakeLLM()) as get_llm:
            await cb(inter2, message)  # 즉시 두 번째 → 쿨다운.
        get_llm.assert_not_called()
        inter2.response.send_message.assert_awaited()


class DisconnectEventTest(_BotCase):
    async def test_resumed_cancels_pending_alert(self) -> None:
        # on_disconnect 가 알림 태스크를 예약하면, on_resumed 가 취소한다.
        await self.bot.on_disconnect()
        await self.bot.on_resumed()
        # 대기 태스크가 없거나 취소됐는지만 확인(정확한 내부 상태는 흡수).
        await asyncio.sleep(0)

    async def test_connect_cancels_pending_alert(self) -> None:
        await self.bot.on_disconnect()
        await self.bot.on_connect()
        await asyncio.sleep(0)

    async def test_on_error_notifies_developer(self) -> None:
        # on_error 는 notify_developer 를 호출한다(예외 정보 없는 경로).
        with patch.object(bot_module, "notify_developer", AsyncMock()) as notify:
            await self.bot.on_error("on_message")
        notify.assert_awaited_once()

    async def test_on_error_with_exception_captures(self) -> None:
        with patch.object(bot_module, "notify_developer", AsyncMock()) as notify, \
                patch.object(bot_module.observability, "capture_exception") as cap:
            try:
                raise ValueError("이벤트 핸들러 폭발")
            except ValueError:
                await self.bot.on_error("on_message")
        notify.assert_awaited_once()
        cap.assert_called_once()


class RemindCommandTest(_BotCase):
    def _interaction(self) -> MagicMock:
        inter = MagicMock(name="Interaction")
        inter.id = 4242
        inter.user = SimpleNamespace(id=111, roles=[])
        inter.guild = SimpleNamespace(id=222, name="g")
        inter.channel = SimpleNamespace(id=333)
        inter.response = MagicMock()
        inter.response.send_message = AsyncMock()
        return inter

    def _callback(self, name: str):
        cmd = self.bot.tree.get_command(name)
        assert cmd is not None, f"command not found: {name}"
        return cmd.callback

    async def test_remind_with_text_schedules(self) -> None:
        inter = self._interaction()
        await self._callback("remind")(inter, when="1d", message="회의 준비", repeat="daily")
        inter.response.send_message.assert_awaited_once()
        confirm = inter.response.send_message.await_args.args[0]
        self.assertIn("DM으로 알림", confirm)
        # 실제 DB 에 reminder 가 저장됐는지 확인.
        rows = await self.store.list_by_user(111)
        self.assertEqual(len(rows), 1)
        decoded = _decode_remind_payload(rows[0].payload)
        self.assertEqual(decoded["text"], "회의 준비")
        self.assertEqual(decoded["repeat"], "daily")

    async def test_remind_uses_last_summary_when_no_message(self) -> None:
        # 마지막 요약 결과를 미리 채워 둔다.
        bot_module._last_summaries[111] = ("지난 요약본", 222)
        inter = self._interaction()
        await self._callback("remind")(inter, when="30m", message="", repeat="")
        rows = await self.store.list_by_user(111)
        decoded = _decode_remind_payload(rows[0].payload)
        self.assertEqual(decoded["kind"], "summary")
        self.assertEqual(decoded["text"], "지난 요약본")

    async def test_remind_no_message_no_summary_warns(self) -> None:
        inter = self._interaction()
        await self._callback("remind")(inter, when="30m", message="", repeat="")
        sent = inter.response.send_message.await_args.args[0]
        self.assertIn("보낼 내용이 없어요", sent)
        rows = await self.store.list_by_user(111)
        self.assertEqual(rows, [])

    async def test_remind_invalid_when_warns(self) -> None:
        inter = self._interaction()
        await self._callback("remind")(inter, when="not-a-time", message="x", repeat="")
        sent = inter.response.send_message.await_args.args[0]
        self.assertIn("올바른 형식", sent)


class ReminderDeliveryTest(_BotCase):
    """remind_command → _schedule_reminder → _deliver_reminder 전달 루프.

    asyncio.sleep 를 즉시 반환하도록 패치해 due_at 대기를 건너뛰고, _track_task 가
    띄운 reminder 태스크를 결정적으로 await 해 DM 전송/발송완료 표시를 단언한다.
    """

    def _interaction(self) -> MagicMock:
        inter = MagicMock(name="Interaction")
        inter.id = 4242
        inter.user = SimpleNamespace(id=111, roles=[])
        inter.guild = SimpleNamespace(id=222, name="g")
        inter.channel = SimpleNamespace(id=333)
        inter.response = MagicMock()
        inter.response.send_message = AsyncMock()
        return inter

    def _callback(self, name: str):
        cmd = self.bot.tree.get_command(name)
        assert cmd is not None
        return cmd.callback

    async def test_text_reminder_delivers_via_dm(self) -> None:
        delivered_user = MagicMock()
        delivered_user.send = AsyncMock()
        self.bot.get_user = MagicMock(return_value=delivered_user)

        # due_at 대기를 건너뛰도록 sleep 을 즉시 반환시킨다.
        real_sleep = asyncio.sleep

        async def _instant(delay, *a, **k):
            await real_sleep(0)

        inter = self._interaction()
        before = set(bot_module._background_tasks)
        with patch.object(bot_module.asyncio, "sleep", _instant):
            await self._callback("remind")(
                inter, when="30m", message="DM으로 받을 알림", repeat=""
            )
            # remind_command 가 _track_task 로 띄운 reminder 스케줄 태스크를 await.
            new_tasks = [
                t for t in bot_module._background_tasks if t not in before
            ]
            self.assertTrue(new_tasks)
            await asyncio.gather(*new_tasks, return_exceptions=True)

        delivered_user.send.assert_awaited()
        body = delivered_user.send.await_args.args[0]
        self.assertIn("DM으로 받을 알림", body)
        # 발송 완료로 표시됐는지 — list_by_user 는 미발송만 반환한다.
        remaining = await self.store.list_by_user(111)
        self.assertEqual(remaining, [])

    async def test_reminder_dm_blocked_still_marks_sent(self) -> None:
        # DM 차단(Forbidden)이어도 무한 재시도하지 않도록 발송 완료 처리한다.
        blocked_user = MagicMock()
        blocked_user.send = AsyncMock(
            side_effect=discord.Forbidden(MagicMock(), "blocked")
        )
        self.bot.get_user = MagicMock(return_value=blocked_user)

        real_sleep = asyncio.sleep

        async def _instant(delay, *a, **k):
            await real_sleep(0)

        inter = self._interaction()
        before = set(bot_module._background_tasks)
        with patch.object(bot_module.asyncio, "sleep", _instant):
            await self._callback("remind")(
                inter, when="1h", message="차단된 알림", repeat=""
            )
            new_tasks = [
                t for t in bot_module._background_tasks if t not in before
            ]
            await asyncio.gather(*new_tasks, return_exceptions=True)

        blocked_user.send.assert_awaited()
        # Forbidden 이어도 sent 표시 → 미발송 목록 비어 있음.
        remaining = await self.store.list_by_user(111)
        self.assertEqual(remaining, [])

    async def test_reminder_transient_http_error_not_marked_sent(self) -> None:
        # #14: 일시적 HTTP 오류(429/5xx)에는 발송 완료로 표시하지 않고 남겨,
        # 다음 기동 reschedule 에서 재시도되게 한다(조용한 영구 유실 방지).
        flaky_user = MagicMock()
        flaky_user.send = AsyncMock(
            side_effect=discord.HTTPException(MagicMock(), "rate limited")
        )
        self.bot.get_user = MagicMock(return_value=flaky_user)

        real_sleep = asyncio.sleep

        async def _instant(delay, *a, **k):
            await real_sleep(0)

        inter = self._interaction()
        before = set(bot_module._background_tasks)
        with patch.object(bot_module.asyncio, "sleep", _instant):
            await self._callback("remind")(
                inter, when="1h", message="일시오류 알림", repeat=""
            )
            new_tasks = [t for t in bot_module._background_tasks if t not in before]
            await asyncio.gather(*new_tasks, return_exceptions=True)

        flaky_user.send.assert_awaited()
        # 일시 오류 → sent 미표시 → 여전히 미발송 목록에 남아 재시도 대상이다.
        remaining = await self.store.list_by_user(111)
        self.assertEqual(len(remaining), 1)

    async def test_cancel_stops_live_reminder_task(self) -> None:
        # #12: /reminders cancel 은 DB 행 삭제뿐 아니라 sleep 중인 in-memory 전송
        # 태스크를 취소해 실제 발송을 막아야 한다.
        delivered_user = MagicMock()
        delivered_user.send = AsyncMock()
        self.bot.get_user = MagicMock(return_value=delivered_user)

        # 실제 이벤트 루프 sleep 으로 due 전 'sleep 중' 상태에 머물게 한다. 취소가
        # 동작하지 않더라도 테스트가 영원히 멈추지 않도록 짧은 상한(10s)을 둔다.
        real_sleep = asyncio.sleep

        async def _slow(delay, *a, **k):
            await real_sleep(min(delay, 10) if delay else 0)

        inter = self._interaction()
        before = set(bot_module._background_tasks)
        with patch.object(bot_module.asyncio, "sleep", _slow):
            await self._callback("remind")(
                inter, when="1h", message="취소될 알림", repeat=""
            )
            new_tasks = [t for t in bot_module._background_tasks if t not in before]
            self.assertTrue(new_tasks)
            # 태스크가 sleep 에 진입하도록 한 번 양보한다.
            await real_sleep(0)

            # 방금 만든 reminder id 를 찾아 취소한다.
            mine = await self.store.list_by_user(111)
            self.assertEqual(len(mine), 1)
            rid = mine[0].id
            self.assertIn(rid, bot_module._reminder_tasks)
            live = bot_module._reminder_tasks[rid]

            cancel_inter = self._interaction()
            await self._callback("reminders")(cancel_inter, cancel=rid)

            # 라이브 태스크가 취소되고 레지스트리에서 제거됐다.
            self.assertTrue(live.cancelled() or live.cancelling())
            self.assertNotIn(rid, bot_module._reminder_tasks)
            await asyncio.gather(*new_tasks, return_exceptions=True)

        # 취소됐으므로 DM 은 발송되지 않는다.
        delivered_user.send.assert_not_awaited()
        # DB 에서도 삭제됐다.
        self.assertEqual(await self.store.list_by_user(111), [])


# ---------------------------------------------------------------------------
# run_bot / main — graceful shutdown 분기. 시그널/네트워크 mock.
# ---------------------------------------------------------------------------
class RunBotTest(unittest.IsolatedAsyncioTestCase):
    async def asyncSetUp(self) -> None:
        self._tmp = tempfile.TemporaryDirectory()
        db_path = Path(self._tmp.name) / "runbot.db"
        self.settings = _make_settings(f"sqlite:///{db_path}")

    def tearDown(self) -> None:
        self._tmp.cleanup()

    async def test_run_bot_graceful_shutdown_via_stop_event(self) -> None:
        # bot.start 를 영원히 대기하는 AsyncMock 으로 막고, stop_event 가 먼저
        # 끝나도록 add_signal_handler 가 즉시 핸들러를 호출하게 만든다.
        bot = create_bot(self.settings)

        async def _never_returns(token: str) -> None:
            await asyncio.sleep(3600)

        bot.start = AsyncMock(side_effect=_never_returns)
        bot.close = AsyncMock()
        bot.is_closed = MagicMock(return_value=False)

        loop = asyncio.get_running_loop()

        # add_signal_handler 를 가로채, 등록 즉시 핸들러를 호출해 stop_event 를 set.
        def _fake_add(sig, callback, *args):
            callback()  # 즉시 종료 요청 트리거.

        with patch.object(loop, "add_signal_handler", _fake_add):
            await run_bot(self.settings, bot=bot)

        bot.start.assert_awaited_once()
        bot.close.assert_awaited_once()

    async def test_run_bot_start_error_surfaces(self) -> None:
        bot = create_bot(self.settings)

        async def _boom(token: str) -> None:
            raise RuntimeError("로그인 실패")

        bot.start = AsyncMock(side_effect=_boom)
        bot.close = AsyncMock()
        bot.is_closed = MagicMock(return_value=False)

        loop = asyncio.get_running_loop()
        # 시그널 핸들러는 등록만 하고 트리거하지 않는다(start_task 가 먼저 끝남).
        with patch.object(loop, "add_signal_handler", lambda *a: None):
            with self.assertRaises(RuntimeError):
                await run_bot(self.settings, bot=bot)
        bot.close.assert_awaited()

    async def test_run_bot_signal_handler_unsupported(self) -> None:
        # add_signal_handler 가 NotImplementedError 를 던지는 플랫폼도 방어한다.
        bot = create_bot(self.settings)

        async def _quick(token: str) -> None:
            return None

        bot.start = AsyncMock(side_effect=_quick)
        bot.close = AsyncMock()
        bot.is_closed = MagicMock(return_value=False)

        loop = asyncio.get_running_loop()

        def _raise(*a):
            raise NotImplementedError

        with patch.object(loop, "add_signal_handler", _raise):
            await run_bot(self.settings, bot=bot)
        bot.start.assert_awaited_once()


class MainTest(unittest.TestCase):
    def test_main_inits_sentry_and_runs(self) -> None:
        # main 은 from_env → init_sentry → create_bot → asyncio.run(run_bot).
        # 모든 외부 의존을 mock 해 분기만 확인한다.
        fake_settings = _make_settings("sqlite:///:memory:")

        # asyncio.run 패치는 넘겨받은 run_bot 코루틴을 닫아 'never awaited' 경고를 막는다.
        def _consume(coro, *a, **k):
            coro.close()

        with patch.object(
            bot_module.AppSettings, "from_env", return_value=fake_settings
        ), patch.object(bot_module.observability, "init_sentry") as init_sentry, \
                patch.object(bot_module, "create_bot", return_value=MagicMock()) as cb, \
                patch.object(bot_module.asyncio, "run", side_effect=_consume) as run:
            main()
        init_sentry.assert_called_once()
        cb.assert_called_once()
        run.assert_called_once()


if __name__ == "__main__":
    unittest.main()
