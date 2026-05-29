"""bot.py 슬래시 명령 핸들러 커버리지 (bot-cmds-b).

대상: /remind · /reminders(예약/조회/취소), /stats, /usage, /export,
/pin-summary, /forget-me, /help, 그리고 config 그룹 하위명령(model/summary_limit/
language/admin_role/persona/auto_summary/custom_prompt/allowed_role/
daily_token_budget).

패턴은 tests/test_bot_handlers.py 를 모방한다:
  - create_bot(settings) 의 클로저 콜백을 bot.tree.get_command(name).callback 로 꺼낸다.
  - config 그룹 하위명령은 그룹에서 .commands 로 꺼낸다.
  - :memory: 는 연결별 분리 이슈가 있어 tempfile 기반 파일 DB 를 쓴다(같은 URL 로
    별도 핸들을 열어 검증한다).
  - 네트워크/LLM/Discord 는 전부 mock 한다. _get_llm·_collect_transcript 를 패치.
  - /remind 의 fire-and-forget 예약 태스크는 _track_task 를 패치해 실제로 띄우지
    않는다(긴 sleep 태스크가 누수되지 않게).

모든 단언은 동작/분기를 검증한다(스모크 호출이 아님).
"""
from __future__ import annotations

import tempfile
import unittest
from datetime import datetime, timezone
from pathlib import Path
from typing import Any
from unittest.mock import AsyncMock, MagicMock, patch

import discord

from discord_assistant import bot as bot_module
from discord_assistant.bot import (
    _REMIND_KIND_SUMMARY,
    _REMIND_KIND_TEXT,
    _decode_remind_payload,
    _encode_remind_payload,
    _last_summaries,
    create_bot,
    reset_cooldowns,
)
from discord_assistant.llm import TokenUsage
from discord_assistant.settings import AppSettings
from discord_assistant.storage import ConfigStore


# ---------------------------------------------------------------------------
# 가짜 LLM — 네트워크 없이 고정 응답.
# ---------------------------------------------------------------------------
class _FakeLLM:
    def __init__(self, text: str = "가짜 응답") -> None:
        self.text = text
        self.last_usage = TokenUsage(prompt_tokens=5, completion_tokens=3)
        self.generate_calls: list[str] = []

    async def generate(self, prompt: str, *, model: str | None = None, **_: Any) -> str:
        self.generate_calls.append(prompt)
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


def _make_interaction(
    *,
    user_id: int = 111,
    guild_id: int | None = 222,
    channel_id: int | None = 333,
    administrator: bool = False,
    manage_guild: bool = False,
    manage_messages: bool = False,
    roles: list[int] | None = None,
) -> MagicMock:
    """슬래시 핸들러에 넘길 Interaction 목.

    response.defer/send_message 호출 후 is_done() 이 True 가 되도록 흉내내,
    _send_interaction_chunks 가 followup 경로로 분기하게 한다.
    """
    user = MagicMock(name="User")
    user.id = user_id
    user.display_name = "tester"
    role_objs = []
    for rid in (roles or []):
        r = MagicMock()
        r.id = rid
        role_objs.append(r)
    user.roles = role_objs
    perms = MagicMock()
    perms.administrator = administrator
    perms.manage_guild = manage_guild
    perms.manage_messages = manage_messages
    user.guild_permissions = perms
    user.send = AsyncMock()

    if guild_id is None:
        guild = None
    else:
        guild = MagicMock(name="Guild")
        guild.id = guild_id
        guild.name = "test-guild"

    if channel_id is None:
        channel = None
    else:
        channel = MagicMock(name="Channel")
        channel.id = channel_id
        channel.name = "general"
        channel.send = AsyncMock()

    response = MagicMock(name="Response")
    response.is_done = MagicMock(return_value=False)

    def _mark_done(*_a: Any, **_k: Any) -> None:
        response.is_done.return_value = True

    response.defer = AsyncMock(side_effect=_mark_done)
    response.send_message = AsyncMock(side_effect=_mark_done)
    response.edit_message = AsyncMock(side_effect=_mark_done)

    sent_message = MagicMock(name="SentMessage")
    sent_message.id = 999
    sent_message.pin = AsyncMock()
    sent_message.add_reaction = AsyncMock()
    sent_message.edit = AsyncMock()
    followup = MagicMock(name="Followup")
    followup.send = AsyncMock(return_value=sent_message)

    inter = MagicMock(name="Interaction")
    inter.response = response
    inter.followup = followup
    inter.user = user
    inter.guild = guild
    inter.guild_id = guild_id
    inter.channel = channel
    inter.channel_id = channel_id
    inter.id = 4242
    inter.created_at = datetime.now(timezone.utc)
    inter.edit_original_response = AsyncMock()
    inter._sent_message = sent_message  # 테스트 접근용
    return inter


