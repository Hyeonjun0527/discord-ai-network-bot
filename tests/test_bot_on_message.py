"""on_message 이벤트 핸들러 동작 테스트 (ROADMAP #58).

create_bot(settings) 내부의 on_message 핸들러는 @bot.event 로 등록되어
``bot.on_message`` 속성으로 노출된다. 핸들러는 클로저로 ``store``/``settings``/``bot``
을 캡처하므로, 검증용 ConfigStore 참조는 ``bot.on_message.__closure__`` 에서 꺼낸다.

네트워크/LLM/Discord 는 전부 목으로 대체한다:
  - ``_get_llm`` 을 monkeypatch 해 AsyncMock(generate) 를 가진 가짜 클라이언트를 돌려준다.
  - discord ``Message`` 는 MagicMock 으로, ``channel.typing()`` 은 async 컨텍스트
    매니저 더블로, ``channel.history`` 는 async 이터레이터로 흉내낸다.
  - ``bot.process_commands`` 는 AsyncMock 으로 덮어 실제 명령 처리/네트워크를 막는다.

ConfigStore 는 :memory: 가 연결별로 분리되는 문제를 피하려고 tempfile 기반 파일 DB
를 쓴다(_FileStoreCase 패턴).
"""
from __future__ import annotations

import tempfile
import unittest
from datetime import datetime, timezone
from pathlib import Path
from types import SimpleNamespace
from unittest.mock import AsyncMock, MagicMock

import discord

from discord_assistant import bot as botmod
from discord_assistant.bot import LLMError, reset_cooldowns
from discord_assistant.settings import AppSettings

# ---------------------------------------------------------------------------
# 공용 더블/헬퍼
# ---------------------------------------------------------------------------


class _TypingCtx:
    """``async with channel.typing():`` 를 흉내내는 async 컨텍스트 매니저 더블."""

    async def __aenter__(self) -> "_TypingCtx":
        return self

    async def __aexit__(self, *exc: object) -> bool:
        return False


def _async_history(messages: list[object]):
    """``channel.history(**kwargs)`` 를 대체하는 async 이터레이터 팩토리.

    discord.py 의 history 는 (await 가능한) async 이터레이터를 돌려주므로,
    인자를 받아 ``__aiter__`` 가능한 객체를 반환하는 호출 가능 객체를 만든다.
    transcript 수집은 최신→과거 순회 후 reverse 하므로, 여기서는 넘긴 순서를
    그대로 흘려보낸다(테스트는 내용 존재 여부만 검증).
    """

    def _factory(*args: object, **kwargs: object):
        async def _gen():
            for msg in messages:
                yield msg

        return _gen()

    return _factory


def _make_history_message(*, author_name: str, content: str, is_bot: bool = False) -> MagicMock:
    """channel.history 가 흘려보낼 과거 메시지 더블(from_discord_message 호환)."""
    msg = MagicMock(name=f"HistMessage({author_name})")
    msg.author = SimpleNamespace(display_name=author_name, name=author_name, bot=is_bot)
    msg.content = content
    msg.clean_content = content
    msg.attachments = []
    msg.created_at = datetime(2026, 5, 30, 12, 0, tzinfo=timezone.utc)
    return msg


def _make_message(
    *,
    content: str,
    author_id: int = 111,
    author_bot: bool = False,
    guild_id: int | None = 222,
    channel_id: int = 333,
    mentions: list[object] | None = None,
    history: list[object] | None = None,
    reference: object | None = None,
    is_thread_channel: bool = False,
) -> MagicMock:
    """on_message 에 넘길 discord.Message 더블을 만든다.

    - ``guild_id=None`` → DM 경로.
    - ``mentions`` 에 봇 유저를 넣으면 멘션 분기.
    - ``history`` 는 채널 transcript 수집용 과거 메시지.
    - ``is_thread_channel=True`` 면 채널을 discord.Thread 인스턴스로 흉내낸다.
    """
    message = MagicMock(name="Message")
    message.content = content
    message.author = SimpleNamespace(id=author_id, bot=author_bot, display_name="tester")
    message.attachments = []
    message.mentions = mentions if mentions is not None else []
    message.reference = reference
    message.created_at = datetime(2026, 5, 30, 12, 30, tzinfo=timezone.utc)

    if guild_id is None:
        message.guild = None
    else:
        message.guild = SimpleNamespace(id=guild_id, name="test-guild")

    # 스레드 맥락(#6) 검증 시 isinstance(channel, discord.Thread) 가 True 여야 한다.
    if is_thread_channel:
        channel = MagicMock(spec=discord.Thread)
    else:
        channel = MagicMock(name="Channel")
    channel.id = channel_id
    channel.send = AsyncMock()
    channel.typing = MagicMock(return_value=_TypingCtx())
    channel.history = _async_history(history or [])
    channel.fetch_message = AsyncMock()
    message.channel = channel
    return message


