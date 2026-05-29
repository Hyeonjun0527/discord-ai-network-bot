"""봇 이벤트 핸들러(on_reaction_add / on_raw_reaction_add / on_guild_join) 테스트 (#59).

create_bot(settings) 안의 이벤트 핸들러는 store/settings/bot 을 캡처한 클로저다.
discord.py 의 ``@bot.event`` 데코레이터는 핸들러를 ``bot.on_reaction_add`` 처럼
봇 속성으로 등록하므로, 그 속성을 직접 꺼내 mock 인자로 호출한다.

- store 는 임시 파일 기반 SQLite(:memory: 는 연결별 분리 이슈가 있어 사용 불가).
  봇 내부 store 는 settings.database_url 로 같은 파일을 가리키므로, 검증용으로
  같은 파일을 보는 별도 ConfigStore 를 열어 feedback 행을 직접 조회한다.
- reaction/user/guild/payload/channel/message 는 MagicMock/AsyncMock 으로 더블링한다.
- 네트워크/LLM/Discord 전송은 전부 mock: _get_llm/llm.generate 는 AsyncMock,
  채널 전송은 AsyncMock 으로 호출 여부만 검증한다(실제 호출 없음).
"""
from __future__ import annotations

import tempfile
import unittest
from pathlib import Path
from types import SimpleNamespace
from unittest.mock import AsyncMock, MagicMock, patch

from discord_assistant import bot as bot_module
from discord_assistant.bot import (
    REACTION_SUMMARIZE,
    REACTION_TRANSLATE,
    THUMBS_DOWN,
    THUMBS_UP,
    create_bot,
    reset_cooldowns,
)
from discord_assistant.settings import AppSettings
from discord_assistant.storage import ConfigStore


def _make_settings(database_url: str) -> AppSettings:
    """파일 DB 를 가리키는 테스트용 AppSettings 를 만든다.

    metrics_port=0 으로 헬스 서버를 비활성화하고, auto_sync_commands=False 로
    명령 동기화를 막는다(둘 다 setup_hook 경로이며 본 테스트는 호출하지 않는다).
    """
    return AppSettings(
        discord_bot_token="test-token",
        ollama_base_url="http://localhost:11434",
        ollama_model="llama3.1:8b",
        database_url=database_url,
        default_summary_limit=50,
        max_context_chars=12_000,
        default_language="ko",
        ollama_timeout_seconds=60,
        auto_sync_commands=False,
        secret_key="test-secret",
        metrics_port=0,
    )


class _BotEventCase(unittest.IsolatedAsyncioTestCase):
    """파일 DB 로 봇을 만들고 이벤트 핸들러를 꺼내 쓰는 베이스 케이스.

    봇 내부 store 와 검증용 store 는 같은 파일을 본다(WAL 공유). 검증용 store 의
    initialize() 가 스키마를 먼저 만들어 두면, 봇 내부 store 의 지연 초기화는
    멱등이라 동일 스키마를 재사용한다.
    """

    async def asyncSetUp(self) -> None:
        reset_cooldowns()  # 리액션 트리거 쿨다운 전역 상태 격리 (#83)
        bot_module._tracked_messages.clear()  # 추적 메시지 전역 상태 격리

        self._tmp = tempfile.TemporaryDirectory()
        db_path = Path(self._tmp.name) / "events.db"
        self._db_url = f"sqlite:///{db_path}"

        # 검증용 store — 같은 파일을 보며 스키마를 먼저 만들어 둔다.
        self.verify_store = ConfigStore(
            self._db_url,
            default_model="llama3.1:8b",
            default_summary_limit=50,
            default_language="ko",
        )
        await self.verify_store.initialize()

        self.settings = _make_settings(self._db_url)
        self.bot = create_bot(self.settings)

    async def asyncTearDown(self) -> None:
        await self.verify_store.close()
        # 봇 내부 store 가 지연 초기화로 연결을 열었을 수 있으니 정리한다.
        await self.bot.close()
        self._tmp.cleanup()
        reset_cooldowns()
        bot_module._tracked_messages.clear()

    async def _count_feedback(self, *, message_id: int) -> list[dict]:
        """검증용 store 로 feedback 행을 직접 조회한다(공개 조회 API 가 없어 raw SQL)."""
        conn = await self.verify_store._ensure_conn()
        cur = await conn.execute(
            "SELECT guild_id, message_id, user_id, rating, command "
            "FROM feedback WHERE message_id = ? ORDER BY user_id",
            (message_id,),
        )
        rows = await cur.fetchall()
        return [dict(r) for r in rows]