class _Base(unittest.IsolatedAsyncioTestCase):
    async def asyncSetUp(self) -> None:
        reset_cooldowns()
        self._tmp = tempfile.TemporaryDirectory()
        db_path = Path(self._tmp.name) / "assistant.db"
        self.database_url = f"sqlite:///{db_path}"
        self.settings = _make_settings(self.database_url)
        # /remind 의 fire-and-forget 스케줄 태스크가 누수되지 않게 _track_task 를
        # no-op 으로 패치한다(긴 sleep 코루틴을 띄우지 않는다).
        self._track_patch = patch.object(
            bot_module, "_track_task", lambda coro, **_kw: _close_coro(coro)
        )
        self._track_patch.start()
        self.bot = create_bot(self.settings)
        self.store = ConfigStore(
            self.database_url,
            default_model="test-model",
            default_summary_limit=10,
            default_language="ko",
        )
        await self.store.initialize()

    async def asyncTearDown(self) -> None:
        self._track_patch.stop()
        await self.store.close()
        self._tmp.cleanup()
        reset_cooldowns()
        _last_summaries.clear()

    def _callback(self, name: str):
        cmd = self.bot.tree.get_command(name)
        assert cmd is not None, f"command not found: {name}"
        return cmd.callback

    def _config_sub(self, name: str):
        group = self.bot.tree.get_command("config")
        assert group is not None, "config group not found"
        for cmd in group.commands:
            if cmd.name == name:
                return cmd.callback
        raise AssertionError(f"config subcommand not found: {name}")

    def _patch_llm(self, llm: Any):
        return patch.object(bot_module, "_get_llm", return_value=llm)

    def _patch_transcript(self, text: str):
        return patch.object(
            bot_module, "_collect_transcript", AsyncMock(return_value=text)
        )

    @staticmethod
    def _first_text(inter: MagicMock) -> str:
        """config 류 명령의 첫 사용자 응답 텍스트를 꺼낸다.

        config 하위명령은 defer 하지 않으므로 _send_interaction_chunks 가
        response.send_message 로 첫 메시지를 보낸다(이후가 있으면 followup).
        """
        if inter.response.send_message.await_args is not None:
            args = inter.response.send_message.await_args.args
            if args:
                return args[0]
        if inter.followup.send.await_args is not None:
            args = inter.followup.send.await_args.args
            if args:
                return args[0]
        raise AssertionError("no text response captured")


def _close_coro(coro: Any) -> None:
    """띄우지 않을 코루틴을 즉시 닫아 'never awaited' 경고를 막는다."""
    try:
        coro.close()
    except Exception:  # pragma: no cover - 방어
        pass


