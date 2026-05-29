from __future__ import annotations

import sqlite3
import tempfile
import unittest
from pathlib import Path

from discord_assistant.models import UsageLog
from discord_assistant.storage import ConfigStore, sqlite_path_from_database_url


class StorageTest(unittest.IsolatedAsyncioTestCase):
    async def test_config_defaults_and_updates(self) -> None:
        with tempfile.TemporaryDirectory() as tmpdir:
            db_path = Path(tmpdir) / "assistant.db"
            store = ConfigStore(
                f"sqlite:///{db_path}",
                default_model="llama3.1:8b",
                default_summary_limit=50,
                default_language="ko",
            )
            await store.initialize()
            # #50: 비데몬 aiosqlite 워커 스레드 누수 → 인터프리터 종료 hang 방지.
            self.addAsyncCleanup(store.close)

            default_config = await store.get_guild_config(123)
            self.assertEqual(default_config.model, "llama3.1:8b")
            self.assertEqual(default_config.summary_limit, 50)

            await store.set_model(123, "qwen2.5:7b")
            await store.set_summary_limit(123, 25)
            await store.set_language(123, "en")
            updated = await store.get_guild_config(123)

            self.assertEqual(updated.model, "qwen2.5:7b")
            self.assertEqual(updated.summary_limit, 25)
            self.assertEqual(updated.language, "en")

    async def test_usage_log_insert(self) -> None:
        with tempfile.TemporaryDirectory() as tmpdir:
            db_path = Path(tmpdir) / "assistant.db"
            store = ConfigStore(
                f"sqlite:///{db_path}",
                default_model="llama3.1:8b",
                default_summary_limit=50,
                default_language="ko",
            )
            await store.initialize()
            # #50: 비데몬 aiosqlite 워커 스레드 누수 → 인터프리터 종료 hang 방지.
            self.addAsyncCleanup(store.close)

            await store.log_usage(
                UsageLog(
                    guild_id=1,
                    channel_id=2,
                    user_id=3,
                    command="summarize",
                    status="ok",
                    latency_ms=123,
                )
            )

            with sqlite3.connect(db_path) as conn:
                row = conn.execute("SELECT command, status, latency_ms FROM usage_log").fetchone()

            self.assertEqual(row, ("summarize", "ok", 123))


class DatabaseUrlTest(unittest.TestCase):
    def test_sqlite_url_to_path(self) -> None:
        self.assertEqual(sqlite_path_from_database_url("sqlite:///./data/a.db"), "./data/a.db")
        self.assertEqual(sqlite_path_from_database_url(":memory:"), ":memory:")

    def test_invalid_sqlite_url(self) -> None:
        with self.assertRaises(ValueError):
            sqlite_path_from_database_url("sqlite://relative.db")


if __name__ == "__main__":
    unittest.main()
