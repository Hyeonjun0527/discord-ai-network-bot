"""SQLite-backed server configuration and usage logging."""
from __future__ import annotations

import asyncio
import sqlite3
from dataclasses import replace
from datetime import datetime, timezone
from pathlib import Path
from typing import TYPE_CHECKING

if TYPE_CHECKING:
    from .llm import OllamaManager

from .models import MIN_AUTO_SUMMARY_INTERVAL_MINUTES, GuildConfig, LLMProvider, UsageLog


def _normalize_interval(raw: int | None) -> int | None:
    """Clamp a stored auto-summary interval to the enforced minimum.

    Legacy rows may hold values below the current minimum (written before the
    floor was enforced). Reads must never raise, so we clamp up instead of
    constructing an invalid GuildConfig (#regression-guard).
    """
    if raw is None:
        return None
    return max(int(raw), MIN_AUTO_SUMMARY_INTERVAL_MINUTES)

SCHEMA = """
CREATE TABLE IF NOT EXISTS guild_config (
    guild_id                INTEGER PRIMARY KEY,
    model                   TEXT    NOT NULL,
    summary_limit           INTEGER NOT NULL,
    language                TEXT    NOT NULL,
    admin_role_id           INTEGER,
    provider                TEXT    NOT NULL DEFAULT 'ollama',
    api_key_encrypted       TEXT,
    auto_summary_interval   INTEGER,
    persona                 TEXT,
    custom_summarize_prompt TEXT,
    custom_ask_prompt       TEXT,
    allowed_role_id         INTEGER,
    updated_at              TEXT    NOT NULL
);

CREATE TABLE IF NOT EXISTS usage_log (
    id           INTEGER PRIMARY KEY AUTOINCREMENT,
    guild_id     INTEGER,
    channel_id   INTEGER,
    user_id      INTEGER,
    command      TEXT    NOT NULL,
    status       TEXT    NOT NULL,
    latency_ms   INTEGER,
    error        TEXT,
    created_at   TEXT    NOT NULL
);

CREATE TABLE IF NOT EXISTS chat_history (
    id         INTEGER PRIMARY KEY AUTOINCREMENT,
    guild_id   INTEGER,
    channel_id INTEGER,
    user_id    INTEGER NOT NULL,
    role       TEXT    NOT NULL,
    content    TEXT    NOT NULL,
    created_at TEXT    NOT NULL
);

CREATE TABLE IF NOT EXISTS feedback (
    id         INTEGER PRIMARY KEY AUTOINCREMENT,
    guild_id   INTEGER,
    message_id INTEGER NOT NULL,
    user_id    INTEGER NOT NULL,
    rating     INTEGER NOT NULL,
    command    TEXT,
    created_at TEXT    NOT NULL,
    UNIQUE(message_id, user_id)
);

CREATE INDEX IF NOT EXISTS idx_usage_log_created_at   ON usage_log(created_at);
CREATE INDEX IF NOT EXISTS idx_usage_log_guild_id      ON usage_log(guild_id);
CREATE INDEX IF NOT EXISTS idx_chat_history_user       ON chat_history(user_id, created_at);
CREATE INDEX IF NOT EXISTS idx_chat_history_composite  ON chat_history(guild_id, channel_id, user_id);
CREATE INDEX IF NOT EXISTS idx_feedback_message_id     ON feedback(message_id);
"""


def sqlite_path_from_database_url(database_url: str) -> str:
    """Convert supported DATABASE_URL values into sqlite3 paths."""
    if database_url == ":memory:":
        return database_url
    if database_url.startswith("sqlite:///"):
        return database_url.removeprefix("sqlite:///")
    if database_url.startswith("sqlite://"):
        raise ValueError("Use sqlite:///path/to/file.db for SQLite DATABASE_URL")
    return database_url


def _utc_now() -> str:
    return datetime.now(timezone.utc).isoformat(timespec="seconds")