# ===========================================================================
# /remind
# ===========================================================================
class RemindTest(_Base):
    async def test_text_message_schedules_and_acks(self) -> None:
        inter = _make_interaction()
        await self._callback("remind")(inter, when="30m", message="회의 준비")

        inter.response.send_message.assert_awaited_once()
        msg = inter.response.send_message.await_args.args[0]
        self.assertIn("분 후에", msg)
        # 실제로 DB 에 reminder 가 저장됐는지 확인.
        rows = await self.store.list_by_user(111)
        self.assertEqual(len(rows), 1)
        decoded = _decode_remind_payload(rows[0].payload)
        self.assertEqual(decoded["kind"], _REMIND_KIND_TEXT)
        self.assertEqual(decoded["text"], "회의 준비")

    async def test_invalid_when_format_rejected(self) -> None:
        inter = _make_interaction()
        await self._callback("remind")(inter, when="not-a-time", message="x")

        inter.response.send_message.assert_awaited_once()
        self.assertIn("올바른 형식", inter.response.send_message.await_args.args[0])
        # 저장되지 않아야 한다.
        self.assertEqual(await self.store.list_by_user(111), [])

    async def test_empty_message_without_cached_summary(self) -> None:
        inter = _make_interaction()
        await self._callback("remind")(inter, when="1h", message="")

        inter.response.send_message.assert_awaited_once()
        self.assertIn("보낼 내용이 없어요", inter.response.send_message.await_args.args[0])
        self.assertEqual(await self.store.list_by_user(111), [])

    async def test_empty_message_uses_cached_summary(self) -> None:
        _last_summaries[111] = ("어제 요약 결과", 222)
        inter = _make_interaction()
        await self._callback("remind")(inter, when="2h", message="", repeat="daily")

        inter.response.send_message.assert_awaited_once()
        self.assertIn("시간 후에", inter.response.send_message.await_args.args[0])
        rows = await self.store.list_by_user(111)
        self.assertEqual(len(rows), 1)
        decoded = _decode_remind_payload(rows[0].payload)
        self.assertEqual(decoded["kind"], _REMIND_KIND_SUMMARY)
        self.assertEqual(decoded["text"], "어제 요약 결과")
        self.assertEqual(decoded["repeat"], "daily")

    async def test_day_label_branch(self) -> None:
        inter = _make_interaction()
        await self._callback("remind")(inter, when="2d", message="장기 알림")
        self.assertIn("일 후에", inter.response.send_message.await_args.args[0])

    async def test_zero_delay_rejected(self) -> None:
        inter = _make_interaction()
        await self._callback("remind")(inter, when="0m", message="x")
        self.assertIn("0은 허용", inter.response.send_message.await_args.args[0])

    async def test_cooldown_blocks_second_remind(self) -> None:
        # #2: 다른 명령처럼 쿨다운을 적용해 스팸성 대량 예약을 막는다.
        first = _make_interaction()
        await self._callback("remind")(first, when="30m", message="첫번째")
        self.assertIn("분 후에", first.response.send_message.await_args.args[0])
        second = _make_interaction()
        await self._callback("remind")(second, when="30m", message="두번째")
        self.assertIn("초 후에 다시", second.response.send_message.await_args.args[0])
        # 두 번째는 저장되지 않는다(쿨다운 차단).
        self.assertEqual(len(await self.store.list_by_user(111)), 1)

    async def test_stored_text_is_length_capped(self) -> None:
        # #2: 저장 payload 길이를 제한해 DB 적체/장기 평문 보존(PII)을 억제한다.
        inter = _make_interaction()
        huge = "가" * 5000
        await self._callback("remind")(inter, when="30m", message=huge)
        rows = await self.store.list_by_user(111)
        self.assertEqual(len(rows), 1)
        decoded = _decode_remind_payload(rows[0].payload)
        self.assertEqual(len(decoded["text"]), bot_module._MAX_REMIND_TEXT_CHARS)

    async def test_pending_cap_rejects_new_reminder(self) -> None:
        # #2: 미발송 리마인더가 사용자별 상한을 넘으면 새 예약을 거절한다.
        cap = bot_module._MAX_PENDING_REMINDERS_PER_USER
        due = datetime.now(timezone.utc).isoformat()
        payload = _encode_remind_payload("기존", kind=_REMIND_KIND_TEXT)
        for _ in range(cap):
            await self.store.add_reminder(111, 222, 333, due, payload)
        inter = _make_interaction()
        await self._callback("remind")(inter, when="30m", message="하나 더")
        self.assertIn("최대", inter.response.send_message.await_args.args[0])
        # 상한 도달 상태에서 새 행이 추가되지 않는다.
        self.assertEqual(len(await self.store.list_by_user(111)), cap)


# ===========================================================================
# /reminders
# ===========================================================================
class RemindersTest(_Base):
    async def _seed_reminder(self, *, user_id: int = 111) -> int:
        payload = _encode_remind_payload("내용", kind=_REMIND_KIND_TEXT)
        due = datetime.now(timezone.utc).isoformat()
        return await self.store.add_reminder(user_id, 222, 333, due, payload)

    async def test_empty_list(self) -> None:
        inter = _make_interaction()
        await self._callback("reminders")(inter, cancel=None)
        inter.response.send_message.assert_awaited_once()
        self.assertIn("예약된 알림이 없어요", inter.response.send_message.await_args.args[0])

    async def test_list_shows_embed(self) -> None:
        await self._seed_reminder()
        inter = _make_interaction()
        await self._callback("reminders")(inter, cancel=None)
        kwargs = inter.response.send_message.await_args.kwargs
        embed = kwargs.get("embed")
        self.assertIsNotNone(embed)
        self.assertEqual(embed.title, "내 예약 알림")
        self.assertTrue(embed.fields)

    async def test_cancel_owned_reminder(self) -> None:
        rid = await self._seed_reminder()
        inter = _make_interaction()
        await self._callback("reminders")(inter, cancel=rid)
        self.assertIn(
            f"#{rid}", inter.response.send_message.await_args.args[0]
        )
        # 실제로 삭제됐다.
        self.assertEqual(await self.store.list_by_user(111), [])

    async def test_cancel_unknown_id(self) -> None:
        inter = _make_interaction()
        await self._callback("reminders")(inter, cancel=99999)
        self.assertIn("본인 것이 아니에요", inter.response.send_message.await_args.args[0])

    async def test_cancel_other_users_reminder_blocked(self) -> None:
        # 다른 사용자의 리마인더는 취소할 수 없다.
        other_rid = await self._seed_reminder(user_id=222)
        inter = _make_interaction(user_id=111)
        await self._callback("reminders")(inter, cancel=other_rid)
        self.assertIn("본인 것이 아니에요", inter.response.send_message.await_args.args[0])
        # 다른 사용자의 리마인더는 여전히 존재해야 한다.
        self.assertEqual(len(await self.store.list_by_user(222)), 1)


