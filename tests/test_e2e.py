"""tests/e2e_scenarios.md 의 5개 시나리오를 자동 e2e 테스트로 엮은 모듈 (ROADMAP #63).

`create_bot(settings)` 로 실제 봇을 만들고, 슬래시 명령 콜백/이벤트 핸들러를
mock 한 Discord 객체로 직접 호출해 핸들러→뷰→스토어 흐름을 통째로 검증한다.

핵심 격리 전략:
- LLM·Discord·네트워크는 전부 mock 한다(실제 호출 없음).
  - `discord_assistant.bot._get_llm` 을 monkeypatch 해 페이크 LLM 을 돌려준다.
  - Discord Interaction/Message/Channel 은 MagicMock/AsyncMock 으로 흉내낸다.
- ConfigStore 는 :memory: 가 연결별로 분리되는 문제가 있어, 파일 기반 임시 DB
  (`sqlite:///<tempfile>`) 를 쓴다(_FileStoreCase 패턴과 동일).
- 명령 콜백은 클로저이므로 `bot.tree.get_command(name).callback` 으로 꺼내 호출하고,
  이벤트(on_message/on_guild_join)는 `@bot.event` 로 등록되어 `bot.on_message` 등으로
  접근 가능하므로 직접 await 한다.
"""
from __future__ import annotations

import tempfile
import unittest
from datetime import datetime, timezone
from pathlib import Path
from types import SimpleNamespace
from unittest.mock import AsyncMock, MagicMock, patch

import discord_assistant.bot as bot_module
from discord_assistant.bot import create_bot, reset_cooldowns
from discord_assistant.cache import (
    clear_translation_cache,
    set_translation,
    summarize_cache,
    translation_cache_size,
)
from discord_assistant.crypto import encrypt_api_key
from discord_assistant.llm import OllamaError
from discord_assistant.models import LLMProvider
from discord_assistant.settings import AppSettings

_SECRET = "test-secret-key-for-e2e-tests"


def _make_settings(db_url: str, **overrides: object) -> AppSettings:
    """실 토큰 없이 동작하는 최소 AppSettings 를 만든다(네트워크 의존 없음)."""
    defaults: dict[str, object] = dict(
        discord_bot_token="test-token",
        ollama_base_url="http://localhost:11434",
        ollama_model="llama3.1:8b",
        database_url=db_url,
        default_summary_limit=50,
        max_context_chars=12_000,
        default_language="ko",
        ollama_timeout_seconds=60,
        # 동기화/헬스서버 등 네트워크 부수효과를 끈다.
        auto_sync_commands=False,
        secret_key=_SECRET,
        metrics_port=0,
    )
    defaults.update(overrides)
    return AppSettings(**defaults)  # type: ignore[arg-type]


class _FakeLLM:
    """_get_llm 이 돌려줄 페이크 LLM 클라이언트.

    generate/generate_stream/generate_with_tools 를 제어 가능한 더블로 둔다.
    - ``answer`` 를 고정 응답으로 돌려준다(또는 ``error`` 가 있으면 매 호출 raise).
    - ``last_usage`` 는 _usage_tokens 가 안전하게 (0,0) 으로 폴백하도록 None 으로 둔다.
    """

    def __init__(self, answer: str = "(mock answer)", error: Exception | None = None) -> None:
        self.answer = answer
        self.error = error
        self.last_usage = None
        self.generate = AsyncMock(side_effect=self._generate)
        self.generate_with_tools = AsyncMock(side_effect=self._generate_tools)

    async def _generate(self, *args: object, **kwargs: object) -> str:
        if self.error is not None:
            raise self.error
        return self.answer

    async def _generate_tools(self, *args: object, **kwargs: object) -> str:
        if self.error is not None:
            raise self.error
        return self.answer

    def generate_stream(self, *args: object, **kwargs: object):  # noqa: ANN201
        """스트리밍 경로용. 빈 스트림을 돌려 _run_chat 의 비스트리밍 폴백을 타게 한다."""

        async def _empty():  # noqa: ANN202
            if self.error is not None:
                raise self.error
            # 한 글자도 내지 않으면 호출부가 generate 폴백을 시도한다(#16 경로).
            return
            yield  # pragma: no cover — 제너레이터로 만들기 위한 형식상 yield

        return _empty()