class _FileStoreCase(unittest.IsolatedAsyncioTestCase):
    """tempfile 기반 파일 DB 로 create_bot 의 내부 store 를 초기화하는 베이스.

    create_bot 은 settings.database_url 로 ConfigStore 를 만들지만 외부로 노출하지
    않으므로, on_message 클로저에서 store 참조를 꺼내 직접 initialize 한다.
    """

    async def asyncSetUp(self) -> None:
        reset_cooldowns()
        self._tmp = tempfile.TemporaryDirectory()
        db_path = Path(self._tmp.name) / "on_message.db"
        self.settings = AppSettings(
            discord_bot_token="test-token",
            ollama_base_url="http://localhost:11434",
            ollama_model="llama3.1:8b",
            database_url=f"sqlite:///{db_path}",
            default_summary_limit=50,
            max_context_chars=12_000,
            default_language="ko",
            ollama_timeout_seconds=60,
            auto_sync_commands=False,
            secret_key="test-secret-key-for-on-message",
        )
        self.bot = botmod.create_bot(self.settings)

        # 봇 유저 더블: bot.user 는 property 라 _connection.user 로 주입한다.
        self.bot_user = MagicMock(name="BotUser")
        self.bot_user.id = 999
        self.bot._connection.user = self.bot_user

        # 실제 명령 처리/네트워크를 막는다(핸들러는 항상 process_commands 를 호출).
        self.bot.process_commands = AsyncMock()

        # on_message 클로저에서 내부 store 를 꺼내 파일 DB 로 초기화한다.
        self.handler = self.bot.on_message
        cells = dict(zip(self.handler.__code__.co_freevars, self.handler.__closure__))
        self.store = cells["store"].cell_contents
        await self.store.initialize()

    async def asyncTearDown(self) -> None:
        await self.store.close()
        self._tmp.cleanup()
        reset_cooldowns()

    def _patch_llm(self, answer: str = "목 응답입니다", *, error: Exception | None = None) -> AsyncMock:
        """_get_llm 을 monkeypatch 해 가짜 LLM 클라이언트를 돌려주게 한다.

        반환한 AsyncMock 은 generate 목이며, 호출 인자(prompt 등) 검증에 쓸 수 있다.
        ``error`` 가 주어지면 generate 가 그 예외를 던진다.
        """
        if error is not None:
            generate = AsyncMock(side_effect=error)
        else:
            generate = AsyncMock(return_value=answer)
        fake_llm = MagicMock(name="FakeLLM")
        fake_llm.generate = generate
        fake_llm.last_usage = SimpleNamespace(prompt_tokens=5, completion_tokens=7)
        orig_get_llm = botmod._get_llm
        botmod._get_llm = MagicMock(return_value=fake_llm)
        self.addCleanup(setattr, botmod, "_get_llm", orig_get_llm)
        return generate

    def _spy_record_usage(self) -> AsyncMock:
        """_record_usage 를 AsyncMock 으로 스파이해 command/status 기록을 검증한다.

        usage_log 는 guild_id 별 집계 쿼리만 공개돼 DM(guild_id=None) 경로를 직접
        조회하기 어렵다. _record_usage 호출 인자(키워드)를 캡처하는 편이 명확하다.
        """
        spy = AsyncMock()
        orig = botmod._record_usage
        botmod._record_usage = spy
        self.addCleanup(setattr, botmod, "_record_usage", orig)
        return spy

    @staticmethod
    def _recorded(spy: AsyncMock, *, command: str, status: str | None = None) -> bool:
        """스파이된 _record_usage 호출 중 command(+status) 가 기록됐는지 확인한다."""
        for call in spy.await_args_list:
            if call.kwargs.get("command") != command:
                continue
            if status is not None and call.kwargs.get("status") != status:
                continue
            return True
        return False


# ---------------------------------------------------------------------------
# (a) 멘션: 질문 유무에 따른 mention_ask / mention_summarize 분기
# ---------------------------------------------------------------------------