# ===========================================================================
# /stats
# ===========================================================================
class StatsTest(_Base):
    async def test_dm_blocked(self) -> None:
        inter = _make_interaction(guild_id=None)
        await self._callback("stats")(inter)
        inter.response.send_message.assert_awaited_once()
        self.assertIn("서버 안에서만", inter.response.send_message.await_args.args[0])

    async def test_no_records_description(self) -> None:
        inter = _make_interaction()
        await self._callback("stats")(inter)
        inter.response.defer.assert_awaited_once()
        embed = inter.followup.send.await_args.kwargs.get("embed")
        self.assertIsNotNone(embed)
        self.assertEqual(embed.title, "서버 사용 통계")
        self.assertIn("집계된 사용 기록 없음", embed.description)

    async def test_with_records_shows_command_breakdown(self) -> None:
        # 사용 기록을 직접 심어 by_command 필드 분기를 태운다.
        from discord_assistant.models import UsageLog

        await self.store.log_usage(
            UsageLog(
                guild_id=222, channel_id=333, user_id=111,
                command="summarize", status="ok", latency_ms=120,
            )
        )
        inter = _make_interaction()
        await self._callback("stats")(inter)
        embed = inter.followup.send.await_args.kwargs.get("embed")
        names = [f.name for f in embed.fields]
        self.assertIn("명령어별 사용 횟수", names)
        self.assertIn("집계 기간", embed.description)


# ===========================================================================
# /usage
# ===========================================================================
class UsageTest(_Base):
    async def test_basic_embed_guild(self) -> None:
        inter = _make_interaction()
        await self._callback("usage")(inter)
        inter.response.send_message.assert_awaited_once()
        embed = inter.response.send_message.await_args.kwargs.get("embed")
        self.assertIsNotNone(embed)
        names = [f.name for f in embed.fields]
        self.assertIn("서버 총 사용 횟수", names)
        self.assertIn("남은 쿨다운", names)

    async def test_cooldown_remaining_shown(self) -> None:
        # _cooldowns 에 방금 사용한 것처럼 심으면 '남음' 분기를 탄다.
        from time import perf_counter

        bot_module._cooldowns[(222, 111)] = perf_counter()
        try:
            inter = _make_interaction()
            await self._callback("usage")(inter)
            embed = inter.response.send_message.await_args.kwargs.get("embed")
            cooldown_field = next(f for f in embed.fields if f.name == "남은 쿨다운")
            self.assertIn("초 남음", cooldown_field.value)
        finally:
            bot_module._cooldowns.clear()

    async def test_dm_no_guild_stats(self) -> None:
        inter = _make_interaction(guild_id=None)
        await self._callback("usage")(inter)
        embed = inter.response.send_message.await_args.kwargs.get("embed")
        names = [f.name for f in embed.fields]
        # 길드가 없으면 서버 통계 필드는 빠진다.
        self.assertNotIn("서버 총 사용 횟수", names)
        self.assertIn("남은 쿨다운", names)

    async def test_large_summary_limit_note(self) -> None:
        # summary_limit 를 200 초과로 직접 upsert 해 limit_note 분기를 태운다.
        from dataclasses import replace

        cfg = await self.store.get_guild_config(222)
        await self.store._upsert(replace(cfg, summary_limit=300))
        inter = _make_interaction()
        await self._callback("usage")(inter)
        embed = inter.response.send_message.await_args.kwargs.get("embed")
        limit_field = next(
            f for f in embed.fields if f.name == "서버 요약 범위(summary_limit)"
        )
        self.assertIn("실제", limit_field.value)


