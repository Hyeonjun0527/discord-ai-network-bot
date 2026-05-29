"""Storage-backed tests for Phase 3 commands and integrations.

Covers the data layer behind /stats, feedback tracking, auto-summary polling,
chat-history pagination/pruning (#91, #93, #50, #32), and a real file-based
SQLite integration test that survives reconnects (#95).
"""
from __future__ import annotations

import sqlite3
import tempfile
import unittest
from pathlib import Path

from discord_assistant.crypto import decrypt_api_key, encrypt_api_key
from discord_assistant.models import UsageLog
from discord_assistant.storage import (
    BACKEND_POSTGRES,
    BACKEND_SQLITE,
    LATEST_SCHEMA_VERSION,
    ConfigStore,
    _get_schema_version,
    _mask_secrets,
    _migrate,
    _sanitize_error,
    backend_from_database_url,
    sqlite_path_from_database_url,
)

# 일관된 UTC ISO8601 시각 헬퍼 — due_at 비교는 문자열 사전식이므로 포맷을 맞춘다.
_PAST = "2020-01-01T00:00:00+00:00"
_SOON = "2020-06-01T00:00:00+00:00"
_FUTURE = "2999-12-31T23:59:59+00:00"


def _make_store(database_url: str) -> ConfigStore:
    return ConfigStore(
        database_url,
        default_model="llama3.1:8b",
        default_summary_limit=50,
        default_language="ko",
    )


class _FileStoreCase(unittest.IsolatedAsyncioTestCase):
    """Base case backed by a real temp-file SQLite DB.

    A ``:memory:`` URL opens a *separate* database per connection, so schema
    created in ``initialize()`` is invisible to later operations. Storage tests
    therefore use an on-disk file (#95).
    """

    async def asyncSetUp(self) -> None:
        self._tmp = tempfile.TemporaryDirectory()
        db_path = Path(self._tmp.name) / "assistant.db"
        self.store = _make_store(f"sqlite:///{db_path}")
        await self.store.initialize()

    async def asyncTearDown(self) -> None:
        # #50: 영속 aiosqlite 연결을 정리한다. close() 를 누락하면 워커 스레드가
        # 닫힌 이벤트 루프와 상호작용하며 "Event loop is closed" 경고를 남긴다.
        await self.store.close()
        self._tmp.cleanup()


class StatsTest(_FileStoreCase):

    async def _log(self, command: str, status: str, latency: int) -> None:
        await self.store.log_usage(
            UsageLog(guild_id=1, channel_id=2, user_id=3, command=command, status=status, latency_ms=latency)
        )

    async def test_stats_empty_guild(self) -> None:
        stats = await self.store.get_stats(999)
        self.assertEqual(stats["total"], 0)
        self.assertEqual(stats["avg_latency_ms"], 0)
        self.assertEqual(stats["error_rate"], 0.0)
        self.assertEqual(stats["by_command"], [])

    async def test_stats_aggregates_counts_and_latency(self) -> None:
        await self._log("summarize", "ok", 100)
        await self._log("summarize", "ok", 300)
        await self._log("ask", "error", 50)
        stats = await self.store.get_stats(1)
        self.assertEqual(stats["total"], 3)
        # avg only over status='ok' rows: (100 + 300) / 2 = 200
        self.assertEqual(stats["avg_latency_ms"], 200)
        # 1 error out of 3 → 33.3%
        self.assertEqual(stats["error_rate"], 33.3)
        by_cmd = {r["command"]: r["count"] for r in stats["by_command"]}
        self.assertEqual(by_cmd, {"summarize": 2, "ask": 1})

    async def test_stats_isolated_per_guild(self) -> None:
        await self._log("summarize", "ok", 100)
        other_guild_stats = await self.store.get_stats(2)
        self.assertEqual(other_guild_stats["total"], 0)

    async def test_stats_includes_activity_range(self) -> None:
        await self._log("summarize", "ok", 100)
        stats = await self.store.get_stats(1)
        self.assertIn("first_at", stats)
        self.assertIn("last_at", stats)
        self.assertIsNotNone(stats["first_at"])
        self.assertIsNotNone(stats["last_at"])

    async def test_stats_empty_range_is_none(self) -> None:
        stats = await self.store.get_stats(999)
        self.assertIsNone(stats["first_at"])
        self.assertIsNone(stats["last_at"])


class FeedbackTest(_FileStoreCase):
    async def test_feedback_insert_does_not_raise(self) -> None:
        await self.store.save_feedback(guild_id=1, message_id=10, user_id=20, rating=1, command="ask")

    async def test_duplicate_feedback_rejected_by_unique_constraint(self) -> None:
        # (message_id, user_id) is UNIQUE (#46) — a second insert must raise.
        await self.store.save_feedback(guild_id=1, message_id=10, user_id=20, rating=1, command="ask")
        with self.assertRaises(sqlite3.IntegrityError):
            await self.store.save_feedback(guild_id=1, message_id=10, user_id=20, rating=-1, command="ask")

    async def test_different_user_same_message_allowed(self) -> None:
        await self.store.save_feedback(guild_id=1, message_id=10, user_id=20, rating=1, command="ask")
        await self.store.save_feedback(guild_id=1, message_id=10, user_id=21, rating=-1, command="ask")


class AutoSummaryQueryTest(_FileStoreCase):
    async def test_returns_only_configured_guilds(self) -> None:
        # Guild 1 has auto-summary, guild 2 does not.
        await self.store.set_auto_summary_interval(1, 10)
        await self.store.set_model(2, "llama3.1:8b")  # creates a row without interval
        configured = await self.store.get_guilds_with_auto_summary()
        self.assertEqual(configured, [(1, 10)])

    async def test_disabling_removes_from_query(self) -> None:
        await self.store.set_auto_summary_interval(1, 10)
        await self.store.set_auto_summary_interval(1, None)
        self.assertEqual(await self.store.get_guilds_with_auto_summary(), [])

    async def test_below_minimum_interval_rejected(self) -> None:
        # Floor is enforced consistently (model + storage) to avoid the
        # read-crash regression. Values 1-4 must be rejected with a clear error.
        for bad in (1, 2, 3, 4):
            with self.assertRaises(ValueError):
                await self.store.set_auto_summary_interval(1, bad)

    async def test_minimum_interval_accepted(self) -> None:
        cfg = await self.store.set_auto_summary_interval(1, 5)
        self.assertEqual(cfg.auto_summary_interval, 5)

    async def test_legacy_below_min_clamped_on_read_not_crash(self) -> None:
        # Simulate a legacy row written before the floor existed; reading it
        # must never raise — the value is clamped up to the minimum instead.
        await self.store.set_model(1, "llama3.1:8b")  # create the row
        conn = self.store._connect()
        try:
            conn.execute("UPDATE guild_config SET auto_summary_interval = 2 WHERE guild_id = 1")
            conn.commit()
        finally:
            conn.close()
        cfg = await self.store.get_guild_config(1)  # must not raise
        self.assertEqual(cfg.auto_summary_interval, 5)
        # The polling query also yields a clamped, valid interval.
        self.assertEqual(await self.store.get_guilds_with_auto_summary(), [(1, 5)])


