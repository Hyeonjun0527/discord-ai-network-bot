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

from discord_assistant.models import UsageLog
from discord_assistant.storage import ConfigStore


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
            self.assertEqual(str(journal).lower(), "wal")
            self.assertEqual(fk, 1)


if __name__ == "__main__":
    unittest.main()