# ===========================================================================
# /export
# ===========================================================================
class ExportTest(_Base):
    def _make_history_channel(self, inter: MagicMock, messages: list[Any]) -> None:
        async def _hist(*_a: Any, **_k: Any):
            for m in messages:
                yield m

        inter.channel.history = MagicMock(side_effect=_hist)

    def _fake_msg(self, content: str) -> MagicMock:
        m = MagicMock()
        author = MagicMock()
        author.display_name = "alice"
        m.author = author
        m.content = content
        m.created_at = datetime.now(timezone.utc)
        m.attachments = []
        m.embeds = []
        return m

    async def test_export_sends_dm_file(self) -> None:
        inter = _make_interaction()
        self._make_history_channel(inter, [self._fake_msg("안녕"), self._fake_msg("반가워")])
        await self._callback("export")(inter, limit=5)

        inter.response.defer.assert_awaited_once()
        # DM 으로 파일을 전송한다.
        inter.user.send.assert_awaited_once()
        file_kwarg = inter.user.send.await_args.kwargs.get("file")
        self.assertIsInstance(file_kwarg, discord.File)
        # 성공 안내가 followup 으로 전송.
        sent_texts = [c.args[0] for c in inter.followup.send.await_args_list if c.args]
        self.assertTrue(any("DM으로" in t for t in sent_texts))
        stats = await self.store.get_stats(222)
        by_cmd = {r["command"]: r["count"] for r in stats["by_command"]}
        self.assertEqual(by_cmd.get("export"), 1)

    async def test_export_dm_forbidden(self) -> None:
        inter = _make_interaction()
        self._make_history_channel(inter, [self._fake_msg("내용")])
        inter.user.send = AsyncMock(
            side_effect=discord.Forbidden(MagicMock(status=403), "no dm")
        )
        await self._callback("export")(inter, limit=5)
        sent_texts = [c.args[0] for c in inter.followup.send.await_args_list if c.args]
        self.assertTrue(any("DM을 보낼 수 없어요" in t for t in sent_texts))

    async def test_export_read_history_forbidden(self) -> None:
        inter = _make_interaction()

        async def _hist(*_a: Any, **_k: Any):
            raise discord.Forbidden(MagicMock(status=403), "no history")
            yield  # pragma: no cover

        inter.channel.history = MagicMock(side_effect=_hist)
        await self._callback("export")(inter, limit=5)
        sent_texts = [c.args[0] for c in inter.followup.send.await_args_list if c.args]
        self.assertTrue(any("Read Message History" in t for t in sent_texts))
        stats = await self.store.get_stats(222)
        self.assertEqual(stats["error_rate"], 100.0)


# ===========================================================================
# /pin-summary
# ===========================================================================
class PinSummaryTest(_Base):
    async def test_dm_blocked(self) -> None:
        inter = _make_interaction(guild_id=None)
        await self._callback("pin-summary")(inter, limit=5)
        self.assertIn("서버 안에서만", inter.response.send_message.await_args.args[0])

    async def test_no_permission_blocked(self) -> None:
        inter = _make_interaction(administrator=False, manage_messages=False)
        await self._callback("pin-summary")(inter, limit=5)
        self.assertIn(
            "권한이 필요해요", inter.response.send_message.await_args.args[0]
        )

    async def test_pin_success(self) -> None:
        inter = _make_interaction(manage_messages=True)
        llm = _FakeLLM("핀 요약")
        with self._patch_llm(llm), self._patch_transcript("a: hi\nb: yo"):
            await self._callback("pin-summary")(inter, limit=5)

        inter.response.defer.assert_awaited_once()
        # 요약 본문 followup → 메시지 pin 호출.
        inter._sent_message.pin.assert_awaited_once()
        self.assertEqual(len(llm.generate_calls), 1)
        stats = await self.store.get_stats(222)
        by_cmd = {r["command"]: r["count"] for r in stats["by_command"]}
        self.assertEqual(by_cmd.get("pin_summary"), 1)

    async def test_pin_forbidden_handled(self) -> None:
        inter = _make_interaction(manage_messages=True)
        inter._sent_message.pin = AsyncMock(
            side_effect=discord.Forbidden(MagicMock(status=403), "no pin")
        )
        llm = _FakeLLM("요약")
        with self._patch_llm(llm), self._patch_transcript("a: hi"):
            await self._callback("pin-summary")(inter, limit=5)
        sent_texts = [c.args[0] for c in inter.followup.send.await_args_list if c.args]
        self.assertTrue(any("고정할 권한이 없어요" in t for t in sent_texts))

    async def test_empty_transcript_error(self) -> None:
        inter = _make_interaction(administrator=True)
        llm = _FakeLLM()
        with self._patch_llm(llm), self._patch_transcript(""):
            await self._callback("pin-summary")(inter, limit=5)
        sent_texts = [c.args[0] for c in inter.followup.send.await_args_list if c.args]
        self.assertTrue(any("요약할 메시지가 없어요" in t for t in sent_texts))
        self.assertEqual(len(llm.generate_calls), 0)
        stats = await self.store.get_stats(222)
        self.assertEqual(stats["error_rate"], 100.0)