def _make_history_channel(messages: list[SimpleNamespace], channel_id: int = 333) -> MagicMock:
    """async ``history(...)`` 를 지원하는 채널 더블을 만든다.

    discord.py 의 channel.history(...) 는 async iterator 를 돌려주므로, 동일한
    인터페이스(``async for msg in channel.history(...)``)를 흉내내는 헬퍼.
    """
    channel = MagicMock(name="Channel")
    channel.id = channel_id
    channel.send = AsyncMock()

    def _history(*_args: object, **_kwargs: object):  # noqa: ANN202
        async def _gen():  # noqa: ANN202
            # bot._collect_transcript 는 결과를 reverse() 하므로 입력 순서는 무관.
            for msg in messages:
                yield msg

        return _gen()

    channel.history = MagicMock(side_effect=_history)
    return channel


def _make_message(
    author_name: str = "alice", content: str = "hello", *, is_bot: bool = False
) -> SimpleNamespace:
    """channel.history 가 돌려줄 메시지 더블(from_discord_message 가 읽는 속성만)."""
    return SimpleNamespace(
        author=SimpleNamespace(display_name=author_name, name=author_name, bot=is_bot),
        content=content,
        clean_content=content,
        created_at=datetime(2026, 5, 30, 12, 0, tzinfo=timezone.utc),
        attachments=[],
    )


def _make_interaction(
    *,
    guild_id: int = 222,
    channel: MagicMock | None = None,
    user_id: int = 111,
    is_admin: bool = True,
) -> MagicMock:
    """슬래시 명령 핸들러에 넘길 discord.Interaction 더블.

    conftest.interaction 픽스처와 동일한 형태지만, e2e 흐름에서 채널 히스토리를
    바꿔 끼우기 쉽도록 직접 생성한다.
    """
    # 실제 Discord 처럼 defer()/send_message() 가 호출되면 is_done() 이 True 로
    # 바뀌도록 흉내낸다. 핸들러는 is_done() 으로 응답 전/후 분기를 판단하므로,
    # 이 플립이 없으면 오류 경로가 followup 대신 response.send_message 로 가버린다.
    _done = {"value": False}

    def _mark_done(*_a: object, **_k: object) -> None:
        _done["value"] = True

    response = MagicMock(name="InteractionResponse")
    response.defer = AsyncMock(side_effect=_mark_done)
    response.send_message = AsyncMock(side_effect=_mark_done)
    response.edit_message = AsyncMock(side_effect=_mark_done)
    response.is_done = MagicMock(side_effect=lambda: _done["value"])

    followup = MagicMock(name="InteractionFollowup")
    followup.send = AsyncMock(return_value=MagicMock(id=9999, pin=AsyncMock(), add_reaction=AsyncMock()))

    user = SimpleNamespace(
        id=user_id,
        name="tester",
        display_name="tester",
        mention=f"<@{user_id}>",
        bot=False,
        roles=[],
        guild_permissions=SimpleNamespace(
            administrator=is_admin, manage_guild=is_admin, manage_messages=is_admin
        ),
    )
    guild = SimpleNamespace(id=guild_id, name="test-guild")
    chan = channel if channel is not None else _make_history_channel([_make_message()])

    inter = MagicMock(name="Interaction")
    inter.response = response
    inter.followup = followup
    inter.user = user
    inter.guild = guild
    inter.guild_id = guild_id
    inter.channel = chan
    inter.channel_id = getattr(chan, "id", 333)
    inter.created_at = datetime(2026, 5, 30, 12, 5, tzinfo=timezone.utc)
    inter.edit_original_response = AsyncMock()
    return inter