class DailyTokenBudgetTest(_FileStoreCase):
    """#19 서버별 일일 토큰 상한 setter + 당일 사용량 집계."""

    async def test_default_budget_is_none(self) -> None:
        # 새 길드는 상한이 None(무제한)이어야 한다(백워드 호환 기본값).
        cfg = await self.store.get_guild_config(1)
        self.assertIsNone(cfg.daily_token_budget)

    async def test_set_and_read_budget(self) -> None:
        cfg = await self.store.set_daily_token_budget(1, 10_000)
        self.assertEqual(cfg.daily_token_budget, 10_000)
        # 다시 읽어도 동일하게 유지된다.
        reread = await self.store.get_guild_config(1)
        self.assertEqual(reread.daily_token_budget, 10_000)

    async def test_set_budget_none_clears(self) -> None:
        await self.store.set_daily_token_budget(1, 5_000)
        cfg = await self.store.set_daily_token_budget(1, None)
        self.assertIsNone(cfg.daily_token_budget)

    async def test_negative_budget_rejected(self) -> None:
        with self.assertRaises(ValueError):
            await self.store.set_daily_token_budget(1, -1)

    async def test_zero_budget_allowed(self) -> None:
        # 0 은 유효(사실상 즉시 차단). setter 는 거부하지 않는다.
        cfg = await self.store.set_daily_token_budget(1, 0)
        self.assertEqual(cfg.daily_token_budget, 0)

    async def test_budget_persists_alongside_other_fields(self) -> None:
        # 다른 setter 와 섞어 써도 상한이 보존된다(upsert 컬럼 누락 회귀 방지).
        await self.store.set_daily_token_budget(1, 1_234)
        await self.store.set_model(1, "qwen2.5:7b")
        cfg = await self.store.get_guild_config(1)
        self.assertEqual(cfg.daily_token_budget, 1_234)
        self.assertEqual(cfg.model, "qwen2.5:7b")

    async def test_today_usage_empty_is_zero(self) -> None:
        self.assertEqual(await self.store.get_today_token_usage(1), 0)

    async def test_today_usage_sums_prompt_and_completion(self) -> None:
        await self.store.log_usage(
            UsageLog(
                guild_id=1, channel_id=2, user_id=3, command="ask", status="ok",
                latency_ms=10, prompt_tokens=100, completion_tokens=50,
            )
        )
        await self.store.log_usage(
            UsageLog(
                guild_id=1, channel_id=2, user_id=3, command="chat", status="ok",
                latency_ms=10, prompt_tokens=30, completion_tokens=20,
            )
        )
        # 100 + 50 + 30 + 20 = 200
        self.assertEqual(await self.store.get_today_token_usage(1), 200)

    async def test_today_usage_isolated_per_guild(self) -> None:
        await self.store.log_usage(
            UsageLog(
                guild_id=1, channel_id=2, user_id=3, command="ask", status="ok",
                latency_ms=10, prompt_tokens=100, completion_tokens=50,
            )
        )
        self.assertEqual(await self.store.get_today_token_usage(2), 0)

    async def test_today_usage_excludes_old_rows(self) -> None:
        # 어제 날짜의 usage 행은 오늘 집계에서 제외돼야 한다.
        await self.store.log_usage(
            UsageLog(
                guild_id=1, channel_id=2, user_id=3, command="ask", status="ok",
                latency_ms=10, prompt_tokens=100, completion_tokens=50,
            )
        )
        conn = self.store._connect()
        try:
            # created_at 을 어제로 조정한다(라우팅이 date('now') 기준이므로 제외돼야 함).
            conn.execute(
                "UPDATE usage_log SET created_at = datetime('now', '-2 days') WHERE guild_id = 1"
            )
            conn.commit()
        finally:
            conn.close()
        self.assertEqual(await self.store.get_today_token_usage(1), 0)


class ChatHistoryTest(_FileStoreCase):
    async def test_pagination_offset(self) -> None:
        for i in range(5):
            await self.store.save_chat_message(7, "user", f"msg-{i}", guild_id=1, channel_id=2)
        # Most recent first internally, returned oldest-first per page.
        page1 = await self.store.get_chat_history(7, guild_id=1, channel_id=2, limit=2, offset=0)
        page2 = await self.store.get_chat_history(7, guild_id=1, channel_id=2, limit=2, offset=2)
        self.assertEqual([m["content"] for m in page1], ["msg-3", "msg-4"])
        self.assertEqual([m["content"] for m in page2], ["msg-1", "msg-2"])

    async def test_invalid_pagination_args_raise(self) -> None:
        with self.assertRaises(ValueError):
            await self.store.get_chat_history(7, limit=0)
        with self.assertRaises(ValueError):
            await self.store.get_chat_history(7, offset=-1)

    async def test_history_pruned_to_cap(self) -> None:
        cap = ConfigStore._MAX_CHAT_HISTORY_PER_USER
        for i in range(cap + 25):
            await self.store.save_chat_message(7, "user", f"m{i}")
        rows = await self.store.get_chat_history(7, limit=cap + 100)
        self.assertEqual(len(rows), cap)
        # Oldest rows pruned; the newest message must still be present.
        self.assertEqual(rows[-1]["content"], f"m{cap + 24}")