# ===========================================================================
# /forget-me
# ===========================================================================
class ForgetMeTest(_Base):
    async def test_shows_confirm_view(self) -> None:
        inter = _make_interaction()
        await self._callback("forget-me")(inter)
        inter.response.send_message.assert_awaited_once()
        kwargs = inter.response.send_message.await_args.kwargs
        self.assertIsNotNone(kwargs.get("view"))
        self.assertTrue(kwargs.get("ephemeral"))
        self.assertIn("되돌릴 수 없어요", inter.response.send_message.await_args.args[0])

    async def test_confirm_button_deletes_data(self) -> None:
        # 사용자 데이터를 심어두고, 확인 버튼 콜백을 직접 눌러 삭제 분기를 태운다.
        from discord_assistant.models import UsageLog

        await self.store.log_usage(
            UsageLog(guild_id=222, channel_id=333, user_id=111,
                     command="ask", status="ok", latency_ms=10)
        )
        _last_summaries[111] = ("요약", 222)

        inter = _make_interaction()
        await self._callback("forget-me")(inter)
        view = inter.response.send_message.await_args.kwargs["view"]

        # 확인 버튼 콜백을 찾는다(label="삭제 확인").
        confirm_btn = next(
            c for c in view.children
            if isinstance(c, discord.ui.Button) and c.label == "삭제 확인"
        )
        btn_inter = _make_interaction()
        await confirm_btn.callback(btn_inter)

        btn_inter.response.edit_message.assert_awaited_once()
        content = btn_inter.response.edit_message.await_args.kwargs["content"]
        self.assertIn("데이터를 삭제했어요", content)
        # 캐시에서도 제거됐다.
        self.assertNotIn(111, _last_summaries)
        # DB 에서도 사라졌다.
        stats = await self.store.get_stats(222)
        self.assertEqual(stats["total"], 0)

    async def test_cancel_button_keeps_data(self) -> None:
        inter = _make_interaction()
        await self._callback("forget-me")(inter)
        view = inter.response.send_message.await_args.kwargs["view"]
        cancel_btn = next(
            c for c in view.children
            if isinstance(c, discord.ui.Button) and c.label == "취소"
        )
        btn_inter = _make_interaction()
        await cancel_btn.callback(btn_inter)
        content = btn_inter.response.edit_message.await_args.kwargs["content"]
        self.assertIn("그대로 유지", content)

    async def test_confirm_view_rejects_other_user(self) -> None:
        inter = _make_interaction(user_id=111)
        await self._callback("forget-me")(inter)
        view = inter.response.send_message.await_args.kwargs["view"]
        # interaction_check 가 다른 사용자를 거부한다.
        other = _make_interaction(user_id=222)
        allowed = await view.interaction_check(other)
        self.assertFalse(allowed)
        other.response.send_message.assert_awaited_once()
        self.assertIn("본인만", other.response.send_message.await_args.args[0])
        # 본인은 허용.
        mine = _make_interaction(user_id=111)
        self.assertTrue(await view.interaction_check(mine))


# ===========================================================================
# /help
# ===========================================================================
class HelpTest(_Base):
    async def test_help_in_guild(self) -> None:
        inter = _make_interaction()
        await self._callback("help")(inter)
        inter.response.send_message.assert_awaited_once()
        kwargs = inter.response.send_message.await_args.kwargs
        self.assertIsNotNone(kwargs.get("embed"))
        self.assertIsNotNone(kwargs.get("view"))
        self.assertTrue(kwargs.get("ephemeral"))

    async def test_help_in_dm_falls_back_ko(self) -> None:
        inter = _make_interaction(guild_id=None)
        await self._callback("help")(inter)
        inter.response.send_message.assert_awaited_once()
        # DM 에서도 임베드/뷰가 정상 구성된다(ko 폴백).
        self.assertIsNotNone(
            inter.response.send_message.await_args.kwargs.get("embed")
        )

    async def test_help_with_dashboard_url_adds_button(self) -> None:
        inter = _make_interaction()
        with patch.dict("os.environ", {"DASHBOARD_URL": "https://dash.example"}):
            await self._callback("help")(inter)
        view = inter.response.send_message.await_args.kwargs.get("view")
        # 대시보드 링크 버튼이 추가됐다(url 속성 보유 버튼).
        link_buttons = [
            c for c in view.children
            if isinstance(c, discord.ui.Button) and getattr(c, "url", None)
        ]
        self.assertTrue(link_buttons)