class _E2ECase(unittest.IsolatedAsyncioTestCase):
    """파일 기반 임시 DB + create_bot 으로 구성한 e2e 베이스 케이스.

    :memory: 는 연결별로 별도 DB 라 setup_hook 에서 만든 스키마가 이후 작업에
    보이지 않는다(#95). 따라서 on-disk 임시 파일을 쓴다(_FileStoreCase 패턴).
    """

    async def asyncSetUp(self) -> None:
        reset_cooldowns()
        summarize_cache.clear()
        clear_translation_cache()
        self._tmp = tempfile.TemporaryDirectory()
        db_path = Path(self._tmp.name) / "e2e.db"
        self.settings = _make_settings(f"sqlite:///{db_path}")
        self.bot = create_bot(self.settings)
        # create_bot 내부 클로저가 캡처한 store(파일 DB)를 초기화한다.
        # setup_hook 은 헬스서버/번역기 등록까지 포함하지만 metrics_port=0,
        # auto_sync_commands=False 라 네트워크 부수효과가 없다.
        await self.bot.setup_hook()
        # create_bot 의 store 는 클로저 내부라 직접 노출되지 않는다. 설정 변경은
        # 같은 파일 DB 를 가리키는 별도 ConfigStore 로 기록하고, 봇 핸들러는 자신의
        # 내부 store 로 같은 파일을 읽어 일관성을 확인한다(파일 DB 라 공유됨).

    async def asyncTearDown(self) -> None:
        reset_cooldowns()
        summarize_cache.clear()
        clear_translation_cache()
        await self.bot.close()
        self._tmp.cleanup()

    def _cmd(self, name: str):  # noqa: ANN202
        """슬래시 명령 콜백(클로저)을 꺼낸다."""
        command = self.bot.tree.get_command(name)
        assert command is not None, f"command not found: {name}"
        return command.callback

    def _patch_llm(self, llm: _FakeLLM):  # noqa: ANN202
        """bot._get_llm 을 페이크 LLM 으로 교체하는 patch 컨텍스트를 돌려준다."""
        return patch.object(bot_module, "_get_llm", return_value=llm)


# ---------------------------------------------------------------------------
# 시나리오 1 — 신규 서버 설정(언어 변경) → 요약
# ---------------------------------------------------------------------------


class Scenario1SettingsThenSummarize(_E2ECase):
    """설정(언어/모델 저장) 후 /summarize 가 변경된 설정대로 동작하는지 검증."""

    async def test_config_change_then_summarize(self) -> None:
        guild_id = 222
        # 1) /config language en 으로 언어를 변경(관리자 권한 인터랙션).
        #    config 는 app_commands.Group 이라 하위 명령(language/model)으로 접근한다.
        lang_inter = _make_interaction(guild_id=guild_id)
        config_group = self.bot.tree.get_command("config")
        lang_cmd = config_group.get_command("language")
        await lang_cmd.callback(lang_inter, "en")
        lang_inter.response.send_message.assert_awaited()  # ✅ 저장 응답

        # 2) 모델도 qwen2.5:7b 로 변경.
        model_inter = _make_interaction(guild_id=guild_id)
        model_cmd = config_group.get_command("model")
        await model_cmd.callback(model_inter, "qwen2.5:7b")
        model_inter.response.send_message.assert_awaited()

        # 3) /summarize 실행 — LLM 은 영어 요약을 돌려주도록 mock.
        llm = _FakeLLM(answer="Summary: the team discussed the release plan.")
        channel = _make_history_channel(
            [_make_message("alice", "let's ship friday"), _make_message("bob", "agreed")]
        )
        sum_inter = _make_interaction(guild_id=guild_id, channel=channel)
        with self._patch_llm(llm):
            await self._cmd("summarize")(sum_inter)

        # 응답 전 defer(thinking) 후 followup 으로 결과를 보낸다.
        sum_inter.response.defer.assert_awaited()
        llm.generate.assert_awaited()  # LLM 호출됨
        sum_inter.followup.send.assert_awaited()
        # followup 으로 보낸 본문에 영어 요약이 포함된다.
        sent_texts = [
            str(call.args[0])
            for call in sum_inter.followup.send.await_args_list
            if call.args
        ]
        self.assertTrue(
            any("the release plan" in text for text in sent_texts),
            f"영어 요약이 응답에 없음: {sent_texts}",
        )
        # 모델 변경이 LLM 호출에 반영됐는지 확인(model=qwen2.5:7b).
        _, kwargs = llm.generate.await_args
        self.assertEqual(kwargs.get("model"), "qwen2.5:7b")

    async def test_settings_command_requires_guild_and_is_ephemeral(self) -> None:
        # /settings 는 서버 안에서만 동작하고 ephemeral 패널을 연다.
        inter = _make_interaction(guild_id=222)
        await self._cmd("settings")(inter)
        inter.response.send_message.assert_awaited()
        _, kwargs = inter.response.send_message.await_args
        self.assertTrue(kwargs.get("ephemeral"))
        self.assertIn("view", kwargs)  # SettingsView 가 붙는다.