def _make_reaction(*, emoji: str, message_id: int, guild_id: int | None) -> MagicMock:
    """on_reaction_add 용 discord.Reaction 더블.

    str(reaction.emoji) 가 이모지 문자열을 돌려주도록 emoji 를 직접 문자열로 둔다.
    reaction.message 는 guild.id / id 만 핸들러가 읽는다.
    """
    guild = SimpleNamespace(id=guild_id) if guild_id is not None else None
    message = MagicMock(name="Message")
    message.id = message_id
    message.guild = guild
    reaction = MagicMock(name="Reaction")
    reaction.emoji = emoji  # str(reaction.emoji) == emoji
    reaction.message = message
    return reaction


def _make_member(*, user_id: int, is_bot: bool = False) -> MagicMock:
    member = MagicMock(name="Member")
    member.id = user_id
    member.bot = is_bot
    return member


class OnReactionAddTest(_BotEventCase):
    """👍/👎 피드백 저장 — 추적된 메시지만, 중복은 UNIQUE 제약으로 무시."""

    async def test_thumbs_up_saves_positive_feedback(self) -> None:
        bot_module._tracked_messages[222] = {999: "summarize"}
        reaction = _make_reaction(emoji=THUMBS_UP, message_id=999, guild_id=222)
        user = _make_member(user_id=111)

        await self.bot.on_reaction_add(reaction, user)

        rows = await self._count_feedback(message_id=999)
        self.assertEqual(len(rows), 1)
        self.assertEqual(rows[0]["rating"], 1)
        self.assertEqual(rows[0]["command"], "summarize")
        self.assertEqual(rows[0]["guild_id"], 222)
        self.assertEqual(rows[0]["user_id"], 111)

    async def test_thumbs_down_saves_negative_feedback(self) -> None:
        bot_module._tracked_messages[222] = {999: "ask"}
        reaction = _make_reaction(emoji=THUMBS_DOWN, message_id=999, guild_id=222)
        user = _make_member(user_id=111)

        await self.bot.on_reaction_add(reaction, user)

        rows = await self._count_feedback(message_id=999)
        self.assertEqual(len(rows), 1)
        self.assertEqual(rows[0]["rating"], -1)

    async def test_bot_reaction_ignored(self) -> None:
        # 봇 자신/다른 봇의 리액션은 저장하지 않는다.
        bot_module._tracked_messages[222] = {999: "summarize"}
        reaction = _make_reaction(emoji=THUMBS_UP, message_id=999, guild_id=222)
        user = _make_member(user_id=111, is_bot=True)

        await self.bot.on_reaction_add(reaction, user)

        self.assertEqual(await self._count_feedback(message_id=999), [])

    async def test_untracked_message_ignored(self) -> None:
        # 추적되지 않은 메시지에 대한 리액션은 무시된다.
        reaction = _make_reaction(emoji=THUMBS_UP, message_id=999, guild_id=222)
        user = _make_member(user_id=111)

        await self.bot.on_reaction_add(reaction, user)

        self.assertEqual(await self._count_feedback(message_id=999), [])

    async def test_dm_reaction_ignored(self) -> None:
        # guild 가 없는(DM) 리액션은 guild_id None 이라 일찍 반환한다.
        bot_module._tracked_messages[222] = {999: "summarize"}
        reaction = _make_reaction(emoji=THUMBS_UP, message_id=999, guild_id=None)
        user = _make_member(user_id=111)

        await self.bot.on_reaction_add(reaction, user)

        self.assertEqual(await self._count_feedback(message_id=999), [])

    async def test_non_feedback_emoji_ignored(self) -> None:
        # 👍/👎 이외의 이모지는 추적된 메시지여도 저장하지 않는다.
        bot_module._tracked_messages[222] = {999: "summarize"}
        reaction = _make_reaction(emoji="🎉", message_id=999, guild_id=222)
        user = _make_member(user_id=111)

        await self.bot.on_reaction_add(reaction, user)

        self.assertEqual(await self._count_feedback(message_id=999), [])

    async def test_duplicate_feedback_swallowed(self) -> None:
        # 동일 (message_id, user_id) 중복 저장은 UNIQUE 제약 위반이지만 핸들러가
        # 예외를 잡아 로깅만 하므로, 한 행만 남고 예외가 전파되지 않는다.
        bot_module._tracked_messages[222] = {999: "summarize"}
        reaction = _make_reaction(emoji=THUMBS_UP, message_id=999, guild_id=222)
        user = _make_member(user_id=111)

        await self.bot.on_reaction_add(reaction, user)
        # 두 번째 호출은 예외 없이 통과해야 한다(핸들러 내부에서 흡수).
        await self.bot.on_reaction_add(reaction, user)

        rows = await self._count_feedback(message_id=999)
        self.assertEqual(len(rows), 1)