class MentionBranchTest(_FileStoreCase):
    async def test_mention_with_question_uses_mention_ask(self) -> None:
        generate = self._patch_llm("질문 답변")
        spy = self._spy_record_usage()
        history = [_make_history_message(author_name="alice", content="회의는 3시야")]
        message = _make_message(
            content=f"<@{self.bot_user.id}> 회의 언제야?",
            mentions=[self.bot_user],
            history=history,
        )

        await self.handler(message)

        # LLM 이 호출되고 채널에 답변이 전송됐다.
        generate.assert_awaited_once()
        message.channel.send.assert_awaited()
        sent = message.channel.send.await_args.args[0]
        self.assertIn("질문 답변", sent)
        # 질문 헤딩(**질문:** ...)이 붙는다(ask 경로).
        self.assertIn("질문:", sent)
        # usage 가 mention_ask 로 기록됐다.
        self.assertTrue(self._recorded(spy, command="mention_ask", status="ok"))

    async def test_mention_without_question_uses_mention_summarize(self) -> None:
        generate = self._patch_llm("요약 결과")
        spy = self._spy_record_usage()
        history = [_make_history_message(author_name="bob", content="오늘 배포 끝났어")]
        # 멘션 토큰만 있고 본문 질문이 없다 → 요약 분기.
        message = _make_message(
            content=f"<@{self.bot_user.id}>",
            mentions=[self.bot_user],
            history=history,
        )

        await self.handler(message)

        generate.assert_awaited_once()
        sent = message.channel.send.await_args.args[0]
        self.assertIn("요약 결과", sent)
        self.assertIn("최근 대화 요약", sent)
        self.assertTrue(self._recorded(spy, command="mention_summarize", status="ok"))


# ---------------------------------------------------------------------------
# (b) 스레드 안에서 멘션 시 스레드 맥락 헤딩
# ---------------------------------------------------------------------------


class ThreadContextTest(_FileStoreCase):
    async def test_mention_in_thread_uses_thread_heading(self) -> None:
        self._patch_llm("스레드 요약")
        history = [_make_history_message(author_name="carol", content="스레드 안 대화")]
        message = _make_message(
            content=f"<@{self.bot_user.id}>",
            mentions=[self.bot_user],
            history=history,
            is_thread_channel=True,
        )

        await self.handler(message)

        sent = message.channel.send.await_args.args[0]
        # 스레드 안에서는 🧵 스레드 대화 요약 헤딩을 쓴다(#6).
        self.assertIn("스레드 대화 요약", sent)


# ---------------------------------------------------------------------------
# (c) 봇 자기 메시지 / 타 봇 메시지 무시
# ---------------------------------------------------------------------------


class BotMessageIgnoredTest(_FileStoreCase):
    async def test_other_bot_message_is_ignored(self) -> None:
        generate = self._patch_llm()
        # author.bot=True → 멘션이 있어도 LLM 을 호출하지 않고 즉시 빠져나간다.
        message = _make_message(
            content=f"<@{self.bot_user.id}> 안녕",
            author_bot=True,
            mentions=[self.bot_user],
            history=[_make_history_message(author_name="x", content="hi")],
        )

        await self.handler(message)

        generate.assert_not_awaited()
        message.channel.send.assert_not_awaited()
        # 봇 메시지도 process_commands 는 통과시킨다(상단 가드 동작 확인).
        self.bot.process_commands.assert_awaited_once_with(message)

    async def test_no_mention_no_reply_does_nothing(self) -> None:
        # 멘션/답장 모두 아니면(일반 길드 메시지) LLM 을 호출하지 않는다.
        generate = self._patch_llm()
        message = _make_message(content="그냥 잡담", mentions=[])

        await self.handler(message)

        generate.assert_not_awaited()
        message.channel.send.assert_not_awaited()


# ---------------------------------------------------------------------------
# (d) DM 경로: 쿨다운(_DM_COOLDOWN_GUILD) · 대화 기억
# ---------------------------------------------------------------------------