class PurgeRetentionTest(_FileStoreCase):
    """created_at 기준 보존 정리 (#27)."""

    async def _backdate_usage(self, days_ago: int) -> None:
        """usage_log 한 행을 추가하고 created_at 을 days_ago 일 전으로 조정."""
        await self.store.log_usage(
            UsageLog(guild_id=1, channel_id=2, user_id=3, command="ask", status="ok", latency_ms=10)
        )
        conn = self.store._connect()
        try:
            conn.execute(
                "UPDATE usage_log SET created_at = datetime('now', ?) "
                "WHERE id = (SELECT MAX(id) FROM usage_log)",
                (f"-{days_ago} days",),
            )
            conn.commit()
        finally:
            conn.close()

    async def _backdate_chat(self, days_ago: int) -> None:
        await self.store.save_chat_message(7, "user", "hello", guild_id=1, channel_id=2)
        conn = self.store._connect()
        try:
            conn.execute(
                "UPDATE chat_history SET created_at = datetime('now', ?) "
                "WHERE id = (SELECT MAX(id) FROM chat_history)",
                (f"-{days_ago} days",),
            )
            conn.commit()
        finally:
            conn.close()

    def _count(self, table: str) -> int:
        conn = self.store._connect()
        try:
            return int(conn.execute(f"SELECT COUNT(*) FROM {table}").fetchone()[0])
        finally:
            conn.close()

    async def test_purge_deletes_old_usage_rows(self) -> None:
        await self._backdate_usage(40)  # 오래됨 → 삭제 대상
        await self._backdate_usage(1)   # 최근 → 보존
        result = await self.store.purge_old(usage_days=30, chat_days=30)
        self.assertEqual(result["usage_log"], 1)
        self.assertEqual(self._count("usage_log"), 1)

    async def test_purge_deletes_old_chat_rows(self) -> None:
        await self._backdate_chat(40)
        await self._backdate_chat(1)
        result = await self.store.purge_old(usage_days=30, chat_days=30)
        self.assertEqual(result["chat_history"], 1)
        self.assertEqual(self._count("chat_history"), 1)

    async def test_purge_keeps_recent_rows(self) -> None:
        await self._backdate_usage(5)
        await self._backdate_chat(5)
        result = await self.store.purge_old(usage_days=30, chat_days=30)
        self.assertEqual(result, {"usage_log": 0, "chat_history": 0})
        self.assertEqual(self._count("usage_log"), 1)
        self.assertEqual(self._count("chat_history"), 1)

    async def test_purge_zero_days_skips_table(self) -> None:
        # 0 일은 보존 비활성화 — 해당 테이블은 건드리지 않는다.
        await self._backdate_usage(100)
        await self._backdate_chat(100)
        result = await self.store.purge_old(usage_days=0, chat_days=0)
        self.assertEqual(result, {"usage_log": 0, "chat_history": 0})
        self.assertEqual(self._count("usage_log"), 1)
        self.assertEqual(self._count("chat_history"), 1)

    async def test_purge_returns_expected_keys(self) -> None:
        result = await self.store.purge_old(usage_days=7, chat_days=7)
        self.assertIn("usage_log", result)
        self.assertIn("chat_history", result)

    async def test_purge_negative_days_rejected(self) -> None:
        with self.assertRaises(ValueError):
            await self.store.purge_old(usage_days=-1, chat_days=7)
        with self.assertRaises(ValueError):
            await self.store.purge_old(usage_days=7, chat_days=-1)

    async def _backdate_usage_iso_at_cutoff(self, retention_days: int) -> None:
        """usage_log 한 행의 created_at 을 _utc_now() 와 동일한 ISO 포맷
        ('T' 구분자 + '+00:00' 오프셋)으로, 컷오프 '날짜' 와 같은 날짜이되
        컷오프 '시각' 보다 1시간 더 과거로 조정한다 (#72).

        이 경계(같은 날짜, 더 과거 시각) 행은 보존 기간을 넘겼으므로 삭제돼야
        한다. 과거 버그(양변 정규화 없는 문자열 비교)에서는 'T'(84) > ' '(32)
        때문에 컷오프 날짜의 행이 시각과 무관하게 컷오프보다 '크다'고 판정돼
        삭제되지 않고 남았다. 프로덕션 경로(log_usage)의 실제 포맷을 재현한다.
        """
        await self.store.log_usage(
            UsageLog(guild_id=1, channel_id=2, user_id=3, command="ask", status="ok", latency_ms=10)
        )
        conn = self.store._connect()
        try:
            # 컷오프('now','-Nd')와 같은 날짜이되 1시간 더 과거인 ISO 문자열.
            conn.execute(
                "UPDATE usage_log "
                "SET created_at = "
                "  strftime('%Y-%m-%dT%H:%M:%S', 'now', ?, '-1 hours') || '+00:00' "
                "WHERE id = (SELECT MAX(id) FROM usage_log)",
                (f"-{retention_days} days",),
            )
            conn.commit()
        finally:
            conn.close()

    async def test_purge_deletes_iso_row_on_cutoff_date(self) -> None:
        # #72 회귀: created_at 이 ISO('T'+오프셋) 포맷이고 컷오프 날짜와 같은
        # 날짜이되 더 과거 시각인 행은 정상 삭제돼야 한다. 과거 버그에서는
        # 단순 문자열 비교 탓에 삭제되지 않고 남았다(최대 약 하루치 잔존).
        await self._backdate_usage_iso_at_cutoff(retention_days=7)
        result = await self.store.purge_old(usage_days=7, chat_days=7)
        self.assertEqual(result["usage_log"], 1)
        self.assertEqual(self._count("usage_log"), 0)


class VacuumTest(_FileStoreCase):
    """DB 파일 유지보수 (#33)."""

    async def test_vacuum_does_not_raise_on_file_db(self) -> None:
        await self.store.log_usage(
            UsageLog(guild_id=1, channel_id=2, user_id=3, command="ask", status="ok", latency_ms=10)
        )
        await self.store.vacuum()  # 예외 없이 완료되어야 한다.

    async def test_vacuum_preserves_data(self) -> None:
        await self.store.set_model(5, "qwen2.5:7b")
        await self.store.vacuum()
        cfg = await self.store.get_guild_config(5)
        self.assertEqual(cfg.model, "qwen2.5:7b")


class VacuumMemoryTest(unittest.IsolatedAsyncioTestCase):
    """:memory: DB 에서 vacuum 은 안전하게 무시되어야 한다 (#33)."""

    async def test_vacuum_memory_db_is_noop(self) -> None:
        store = _make_store(":memory:")
        await store.initialize()
        await store.vacuum()  # 파일이 없으므로 조용히 건너뛴다.
        await store.close()


class FileBackedIntegrationTest(unittest.IsolatedAsyncioTestCase):
    """Exercises a real on-disk SQLite DB across reconnects (#95)."""

    async def test_config_persists_across_store_instances(self) -> None:
        with tempfile.TemporaryDirectory() as tmpdir:
            db_path = Path(tmpdir) / "assistant.db"
            url = f"sqlite:///{db_path}"

            store1 = _make_store(url)
            await store1.initialize()
            await store1.set_model(42, "qwen2.5:7b")
            await store1.set_language(42, "en")
            await store1.set_persona(42, "테스트 페르소나")

            # A brand-new store instance (new connections) must read the same data.
            store2 = _make_store(url)
            cfg = await store2.get_guild_config(42)
            self.assertEqual(cfg.model, "qwen2.5:7b")
            self.assertEqual(cfg.language, "en")
            self.assertEqual(cfg.persona, "테스트 페르소나")

            self.assertTrue(db_path.exists())
            await store1.close()
            await store2.close()

    async def test_pragmas_enabled_on_file_db(self) -> None:
        with tempfile.TemporaryDirectory() as tmpdir:
            db_path = Path(tmpdir) / "assistant.db"
            store = _make_store(f"sqlite:///{db_path}")
            await store.initialize()
            # Inspect the pragmas via a raw connection through the store's own helper.
            conn = store._connect()
            try:
                journal = conn.execute("PRAGMA journal_mode").fetchone()[0]
                fk = conn.execute("PRAGMA foreign_keys").fetchone()[0]
            finally:
                conn.close()
            await store.close()
            self.assertEqual(str(journal).lower(), "wal")
            self.assertEqual(fk, 1)