def _make_raw_payload(
    *, emoji: str, message_id: int, channel_id: int, guild_id: int | None, user_id: int
) -> MagicMock:
    """on_raw_reaction_add 용 RawReactionActionEvent 더블."""
    payload = MagicMock(name="RawReactionActionEvent")
    payload.emoji = emoji  # str(payload.emoji) == emoji
    payload.message_id = message_id
    payload.channel_id = channel_id
    payload.guild_id = guild_id
    payload.user_id = user_id
    return payload


class OnRawReactionAddTest(_BotEventCase):
    """📝/🌐 리액션 트리거 — 메시지 요약/번역 후 답장."""

    def _wire_channel(self, *, content: str = "안녕하세요 여러분") -> tuple[MagicMock, MagicMock]:
        """bot.get_channel 이 fetch_message 가능한 채널을 돌려주도록 배선한다.

        fetch_message 는 reply 가 AsyncMock 인 target 메시지를 돌려준다.
        반환: (channel, target_message).
        """
        target = MagicMock(name="TargetMessage")
        target.content = content
        target.reply = AsyncMock()

        channel = MagicMock(name="Channel")
        channel.fetch_message = AsyncMock(return_value=target)
        channel.send = AsyncMock()

        self.bot.get_channel = MagicMock(return_value=channel)
        # 봇 자신의 user.id 와 겹치지 않도록 명시적으로 다른 id 를 둔다.
        self.bot._connection.user = SimpleNamespace(id=424242)
        return channel, target

    async def test_summarize_reaction_replies_with_summary(self) -> None:
        channel, target = self._wire_channel()
        payload = _make_raw_payload(
            emoji=REACTION_SUMMARIZE, message_id=999, channel_id=333,
            guild_id=222, user_id=111,
        )
        fake_llm = MagicMock()
        fake_llm.generate = AsyncMock(return_value="요약 결과입니다.")

        with patch.object(bot_module, "_get_llm", return_value=fake_llm):
            await self.bot.on_raw_reaction_add(payload)

        fake_llm.generate.assert_awaited_once()
        target.reply.assert_awaited()  # 요약 결과를 답장으로 보냈는지 검증
        # 답장 본문에 요약 헤딩이 포함되어야 한다.
        sent = target.reply.await_args.args[0]
        self.assertIn("메시지 요약", sent)

    async def test_translate_reaction_replies_with_translation(self) -> None:
        channel, target = self._wire_channel()
        payload = _make_raw_payload(
            emoji=REACTION_TRANSLATE, message_id=999, channel_id=333,
            guild_id=222, user_id=111,
        )
        fake_llm = MagicMock()
        fake_llm.generate = AsyncMock(return_value="Translated text.")

        with patch.object(bot_module, "_get_llm", return_value=fake_llm):
            await self.bot.on_raw_reaction_add(payload)

        fake_llm.generate.assert_awaited_once()
        sent = target.reply.await_args.args[0]
        self.assertIn("번역", sent)

    async def test_unrelated_emoji_does_nothing(self) -> None:
        channel, target = self._wire_channel()
        payload = _make_raw_payload(
            emoji=THUMBS_UP, message_id=999, channel_id=333,
            guild_id=222, user_id=111,
        )
        with patch.object(bot_module, "_get_llm") as get_llm:
            await self.bot.on_raw_reaction_add(payload)

        # 트리거 이모지가 아니므로 LLM/채널 조회조차 하지 않는다.
        get_llm.assert_not_called()
        channel.fetch_message.assert_not_called()

    async def test_bot_own_reaction_ignored(self) -> None:
        channel, target = self._wire_channel()
        # 봇 자신의 user.id 와 동일한 user_id 로 리액션 → 무시.
        self.bot._connection.user = SimpleNamespace(id=111)
        payload = _make_raw_payload(
            emoji=REACTION_SUMMARIZE, message_id=999, channel_id=333,
            guild_id=222, user_id=111,
        )
        with patch.object(bot_module, "_get_llm") as get_llm:
            await self.bot.on_raw_reaction_add(payload)

        get_llm.assert_not_called()
        channel.fetch_message.assert_not_called()

    async def test_cooldown_blocks_second_trigger(self) -> None:
        channel, target = self._wire_channel()
        payload = _make_raw_payload(
            emoji=REACTION_SUMMARIZE, message_id=999, channel_id=333,
            guild_id=222, user_id=111,
        )
        fake_llm = MagicMock()
        fake_llm.generate = AsyncMock(return_value="요약")

        with patch.object(bot_module, "_get_llm", return_value=fake_llm):
            await self.bot.on_raw_reaction_add(payload)
            # 같은 사용자/길드의 즉시 두 번째 트리거는 쿨다운에 막혀야 한다.
            await self.bot.on_raw_reaction_add(payload)

        # 첫 번째만 LLM 호출 — 두 번째는 쿨다운으로 차단.
        self.assertEqual(fake_llm.generate.await_count, 1)

    async def test_empty_message_skipped(self) -> None:
        # 텍스트가 없는(첨부만 등) 메시지는 LLM 을 호출하지 않는다.
        channel, target = self._wire_channel(content="   ")
        payload = _make_raw_payload(
            emoji=REACTION_SUMMARIZE, message_id=999, channel_id=333,
            guild_id=222, user_id=111,
        )
        with patch.object(bot_module, "_get_llm") as get_llm:
            await self.bot.on_raw_reaction_add(payload)

        get_llm.assert_not_called()
        target.reply.assert_not_called()