# ---------------------------------------------------------------------------
# 시나리오 2 — API 키 등록 → OpenAI 전환 → /chat
# ---------------------------------------------------------------------------


class Scenario2ApiKeyOpenAIChat(_E2ECase):
    """OpenAI 제공자 + 암호화된 API 키로 전환 후 /chat 이 동작하는지 검증.

    키 검증/암호화는 실제 네트워크가 없는 로컬 crypto 만 사용하고, OpenAI 호출
    자체는 _get_llm mock 으로 차단한다(실제 OpenAI 호출 없음).
    """

    async def test_register_key_switch_provider_then_chat(self) -> None:
        guild_id = 222
        # 1) 제공자/모델/암호화 키를 한 번에 저장(set_provider_config 경로).
        #    실제 UI(SettingsView) 대신 store API 로 동일 상태를 만든다 — UI 모달
        #    입력은 Discord 게이트웨이가 필요해 모킹 부담이 크고, 핵심은 저장된
        #    설정대로 /chat 이 OpenAI 경로를 타는지이기 때문.
        encrypted = encrypt_api_key("sk-test-openai-key-1234", _SECRET)
        # store 는 클로저 내부라 직접 못 잡으므로, 같은 DB URL 로 별도 store 를 열어
        # 동일 파일에 기록한다(파일 DB 라 동일 데이터를 공유한다).
        from discord_assistant.storage import ConfigStore

        side_store = ConfigStore(
            self.settings.database_url,
            default_model=self.settings.ollama_model,
            default_summary_limit=self.settings.default_summary_limit,
            default_language=self.settings.default_language,
        )
        await side_store.initialize()
        try:
            await side_store.set_provider_config(
                guild_id,
                provider=LLMProvider.OPENAI,
                model="gpt-4o-mini",
                api_key_encrypted=encrypted,
            )
        finally:
            await side_store.close()

        # 2) /chat 실행 — OpenAI 응답을 mock. 한국어 코드 설명을 돌려준다.
        llm = _FakeLLM(
            answer="물론이죠! 파이썬 Hello World:\n```python\nprint('Hello, World!')\n```"
        )
        inter = _make_interaction(guild_id=guild_id)
        with self._patch_llm(llm):
            await self._cmd("chat")(inter, "파이썬으로 Hello World 코드 작성해줘", False)

        inter.response.defer.assert_awaited()
        llm.generate.assert_awaited()  # 스트림 폴백 후 generate 호출
        # 응답(또는 폴백 followup)에 한국어 코드 설명이 담긴다.
        sent = [
            str(call.args[0])
            for call in inter.followup.send.await_args_list
            if call.args
        ]
        # 첫 응답이 response.send_message 로 갈 수도 있어 함께 수집.
        sent += [
            str(call.args[0])
            for call in inter.response.send_message.await_args_list
            if call.args
        ]
        self.assertTrue(
            any("Hello, World!" in text for text in sent),
            f"코드 응답이 없음: {sent}",
        )
        # 저장된 설정대로 gpt-4o-mini 모델로 호출됐는지 확인.
        _, kwargs = llm.generate.await_args
        self.assertEqual(kwargs.get("model"), "gpt-4o-mini")

    async def test_openai_without_key_raises_user_facing_error(self) -> None:
        # 키 없이 OpenAI 로만 전환된 상태면 _get_llm 이 UserFacingError 를 던지고,
        # /chat 은 친절 오류 임베드로 응답한다(실제 _get_llm 사용, mock 안 함).
        guild_id = 222
        from discord_assistant.storage import ConfigStore

        side_store = ConfigStore(
            self.settings.database_url,
            default_model=self.settings.ollama_model,
            default_summary_limit=self.settings.default_summary_limit,
            default_language=self.settings.default_language,
        )
        await side_store.initialize()
        try:
            await side_store.set_provider_config(
                guild_id, provider=LLMProvider.OPENAI, model="gpt-4o-mini", api_key_encrypted=None
            )
        finally:
            await side_store.close()

        inter = _make_interaction(guild_id=guild_id)
        await self._cmd("chat")(inter, "안녕", False)
        # 오류 임베드는 followup 으로 ephemeral 전송된다.
        inter.followup.send.assert_awaited()
        _, kwargs = inter.followup.send.await_args
        self.assertTrue(kwargs.get("ephemeral"))
        self.assertIn("embed", kwargs)