def _migrate(conn: sqlite3.Connection) -> None:
    """Idempotently add columns introduced after initial schema."""
    existing = {row[1] for row in conn.execute("PRAGMA table_info(guild_config)")}
    if "provider" not in existing:
        conn.execute("ALTER TABLE guild_config ADD COLUMN provider TEXT NOT NULL DEFAULT 'ollama'")
    if "api_key_encrypted" not in existing:
        conn.execute("ALTER TABLE guild_config ADD COLUMN api_key_encrypted TEXT")
    if "auto_summary_interval" not in existing:
        conn.execute("ALTER TABLE guild_config ADD COLUMN auto_summary_interval INTEGER")
    if "persona" not in existing:
        conn.execute("ALTER TABLE guild_config ADD COLUMN persona TEXT")
    if "custom_summarize_prompt" not in existing:
        conn.execute("ALTER TABLE guild_config ADD COLUMN custom_summarize_prompt TEXT")
    if "custom_ask_prompt" not in existing:
        conn.execute("ALTER TABLE guild_config ADD COLUMN custom_ask_prompt TEXT")
    if "allowed_role_id" not in existing:
        conn.execute("ALTER TABLE guild_config ADD COLUMN allowed_role_id INTEGER")

    # feedback table — ensure it exists with correct schema
    conn.execute(
        """CREATE TABLE IF NOT EXISTS feedback (
            id         INTEGER PRIMARY KEY AUTOINCREMENT,
            guild_id   INTEGER,
            message_id INTEGER NOT NULL,
            user_id    INTEGER NOT NULL,
            rating     INTEGER NOT NULL,
            command    TEXT,
            created_at TEXT    NOT NULL,
            UNIQUE(message_id, user_id)
        )"""
    )
    conn.execute("CREATE INDEX IF NOT EXISTS idx_feedback_message_id ON feedback(message_id)")
    conn.commit()