class OnGuildJoinTest(_BotEventCase):
    """서버 합류 시 환영 메시지 전송 / 가능한 채널이 없으면 경고 로그."""

    def _make_channel(self, *, can_send: bool) -> MagicMock:
        channel = MagicMock(name="TextChannel")
        channel.send = AsyncMock()
        perms = SimpleNamespace(send_messages=can_send)
        channel.permissions_for = MagicMock(return_value=perms)
        return channel

    def _make_guild(self, channels: list[MagicMock]) -> MagicMock:
        guild = MagicMock(name="Guild")
        guild.id = 222
        guild.name = "test-guild"
        guild.member_count = 5
        guild.me = SimpleNamespace(id=424242)
        guild.text_channels = channels
        return guild

    async def test_welcome_sent_to_first_permitted_channel(self) -> None:
        ok_channel = self._make_channel(can_send=True)
        guild = self._make_guild([ok_channel])

        await self.bot.on_guild_join(guild)

        ok_channel.send.assert_awaited_once()
        # 환영 임베드(embeds=...)로 전송했는지 확인.
        self.assertIn("embeds", ok_channel.send.await_args.kwargs)

    async def test_skips_channel_without_send_permission(self) -> None:
        no_perm = self._make_channel(can_send=False)
        ok_channel = self._make_channel(can_send=True)
        guild = self._make_guild([no_perm, ok_channel])

        await self.bot.on_guild_join(guild)

        # 권한 없는 채널은 건너뛰고 권한 있는 첫 채널로만 전송한다.
        no_perm.send.assert_not_called()
        ok_channel.send.assert_awaited_once()

    async def test_warns_when_no_accessible_channel(self) -> None:
        no_perm = self._make_channel(can_send=False)
        guild = self._make_guild([no_perm])

        with self.assertLogs(bot_module.logger, level="WARNING") as captured:
            await self.bot.on_guild_join(guild)

        no_perm.send.assert_not_called()
        # 접근 가능한 채널이 없을 때 경고 로그를 남긴다.
        joined = "\n".join(captured.output)
        self.assertIn("welcome message", joined)


if __name__ == "__main__":
    unittest.main()