# ---------------------------------------------------------------------------
# 시나리오 3 — 멘션 Q&A (on_message 에서 @봇 멘션 처리)
# ---------------------------------------------------------------------------


class Scenario3MentionQA(_E2ECase):
    """@봇 멘션 시 질문이 있으면 /ask 처럼, 없으면 요약처럼 동작하는지 검증."""

    def _make_mention_message(
        self, content: str, *, channel: MagicMock, mentions_bot: bool = True
    ) -> MagicMock:
        bot_user = self.bot.user
        msg = MagicMock(name="Message")
        msg.author = SimpleNamespace(id=111, name="alice", display_name="alice", bot=False)
        msg.guild = SimpleNamespace(id=222, name="g")
        msg.channel = channel
        msg.content = content
        msg.created_at = datetime(2026, 5, 30, 12, 5, tzinfo=timezone.utc)
        msg.attachments = []
        msg.reference = None
        msg.mentions = [bot_user] if mentions_bot else []
        return msg

    async def asyncSetUp(self) -> None:
        await super().asyncSetUp()
        # on_message 가드(bot.user 비교)를 위해 봇 사용자 ID 를 고정한다.
        self.bot._connection.user = SimpleNamespace(id=42, bot=True)  # type: ignore[attr-defined]

    async def test_mention_with_question_answers(self) -> None:
        bot_user = self.bot.user
        self.assertIsNotNone(bot_user)
        channel = _make_history_channel(
            [
                _make_message("alice", "오늘 회의에서 금요일 배포로 결정했어"),
                _make_message("bob", "담당자는 carol 로 하자"),
            ]
        )
        # typing() 컨텍스트 매니저를 async with 가능하도록 흉내낸다.
        channel.typing = MagicMock(return_value=_async_cm())
        msg = self._make_mention_message(
            f"<@{bot_user.id}> 오늘 회의 결론이 뭐야?", channel=channel
        )
        llm = _FakeLLM(answer="금요일 배포로 결정했고 담당자는 carol 입니다.")
        # process_commands 는 슬래시 e2e 와 무관하므로 no-op 으로 막는다(prefix 명령 없음).
        with self._patch_llm(llm), patch.object(
            self.bot, "process_commands", new=AsyncMock()
        ):
            await self.bot.on_message(msg)

        llm.generate.assert_awaited()  # 멘션 질문 → LLM 호출
        channel.send.assert_awaited()  # 채널에 공개 답변 게시
        sent = [str(c.args[0]) for c in channel.send.await_args_list if c.args]
        self.assertTrue(any("carol" in text for text in sent), f"답변 없음: {sent}")
        # 질문이 있으므로 '질문:' 헤딩이 붙는다.
        self.assertTrue(any("질문:" in text for text in sent))

    async def test_mention_without_question_summarizes(self) -> None:
        bot_user = self.bot.user
        channel = _make_history_channel(
            [_make_message("alice", "아침에 일정 공유함"), _make_message("bob", "확인했어")]
        )
        channel.typing = MagicMock(return_value=_async_cm())
        msg = self._make_mention_message(f"<@{bot_user.id}>", channel=channel)
        llm = _FakeLLM(answer="최근 대화는 일정 공유와 확인 내용입니다.")
        with self._patch_llm(llm), patch.object(
            self.bot, "process_commands", new=AsyncMock()
        ):
            await self.bot.on_message(msg)

        llm.generate.assert_awaited()
        channel.send.assert_awaited()
        sent = [str(c.args[0]) for c in channel.send.await_args_list if c.args]
        # 질문이 없으므로 요약 헤딩(최근 대화 요약)이 붙는다.
        self.assertTrue(any("요약" in text for text in sent), f"요약 헤딩 없음: {sent}")


# ---------------------------------------------------------------------------
# 시나리오 4 — /translate 캐시 히트
# ---------------------------------------------------------------------------