class RemindersTest(_FileStoreCase):
    """예약 리마인더 데이터 계층 (#26)."""

    async def test_add_returns_id_and_lists_by_user(self) -> None:
        rid = await self.store.add_reminder(
            user_id=7, guild_id=1, channel_id=2, due_at=_FUTURE, payload="회의 알림"
        )
        self.assertIsInstance(rid, int)
        self.assertGreater(rid, 0)
        reminders = await self.store.list_by_user(7)
        self.assertEqual(len(reminders), 1)
        r = reminders[0]
        self.assertEqual(r.id, rid)
        self.assertEqual(r.payload, "회의 알림")
        self.assertEqual(r.due_at, _FUTURE)
        self.assertFalse(r.sent)
        self.assertIsNotNone(r.created_at)

    async def test_add_rejects_empty_due_at_or_payload(self) -> None:
        with self.assertRaises(ValueError):
            await self.store.add_reminder(7, 1, 2, "   ", "내용")
        with self.assertRaises(ValueError):
            await self.store.add_reminder(7, 1, 2, _FUTURE, "   ")

    async def test_list_due_returns_only_past_unsent_sorted(self) -> None:
        await self.store.add_reminder(7, 1, 2, _PAST, "오래된 만기")
        await self.store.add_reminder(7, 1, 2, _SOON, "다음 만기")
        await self.store.add_reminder(7, 1, 2, _FUTURE, "미래(미만기)")
        # now 를 _SOON 으로 두면 _PAST, _SOON 만 만기.
        due = await self.store.list_due(now=_SOON)
        self.assertEqual([r.payload for r in due], ["오래된 만기", "다음 만기"])

    async def test_list_due_defaults_to_now(self) -> None:
        # 명시적 now 없이 호출하면 현재 시각 기준 — 과거 만기는 잡히고 미래는 제외.
        await self.store.add_reminder(7, 1, 2, _PAST, "과거")
        await self.store.add_reminder(7, 1, 2, _FUTURE, "미래")
        due = await self.store.list_due()
        self.assertEqual([r.payload for r in due], ["과거"])

    async def test_mark_sent_excludes_from_due_and_default_list(self) -> None:
        rid = await self.store.add_reminder(7, 1, 2, _PAST, "처리할 것")
        self.assertTrue(await self.store.mark_sent(rid))
        self.assertEqual(await self.store.list_due(now=_SOON), [])
        # 기본 list_by_user 는 미발송만.
        self.assertEqual(await self.store.list_by_user(7), [])
        # include_sent=True 면 다시 보인다.
        all_r = await self.store.list_by_user(7, include_sent=True)
        self.assertEqual(len(all_r), 1)
        self.assertTrue(all_r[0].sent)

    async def test_mark_sent_unknown_id_returns_false(self) -> None:
        self.assertFalse(await self.store.mark_sent(99999))

    async def test_delete_reminder(self) -> None:
        rid = await self.store.add_reminder(7, 1, 2, _FUTURE, "삭제 대상")
        self.assertTrue(await self.store.delete_reminder(rid))
        self.assertEqual(await self.store.list_by_user(7, include_sent=True), [])
        # 이미 삭제된 id 는 False.
        self.assertFalse(await self.store.delete_reminder(rid))

    async def test_list_by_user_isolated(self) -> None:
        await self.store.add_reminder(7, 1, 2, _FUTURE, "유저7")
        await self.store.add_reminder(8, 1, 2, _FUTURE, "유저8")
        self.assertEqual(len(await self.store.list_by_user(7)), 1)
        self.assertEqual(len(await self.store.list_by_user(8)), 1)


class AuditLogTest(_FileStoreCase):
    """감사 로그 데이터 계층 (#39)."""

    async def test_record_and_list_newest_first(self) -> None:
        id1 = await self.store.record_audit(
            guild_id=1, user_id=10, action="set_model", target="model", before="a", after="b"
        )
        id2 = await self.store.record_audit(
            guild_id=1, user_id=11, action="set_language", target="language", after="en"
        )
        self.assertGreater(id2, id1)
        entries = await self.store.list_audit(1)
        self.assertEqual(len(entries), 2)
        # 최신 우선.
        self.assertEqual(entries[0].action, "set_language")
        self.assertEqual(entries[0].after, "en")
        self.assertIsNone(entries[0].before)
        self.assertEqual(entries[1].action, "set_model")
        self.assertEqual(entries[1].before, "a")
        self.assertIsNotNone(entries[0].created_at)

    async def test_list_respects_limit(self) -> None:
        for i in range(5):
            await self.store.record_audit(guild_id=1, user_id=10, action=f"act{i}")
        entries = await self.store.list_audit(1, limit=3)
        self.assertEqual(len(entries), 3)

    async def test_list_isolated_per_guild(self) -> None:
        await self.store.record_audit(guild_id=1, user_id=10, action="a")
        await self.store.record_audit(guild_id=2, user_id=10, action="b")
        self.assertEqual(len(await self.store.list_audit(1)), 1)
        self.assertEqual(len(await self.store.list_audit(2)), 1)
        self.assertEqual(await self.store.list_audit(999), [])

    async def test_record_rejects_empty_action(self) -> None:
        with self.assertRaises(ValueError):
            await self.store.record_audit(guild_id=1, user_id=10, action="  ")

    async def test_list_rejects_bad_limit(self) -> None:
        with self.assertRaises(ValueError):
            await self.store.list_audit(1, limit=0)