# ===========================================================================
# config 그룹 하위명령
# ===========================================================================
class ConfigGroupTest(_Base):
    # --- model ---
    async def test_config_model_admin_ok(self) -> None:
        inter = _make_interaction(administrator=True)
        await self._config_sub("model")(inter, model="llama3.1:8b")
        sent = self._first_text(inter)
        self.assertIn("llama3.1:8b", sent)
        cfg = await self.store.get_guild_config(222)
        self.assertEqual(cfg.model, "llama3.1:8b")

    async def test_config_model_no_permission(self) -> None:
        inter = _make_interaction(administrator=False, manage_guild=False)
        await self._config_sub("model")(inter, model="x")
        sent = self._first_text(inter)
        self.assertIn("권한이 필요해요", sent)
        # 변경되지 않았다.
        cfg = await self.store.get_guild_config(222)
        self.assertEqual(cfg.model, "test-model")

    async def test_config_model_in_dm_blocked(self) -> None:
        inter = _make_interaction(guild_id=None, administrator=True)
        await self._config_sub("model")(inter, model="x")
        sent = self._first_text(inter)
        self.assertIn("서버 안에서만", sent)

    async def test_config_model_empty_value_error(self) -> None:
        # set_model 이 ValueError 를 던지는 분기(빈 모델명).
        inter = _make_interaction(administrator=True)
        await self._config_sub("model")(inter, model="   ")
        sent = self._first_text(inter)
        self.assertTrue(sent.startswith("⚠️"))

    async def test_config_uses_admin_role(self) -> None:
        # 관리자 권한은 없지만 admin_role 을 가진 사용자도 허용된다.
        await self.store.set_admin_role(222, 555)
        inter = _make_interaction(administrator=False, roles=[555])
        await self._config_sub("model")(inter, model="role-model")
        cfg = await self.store.get_guild_config(222)
        self.assertEqual(cfg.model, "role-model")

    # --- summary_limit ---
    async def test_config_summary_limit_ok(self) -> None:
        inter = _make_interaction(administrator=True)
        await self._config_sub("summary_limit")(inter, limit=50)
        cfg = await self.store.get_guild_config(222)
        self.assertEqual(cfg.summary_limit, 50)

    async def test_config_summary_limit_out_of_range(self) -> None:
        inter = _make_interaction(administrator=True)
        await self._config_sub("summary_limit")(inter, limit=999)
        sent = self._first_text(inter)
        self.assertTrue(sent.startswith("⚠️"))
        cfg = await self.store.get_guild_config(222)
        self.assertEqual(cfg.summary_limit, 10)

    # --- language ---
    async def test_config_language_ok(self) -> None:
        inter = _make_interaction(administrator=True)
        await self._config_sub("language")(inter, language="en")
        cfg = await self.store.get_guild_config(222)
        self.assertEqual(cfg.language, "en")

    async def test_config_language_empty_error(self) -> None:
        inter = _make_interaction(administrator=True)
        await self._config_sub("language")(inter, language="   ")
        sent = self._first_text(inter)
        self.assertTrue(sent.startswith("⚠️"))

    # --- admin_role ---
    async def test_config_admin_role_ok(self) -> None:
        inter = _make_interaction(administrator=True)
        role = MagicMock()
        role.id = 777
        role.name = "Mods"
        await self._config_sub("admin_role")(inter, role=role)
        sent = self._first_text(inter)
        self.assertIn("Mods", sent)
        cfg = await self.store.get_guild_config(222)
        self.assertEqual(cfg.admin_role_id, 777)

    async def test_config_admin_role_no_permission(self) -> None:
        inter = _make_interaction(administrator=False)
        role = MagicMock()
        role.id = 777
        role.name = "Mods"
        await self._config_sub("admin_role")(inter, role=role)
        self.assertIn("권한이 필요해요", self._first_text(inter))

    # --- persona ---
    async def test_config_persona_set(self) -> None:
        inter = _make_interaction(administrator=True)
        await self._config_sub("persona")(inter, description="친절한 도우미")
        sent = self._first_text(inter)
        self.assertIn("페르소나를 설정", sent)
        cfg = await self.store.get_guild_config(222)
        self.assertEqual(cfg.persona, "친절한 도우미")

    async def test_config_persona_reset_empty(self) -> None:
        await self.store.set_persona(222, "기존")
        inter = _make_interaction(administrator=True)
        await self._config_sub("persona")(inter, description="")
        sent = self._first_text(inter)
        self.assertIn("초기화", sent)
        cfg = await self.store.get_guild_config(222)
        self.assertIsNone(cfg.persona)

    async def test_config_persona_too_long(self) -> None:
        inter = _make_interaction(administrator=True)
        await self._config_sub("persona")(inter, description="가" * 600)
        sent = self._first_text(inter)
        self.assertIn("이하여야", sent)

    # --- auto_summary ---
    async def test_config_auto_summary_enable(self) -> None:
        inter = _make_interaction(administrator=True)
        await self._config_sub("auto_summary")(inter, interval=15)
        sent = self._first_text(inter)
        self.assertIn("15분", sent)
        cfg = await self.store.get_guild_config(222)
        self.assertEqual(cfg.auto_summary_interval, 15)

    async def test_config_auto_summary_disable(self) -> None:
        await self.store.set_auto_summary_interval(222, 10)
        inter = _make_interaction(administrator=True)
        await self._config_sub("auto_summary")(inter, interval=0)
        sent = self._first_text(inter)
        self.assertIn("비활성화", sent)
        cfg = await self.store.get_guild_config(222)
        self.assertIsNone(cfg.auto_summary_interval)

    # --- custom_prompt ---
    async def test_config_custom_prompt_summarize_set(self) -> None:
        inter = _make_interaction(administrator=True)
        await self._config_sub("custom_prompt")(
            inter, prompt_type="summarize", text="요약: {transcript}"
        )
        sent = self._first_text(inter)
        self.assertIn("저장", sent)
        cfg = await self.store.get_guild_config(222)
        self.assertEqual(cfg.custom_summarize_prompt, "요약: {transcript}")

    async def test_config_custom_prompt_ask_reset(self) -> None:
        await self.store.set_custom_prompt(222, "ask", "기존 질문 프롬프트")
        inter = _make_interaction(administrator=True)
        await self._config_sub("custom_prompt")(inter, prompt_type="ask", text="")
        sent = self._first_text(inter)
        self.assertIn("초기화", sent)
        cfg = await self.store.get_guild_config(222)
        self.assertIsNone(cfg.custom_ask_prompt)

    async def test_config_custom_prompt_too_long(self) -> None:
        inter = _make_interaction(administrator=True)
        await self._config_sub("custom_prompt")(
            inter, prompt_type="summarize", text="x" * 2500
        )
        sent = self._first_text(inter)
        self.assertIn("이하여야", sent)

    # --- allowed_role ---
    async def test_config_allowed_role_set(self) -> None:
        inter = _make_interaction(administrator=True)
        role = MagicMock()
        role.id = 888
        role.name = "Member"
        await self._config_sub("allowed_role")(inter, role=role)
        sent = self._first_text(inter)
        self.assertIn("Member", sent)
        cfg = await self.store.get_guild_config(222)
        self.assertEqual(cfg.allowed_role_id, 888)

    async def test_config_allowed_role_clear(self) -> None:
        await self.store.set_allowed_role(222, 888)
        inter = _make_interaction(administrator=True)
        await self._config_sub("allowed_role")(inter, role=None)
        sent = self._first_text(inter)
        self.assertIn("역할 제한을 해제", sent)
        cfg = await self.store.get_guild_config(222)
        self.assertIsNone(cfg.allowed_role_id)

    # --- daily_token_budget ---
    async def test_config_daily_token_budget_set(self) -> None:
        inter = _make_interaction(administrator=True)
        await self._config_sub("daily_token_budget")(inter, budget=100000)
        sent = self._first_text(inter)
        self.assertIn("100,000", sent)
        cfg = await self.store.get_guild_config(222)
        self.assertEqual(cfg.daily_token_budget, 100000)

    async def test_config_daily_token_budget_unlimited(self) -> None:
        await self.store.set_daily_token_budget(222, 5000)
        inter = _make_interaction(administrator=True)
        await self._config_sub("daily_token_budget")(inter, budget=0)
        sent = self._first_text(inter)
        self.assertIn("무제한", sent)
        cfg = await self.store.get_guild_config(222)
        self.assertIsNone(cfg.daily_token_budget)

    async def test_config_daily_token_budget_negative(self) -> None:
        inter = _make_interaction(administrator=True)
        await self._config_sub("daily_token_budget")(inter, budget=-5)
        sent = self._first_text(inter)
        self.assertIn("0 이상", sent)