class Scenario4TranslateCacheHit(_E2ECase):
    """/translate 가 캐시에 있으면 LLM 호출 없이 (캐시) 표시로 응답하는지 검증."""

    async def test_cache_hit_skips_llm(self) -> None:
        text = "Hello, world"
        target = "ko"
        # 캐시를 미리 채워 둔다(이전 번역이 있었다고 가정).
        set_translation(text, target, "안녕, 세계")
        self.assertEqual(translation_cache_size(), 1)

        llm = _FakeLLM(answer="(should-not-be-called)")
        inter = _make_interaction(guild_id=222)
        with self._patch_llm(llm):
            await self._cmd("translate")(inter, text, target)

        # 캐시 히트이므로 LLM 은 호출되지 않는다.
        llm.generate.assert_not_awaited()
        inter.followup.send.assert_awaited()
        # 캐시 표시(*(캐시)*)가 임베드 필드명에 포함된다.
        _, kwargs = inter.followup.send.await_args
        embed = kwargs.get("embed")
        self.assertIsNotNone(embed)
        field_names = [f.name for f in embed.fields]
        self.assertTrue(
            any("캐시" in name for name in field_names),
            f"캐시 표시가 없음: {field_names}",
        )

    async def test_cache_miss_calls_llm_and_populates_cache(self) -> None:
        # 캐시 미스 → LLM 호출 후 결과를 캐시에 저장한다(다음 호출이 히트되도록).
        text = "Good morning"
        target = "ko"
        llm = _FakeLLM(answer="좋은 아침이에요")
        inter = _make_interaction(guild_id=222)
        with self._patch_llm(llm):
            await self._cmd("translate")(inter, text, target)

        llm.generate.assert_awaited()  # 캐시 미스라 LLM 호출
        self.assertEqual(translation_cache_size(), 1)  # 결과가 캐시에 적재됨


# ---------------------------------------------------------------------------
# 시나리오 5 — Ollama 중단 시 사용자 친절 오류
# ---------------------------------------------------------------------------


class Scenario5OllamaDownFriendlyError(_E2ECase):
    """Ollama 가 죽어 OllamaError 가 나면 사용자에게 친절 오류 임베드를 보여주는지 검증."""

    async def test_summarize_shows_friendly_ollama_error(self) -> None:
        # Ollama 미실행 시 generate 가 던지는 것과 동일한 OllamaError.
        err = OllamaError(
            "Ollama가 실행 중이지 않습니다. 터미널에서 `ollama serve`를 실행해 주세요."
        )
        llm = _FakeLLM(error=err)
        channel = _make_history_channel([_make_message("alice", "안녕")])
        inter = _make_interaction(guild_id=222, channel=channel)
        with self._patch_llm(llm):
            await self._cmd("summarize")(inter)

        # 오류는 ephemeral 임베드 + RetryView 로 전달된다(#92).
        inter.followup.send.assert_awaited()
        _, kwargs = inter.followup.send.await_args
        self.assertTrue(kwargs.get("ephemeral"))
        embed = kwargs.get("embed")
        self.assertIsNotNone(embed)
        # error_hint(OllamaError) 의 친절 안내 문구가 임베드에 담긴다.
        self.assertIn("Ollama", str(embed.description))
        # 재시도 가능한 오류이므로 RetryView 가 함께 붙는다.
        self.assertIn("view", kwargs)

    async def test_translate_shows_friendly_ollama_error(self) -> None:
        # /translate 경로도 LLMError 를 잡아 사용자에게 안내한다(캐시 미스 경로).
        err = OllamaError("Ollama가 실행 중이지 않습니다.")
        llm = _FakeLLM(error=err)
        inter = _make_interaction(guild_id=222)
        with self._patch_llm(llm):
            await self._cmd("translate")(inter, "untranslated text", "ko")

        inter.followup.send.assert_awaited()
        sent = [
            str(call.args[0])
            for call in inter.followup.send.await_args_list
            if call.args
        ]
        self.assertTrue(
            any("Ollama" in text or "⚠️" in text for text in sent),
            f"오류 안내가 없음: {sent}",
        )


def _async_cm():  # noqa: ANN202
    """`async with channel.typing():` 를 흉내내는 async 컨텍스트 매니저."""

    class _CM:
        async def __aenter__(self):  # noqa: ANN204
            return self

        async def __aexit__(self, *exc: object) -> bool:
            return False

    return _CM()


if __name__ == "__main__":
    unittest.main()