class DeleteDataTest(_FileStoreCase):
    """사용자/길드 데이터 삭제 (#40)."""

    async def test_delete_user_data_counts(self) -> None:
        await self.store.save_chat_message(7, "user", "hi", guild_id=1, channel_id=2)
        await self.store.save_chat_message(7, "assistant", "yo", guild_id=1, channel_id=2)
        await self.store.save_feedback(guild_id=1, message_id=100, user_id=7, rating=1)
        await self.store.log_usage(
            UsageLog(guild_id=1, channel_id=2, user_id=7, command="ask", status="ok", latency_ms=5)
        )
        await self.store.add_reminder(7, 1, 2, _FUTURE, "리마인더")
        # 다른 사용자 데이터는 남아야 한다.
        await self.store.save_chat_message(8, "user", "other", guild_id=1, channel_id=2)

        result = await self.store.delete_user_data(7)
        self.assertEqual(result["chat_history"], 2)
        self.assertEqual(result["feedback"], 1)
        self.assertEqual(result["usage_log"], 1)
        self.assertEqual(result["reminders"], 1)
        # 유저 7 데이터는 모두 사라짐.
        self.assertEqual(await self.store.get_chat_history(7, limit=100), [])
        self.assertEqual(await self.store.list_by_user(7, include_sent=True), [])
        # 유저 8 은 보존.
        self.assertEqual(len(await self.store.get_chat_history(8, limit=100)), 1)

    async def test_delete_user_data_returns_keys_even_when_empty(self) -> None:
        result = await self.store.delete_user_data(12345)
        self.assertEqual(
            result, {"chat_history": 0, "feedback": 0, "usage_log": 0, "reminders": 0}
        )

    async def test_delete_guild_data_counts(self) -> None:
        await self.store.set_model(1, "llama3.1:8b")  # guild_config 행
        await self.store.log_usage(
            UsageLog(guild_id=1, channel_id=2, user_id=3, command="ask", status="ok", latency_ms=5)
        )
        await self.store.save_feedback(guild_id=1, message_id=100, user_id=3, rating=1)
        await self.store.save_chat_message(3, "user", "hi", guild_id=1, channel_id=2)
        await self.store.add_reminder(3, 1, 2, _FUTURE, "리마인더")
        # 다른 길드 데이터는 보존.
        await self.store.set_model(2, "qwen2.5:7b")

        result = await self.store.delete_guild_data(1)
        self.assertEqual(result["guild_config"], 1)
        self.assertEqual(result["usage_log"], 1)
        self.assertEqual(result["feedback"], 1)
        self.assertEqual(result["chat_history"], 1)
        self.assertEqual(result["reminders"], 1)
        # 길드 1 설정은 기본값으로 되돌아간다(행 없음) → usage 통계도 비어 있다.
        self.assertEqual((await self.store.get_stats(1))["total"], 0)
        # 길드 2 는 보존.
        self.assertIn(2, await self.store.get_all_guild_ids())
        self.assertNotIn(1, await self.store.get_all_guild_ids())

    async def test_delete_guild_data_returns_keys_even_when_empty(self) -> None:
        result = await self.store.delete_guild_data(999)
        self.assertEqual(
            result,
            {
                "guild_config": 0,
                "usage_log": 0,
                "feedback": 0,
                "chat_history": 0,
                "reminders": 0,
            },
        )


class IndexPresenceTest(_FileStoreCase):
    """#32: 주요 쿼리 경로 인덱스가 실제로 생성됐는지 확인."""

    async def test_expected_indexes_exist(self) -> None:
        conn = self.store._connect()
        try:
            names = {
                row[0]
                for row in conn.execute(
                    "SELECT name FROM sqlite_master WHERE type='index'"
                ).fetchall()
            }
        finally:
            conn.close()
        for expected in (
            "idx_usage_log_guild_created",
            "idx_feedback_guild_id",
            "idx_reminders_due",
            "idx_audit_log_guild",
        ):
            self.assertIn(expected, names)


class MigrationIdempotencyTest(unittest.IsolatedAsyncioTestCase):
    """기존(레거시) DB 에 _migrate 가 안전하게 새 테이블/인덱스를 추가하는지 (#26/#39/#32)."""

    async def test_migrate_adds_new_tables_to_legacy_db(self) -> None:
        with tempfile.TemporaryDirectory() as tmpdir:
            db_path = Path(tmpdir) / "legacy.db"
            # 신규 테이블이 없는 최소 레거시 스키마를 손으로 만든다.
            conn = sqlite3.connect(db_path)
            conn.execute(
                """CREATE TABLE guild_config (
                    guild_id INTEGER PRIMARY KEY,
                    model TEXT NOT NULL,
                    summary_limit INTEGER NOT NULL,
                    language TEXT NOT NULL,
                    updated_at TEXT NOT NULL
                )"""
            )
            conn.commit()
            conn.close()

            store = _make_store(f"sqlite:///{db_path}")
            await store.initialize()  # SCHEMA + _migrate 모두 실행되어야 한다.
            # 새 테이블에 대한 CRUD 가 동작해야 한다.
            rid = await store.add_reminder(1, 1, 2, _FUTURE, "ok")
            self.assertGreater(rid, 0)
            aid = await store.record_audit(guild_id=1, user_id=1, action="test")
            self.assertGreater(aid, 0)
            await store.close()


class SchemaVersionTest(_FileStoreCase):
    """버전 추적형 마이그레이션 프레임워크 (#26)."""

    def _version(self) -> int:
        """현재 DB 의 schema_version 을 raw 연결로 읽는다."""
        conn = self.store._connect()
        try:
            row = conn.execute("SELECT version FROM schema_version LIMIT 1").fetchone()
            return int(row[0]) if row is not None else -1
        finally:
            conn.close()

    def _single_version_row(self) -> int:
        """schema_version 테이블의 행 개수 — 단일 행 규약 검증용."""
        conn = self.store._connect()
        try:
            return int(conn.execute("SELECT COUNT(*) FROM schema_version").fetchone()[0])
        finally:
            conn.close()

    async def test_new_db_reaches_latest_version(self) -> None:
        # initialize() 가 SCHEMA + _migrate 를 모두 돌려 최신 버전에 도달해야 한다.
        self.assertEqual(self._version(), LATEST_SCHEMA_VERSION)
        # 단일 행 규약 — 버전 행이 정확히 하나여야 한다.
        self.assertEqual(self._single_version_row(), 1)
        self.assertGreater(LATEST_SCHEMA_VERSION, 0)

    async def test_idempotent_repeated_initialize(self) -> None:
        # 두 번 호출해도 안전 — 버전·행 개수 불변, 데이터 보존.
        await self.store.set_model(5, "qwen2.5:7b")
        await self.store.initialize()
        await self.store.initialize()
        self.assertEqual(self._version(), LATEST_SCHEMA_VERSION)
        self.assertEqual(self._single_version_row(), 1)
        cfg = await self.store.get_guild_config(5)
        self.assertEqual(cfg.model, "qwen2.5:7b")

    async def test_idempotent_repeated_migrate_no_duplicate_rows(self) -> None:
        # _migrate 를 raw 연결로 여러 번 호출해도 버전 행이 늘지 않아야 한다.
        conn = self.store._connect()
        try:
            _migrate(conn)
            _migrate(conn)
        finally:
            conn.close()
        self.assertEqual(self._single_version_row(), 1)
        self.assertEqual(self._version(), LATEST_SCHEMA_VERSION)