class ConfigStore:
    """Async façade for SQLite operations used by Discord handlers."""

    def __init__(
        self,
        database_url: str,
        *,
        default_model: str,
        default_summary_limit: int,
        default_language: str,
    ) -> None:
        self.database_url = database_url
        raw_path = sqlite_path_from_database_url(database_url)
        self.path = raw_path if raw_path == ":memory:" else str(Path(raw_path).expanduser())
        self.default_config = GuildConfig(
            guild_id=0,
            model=default_model,
            summary_limit=default_summary_limit,
            language=default_language,
        )

    async def initialize(self) -> None:
        await asyncio.to_thread(self._initialize_sync)

    def _initialize_sync(self) -> None:
        if self.path != ":memory:":
            Path(self.path).expanduser().parent.mkdir(parents=True, exist_ok=True)
        with self._connect() as conn:
            conn.executescript(SCHEMA)
            _migrate(conn)

    def _connect(self) -> sqlite3.Connection:
        conn = sqlite3.connect(self.path)
        conn.row_factory = sqlite3.Row
        conn.execute("PRAGMA journal_mode=WAL")
        conn.execute("PRAGMA foreign_keys=ON")
        # 'database is locked' 방어: 잠긴 DB를 만나면 즉시 실패하지 않고 최대
        # 5초까지 재시도한다. WAL 모드에서 synchronous=NORMAL 은 내구성을 크게
        # 해치지 않으면서 쓰기 부하를 줄여 잠금 경합을 완화한다 (#25).
        conn.execute("PRAGMA busy_timeout=5000")
        conn.execute("PRAGMA synchronous=NORMAL")
        return conn

    # ------------------------------------------------------------------
    # Read
    # ------------------------------------------------------------------

    async def get_guild_config(self, guild_id: int) -> GuildConfig:
        return await asyncio.to_thread(self._get_guild_config_sync, guild_id)

    def _get_guild_config_sync(self, guild_id: int) -> GuildConfig:
        with self._connect() as conn:
            row = conn.execute(
                "SELECT guild_id, model, summary_limit, language, admin_role_id, "
                "provider, api_key_encrypted, auto_summary_interval, persona, "
                "custom_summarize_prompt, custom_ask_prompt, allowed_role_id "
                "FROM guild_config WHERE guild_id = ?",
                (guild_id,),
            ).fetchone()
        if row is None:
            return replace(self.default_config, guild_id=guild_id)
        return GuildConfig(
            guild_id=int(row["guild_id"]),
            model=str(row["model"]),
            summary_limit=int(row["summary_limit"]),
            language=str(row["language"]),
            admin_role_id=(int(row["admin_role_id"]) if row["admin_role_id"] is not None else None),
            provider=LLMProvider(row["provider"]) if row["provider"] else LLMProvider.OLLAMA,
            api_key_encrypted=row["api_key_encrypted"],
            auto_summary_interval=_normalize_interval(row["auto_summary_interval"]),
            persona=row["persona"],
            custom_summarize_prompt=row["custom_summarize_prompt"],
            custom_ask_prompt=row["custom_ask_prompt"],
            allowed_role_id=(int(row["allowed_role_id"]) if row["allowed_role_id"] is not None else None),
        )

    # ------------------------------------------------------------------
    # Write — individual setters
    # ------------------------------------------------------------------

    async def set_model(
        self,
        guild_id: int,
        model: str,
        *,
        ollama_manager: "OllamaManager | None" = None,
    ) -> GuildConfig:
        normalized = model.strip()
        if not normalized:
            raise ValueError("model cannot be empty")
        current = await self.get_guild_config(guild_id)
        # Validate model existence when switching Ollama models
        if ollama_manager is not None and current.provider.value == "ollama":
            installed = await ollama_manager.list_models()
            installed_names = {m.name for m in installed}
            if installed_names and normalized not in installed_names:
                raise ValueError(
                    f"Ollama 모델 `{normalized}`이(가) 설치되어 있지 않습니다. "
                    f"`/settings` → 모델 관리 → 새 모델 설치에서 먼저 설치해 주세요."
                )
        updated = replace(current, model=normalized)
        await self._upsert(updated)
        return updated

    async def set_summary_limit(self, guild_id: int, summary_limit: int) -> GuildConfig:
        if summary_limit < 1 or summary_limit > 200:
            raise ValueError("summary_limit must be between 1 and 200")
        current = await self.get_guild_config(guild_id)
        updated = replace(current, summary_limit=summary_limit)
        await self._upsert(updated)
        return updated

    async def set_language(self, guild_id: int, language: str) -> GuildConfig:
        normalized = language.strip()
        if not normalized:
            raise ValueError("language cannot be empty")
        current = await self.get_guild_config(guild_id)
        updated = replace(current, language=normalized)
        await self._upsert(updated)
        return updated

    async def set_provider_config(
        self,
        guild_id: int,
        *,
        provider: LLMProvider,
        model: str,
        api_key_encrypted: str | None,
    ) -> GuildConfig:
        """Atomically update provider, model, and API key together."""
        current = await self.get_guild_config(guild_id)
        updated = replace(
            current,
            provider=provider,
            model=model.strip(),
            api_key_encrypted=api_key_encrypted,
        )
        await self._upsert(updated)
        return updated

    async def clear_api_key(self, guild_id: int) -> GuildConfig:
        current = await self.get_guild_config(guild_id)
        updated = replace(current, api_key_encrypted=None)
        await self._upsert(updated)
        return updated

    # ------------------------------------------------------------------
    # Upsert
    # ------------------------------------------------------------------

    async def _upsert(self, config: GuildConfig) -> None:
        await asyncio.to_thread(self._upsert_sync, config)

    def _upsert_sync(self, config: GuildConfig) -> None:
        with self._connect() as conn:
            conn.execute(
                """
                INSERT INTO guild_config
                    (guild_id, model, summary_limit, language, admin_role_id,
                     provider, api_key_encrypted, auto_summary_interval, persona,
                     custom_summarize_prompt, custom_ask_prompt, allowed_role_id, updated_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                ON CONFLICT(guild_id) DO UPDATE SET
                    model                   = excluded.model,
                    summary_limit           = excluded.summary_limit,
                    language                = excluded.language,
                    admin_role_id           = excluded.admin_role_id,
                    provider                = excluded.provider,
                    api_key_encrypted       = excluded.api_key_encrypted,
                    auto_summary_interval   = excluded.auto_summary_interval,
                    persona                 = excluded.persona,
                    custom_summarize_prompt = excluded.custom_summarize_prompt,
                    custom_ask_prompt       = excluded.custom_ask_prompt,
                    allowed_role_id         = excluded.allowed_role_id,
                    updated_at              = excluded.updated_at
                """,
                (
                    config.guild_id,
                    config.model,
                    config.summary_limit,
                    config.language,
                    config.admin_role_id,
                    config.provider.value,
                    config.api_key_encrypted,
                    config.auto_summary_interval,
                    config.persona,
                    config.custom_summarize_prompt,
                    config.custom_ask_prompt,
                    config.allowed_role_id,
                    _utc_now(),
                ),
            )
            conn.commit()

    # ------------------------------------------------------------------
    # Chat history
    # ------------------------------------------------------------------

    async def get_chat_history(
        self,
        user_id: int,
        *,
        guild_id: int | None = None,
        channel_id: int | None = None,
        limit: int = 10,
        offset: int = 0,
    ) -> list[dict[str, str]]:
        """Return ``limit`` chat_history rows as {role, content} dicts.

        ``offset`` skips that many of the most recent rows, enabling
        pagination over large histories (#50).
        """
        if limit < 1:
            raise ValueError("limit must be >= 1")
        if offset < 0:
            raise ValueError("offset must be >= 0")
        return await asyncio.to_thread(
            self._get_chat_history_sync, user_id, guild_id, channel_id, limit, offset
        )

    def _get_chat_history_sync(
        self,
        user_id: int,
        guild_id: int | None,
        channel_id: int | None,
        limit: int,
        offset: int,
    ) -> list[dict[str, str]]:
        with self._connect() as conn:
            if guild_id is not None and channel_id is not None:
                rows = conn.execute(
                    """
                    SELECT role, content FROM chat_history
                    WHERE user_id = ? AND guild_id = ? AND channel_id = ?
                    ORDER BY id DESC
                    LIMIT ? OFFSET ?
                    """,
                    (user_id, guild_id, channel_id, limit, offset),
                ).fetchall()
            else:
                rows = conn.execute(
                    """
                    SELECT role, content FROM chat_history
                    WHERE user_id = ?
                    ORDER BY id DESC
                    LIMIT ? OFFSET ?
                    """,
                    (user_id, limit, offset),
                ).fetchall()
        rows.reverse()
        return [{"role": row["role"], "content": row["content"]} for row in rows]

    async def save_chat_message(
        self,
        user_id: int,
        role: str,
        content: str,
        *,
        guild_id: int | None = None,
        channel_id: int | None = None,
    ) -> None:
        await asyncio.to_thread(
            self._save_chat_message_sync, user_id, role, content, guild_id, channel_id
        )

    _MAX_CHAT_HISTORY_PER_USER = 200

    def _save_chat_message_sync(
        self,
        user_id: int,
        role: str,
        content: str,
        guild_id: int | None,
        channel_id: int | None,
    ) -> None:
        with self._connect() as conn:
            conn.execute(
                """
                INSERT INTO chat_history (guild_id, channel_id, user_id, role, content, created_at)
                VALUES (?, ?, ?, ?, ?, ?)
                """,
                (guild_id, channel_id, user_id, role, content, _utc_now()),
            )
            # Prune oldest rows for this user beyond the limit
            conn.execute(
                """
                DELETE FROM chat_history
                WHERE user_id = ?
                  AND id NOT IN (
                    SELECT id FROM chat_history
                    WHERE user_id = ?
                    ORDER BY id DESC
                    LIMIT ?
                  )
                """,
                (user_id, user_id, self._MAX_CHAT_HISTORY_PER_USER),
            )
            conn.commit()

    # ------------------------------------------------------------------
    # Usage log
    # ------------------------------------------------------------------

    async def log_usage(self, log: UsageLog) -> None:
        await asyncio.to_thread(self._log_usage_sync, log)

    def _log_usage_sync(self, log: UsageLog) -> None:
        error = log.error
        if error is not None and len(error) > 500:
            error = error[:497] + "..."
        with self._connect() as conn:
            conn.execute(
                """
                INSERT INTO usage_log
                    (guild_id, channel_id, user_id, command, status, latency_ms, error, created_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?)
                """,
                (
                    log.guild_id,
                    log.channel_id,
                    log.user_id,
                    log.command,
                    log.status,
                    log.latency_ms,
                    error,
                    _utc_now(),
                ),
            )
            conn.commit()

    # ------------------------------------------------------------------
    # Retention / maintenance (#27, #33)
    # ------------------------------------------------------------------

    async def purge_old(self, *, usage_days: int, chat_days: int) -> dict[str, int]:
        """created_at 기준으로 오래된 usage_log/chat_history 행을 삭제한다.

        ``usage_days``/``chat_days`` 일보다 오래된 행을 각각 삭제하고 삭제된
        건수를 ``{"usage_log": N, "chat_history": M}`` 형태로 반환한다.
        0 이하의 일수는 해당 테이블 정리를 건너뛴다(보존 비활성화).

        백그라운드 태스크 등록은 호출 측(bot.py) 책임이며, 여기서는 메서드만
        제공한다 (#27).
        """
        if usage_days < 0 or chat_days < 0:
            raise ValueError("retention days must be >= 0")
        return await asyncio.to_thread(self._purge_old_sync, usage_days, chat_days)

    def _purge_old_sync(self, usage_days: int, chat_days: int) -> dict[str, int]:
        deleted = {"usage_log": 0, "chat_history": 0}
        with self._connect() as conn:
            # SQLite 의 datetime() 으로 컷오프 시각을 계산하면 ISO8601 문자열
            # 비교만으로 N일 경과 행을 안전하게 골라낼 수 있다.
            if usage_days > 0:
                cur = conn.execute(
                    "DELETE FROM usage_log "
                    "WHERE created_at < datetime('now', ?)",
                    (f"-{usage_days} days",),
                )
                deleted["usage_log"] = cur.rowcount if cur.rowcount and cur.rowcount > 0 else 0
            if chat_days > 0:
                cur = conn.execute(
                    "DELETE FROM chat_history "
                    "WHERE created_at < datetime('now', ?)",
                    (f"-{chat_days} days",),
                )
                deleted["chat_history"] = cur.rowcount if cur.rowcount and cur.rowcount > 0 else 0
            conn.commit()
        return deleted

    async def vacuum(self) -> None:
        """DB 파일 비대화를 막기 위해 WAL 체크포인트(TRUNCATE) 후 VACUUM 한다.

        ``:memory:`` DB 는 파일이 없어 VACUUM/체크포인트 의미가 없으므로 안전하게
        건너뛴다 (#33).
        """
        if self.path == ":memory:":
            return
        await asyncio.to_thread(self._vacuum_sync)

    def _vacuum_sync(self) -> None:
        # WAL 파일을 본 DB 에 합치고(TRUNCATE) 잘라낸 뒤 VACUUM 으로 미사용
        # 페이지를 회수한다. VACUUM 은 트랜잭션 안에서 실행할 수 없으므로
        # 별도 커밋 없이 단독 실행한다.
        with self._connect() as conn:
            conn.execute("PRAGMA wal_checkpoint(TRUNCATE)")
            conn.execute("VACUUM")

    # ------------------------------------------------------------------
    # Phase 3 setters
    # ------------------------------------------------------------------

    async def set_admin_role(self, guild_id: int, role_id: int | None) -> GuildConfig:
        current = await self.get_guild_config(guild_id)
        updated = replace(current, admin_role_id=role_id)
        await self._upsert(updated)
        return updated

    async def set_auto_summary_interval(self, guild_id: int, interval: int | None) -> GuildConfig:
        """Set auto-summary interval in minutes. None disables."""
        if interval is not None and interval < MIN_AUTO_SUMMARY_INTERVAL_MINUTES:
            raise ValueError(
                f"자동 요약 간격은 최소 {MIN_AUTO_SUMMARY_INTERVAL_MINUTES}분이어야 합니다. (0으로 비활성화)"
            )
        current = await self.get_guild_config(guild_id)
        updated = replace(current, auto_summary_interval=interval)
        await self._upsert(updated)
        return updated

    async def set_persona(self, guild_id: int, persona: str | None) -> GuildConfig:
        current = await self.get_guild_config(guild_id)
        updated = replace(current, persona=persona)
        await self._upsert(updated)
        return updated

    async def set_custom_prompt(
        self, guild_id: int, prompt_type: str, text: str | None
    ) -> GuildConfig:
        """Set a custom prompt. prompt_type must be 'summarize' or 'ask'."""
        if prompt_type not in ("summarize", "ask"):
            raise ValueError("prompt_type must be 'summarize' or 'ask'")
        current = await self.get_guild_config(guild_id)
        if prompt_type == "summarize":
            updated = replace(current, custom_summarize_prompt=text)
        else:
            updated = replace(current, custom_ask_prompt=text)
        await self._upsert(updated)
        return updated

    async def set_allowed_role(self, guild_id: int, role_id: int | None) -> GuildConfig:
        current = await self.get_guild_config(guild_id)
        updated = replace(current, allowed_role_id=role_id)
        await self._upsert(updated)
        return updated

    # ------------------------------------------------------------------
    # Feedback
    # ------------------------------------------------------------------

    async def save_feedback(
        self,
        *,
        guild_id: int | None,
        message_id: int,
        user_id: int,
        rating: int,
        command: str | None = None,
    ) -> None:
        await asyncio.to_thread(
            self._save_feedback_sync, guild_id, message_id, user_id, rating, command
        )

    def _save_feedback_sync(
        self,
        guild_id: int | None,
        message_id: int,
        user_id: int,
        rating: int,
        command: str | None,
    ) -> None:
        with self._connect() as conn:
            conn.execute(
                """
                INSERT INTO feedback (guild_id, message_id, user_id, rating, command, created_at)
                VALUES (?, ?, ?, ?, ?, ?)
                """,
                (guild_id, message_id, user_id, rating, command, _utc_now()),
            )
            conn.commit()

    # ------------------------------------------------------------------
    # Stats (Phase 3 #43)
    # ------------------------------------------------------------------

    async def get_stats(self, guild_id: int) -> dict:
        return await asyncio.to_thread(self._get_stats_sync, guild_id)

    def _get_stats_sync(self, guild_id: int) -> dict:
        with self._connect() as conn:
            total_row = conn.execute(
                "SELECT COUNT(*) AS cnt FROM usage_log WHERE guild_id = ?", (guild_id,)
            ).fetchone()
            total = total_row["cnt"] if total_row else 0

            by_command = conn.execute(
                "SELECT command, COUNT(*) AS cnt FROM usage_log WHERE guild_id = ? "
                "GROUP BY command ORDER BY cnt DESC",
                (guild_id,),
            ).fetchall()

            avg_row = conn.execute(
                "SELECT AVG(latency_ms) AS avg_ms FROM usage_log WHERE guild_id = ? AND status = 'ok'",
                (guild_id,),
            ).fetchone()
            avg_latency = round(avg_row["avg_ms"]) if avg_row and avg_row["avg_ms"] is not None else 0

            error_row = conn.execute(
                "SELECT COUNT(*) AS cnt FROM usage_log WHERE guild_id = ? AND status = 'error'",
                (guild_id,),
            ).fetchone()
            error_count = error_row["cnt"] if error_row else 0

            # Actual activity date range for this guild (#69)
            range_row = conn.execute(
                "SELECT MIN(created_at) AS first_at, MAX(created_at) AS last_at "
                "FROM usage_log WHERE guild_id = ?",
                (guild_id,),
            ).fetchone()
            first_at = range_row["first_at"] if range_row else None
            last_at = range_row["last_at"] if range_row else None

        error_rate = round(error_count / total * 100, 1) if total > 0 else 0.0
        return {
            "total": total,
            "by_command": [{"command": r["command"], "count": r["cnt"]} for r in by_command],
            "avg_latency_ms": avg_latency,
            "error_rate": error_rate,
            "first_at": first_at,
            "last_at": last_at,
        }

    # ------------------------------------------------------------------
    # Auto-summary tracking: last run timestamps
    # ------------------------------------------------------------------

    async def get_all_guild_ids(self) -> list[int]:
        """Return all guild_ids that have a saved config row."""
        return await asyncio.to_thread(self._get_all_guild_ids_sync)

    def _get_all_guild_ids_sync(self) -> list[int]:
        with self._connect() as conn:
            rows = conn.execute("SELECT guild_id FROM guild_config").fetchall()
        return [int(row["guild_id"]) for row in rows]

    async def get_guilds_with_auto_summary(self) -> list[tuple[int, int]]:
        """Return (guild_id, interval_minutes) for guilds with auto-summary enabled (#25).

        Lets the polling task skip guilds that have not configured auto-summary
        without loading every guild's full config row.
        """
        return await asyncio.to_thread(self._get_guilds_with_auto_summary_sync)

    def _get_guilds_with_auto_summary_sync(self) -> list[tuple[int, int]]:
        with self._connect() as conn:
            rows = conn.execute(
                "SELECT guild_id, auto_summary_interval FROM guild_config "
                "WHERE auto_summary_interval IS NOT NULL AND auto_summary_interval > 0"
            ).fetchall()
        return [
            (int(row["guild_id"]), _normalize_interval(row["auto_summary_interval"]) or MIN_AUTO_SUMMARY_INTERVAL_MINUTES)
            for row in rows
        ]