# ===========================================================================
# remind payload 헬퍼 (직렬화/역직렬화 분기)
# ===========================================================================
class RemindPayloadTest(unittest.TestCase):
    def test_encode_decode_roundtrip(self) -> None:
        payload = _encode_remind_payload(
            "내용", kind=_REMIND_KIND_SUMMARY, repeat="weekly"
        )
        decoded = _decode_remind_payload(payload)
        self.assertEqual(decoded["kind"], _REMIND_KIND_SUMMARY)
        self.assertEqual(decoded["text"], "내용")
        self.assertEqual(decoded["repeat"], "weekly")

    def test_decode_legacy_plaintext(self) -> None:
        # 비-JSON 레거시 payload 는 평문 텍스트로 폴백한다.
        decoded = _decode_remind_payload("그냥 평문")
        self.assertEqual(decoded["kind"], _REMIND_KIND_TEXT)
        self.assertEqual(decoded["text"], "그냥 평문")
        self.assertIsNone(decoded["repeat"])

    def test_decode_invalid_json_falls_back(self) -> None:
        decoded = _decode_remind_payload("{broken json")
        self.assertEqual(decoded["text"], "{broken json")

    def test_encode_without_repeat_omits_key(self) -> None:
        payload = _encode_remind_payload("t", kind=_REMIND_KIND_TEXT)
        decoded = _decode_remind_payload(payload)
        self.assertIsNone(decoded["repeat"])


if __name__ == "__main__":  # pragma: no cover
    unittest.main()