class LegacyMigrationVersionTest(unittest.IsolatedAsyncioTestCase):
    """레거시 DB(구 스키마만 존재, schema_version 없음) → 누락분만 적용 (#26)."""

    async def test_legacy_db_applies_missing_and_records_version(self) -> None:
        with tempfile.TemporaryDirectory() as tmpdir:
            db_path = Path(tmpdir) / "legacy.db"
            # schema_version 도, 신규 컬럼/테이블도 없는 최소 레거시 스키마.
            conn = sqlite3.connect(db_path)
            conn.execute(
                """CREATE TABLE guild_config (
                    guild_id INTEGER PRIMARY KEY,
                    model TEXT NOT NULL,
                    summary_limit INTEGER NOT NULL,
                    language TEXT NOT NULL,
                    updated_at TEXT NOT NULL
                )"""
            )
            # usage_log 는 원래(초기) 스키마 형태로 존재 — guild_id/created_at 포함.
            conn.execute(
                """CREATE TABLE usage_log (
                    id         INTEGER PRIMARY KEY AUTOINCREMENT,
                    guild_id   INTEGER,
                    channel_id INTEGER,
                    user_id    INTEGER,
                    command    TEXT    NOT NULL,
                    status     TEXT    NOT NULL,
                    latency_ms INTEGER,
                    error      TEXT,
                    created_at TEXT    NOT NULL
                )"""
            )
            conn.commit()
            conn.close()

            store = _make_store(f"sqlite:///{db_path}")
            await store.initialize()

            verify = sqlite3.connect(db_path)
            try:
                # 버전이 최신으로 기록되고 단일 행을 유지한다.
                rows = verify.execute("SELECT version FROM schema_version").fetchall()
                self.assertEqual(len(rows), 1)
                self.assertEqual(int(rows[0][0]), LATEST_SCHEMA_VERSION)
                # 누락됐던 컬럼/테이블/인덱스가 실제로 추가됐는지 확인.
                cols = {r[1] for r in verify.execute("PRAGMA table_info(guild_config)")}
                for expected_col in (
                    "provider",
                    "api_key_encrypted",
                    "auto_summary_interval",
                    "persona",
                    "custom_summarize_prompt",
                    "custom_ask_prompt",
                    "allowed_role_id",
                    "daily_token_budget",
                ):
                    self.assertIn(expected_col, cols)
                tables = {
                    r[0]
                    for r in verify.execute(
                        "SELECT name FROM sqlite_master WHERE type='table'"
                    )
                }
                for expected_tbl in ("feedback", "reminders", "audit_log"):
                    self.assertIn(expected_tbl, tables)
                indexes = {
                    r[0]
                    for r in verify.execute(
                        "SELECT name FROM sqlite_master WHERE type='index'"
                    )
                }
                for expected_idx in (
                    "idx_usage_log_guild_created",
                    "idx_feedback_guild_id",
                    "idx_reminders_due",
                    "idx_audit_log_guild",
                ):
                    self.assertIn(expected_idx, indexes)
            finally:
                verify.close()
            await store.close()

    async def test_partially_migrated_db_only_applies_remainder(self) -> None:
        # schema_version=2 로 기록된 DB(=feedback 까지만 적용)에서 3~5 만 적용돼야 한다.
        with tempfile.TemporaryDirectory() as tmpdir:
            db_path = Path(tmpdir) / "partial.db"
            conn = sqlite3.connect(db_path)
            conn.execute(
                """CREATE TABLE guild_config (
                    guild_id INTEGER PRIMARY KEY,
                    model TEXT NOT NULL,
                    summary_limit INTEGER NOT NULL,
                    language TEXT NOT NULL,
                    provider TEXT NOT NULL DEFAULT 'ollama',
                    updated_at TEXT NOT NULL
                )"""
            )
            conn.execute("CREATE TABLE schema_version (version INTEGER NOT NULL)")
            conn.execute("INSERT INTO schema_version (version) VALUES (2)")
            conn.commit()
            conn.close()

            store = _make_store(f"sqlite:///{db_path}")
            await store.initialize()
            # 3~5 가 적용되어 reminders/audit_log/인덱스가 생기고 버전이 최신이 된다.
            self.assertGreater(await store.add_reminder(1, 1, 2, _FUTURE, "ok"), 0)
            self.assertGreater(
                await store.record_audit(guild_id=1, user_id=1, action="test"), 0
            )

            verify = sqlite3.connect(db_path)
            try:
                self.assertEqual(
                    int(verify.execute("SELECT version FROM schema_version").fetchone()[0]),
                    LATEST_SCHEMA_VERSION,
                )
            finally:
                verify.close()
            await store.close()


class SchemaVersionHelperTest(unittest.IsolatedAsyncioTestCase):
    """_get_schema_version 시드 동작 (#26)."""

    async def test_get_schema_version_seeds_zero_on_first_call(self) -> None:
        conn = sqlite3.connect(":memory:")
        try:
            # schema_version 테이블이 없는 상태에서 호출 → 0 으로 시드.
            self.assertEqual(_get_schema_version(conn), 0)
            # 두 번째 호출도 0, 행은 하나만.
            self.assertEqual(_get_schema_version(conn), 0)
            count = conn.execute("SELECT COUNT(*) FROM schema_version").fetchone()[0]
            self.assertEqual(int(count), 1)
        finally:
            conn.close()


class UsageLogTokenColumnsTest(_FileStoreCase):
    """#17: usage_log 토큰 컬럼 저장·기본값."""

    async def test_log_usage_persists_token_counts(self) -> None:
        await self.store.log_usage(
            UsageLog(
                guild_id=1,
                channel_id=2,
                user_id=3,
                command="ask",
                status="ok",
                latency_ms=10,
                prompt_tokens=42,
                completion_tokens=17,
            )
        )
        conn = self.store._connect()
        try:
            row = conn.execute(
                "SELECT prompt_tokens, completion_tokens FROM usage_log "
                "WHERE command = 'ask'"
            ).fetchone()
        finally:
            conn.close()
        self.assertEqual((int(row[0]), int(row[1])), (42, 17))

    async def test_log_usage_defaults_tokens_to_zero(self) -> None:
        # 토큰을 넘기지 않으면(기존 호출부) 0 으로 기록된다(백워드 호환).
        await self.store.log_usage(
            UsageLog(guild_id=1, channel_id=2, user_id=3, command="chat", status="ok")
        )
        conn = self.store._connect()
        try:
            row = conn.execute(
                "SELECT prompt_tokens, completion_tokens FROM usage_log "
                "WHERE command = 'chat'"
            ).fetchone()
        finally:
            conn.close()
        self.assertEqual((int(row[0]), int(row[1])), (0, 0))

    async def test_negative_tokens_clamped_to_zero(self) -> None:
        await self.store.log_usage(
            UsageLog(
                guild_id=1,
                channel_id=2,
                user_id=3,
                command="search",
                status="ok",
                prompt_tokens=-5,
                completion_tokens=-9,
            )
        )
        conn = self.store._connect()
        try:
            row = conn.execute(
                "SELECT prompt_tokens, completion_tokens FROM usage_log "
                "WHERE command = 'search'"
            ).fetchone()
        finally:
            conn.close()
        self.assertEqual((int(row[0]), int(row[1])), (0, 0))