class DmPathTest(_FileStoreCase):
    async def test_dm_generates_and_persists_history(self) -> None:
        generate = self._patch_llm("DM 답변")
        spy = self._spy_record_usage()
        message = _make_message(content="DM 질문이야", guild_id=None, author_id=777)

        await self.handler(message)

        generate.assert_awaited_once()
        message.channel.send.assert_awaited()
        sent = message.channel.send.await_args.args[0]
        self.assertIn("DM 답변", sent)
        # DM 대화 기억(#10): user/assistant 턴이 guild_id=None 으로 저장된다.
        history = await self.store.get_chat_history(777, guild_id=None, limit=10)
        roles = [h["role"] for h in history]
        self.assertIn("user", roles)
        self.assertIn("assistant", roles)
        self.assertTrue(any(h["content"] == "DM 답변" for h in history))
        # usage 가 dm_chat 로 기록된다.
        self.assertTrue(self._recorded(spy, command="dm_chat", status="ok"))

    async def test_dm_uses_prior_history_for_context(self) -> None:
        # 직전 DM 대화가 있으면 build_chat_with_history_prompt 경로를 탄다(#10).
        await self.store.save_chat_message(777, "user", "이전 질문", guild_id=None)
        await self.store.save_chat_message(777, "assistant", "이전 답변", guild_id=None)
        generate = self._patch_llm("이어진 답변")
        message = _make_message(content="이어서 물어볼게", guild_id=None, author_id=777)

        await self.handler(message)

        generate.assert_awaited_once()
        # history 가 프롬프트에 반영됐는지 확인: 직전 대화 내용이 프롬프트에 포함된다.
        prompt = generate.await_args.args[0]
        self.assertIn("이전 답변", prompt)

    async def test_dm_cooldown_blocks_second_call(self) -> None:
        generate = self._patch_llm("첫 응답")
        first = _make_message(content="첫 DM", guild_id=None, author_id=555)
        await self.handler(first)
        generate.assert_awaited_once()

        # 같은 유저의 즉시 두 번째 DM 은 쿨다운에 막혀 LLM 을 다시 부르지 않는다.
        second = _make_message(content="둘째 DM", guild_id=None, author_id=555)
        await self.handler(second)

        self.assertEqual(generate.await_count, 1)
        # 쿨다운 안내 메시지를 보냈다.
        cooldown_msg = second.channel.send.await_args.args[0]
        self.assertIn("초 후에 다시", cooldown_msg)

    async def test_dm_llm_error_is_surfaced(self) -> None:
        # LLMError 는 사용자에게 ⚠️ 로 노출되고 dm_chat error 로 기록된다.
        self._patch_llm(error=LLMError("provider down"))
        spy = self._spy_record_usage()
        message = _make_message(content="DM 질문", guild_id=None, author_id=888)

        await self.handler(message)

        sent = message.channel.send.await_args.args[0]
        self.assertIn("⚠️", sent)
        self.assertTrue(self._recorded(spy, command="dm_chat", status="error"))


# ---------------------------------------------------------------------------
# (e) 답장(reply) 맥락: 봇 이전 메시지에 답장하면 대화를 이어간다 (#8)
# ---------------------------------------------------------------------------


class ReplyContextTest(_FileStoreCase):
    def _make_bot_reference(self, *, text: str) -> SimpleNamespace:
        """message.reference 로 쓸, 봇이 보낸 메시지를 resolved 로 가진 더블."""
        referenced = MagicMock(name="ReferencedMessage", spec=discord.Message)
        referenced.author = SimpleNamespace(id=self.bot_user.id, bot=True)
        referenced.content = text
        return SimpleNamespace(resolved=referenced, message_id=12345)

    async def test_reply_to_bot_continues_conversation(self) -> None:
        generate = self._patch_llm("후속 답변")
        spy = self._spy_record_usage()
        ref = self._make_bot_reference(text="봇의 이전 답변")
        message = _make_message(
            content="그럼 이건 어때?",
            reference=ref,
            mentions=[],  # 멘션 없이 답장만으로 이어진다.
        )

        await self.handler(message)

        generate.assert_awaited_once()
        # 봇의 직전 답변이 assistant 턴으로 프롬프트에 반영된다(#8).
        prompt = generate.await_args.args[0]
        self.assertIn("봇의 이전 답변", prompt)
        sent = message.channel.send.await_args.args[0]
        self.assertIn("후속 답변", sent)
        self.assertTrue(self._recorded(spy, command="reply_chat", status="ok"))

    async def test_reply_to_non_bot_falls_through_to_mention_check(self) -> None:
        # 답장 대상이 봇이 아니고 멘션도 없으면 아무 응답도 하지 않는다.
        generate = self._patch_llm()
        referenced = MagicMock(name="OtherUserMessage", spec=discord.Message)
        referenced.author = SimpleNamespace(id=4242, bot=False)
        referenced.content = "다른 사람 메시지"
        ref = SimpleNamespace(resolved=referenced, message_id=4242)
        message = _make_message(content="답장이지만 봇 아님", reference=ref, mentions=[])

        await self.handler(message)

        generate.assert_not_awaited()
        message.channel.send.assert_not_awaited()

    async def test_reply_to_bot_empty_question_returns_early(self) -> None:
        # 답장 본문이 비어 있으면(멘션 토큰만) LLM 을 호출하지 않는다.
        generate = self._patch_llm()
        ref = self._make_bot_reference(text="봇 답변")
        message = _make_message(
            content=f"<@{self.bot_user.id}>",  # 멘션 토큰 제거 후 빈 질문.
            reference=ref,
            mentions=[],
        )

        await self.handler(message)

        generate.assert_not_awaited()


if __name__ == "__main__":
    unittest.main()