class LegacyUsageLogTokenMigrationTest(unittest.IsolatedAsyncioTestCase):
    """#17: 토큰 컬럼이 없는 레거시 usage_log 에도 컬럼이 멱등하게 추가된다."""

    async def test_legacy_usage_log_gets_token_columns(self) -> None:
        with tempfile.TemporaryDirectory() as tmpdir:
            db_path = Path(tmpdir) / "legacy_tokens.db"
            conn = sqlite3.connect(db_path)
            # 토큰 컬럼이 없는 초기 usage_log 스키마.
            conn.execute(
                """CREATE TABLE usage_log (
                    id         INTEGER PRIMARY KEY AUTOINCREMENT,
                    guild_id   INTEGER,
                    channel_id INTEGER,
                    user_id    INTEGER,
                    command    TEXT    NOT NULL,
                    status     TEXT    NOT NULL,
                    latency_ms INTEGER,
                    error      TEXT,
                    created_at TEXT    NOT NULL
                )"""
            )
            conn.commit()
            conn.close()

            store = _make_store(f"sqlite:///{db_path}")
            await store.initialize()
            try:
                # 마이그레이션 후 토큰 컬럼이 추가되고, 새 insert 가 동작해야 한다.
                await store.log_usage(
                    UsageLog(
                        guild_id=1,
                        channel_id=2,
                        user_id=3,
                        command="ask",
                        status="ok",
                        prompt_tokens=4,
                        completion_tokens=5,
                    )
                )
                verify = sqlite3.connect(db_path)
                try:
                    cols = {r[1] for r in verify.execute("PRAGMA table_info(usage_log)")}
                    self.assertIn("prompt_tokens", cols)
                    self.assertIn("completion_tokens", cols)
                    row = verify.execute(
                        "SELECT prompt_tokens, completion_tokens FROM usage_log"
                    ).fetchone()
                    self.assertEqual((int(row[0]), int(row[1])), (4, 5))
                    # 버전이 최신으로 기록된다.
                    ver = verify.execute("SELECT version FROM schema_version").fetchone()
                    self.assertEqual(int(ver[0]), LATEST_SCHEMA_VERSION)
                finally:
                    verify.close()
            finally:
                await store.close()


class SecretMaskingTest(unittest.TestCase):
    """#41: usage_log.error 의 PII/시크릿 마스킹 단위 테스트."""

    def test_bearer_token_masked(self) -> None:
        masked = _mask_secrets("Authorization: Bearer abc123XYZ_token-value")
        self.assertNotIn("abc123XYZ_token-value", masked)
        self.assertIn("***", masked)

    def test_openai_key_masked(self) -> None:
        masked = _mask_secrets("call failed with key sk-proj-ABCDEFGH12345678abcdefgh")
        self.assertNotIn("sk-proj-ABCDEFGH12345678abcdefgh", masked)
        self.assertIn("***", masked)

    def test_anthropic_key_masked(self) -> None:
        masked = _mask_secrets("auth error: sk-ant-api03-XYZ1234567890abcdef")
        self.assertNotIn("sk-ant-api03-XYZ1234567890abcdef", masked)
        self.assertIn("***", masked)

    def test_google_key_masked(self) -> None:
        masked = _mask_secrets("bad request AIzaSyA1234567890abcdefghIJK")
        self.assertNotIn("AIzaSyA1234567890abcdefghIJK", masked)
        self.assertIn("***", masked)

    def test_email_masked(self) -> None:
        masked = _mask_secrets("user alice.smith@example.com was rejected")
        self.assertNotIn("alice.smith@example.com", masked)
        self.assertIn("***", masked)

    def test_key_value_credential_masks_value_keeps_key(self) -> None:
        masked = _mask_secrets("error: api_key=supersecretvalue123 invalid")
        self.assertNotIn("supersecretvalue123", masked)
        # 키 이름은 디버깅 단서로 보존된다.
        self.assertIn("api_key", masked)
        self.assertIn("***", masked)

    def test_discord_bot_token_masked(self) -> None:
        token = "MTk4NjIyNDgzNDcxOTI1MjQ4.Cl2FMQ.ABCdefGHIjklMNOpqrSTUvwxyz12"
        masked = _mask_secrets(f"login failed token={token}")
        self.assertNotIn(token, masked)
        self.assertIn("***", masked)

    def test_benign_message_unchanged(self) -> None:
        msg = "timeout after 30 seconds while contacting upstream"
        self.assertEqual(_mask_secrets(msg), msg)

    def test_sanitize_none_passthrough(self) -> None:
        self.assertIsNone(_sanitize_error(None))

    def test_sanitize_masks_and_clamps_length(self) -> None:
        # 마스킹 후에도 500자 클램프가 유지된다.
        long_error = "x" * 600 + " token=secretvalue"
        sanitized = _sanitize_error(long_error)
        assert sanitized is not None
        self.assertLessEqual(len(sanitized), 500)
        self.assertTrue(sanitized.endswith("..."))


class LogUsageMaskingTest(_FileStoreCase):
    """#41: log_usage 가 저장 직전에 민감 패턴을 마스킹하는지 검증."""

    async def test_stored_error_is_masked(self) -> None:
        await self.store.log_usage(
            UsageLog(
                guild_id=1,
                channel_id=2,
                user_id=3,
                command="ask",
                status="error",
                error="auth failed: api_key=sk-proj-VERYSECRETKEY1234567890",
            )
        )
        conn = self.store._connect()
        try:
            row = conn.execute(
                "SELECT error FROM usage_log WHERE command = 'ask'"
            ).fetchone()
        finally:
            conn.close()
        stored = str(row[0])
        self.assertNotIn("sk-proj-VERYSECRETKEY1234567890", stored)
        self.assertIn("***", stored)


class DatabaseBackendDetectionTest(unittest.TestCase):
    """#31: DATABASE_URL 스킴으로부터 백엔드 식별 + sqlite 경로 보존."""

    def test_sqlite_urls_detected_as_sqlite(self) -> None:
        self.assertEqual(backend_from_database_url("sqlite:///./data/a.db"), BACKEND_SQLITE)
        self.assertEqual(backend_from_database_url(":memory:"), BACKEND_SQLITE)
        self.assertEqual(backend_from_database_url("/var/lib/app.db"), BACKEND_SQLITE)

    def test_postgres_urls_detected(self) -> None:
        for url in (
            "postgresql://user:pw@localhost:5432/db",
            "postgres://user:pw@localhost/db",
            "postgresql+asyncpg://user:pw@localhost/db",
            "POSTGRESQL://USER@HOST/DB",  # 대소문자 무관
        ):
            self.assertEqual(backend_from_database_url(url), BACKEND_POSTGRES, url)

    def test_sqlite_path_preserved(self) -> None:
        # sqlite 경로 변환 동작은 100% 불변.
        self.assertEqual(sqlite_path_from_database_url("sqlite:///./data/a.db"), "./data/a.db")
        self.assertEqual(sqlite_path_from_database_url(":memory:"), ":memory:")

    def test_postgres_url_not_a_sqlite_path(self) -> None:
        with self.assertRaises(ValueError):
            sqlite_path_from_database_url("postgresql://user:pw@localhost/db")

    def test_construction_with_postgres_url_does_not_raise(self) -> None:
        # 생성자는 어떤 백엔드든 ValueError 로 죽지 않아야 한다(#31).
        store = ConfigStore(
            "postgresql://user:pw@localhost:5432/db",
            default_model="m",
            default_summary_limit=10,
            default_language="ko",
        )
        self.assertEqual(store.backend, BACKEND_POSTGRES)

    def test_construction_with_sqlite_url_sets_sqlite_backend(self) -> None:
        store = ConfigStore(
            "sqlite:///./data/a.db",
            default_model="m",
            default_summary_limit=10,
            default_language="ko",
        )
        self.assertEqual(store.backend, BACKEND_SQLITE)


class RekeyApiKeysTest(unittest.TestCase):
    """#37: scripts/rekey_api_keys.py 의 재암호화 동작(구→신 SECRET_KEY)."""

    @staticmethod
    def _import_rekey():
        import importlib.util

        script = Path(__file__).resolve().parent.parent / "scripts" / "rekey_api_keys.py"
        spec = importlib.util.spec_from_file_location("rekey_api_keys", script)
        assert spec is not None and spec.loader is not None
        module = importlib.util.module_from_spec(spec)
        spec.loader.exec_module(module)
        return module

    def _seed_db(self, db_path: Path, *, secret: str, plaintext: str) -> None:
        conn = sqlite3.connect(db_path)
        conn.execute(
            """CREATE TABLE guild_config (
                guild_id          INTEGER PRIMARY KEY,
                api_key_encrypted TEXT
            )"""
        )
        conn.execute(
            "INSERT INTO guild_config (guild_id, api_key_encrypted) VALUES (?, ?)",
            (1, encrypt_api_key(plaintext, secret)),
        )
        # api_key 가 없는 길드(NULL)는 건드리지 않아야 한다.
        conn.execute("INSERT INTO guild_config (guild_id, api_key_encrypted) VALUES (2, NULL)")
        conn.commit()
        conn.close()

    def test_rekey_reencrypts_with_new_secret(self) -> None:
        rekey_mod = self._import_rekey()
        old_secret, new_secret = "old-secret-value", "new-secret-value"
        plaintext = "sk-plaintext-api-key-12345"
        with tempfile.TemporaryDirectory() as tmpdir:
            db_path = Path(tmpdir) / "rekey.db"
            self._seed_db(db_path, secret=old_secret, plaintext=plaintext)

            rekeyed, skipped = rekey_mod.rekey(
                database_url=f"sqlite:///{db_path}",
                old_secret=old_secret,
                new_secret=new_secret,
                dry_run=False,
            )
            self.assertEqual((rekeyed, skipped), (1, 0))

            # 새 토큰은 신 SECRET_KEY 로만 복호화되어야 한다.
            conn = sqlite3.connect(db_path)
            token = conn.execute(
                "SELECT api_key_encrypted FROM guild_config WHERE guild_id = 1"
            ).fetchone()[0]
            conn.close()
            self.assertEqual(decrypt_api_key(token, new_secret), plaintext)

    def test_dry_run_does_not_modify(self) -> None:
        rekey_mod = self._import_rekey()
        old_secret, new_secret = "old-secret-value", "new-secret-value"
        plaintext = "sk-plaintext-api-key-12345"
        with tempfile.TemporaryDirectory() as tmpdir:
            db_path = Path(tmpdir) / "rekey.db"
            self._seed_db(db_path, secret=old_secret, plaintext=plaintext)

            conn = sqlite3.connect(db_path)
            before = conn.execute(
                "SELECT api_key_encrypted FROM guild_config WHERE guild_id = 1"
            ).fetchone()[0]
            conn.close()

            rekeyed, skipped = rekey_mod.rekey(
                database_url=f"sqlite:///{db_path}",
                old_secret=old_secret,
                new_secret=new_secret,
                dry_run=True,
            )
            self.assertEqual((rekeyed, skipped), (1, 0))

            conn = sqlite3.connect(db_path)
            after = conn.execute(
                "SELECT api_key_encrypted FROM guild_config WHERE guild_id = 1"
            ).fetchone()[0]
            conn.close()
            self.assertEqual(before, after)  # dry-run 은 DB 를 변경하지 않는다.

    def test_undecryptable_rows_skipped(self) -> None:
        rekey_mod = self._import_rekey()
        # DB 에는 신 키로 암호화된 토큰이 있고, 구 키로는 복호화 불가 → 스킵.
        old_secret, new_secret = "old-secret-value", "new-secret-value"
        plaintext = "sk-plaintext-api-key-12345"
        with tempfile.TemporaryDirectory() as tmpdir:
            db_path = Path(tmpdir) / "rekey.db"
            self._seed_db(db_path, secret=new_secret, plaintext=plaintext)

            rekeyed, skipped = rekey_mod.rekey(
                database_url=f"sqlite:///{db_path}",
                old_secret=old_secret,
                new_secret=new_secret,
                dry_run=False,
            )
            self.assertEqual((rekeyed, skipped), (0, 1))


class PostgresInitializeTest(unittest.IsolatedAsyncioTestCase):
    """#31: postgres 백엔드는 의존성 없이도 안전하게 분기하고 명확히 안내한다."""

    async def test_initialize_postgres_raises_friendly_error(self) -> None:
        store = ConfigStore(
            "postgresql://user:pw@localhost:5432/db",
            default_model="m",
            default_summary_limit=10,
            default_language="ko",
        )
        # asyncpg 미설치 → RuntimeError(의존성 안내), 설치 → NotImplementedError.
        # 어느 경우든 ValueError/예기치 못한 충돌로 죽지 않아야 한다.
        with self.assertRaises((RuntimeError, NotImplementedError)):
            await store.initialize()


if __name__ == "__main__":
    unittest.main()
