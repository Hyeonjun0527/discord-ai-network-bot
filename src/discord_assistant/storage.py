"""SQLite-backed server configuration and usage logging.

#24: 매 작업마다 ``sqlite3.connect()`` + ``asyncio.to_thread`` 로 새 연결을 열던
패턴을 aiosqlite 기반의 **단일 영속 연결**로 전환했다. 연결은 ``initialize()``
에서 1회 열고(WAL/foreign_keys/busy_timeout/synchronous PRAGMA 동일 적용),
``close()`` (#50) 로 정리한다. aiosqlite 는 내부적으로 단일 전용 스레드에서
연산을 직렬화하지만, read-modify-write(setter)·write 경합을 막기 위해 추가로
``asyncio.Lock`` 으로 보호한다(특히 upsert/purge/vacuum).

스키마·마이그레이션(#26 schema_version/MIGRATIONS) 로직은 기존 동기 sqlite3
헬퍼(``_migrate`` 등)를 그대로 재사용한다. 영속 연결의 내부 sqlite3 객체에 대해
동기 함수를 실행함으로써(:memory: 포함) 단일 출처를 유지한다.
"""
from __future__ import annotations

import asyncio
import re
import sqlite3
from collections.abc import Callable
from dataclasses import replace
from datetime import datetime, timezone
from pathlib import Path
from typing import TYPE_CHECKING

import aiosqlite

if TYPE_CHECKING:
    from .llm import OllamaManager

from .models import (
    MIN_AUTO_SUMMARY_INTERVAL_MINUTES,
    AuditEntry,
    GuildConfig,
    LLMProvider,
    Reminder,
    UsageLog,
)
from .prompts import _LANGUAGE_ALIASES, _LANGUAGE_LABELS


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
-- #26: 버전 추적형 마이그레이션 프레임워크의 단일 행 버전 레지스트리.
CREATE TABLE IF NOT EXISTS schema_version (
    version INTEGER NOT NULL
);

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
    -- #19: 서버별 일일 토큰 상한(NULL = 무제한). 신규 빈 DB 는 여기서 컬럼을
    -- 곧바로 만들고, 레거시 DB 는 마이그레이션(버전 7)으로 보강한다.
    daily_token_budget      INTEGER,
    updated_at              TEXT    NOT NULL
);

CREATE TABLE IF NOT EXISTS usage_log (
    id                INTEGER PRIMARY KEY AUTOINCREMENT,
    guild_id          INTEGER,
    channel_id        INTEGER,
    user_id           INTEGER,
    command           TEXT    NOT NULL,
    status            TEXT    NOT NULL,
    latency_ms        INTEGER,
    error             TEXT,
    -- #17: 토큰 사용량 집계. 토큰 정보가 없는 응답/제공자는 0 으로 기록.
    prompt_tokens     INTEGER NOT NULL DEFAULT 0,
    completion_tokens INTEGER NOT NULL DEFAULT 0,
    created_at        TEXT    NOT NULL
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

CREATE TABLE IF NOT EXISTS reminders (
    id         INTEGER PRIMARY KEY AUTOINCREMENT,
    user_id    INTEGER NOT NULL,
    guild_id   INTEGER,
    channel_id INTEGER,
    due_at     TEXT    NOT NULL,
    payload    TEXT    NOT NULL,
    sent       INTEGER NOT NULL DEFAULT 0,
    created_at TEXT    NOT NULL
);

CREATE TABLE IF NOT EXISTS audit_log (
    id         INTEGER PRIMARY KEY AUTOINCREMENT,
    guild_id   INTEGER,
    user_id    INTEGER,
    action     TEXT    NOT NULL,
    target     TEXT,
    before     TEXT,
    after      TEXT,
    created_at TEXT    NOT NULL
);

CREATE INDEX IF NOT EXISTS idx_usage_log_created_at   ON usage_log(created_at);
CREATE INDEX IF NOT EXISTS idx_usage_log_guild_id      ON usage_log(guild_id);
-- #32: /stats 등 길드별 기간 조회 경로를 위한 복합 인덱스.
CREATE INDEX IF NOT EXISTS idx_usage_log_guild_created ON usage_log(guild_id, created_at);
CREATE INDEX IF NOT EXISTS idx_chat_history_user       ON chat_history(user_id, created_at);
CREATE INDEX IF NOT EXISTS idx_chat_history_composite  ON chat_history(guild_id, channel_id, user_id);
CREATE INDEX IF NOT EXISTS idx_feedback_message_id     ON feedback(message_id);
-- #32: 길드 단위 피드백 집계/삭제 경로.
CREATE INDEX IF NOT EXISTS idx_feedback_guild_id       ON feedback(guild_id);
-- #26: 만기 리마인더 폴링은 (sent, due_at) 으로 미발송 행만 정렬·조회한다.
CREATE INDEX IF NOT EXISTS idx_reminders_due           ON reminders(sent, due_at);
CREATE INDEX IF NOT EXISTS idx_reminders_user          ON reminders(user_id);
CREATE INDEX IF NOT EXISTS idx_reminders_guild         ON reminders(guild_id);
-- #39: 길드별 최근 감사 로그 조회.
CREATE INDEX IF NOT EXISTS idx_audit_log_guild         ON audit_log(guild_id, created_at);
"""


# #31: 지원하는 DB 백엔드(드라이버) 식별자. DATABASE_URL 스킴으로부터 결정된다.
#   * "sqlite"   — 기존 aiosqlite 경로(완전 구현, 기본값).
#   * "postgres" — postgresql:// 계열. URL 인식·백엔드 분기 골격만 제공하며,
#                  실제 asyncpg 쿼리 구현은 아직 없다(NotImplementedError 안내).
BACKEND_SQLITE = "sqlite"
BACKEND_POSTGRES = "postgres"

# postgresql 계열 URL 스킴. SQLAlchemy/asyncpg 관용에 맞춰 여러 별칭을 인식한다.
_POSTGRES_SCHEMES = (
    "postgresql://",
    "postgres://",
    "postgresql+asyncpg://",
    "postgresql+psycopg://",
    "postgresql+psycopg2://",
)


def backend_from_database_url(database_url: str) -> str:
    """DATABASE_URL 스킴으로부터 백엔드 식별자를 결정한다 (#31).

    postgresql:// 계열이면 ``BACKEND_POSTGRES``, 그 외(sqlite:/// · :memory: ·
    드라이버 없는 평문 경로)는 ``BACKEND_SQLITE`` 를 반환한다. URL 인식만
    수행하므로 asyncpg 등 외부 의존성이 없어도 안전하게 호출할 수 있다.
    """
    lowered = database_url.strip().lower()
    if any(lowered.startswith(scheme) for scheme in _POSTGRES_SCHEMES):
        return BACKEND_POSTGRES
    return BACKEND_SQLITE


def sqlite_path_from_database_url(database_url: str) -> str:
    """Convert supported DATABASE_URL values into sqlite3 paths.

    #31: postgresql:// 계열 URL 은 sqlite 경로로 변환할 수 없으므로 명확한
    ValueError 를 던진다(상위에서 백엔드 분기로 처리됨). sqlite 경로 동작은 불변.
    """
    if database_url == ":memory:":
        return database_url
    if database_url.startswith("sqlite:///"):
        return database_url.removeprefix("sqlite:///")
    if database_url.startswith("sqlite://"):
        raise ValueError("Use sqlite:///path/to/file.db for SQLite DATABASE_URL")
    if backend_from_database_url(database_url) == BACKEND_POSTGRES:
        raise ValueError(
            "postgresql:// DATABASE_URL is not a SQLite path; "
            "use the Postgres backend instead."
        )
    return database_url


def _utc_now() -> str:
    return datetime.now(timezone.utc).isoformat(timespec="seconds")


def _normalize_due_at(due_at: str) -> str:
    """리마인더 ``due_at`` 을 UTC·초 단위 고정 ISO 포맷으로 정규화한다 (#17).

    ``list_due`` 의 만기 비교는 SQLite 문자열 사전식 비교라, ``+09:00`` 같은
    비-UTC 오프셋이나 마이크로초 정밀도가 섞이면 정렬이 실제 시간 순서와
    어긋나 만기 항목을 누락할 수 있다. 파싱 가능한 입력은 UTC 로 변환하고
    ``timespec='seconds'`` 로 통일해 불변식을 코드로 강제한다.

    타임존 정보가 없는(naive) 입력은 UTC 로 간주한다. 파싱 불가한 입력은
    기존 동작(입력 문자열 그대로 저장)을 보존해 회귀를 만들지 않는다.
    """
    try:
        parsed = datetime.fromisoformat(due_at)
    except ValueError:
        return due_at
    if parsed.tzinfo is None:
        parsed = parsed.replace(tzinfo=timezone.utc)
    return parsed.astimezone(timezone.utc).isoformat(timespec="seconds")


# ----------------------------------------------------------------------
# usage_log.error PII/시크릿 마스킹 (#41)
# ----------------------------------------------------------------------
#
# 에러 문자열에는 종종 디스코드 토큰·API 키·Bearer 토큰·이메일 등 민감 정보가
# 그대로 섞여 들어온다(예: 외부 API 클라이언트의 4xx 응답 본문, 스택트레이스).
# usage_log 에 저장되기 전에 이런 패턴을 정규식으로 ``***`` 마스킹한다. 길이
# 제한(기존 500자 클램프)은 마스킹 후에도 그대로 유지한다.

_ERROR_MAX_LEN = 500

# 정규식은 위→아래 순서대로 적용된다. 더 구체적(접두사 있는)인 패턴을 먼저
# 두어 부분 매칭으로 인한 토막 노출을 방지한다.
_SECRET_PATTERNS: tuple[re.Pattern[str], ...] = (
    # "Authorization: Bearer <token>" / "Bearer <token>"
    re.compile(r"(?i)\bBearer\s+[A-Za-z0-9._\-]+"),
    # OpenAI 스타일 키: sk-... / sk-proj-...
    re.compile(r"\bsk-(?:proj-)?[A-Za-z0-9._\-]{8,}"),
    # Anthropic 스타일 키: sk-ant-...
    re.compile(r"\bsk-ant-[A-Za-z0-9._\-]{8,}"),
    # Google API 키: AIza...
    re.compile(r"\bAIza[A-Za-z0-9._\-]{10,}"),
    # Discord 봇 토큰: <base64url id>.<base64url ts>.<base64url hmac> (마침표 2개).
    # #79: 과거 패턴 ``{20,}\.{5,}\.{20,}`` 은 '점 두 개로 구분된 길이 조건 충족
    # 식별자'를 모두 매칭해, 실제 토큰이 아닌 JWT(eyJ...)·긴 점-구분 모듈 경로
    # 식별자까지 통째로 *** 로 치환했다(과잉 마스킹 = 디버그 컨텍스트 손실).
    # 실제 Discord 토큰 구조에 맞춰 좁힌다:
    #   * 두 번째(타임스탬프) 세그먼트는 5~7자 base64url(JWT payload 처럼 긴
    #     세그먼트를 배제) — 이 길이 제한이 JWT 와 토큰을 가르는 핵심이다.
    #   * 토큰 전체가 base64(이진 인코딩)라 대문자와 숫자를 동시에 포함한다.
    #     소문자+밑줄로만 이뤄진 snake_case 모듈 경로(대문자·숫자 부재)는 제외.
    #   * ``eyJ`` (JWT 헤더 base64) 로 시작하는 문자열은 명시적으로 제외.
    re.compile(
        r"\b(?!eyJ)"
        r"(?=[A-Za-z0-9_\-.]*[A-Z])"
        r"(?=[A-Za-z0-9_\-.]*[0-9])"
        r"[A-Za-z0-9_\-]{24,}\.[A-Za-z0-9_\-]{5,7}\.[A-Za-z0-9_\-]{25,}\b"
    ),
    # "api_key=...", "token: ...", "secret = ..." 등 key=value 형태의 자격증명.
    re.compile(
        r"(?i)\b(?:api[_-]?key|token|secret|password|passwd|pwd|access[_-]?token)\b"
        r"\s*[:=]\s*[^\s,;'\"]+"
    ),
    # 이메일 주소.
    re.compile(r"\b[A-Za-z0-9._%+\-]+@[A-Za-z0-9.\-]+\.[A-Za-z]{2,}\b"),
)


def _mask_secrets(error: str) -> str:
    """에러 문자열에서 토큰/API키/Bearer/이메일 등 민감 패턴을 ``***`` 로 가린다 (#41).

    key=value 형태(``api_key=abc``)는 키 이름과 구분자를 보존하고 값만 가려서
    어떤 종류의 자격증명이 노출됐었는지(디버깅 단서)는 남긴다. 그 외 단독
    토큰/이메일 패턴은 매칭 전체를 ``***`` 로 치환한다.
    """
    # key=value 패턴은 값 부분만 마스킹(키 이름 보존)하기 위해 별도 처리한다.
    kv_pattern = _SECRET_PATTERNS[-2]

    def _kv_repl(match: re.Match[str]) -> str:
        text = match.group(0)
        sep_match = re.search(r"[:=]", text)
        if sep_match is None:  # pragma: no cover - 패턴상 도달 불가
            return "***"
        sep_idx = sep_match.start()
        return f"{text[: sep_idx + 1]}***"

    masked = error
    for pattern in _SECRET_PATTERNS:
        if pattern is kv_pattern:
            masked = pattern.sub(_kv_repl, masked)
        else:
            masked = pattern.sub("***", masked)
    return masked


def _sanitize_error(error: str | None) -> str | None:
    """저장 직전의 error 문자열을 마스킹하고 길이를 클램프한다 (#41).

    None 은 그대로 통과한다. 마스킹 후 ``_ERROR_MAX_LEN`` 을 넘으면 잘라낸다
    (기존 500자 클램프 동작 보존, 단 마스킹을 먼저 적용한다).
    """
    if error is None:
        return None
    masked = _mask_secrets(error)
    if len(masked) > _ERROR_MAX_LEN:
        masked = masked[: _ERROR_MAX_LEN - 3] + "..."
    return masked


# ----------------------------------------------------------------------
# 버전 추적형 마이그레이션 프레임워크 (#26)
# ----------------------------------------------------------------------
#
# 기존의 ad-hoc _migrate(컬럼별 PRAGMA + ALTER 나열)를 순차 마이그레이션 목록으로
# 재구성한다. schema_version 테이블(version INTEGER)에 현재 적용된 스키마 버전을
# 한 행으로 보관하고, 적용 시 미적용 마이그레이션만 순서대로 실행한 뒤 버전을
# 기록한다.
#
# 설계 원칙:
#   * 각 마이그레이션은 멱등(IF NOT EXISTS / 컬럼 존재 검사)이어야 한다. 같은
#     버전을 다시 실행해도 안전하다(두 번 호출 방어).
#   * 신규 빈 DB(SCHEMA executescript 직후, 모든 테이블/컬럼/인덱스 존재)와
#     레거시 DB(구 스키마만 존재) 모두 안전하게 최신 버전까지 끌어올린다.
#   * MIGRATIONS 목록의 순서·내용이 곧 스키마 진화의 단일 출처다. 새 변경은
#     목록 끝에 (version, fn) 항목을 추가하기만 하면 된다.


def _add_guild_config_columns(conn: sqlite3.Connection) -> None:
    """초기 스키마 이후 추가된 guild_config 컬럼을 멱등하게 보강한다 (#26)."""
    existing = {row[1] for row in conn.execute("PRAGMA table_info(guild_config)")}
    column_defs = (
        ("provider", "TEXT NOT NULL DEFAULT 'ollama'"),
        ("api_key_encrypted", "TEXT"),
        ("auto_summary_interval", "INTEGER"),
        ("persona", "TEXT"),
        ("custom_summarize_prompt", "TEXT"),
        ("custom_ask_prompt", "TEXT"),
        ("allowed_role_id", "INTEGER"),
    )
    for name, ddl in column_defs:
        if name not in existing:
            conn.execute(f"ALTER TABLE guild_config ADD COLUMN {name} {ddl}")


def _create_feedback_table(conn: sqlite3.Connection) -> None:
    """feedback 테이블과 message_id 인덱스를 멱등하게 보장한다 (#46)."""
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


def _create_reminders_table(conn: sqlite3.Connection) -> None:
    """reminders 테이블을 기존 배포 DB 에도 멱등하게 추가한다 (#26)."""
    conn.execute(
        """CREATE TABLE IF NOT EXISTS reminders (
            id         INTEGER PRIMARY KEY AUTOINCREMENT,
            user_id    INTEGER NOT NULL,
            guild_id   INTEGER,
            channel_id INTEGER,
            due_at     TEXT    NOT NULL,
            payload    TEXT    NOT NULL,
            sent       INTEGER NOT NULL DEFAULT 0,
            created_at TEXT    NOT NULL
        )"""
    )


def _create_audit_log_table(conn: sqlite3.Connection) -> None:
    """audit_log 테이블을 기존 배포 DB 에도 멱등하게 추가한다 (#39)."""
    conn.execute(
        """CREATE TABLE IF NOT EXISTS audit_log (
            id         INTEGER PRIMARY KEY AUTOINCREMENT,
            guild_id   INTEGER,
            user_id    INTEGER,
            action     TEXT    NOT NULL,
            target     TEXT,
            before     TEXT,
            after      TEXT,
            created_at TEXT    NOT NULL
        )"""
    )


def _add_usage_log_token_columns(conn: sqlite3.Connection) -> None:
    """usage_log 에 prompt_tokens/completion_tokens 컬럼을 멱등하게 보강한다 (#17).

    기존 배포 DB(토큰 컬럼이 없는 usage_log)에도 안전하게 적용된다. 컬럼 존재
    검사를 거쳐 없을 때만 ALTER 하므로 두 번 호출해도 안전하다.
    """
    existing = {row[1] for row in conn.execute("PRAGMA table_info(usage_log)")}
    for name in ("prompt_tokens", "completion_tokens"):
        if name not in existing:
            conn.execute(
                f"ALTER TABLE usage_log ADD COLUMN {name} INTEGER NOT NULL DEFAULT 0"
            )


def _add_guild_config_daily_budget(conn: sqlite3.Connection) -> None:
    """guild_config 에 daily_token_budget 컬럼을 멱등하게 보강한다 (#19).

    기존 배포 DB(이 컬럼이 없는 guild_config)에도 안전하게 적용된다. 컬럼 존재
    검사를 거쳐 없을 때만 ALTER 하므로 두 번 호출해도 안전하다(NULL = 무제한).
    """
    existing = {row[1] for row in conn.execute("PRAGMA table_info(guild_config)")}
    if "daily_token_budget" not in existing:
        conn.execute("ALTER TABLE guild_config ADD COLUMN daily_token_budget INTEGER")


def _create_query_indexes(conn: sqlite3.Connection) -> None:
    """주요 쿼리 경로 인덱스를 멱등하게 보강한다 (#32/#39)."""
    conn.execute("CREATE INDEX IF NOT EXISTS idx_usage_log_guild_created ON usage_log(guild_id, created_at)")
    conn.execute("CREATE INDEX IF NOT EXISTS idx_feedback_guild_id ON feedback(guild_id)")
    conn.execute("CREATE INDEX IF NOT EXISTS idx_reminders_due ON reminders(sent, due_at)")
    conn.execute("CREATE INDEX IF NOT EXISTS idx_reminders_user ON reminders(user_id)")
    conn.execute("CREATE INDEX IF NOT EXISTS idx_reminders_guild ON reminders(guild_id)")
    conn.execute("CREATE INDEX IF NOT EXISTS idx_audit_log_guild ON audit_log(guild_id, created_at)")


# 순차 마이그레이션 목록: (target_version, migration_fn).
# version 오름차순이며, 현재 schema_version 보다 큰 항목만 순서대로 실행된다.
MIGRATIONS: list[tuple[int, Callable[[sqlite3.Connection], None]]] = [
    (1, _add_guild_config_columns),
    (2, _create_feedback_table),
    (3, _create_reminders_table),
    (4, _create_audit_log_table),
    (5, _create_query_indexes),
    (6, _add_usage_log_token_columns),
    (7, _add_guild_config_daily_budget),
]

# 코드가 도달 가능한 최신 스키마 버전. MIGRATIONS 가 비어 있으면 0.
LATEST_SCHEMA_VERSION = MIGRATIONS[-1][0] if MIGRATIONS else 0


def _get_schema_version(conn: sqlite3.Connection) -> int:
    """현재 적용된 schema_version 을 반환한다. 테이블이 없으면 0 (#26).

    #75 방어: 단일 행 불변식이 어떤 경로로든 깨져 여러 행이 존재하더라도
    ``MAX(version)`` 으로 가장 진전된 버전을 읽어 마이그레이션이 이미 적용된
    변경을 되돌리지 않도록 한다(LIMIT 1 은 임의 한 행을 읽어 모호했다).
    """
    conn.execute("CREATE TABLE IF NOT EXISTS schema_version (version INTEGER NOT NULL)")
    row = conn.execute("SELECT MAX(version) FROM schema_version").fetchone()
    if row is None or row[0] is None:
        # 단일 행 규약: 최초 진입 시 0 으로 시드한다.
        conn.execute("INSERT INTO schema_version (version) VALUES (0)")
        return 0
    return int(row[0])


def _set_schema_version(conn: sqlite3.Connection, version: int) -> None:
    """schema_version 을 주어진 버전으로 갱신하고 단일 행 불변식을 강제한다 (#26).

    #75 방어: schema_version 테이블에는 PK/UNIQUE 가 없어(기존 배포 DB 호환을 위해
    스키마 재정의를 피한다) 다중 행이 생기면 ``WHERE`` 없는 UPDATE 가 모든 행을
    같은 값으로 만들 뿐 단일 행을 보장하지 못한다. 여기서 잉여 행을 코드 레벨에서
    정리해 정확히 한 행만 남도록 보장한다: 모든 행을 ``version`` 으로 맞춘 뒤 최소
    한 행을 남기고 나머지(rowid 가 더 큰 중복)를 삭제한다. 행이 하나도 없으면
    한 행을 삽입한다(빈 테이블 방어). 정상 경로(단일 행)에서는 UPDATE 1건과
    동일한 결과라 동작/성능 회귀가 없다.
    """
    cur = conn.execute("UPDATE schema_version SET version = ?", (version,))
    if cur.rowcount == 0:
        # 빈 테이블: 한 행을 시드한다(단일 행 규약).
        conn.execute("INSERT INTO schema_version (version) VALUES (?)", (version,))
        return
    # 잉여 행 정리: rowid 가 최소인 한 행만 남기고 나머지를 제거한다.
    conn.execute(
        "DELETE FROM schema_version "
        "WHERE rowid <> (SELECT MIN(rowid) FROM schema_version)"
    )


def _migrate(conn: sqlite3.Connection) -> None:
    """미적용 마이그레이션만 순서대로 실행하고 schema_version 을 기록한다 (#26).

    신규 빈 DB(SCHEMA 적용 후)와 레거시 DB(구 스키마만 존재) 모두 안전하다. 각
    마이그레이션 함수는 멱등하므로 SCHEMA 가 이미 최종 객체를 만들어 두었더라도
    충돌 없이 통과하며, 두 번 호출해도 안전하다(두 번째 호출에서는 적용할
    마이그레이션이 없어 no-op).
    """
    current = _get_schema_version(conn)
    for version, migrate_fn in MIGRATIONS:
        if version > current:
            migrate_fn(conn)
            _set_schema_version(conn, version)
            current = version
    conn.commit()


def _apply_pragmas(conn: sqlite3.Connection) -> None:
    """단일 영속 연결에 적용할 PRAGMA 묶음 (#24, #25).

    'database is locked' 방어: 잠긴 DB를 만나면 즉시 실패하지 않고 최대 5초까지
    재시도한다. WAL 모드에서 synchronous=NORMAL 은 내구성을 크게 해치지 않으면서
    쓰기 부하를 줄여 잠금 경합을 완화한다.
    """
    conn.execute("PRAGMA journal_mode=WAL")
    conn.execute("PRAGMA foreign_keys=ON")
    conn.execute("PRAGMA busy_timeout=5000")
    conn.execute("PRAGMA synchronous=NORMAL")


def _initialize_schema(conn: sqlite3.Connection) -> None:
    """SCHEMA executescript + 순차 마이그레이션을 동기 연결에 적용한다 (#26)."""
    conn.executescript(SCHEMA)
    _migrate(conn)


class ConfigStore:
    """Async façade for SQLite operations used by Discord handlers.

    #24: 내부적으로 단일 aiosqlite 연결(``self._conn``)을 유지하며, ``initialize()``
    에서 열고 ``close()`` (#50) 에서 정리한다. read-modify-write/write 경합은
    ``self._lock`` (asyncio.Lock) 으로 보호한다. 모든 공개 async 메서드의 시그니처·
    반환·동작은 기존 sqlite3 + to_thread 구현과 100% 동일하게 유지된다.
    """

    def __init__(
        self,
        database_url: str,
        *,
        default_model: str,
        default_summary_limit: int,
        default_language: str,
    ) -> None:
        self.database_url = database_url
        # #31: 백엔드(드라이버) 선택을 URL 스킴에서 추상화한다. postgresql:// 계열은
        # BACKEND_POSTGRES, 그 외는 BACKEND_SQLITE. 생성 시점에는 어떤 백엔드든
        # ValueError 로 죽지 않는다(친절한 에러는 실제 사용 시점으로 미룬다).
        self.backend = backend_from_database_url(database_url)
        if self.backend == BACKEND_POSTGRES:
            # Postgres 경로: sqlite 경로 변환은 의미가 없으므로 건너뛴다. 실제
            # asyncpg 쿼리는 아직 미구현이라 initialize() 에서 명확히 안내한다.
            self.path = database_url
        else:
            raw_path = sqlite_path_from_database_url(database_url)
            self.path = raw_path if raw_path == ":memory:" else str(Path(raw_path).expanduser())
        self.default_config = GuildConfig(
            guild_id=0,
            model=default_model,
            summary_limit=default_summary_limit,
            language=default_language,
        )
        # 영속 aiosqlite 연결. initialize() 전에는 None.
        self._conn: aiosqlite.Connection | None = None
        # read-modify-write(setter)/write 경합 직렬화용 락 (#24).
        self._lock = asyncio.Lock()

    async def initialize(self) -> None:
        """단일 영속 aiosqlite 연결을 열고 스키마/마이그레이션을 적용한다 (#24).

        멱등하다: 이미 연결이 열려 있으면 스키마/마이그레이션만 다시 보장한다
        (멱등 마이그레이션이므로 버전·데이터 불변).

        #31: postgresql:// 백엔드는 별도 경로로 분기한다. asyncpg 미설치 환경에서는
        친절한 의존성 안내를, 설치되어 있어도 실제 쿼리는 아직 미구현이라 명확한
        NotImplementedError 를 던진다. sqlite 경로는 기존과 100% 동일하다.
        """
        if self.backend == BACKEND_POSTGRES:
            await self._initialize_postgres()
            return
        if self.path != ":memory:":
            Path(self.path).expanduser().parent.mkdir(parents=True, exist_ok=True)
        if self._conn is None:
            # isolation_level=None → 자동 커밋(autocommit) 모드. 각 문장이 즉시
            # 커밋되어 암시적 트랜잭션이 열린 채로 남지 않는다. 이는 VACUUM 이
            # "SQL statements in progress" 로 실패/교착하지 않게 하는 핵심이며
            # (#33), 기존 sqlite3 구현이 작업마다 새 연결을 with 블록으로 열고
            # 닫던 동작과 동치다. 명시적 commit() 호출은 무해한 no-op 으로 남는다.
            conn = await aiosqlite.connect(self.path, isolation_level=None)
            conn.row_factory = aiosqlite.Row
            self._conn = conn
        # PRAGMA + 스키마/마이그레이션은 영속 연결의 내부 sqlite3 객체에서 동기로
        # 실행한다(:memory: 포함 단일 출처). aiosqlite._execute 는 전용 스레드에서
        # 콜러블을 직렬 실행한다.
        await self._run_sync(_apply_pragmas)
        await self._run_sync(_initialize_schema)

    async def _initialize_postgres(self) -> None:
        """postgresql:// 백엔드 초기화 골격 (#31).

        현실적 범위: URL 인식 + 백엔드 분기 + 의존성 안내까지만 책임진다. 실제
        asyncpg 기반 쿼리(스키마/CRUD)는 아직 구현되지 않았다. asyncpg 미설치
        환경을 고려해 import 는 이 시점까지 **지연**하며, 미설치 시에는 설치
        방법을 안내하는 ``RuntimeError`` 를, 설치되어 있으나 구현이 없으면
        ``NotImplementedError`` 를 던진다. 어느 경우든 ValueError 로 죽지 않는다.
        """
        try:
            import asyncpg  # noqa: F401  # 지연 import: postgres 선택 시에만 요구.
        except ImportError as exc:
            raise RuntimeError(
                "PostgreSQL DATABASE_URL 을 사용하려면 asyncpg 가 필요합니다. "
                "`pip install asyncpg` 로 설치한 뒤 다시 시도해 주세요. "
                "(SQLite 를 사용하려면 DATABASE_URL 을 sqlite:///path/to/file.db 로 설정하세요.)"
            ) from exc
        raise NotImplementedError(
            "PostgreSQL 백엔드는 아직 쿼리 구현이 완료되지 않았습니다(#31). "
            "현재는 URL 인식과 백엔드 분기 골격까지만 제공합니다. "
            "운영에는 SQLite(DATABASE_URL=sqlite:///...) 를 사용해 주세요."
        )

    async def close(self) -> None:
        """영속 aiosqlite 연결을 정리한다 (#50).

        bot.py 의 graceful shutdown 에서 호출한다(호출은 다른 파일 소관, 여기서는
        메서드만 제공). 이미 닫혔거나 열린 적 없으면 no-op.
        """
        conn = self._conn
        if conn is None:
            return
        self._conn = None
        await conn.close()

    def _require_conn(self) -> aiosqlite.Connection:
        """이미 초기화된 영속 연결을 반환한다. 내부(_run_sync) 전용.

        ``initialize()`` 가 연결을 먼저 설정한 직후 스키마/PRAGMA 적용 경로에서만
        쓰인다. 공개 메서드는 ``_ensure_conn()`` 으로 지연 초기화를 거친다.
        """
        if self._conn is None:
            raise RuntimeError("ConfigStore.initialize() must be called before use")
        return self._conn

    async def _ensure_conn(self) -> aiosqlite.Connection:
        """영속 연결을 반환하되, 미초기화면 지연 초기화한다 (#24 백워드 호환).

        기존 sqlite3 구현은 작업마다 연결을 새로 열어 ``initialize()`` 없이도
        (스키마가 이미 존재하면) 읽기/쓰기가 동작했다. 같은 관용을 유지하기 위해
        첫 접근 시 자동으로 ``initialize()`` 한다. ``initialize()`` 자체가 멱등이라
        명시 호출과 지연 초기화가 동일한 최종 상태에 도달한다.
        """
        if self._conn is None:
            await self.initialize()
        return self._require_conn()

    async def _run_sync(self, fn: Callable[[sqlite3.Connection], object]) -> object:
        """영속 연결의 내부 sqlite3 객체에 대해 동기 콜러블을 실행한다 (#24).

        aiosqlite 는 모든 연산을 전용 스레드에서 직렬 실행하므로, 동기
        마이그레이션·PRAGMA 헬퍼를 그 스레드에서 안전하게 재사용할 수 있다.
        스키마/마이그레이션의 단일 출처(동기 sqlite3 헬퍼)를 유지하는 핵심 경로다.

        #77: aiosqlite 공개 API 에는 임의 동기 콜러블을 전용 스레드에서 실행하는
        수단이 없어, 비공개 ``_execute``/``_conn`` 에 의존한다(pyproject 가
        aiosqlite<1.0 으로 상한을 둬 메이저 파손은 막는다). 0.x 마이너에서 이
        내부가 사라지면 ``getattr`` 가드가 불투명한 AttributeError 대신 원인과
        조치를 담은 명확한 RuntimeError 를 던져 진단·핀 조정을 쉽게 한다.
        """
        conn = self._require_conn()  # _run_sync 는 initialize() 직후에만 호출됨
        execute = getattr(conn, "_execute", None)
        raw_conn = getattr(conn, "_conn", None)
        if execute is None or raw_conn is None:
            raise RuntimeError(
                "설치된 aiosqlite 버전이 스키마 초기화에 필요한 내부 API"
                "(_execute/_conn)를 제공하지 않습니다. aiosqlite 를 호환 버전"
                "(>=0.20,<1.0)으로 고정해 주세요."
            )
        return await execute(fn, raw_conn)

    def _connect(self) -> sqlite3.Connection:
        """원시(raw) 동기 sqlite3 연결을 새로 연다.

        테스트·진단용 직접 접근 경로다. 영속 aiosqlite 연결과는 별개의 연결이며,
        파일 DB 에서는 같은 파일을 본다(WAL 공유). PRAGMA 도 동일하게 적용한다.

        주의: ``:memory:`` 의 경우 영속 연결과 다른 빈 DB 를 연다(연결별 분리). 영속
        연결의 데이터를 보려면 공개 async 메서드를 사용해야 한다.
        """
        conn = sqlite3.connect(self.path)
        conn.row_factory = sqlite3.Row
        _apply_pragmas(conn)
        return conn

    # ------------------------------------------------------------------
    # Read
    # ------------------------------------------------------------------

    async def get_guild_config(self, guild_id: int) -> GuildConfig:
        conn = await self._ensure_conn()
        cur = await conn.execute(
            "SELECT guild_id, model, summary_limit, language, admin_role_id, "
            "provider, api_key_encrypted, auto_summary_interval, persona, "
            "custom_summarize_prompt, custom_ask_prompt, allowed_role_id, "
            "daily_token_budget "
            "FROM guild_config WHERE guild_id = ?",
            (guild_id,),
        )
        row = await cur.fetchone()
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
            # #19: 일일 토큰 상한(NULL = 무제한). 레거시 행은 컬럼이 없을 수 있으나
            # 마이그레이션(버전 7)으로 항상 존재한다. 음수 방어는 GuildConfig 측에서.
            daily_token_budget=(
                int(row["daily_token_budget"]) if row["daily_token_budget"] is not None else None
            ),
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
        # #117: 언어 코드를 화이트리스트로 검증·정규화한다. 과거에는 빈 문자열만
        # 거부하고 임의 문자열을 그대로 저장해, 그 값이 프롬프트의
        # 'Answer in {target_language}.' 자리에 박히는 2차 인젝션 표면이 됐다.
        # 정규화(소문자·레거시 별칭 흡수) 후 'auto'(자동 감지) 또는 지원 언어
        # 코드(prompts._LANGUAGE_LABELS)만 허용한다. 검증/정규화는 prompts 의
        # 단일 출처(SSOT)를 재사용한다.
        normalized = language.strip().lower()
        if not normalized:
            raise ValueError("language cannot be empty")
        normalized = _LANGUAGE_ALIASES.get(normalized, normalized)
        if normalized != "auto" and normalized not in _LANGUAGE_LABELS:
            allowed = ", ".join(("auto", *_LANGUAGE_LABELS))
            raise ValueError(f"language must be one of: {allowed}")
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
        # #76: 빈 모델 검증을 set_model 과 동일한 진입점·메시지로 일원화한다.
        # (검증 자체는 GuildConfig.__post_init__ 가 하지만, 다른 계층/메시지로
        # 실패해 일관성이 깨지므로 여기서 동일하게 막는다.)
        normalized_model = model.strip()
        if not normalized_model:
            raise ValueError("model cannot be empty")
        current = await self.get_guild_config(guild_id)
        updated = replace(
            current,
            provider=provider,
            model=normalized_model,
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
        # read-modify-write 경합 직렬화 (#24). 단일 쓰기지만 setter 들이
        # get→replace→upsert 패턴이라 락으로 마지막 쓰기 누락을 방지한다.
        conn = await self._ensure_conn()
        async with self._lock:
            await conn.execute(
                """
                INSERT INTO guild_config
                    (guild_id, model, summary_limit, language, admin_role_id,
                     provider, api_key_encrypted, auto_summary_interval, persona,
                     custom_summarize_prompt, custom_ask_prompt, allowed_role_id,
                     daily_token_budget, updated_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
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
                    daily_token_budget      = excluded.daily_token_budget,
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
                    config.daily_token_budget,
                    _utc_now(),
                ),
            )
            await conn.commit()

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
        conn = await self._ensure_conn()
        if guild_id is not None and channel_id is not None:
            cur = await conn.execute(
                """
                SELECT role, content FROM chat_history
                WHERE user_id = ? AND guild_id = ? AND channel_id = ?
                ORDER BY id DESC
                LIMIT ? OFFSET ?
                """,
                (user_id, guild_id, channel_id, limit, offset),
            )
        else:
            cur = await conn.execute(
                """
                SELECT role, content FROM chat_history
                WHERE user_id = ?
                ORDER BY id DESC
                LIMIT ? OFFSET ?
                """,
                (user_id, limit, offset),
            )
        rows = await cur.fetchall()
        ordered = list(rows)
        ordered.reverse()
        return [{"role": row["role"], "content": row["content"]} for row in ordered]

    _MAX_CHAT_HISTORY_PER_USER = 200

    async def save_chat_message(
        self,
        user_id: int,
        role: str,
        content: str,
        *,
        guild_id: int | None = None,
        channel_id: int | None = None,
    ) -> None:
        conn = await self._ensure_conn()
        # insert + prune 가 한 쓰기 단위로 묶이도록 락으로 보호 (#24).
        async with self._lock:
            await conn.execute(
                """
                INSERT INTO chat_history (guild_id, channel_id, user_id, role, content, created_at)
                VALUES (?, ?, ?, ?, ?, ?)
                """,
                (guild_id, channel_id, user_id, role, content, _utc_now()),
            )
            # #73: prune 키를 조회 키(get_chat_history)와 일치시킨다. 조회는
            # (user_id, guild_id, channel_id) 조합으로 필터하므로, prune 도 같은
            # 조합 버킷 안에서만 오래된 행을 지워야 한다. 과거에는 user_id 전역으로
            # 최신 200행만 남겨, 한 채널의 활동이 다른 길드/채널의 기록을 통째로
            # 비결정적으로 삭제하던 버그가 있었다. guild_id/channel_id 는 NULL 일 수
            # 있어(DM 경로) ``IS`` 로 NULL-safe 비교한다(``= NULL`` 은 항상 거짓).
            await conn.execute(
                """
                DELETE FROM chat_history
                WHERE user_id = ?
                  AND guild_id IS ?
                  AND channel_id IS ?
                  AND id NOT IN (
                    SELECT id FROM chat_history
                    WHERE user_id = ?
                      AND guild_id IS ?
                      AND channel_id IS ?
                    ORDER BY id DESC
                    LIMIT ?
                  )
                """,
                (
                    user_id,
                    guild_id,
                    channel_id,
                    user_id,
                    guild_id,
                    channel_id,
                    self._MAX_CHAT_HISTORY_PER_USER,
                ),
            )
            await conn.commit()

    # ------------------------------------------------------------------
    # Usage log
    # ------------------------------------------------------------------

    async def log_usage(self, log: UsageLog) -> None:
        # #41: 저장 전에 토큰/API키/Bearer/이메일 등 민감 패턴을 ``***`` 로
        # 마스킹하고 길이를 클램프한다(마스킹 → 클램프 순서).
        error = _sanitize_error(log.error)
        conn = await self._ensure_conn()
        async with self._lock:
            await conn.execute(
                """
                INSERT INTO usage_log
                    (guild_id, channel_id, user_id, command, status, latency_ms,
                     error, prompt_tokens, completion_tokens, created_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """,
                (
                    log.guild_id,
                    log.channel_id,
                    log.user_id,
                    log.command,
                    log.status,
                    log.latency_ms,
                    error,
                    # #17: 음수 토큰 수는 0 으로 보정해 통계/과금 집계를 보호한다.
                    max(log.prompt_tokens, 0),
                    max(log.completion_tokens, 0),
                    _utc_now(),
                ),
            )
            await conn.commit()

    # ------------------------------------------------------------------
    # Retention / maintenance (#27, #33)
    # ------------------------------------------------------------------

    async def purge_old(
        self,
        *,
        usage_days: int,
        chat_days: int,
        reminder_days: int | None = None,
    ) -> dict[str, int]:
        """created_at 기준으로 오래된 usage_log/chat_history 행을 삭제한다.

        ``usage_days``/``chat_days`` 일보다 오래된 행을 각각 삭제하고 삭제된
        건수를 ``{"usage_log": N, "chat_history": M}`` 형태로 반환한다.
        0 이하의 일수는 해당 테이블 정리를 건너뛴다(보존 비활성화).

        #18: ``reminder_days`` 가 주어지면(0 초과) ``created_at`` 기준 그만큼
        오래된 **발송 완료(sent=1)** 리마인더도 정리해 sent 행이 무한 누적되는
        것을 막는다. 미발송(sent=0) 리마인더는 미래 발송 대상이므로 절대 삭제하지
        않는다. 백워드 호환: 기본값(None)이면 reminders 는 건드리지 않고 반환
        dict 에 ``reminders`` 키도 추가하지 않는다(기존 호출부/단언 보존).

        백그라운드 태스크 등록은 호출 측(bot.py) 책임이며, 여기서는 메서드만
        제공한다 (#27).
        """
        if usage_days < 0 or chat_days < 0:
            raise ValueError("retention days must be >= 0")
        if reminder_days is not None and reminder_days < 0:
            raise ValueError("retention days must be >= 0")
        conn = await self._ensure_conn()
        deleted = {"usage_log": 0, "chat_history": 0}
        if reminder_days is not None:
            deleted["reminders"] = 0
        async with self._lock:
            # 컷오프 비교는 양변을 SQLite ``datetime()`` 으로 정규화한다 (#72).
            # created_at 은 ISO('T' 구분자 + '+00:00' 오프셋)이고
            # datetime('now', ?) 는 공백 구분자·무오프셋 형식이라, 정규화 없이
            # 단순 문자열 비교하면 위치 10('T'=84 vs ' '=32)에서 갈려 컷오프 날짜와
            # 같은 날짜의 행이 시각과 무관하게 삭제되지 않는다. 양변을 datetime()
            # 으로 파싱하면 get_today_token_usage 의 date()=date() 패턴과 동일하게
            # 안전하다.
            if usage_days > 0:
                cur = await conn.execute(
                    "DELETE FROM usage_log "
                    "WHERE datetime(created_at) < datetime('now', ?)",
                    (f"-{usage_days} days",),
                )
                deleted["usage_log"] = cur.rowcount if cur.rowcount and cur.rowcount > 0 else 0
            if chat_days > 0:
                cur = await conn.execute(
                    "DELETE FROM chat_history "
                    "WHERE datetime(created_at) < datetime('now', ?)",
                    (f"-{chat_days} days",),
                )
                deleted["chat_history"] = cur.rowcount if cur.rowcount and cur.rowcount > 0 else 0
            # #18: 발송 완료(sent=1) 리마인더만, created_at 기준으로 정리한다.
            # 미발송(sent=0)은 미래 발송 대상이라 절대 삭제하지 않는다. 컷오프
            # 비교는 usage_log/chat_history 와 동일하게 양변을 datetime() 으로
            # 정규화한다(#72 와 동일한 안전성).
            if reminder_days is not None and reminder_days > 0:
                cur = await conn.execute(
                    "DELETE FROM reminders "
                    "WHERE sent = 1 AND datetime(created_at) < datetime('now', ?)",
                    (f"-{reminder_days} days",),
                )
                deleted["reminders"] = cur.rowcount if cur.rowcount and cur.rowcount > 0 else 0
            await conn.commit()
        return deleted

    async def vacuum(self) -> None:
        """DB 파일 비대화를 막기 위해 WAL 체크포인트(TRUNCATE) 후 VACUUM 한다.

        ``:memory:`` DB 는 파일이 없어 VACUUM/체크포인트 의미가 없으므로 안전하게
        건너뛴다 (#33).
        """
        if self.path == ":memory:":
            return
        conn = await self._ensure_conn()
        # WAL 파일을 본 DB 에 합치고(TRUNCATE) 잘라낸 뒤 VACUUM 으로 미사용
        # 페이지를 회수한다. VACUUM 은 트랜잭션 안에서 실행할 수 없으므로 진행
        # 중인 쓰기와 겹치지 않도록 락으로 보호하고 별도 커밋 없이 단독 실행한다.
        # 체크포인트 PRAGMA 가 돌려준 커서를 반드시 소진·종료해야 한다 — 열린
        # 커서(prepared statement)가 남아 있으면 VACUUM 이 "SQL statements in
        # progress" 로 실패한다(isolation_level=None 이라 트랜잭션은 없음). (#33)
        async with self._lock:
            checkpoint_cur = await conn.execute("PRAGMA wal_checkpoint(TRUNCATE)")
            await checkpoint_cur.fetchall()
            await checkpoint_cur.close()
            vacuum_cur = await conn.execute("VACUUM")
            await vacuum_cur.close()

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

    async def set_daily_token_budget(
        self, guild_id: int, budget: int | None
    ) -> GuildConfig:
        """서버별 일일 토큰 상한을 설정한다. None = 무제한(기본) (#19).

        음수 예산은 의미가 없으므로 ValueError 로 거부한다(GuildConfig 가
        동일하게 검증하지만, 저장 직전 명확한 에러를 위해 여기서도 막는다).
        """
        if budget is not None and budget < 0:
            raise ValueError("daily_token_budget must be >= 0 or None")
        current = await self.get_guild_config(guild_id)
        updated = replace(current, daily_token_budget=budget)
        await self._upsert(updated)
        return updated

    async def get_today_token_usage(self, guild_id: int) -> int:
        """오늘(UTC) 해당 길드가 사용한 누적 토큰 수를 반환한다 (#19).

        usage_log 의 prompt_tokens + completion_tokens 합을, created_at 이 오늘
        (UTC, SQLite ``date('now')``)인 행에 한해 집계한다. 기록이 없으면 0.
        일일 상한 초과 여부 판단(bot.py)에 사용한다.
        """
        conn = await self._ensure_conn()
        cur = await conn.execute(
            "SELECT COALESCE(SUM(prompt_tokens + completion_tokens), 0) AS total "
            "FROM usage_log "
            "WHERE guild_id = ? AND date(created_at) = date('now')",
            (guild_id,),
        )
        row = await cur.fetchone()
        if row is None:
            return 0
        return int(row["total"] or 0)

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
        conn = await self._ensure_conn()
        async with self._lock:
            # #74: (message_id, user_id) 가 UNIQUE 이므로 평범한 INSERT 는 동일
            # 사용자의 평점 변경(👍→👎) 시 IntegrityError 를 던지고, 호출 측이
            # 이를 삼켜 변경이 조용히 저장되지 않았다. ON CONFLICT upsert 로
            # 재평가 시 rating/command/created_at 을 갱신해 평점 토글이 실제로
            # 반영되게 한다.
            await conn.execute(
                """
                INSERT INTO feedback (guild_id, message_id, user_id, rating, command, created_at)
                VALUES (?, ?, ?, ?, ?, ?)
                ON CONFLICT(message_id, user_id) DO UPDATE SET
                    rating     = excluded.rating,
                    command    = excluded.command,
                    created_at = excluded.created_at
                """,
                (guild_id, message_id, user_id, rating, command, _utc_now()),
            )
            await conn.commit()

    # ------------------------------------------------------------------
    # Stats (Phase 3 #43)
    # ------------------------------------------------------------------

    async def get_stats(self, guild_id: int) -> dict:
        conn = await self._ensure_conn()
        total_cur = await conn.execute(
            "SELECT COUNT(*) AS cnt FROM usage_log WHERE guild_id = ?", (guild_id,)
        )
        total_row = await total_cur.fetchone()
        total = total_row["cnt"] if total_row else 0

        by_command_cur = await conn.execute(
            "SELECT command, COUNT(*) AS cnt FROM usage_log WHERE guild_id = ? "
            "GROUP BY command ORDER BY cnt DESC",
            (guild_id,),
        )
        by_command = await by_command_cur.fetchall()

        avg_cur = await conn.execute(
            "SELECT AVG(latency_ms) AS avg_ms FROM usage_log WHERE guild_id = ? AND status = 'ok'",
            (guild_id,),
        )
        avg_row = await avg_cur.fetchone()
        avg_latency = round(avg_row["avg_ms"]) if avg_row and avg_row["avg_ms"] is not None else 0

        error_cur = await conn.execute(
            "SELECT COUNT(*) AS cnt FROM usage_log WHERE guild_id = ? AND status = 'error'",
            (guild_id,),
        )
        error_row = await error_cur.fetchone()
        error_count = error_row["cnt"] if error_row else 0

        # Actual activity date range for this guild (#69)
        range_cur = await conn.execute(
            "SELECT MIN(created_at) AS first_at, MAX(created_at) AS last_at "
            "FROM usage_log WHERE guild_id = ?",
            (guild_id,),
        )
        range_row = await range_cur.fetchone()
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
        conn = await self._ensure_conn()
        cur = await conn.execute("SELECT guild_id FROM guild_config")
        rows = await cur.fetchall()
        return [int(row["guild_id"]) for row in rows]

    async def get_guilds_with_auto_summary(self) -> list[tuple[int, int]]:
        """Return (guild_id, interval_minutes) for guilds with auto-summary enabled (#25).

        Lets the polling task skip guilds that have not configured auto-summary
        without loading every guild's full config row.
        """
        conn = await self._ensure_conn()
        cur = await conn.execute(
            "SELECT guild_id, auto_summary_interval FROM guild_config "
            "WHERE auto_summary_interval IS NOT NULL AND auto_summary_interval > 0"
        )
        rows = await cur.fetchall()
        return [
            (int(row["guild_id"]), _normalize_interval(row["auto_summary_interval"]) or MIN_AUTO_SUMMARY_INTERVAL_MINUTES)
            for row in rows
        ]

    # ------------------------------------------------------------------
    # Reminders (#26)
    # ------------------------------------------------------------------
    #
    # 데이터 계층만 제공한다. on_ready 재예약/디스코드 전송은 bot.py 소관이며
    # 여기서는 스키마·CRUD·만기 조회만 책임진다.

    @staticmethod
    def _row_to_reminder(row: aiosqlite.Row) -> Reminder:
        return Reminder(
            id=int(row["id"]),
            user_id=int(row["user_id"]),
            guild_id=(int(row["guild_id"]) if row["guild_id"] is not None else None),
            channel_id=(int(row["channel_id"]) if row["channel_id"] is not None else None),
            due_at=str(row["due_at"]),
            payload=str(row["payload"]),
            sent=bool(row["sent"]),
            created_at=str(row["created_at"]),
        )

    async def add_reminder(
        self,
        user_id: int,
        guild_id: int | None,
        channel_id: int | None,
        due_at: str,
        payload: str,
    ) -> int:
        """리마인더를 추가하고 새 행의 id 를 반환한다.

        ``due_at`` 은 ISO8601 문자열(예: ``2026-05-29T12:00:00+00:00``)이어야 한다.
        만기 비교(``list_due``)는 SQLite 문자열 사전식 비교이므로, 저장 전에
        ``+09:00`` 같은 비-UTC 오프셋을 UTC 로 정규화하고 초 단위 고정 포맷
        (``timespec='seconds'``)으로 통일해 사전식 정렬이 실제 시간 순서와
        일치하도록 강제한다 (#17). 파싱 불가한 입력은 기존 동작(입력 그대로 저장)
        을 보존해 회귀를 막는다.
        """
        normalized_due = due_at.strip()
        if not normalized_due:
            raise ValueError("due_at cannot be empty")
        normalized_due = _normalize_due_at(normalized_due)
        if not payload.strip():
            raise ValueError("payload cannot be empty")
        conn = await self._ensure_conn()
        async with self._lock:
            cur = await conn.execute(
                """
                INSERT INTO reminders (user_id, guild_id, channel_id, due_at, payload, sent, created_at)
                VALUES (?, ?, ?, ?, ?, 0, ?)
                """,
                (user_id, guild_id, channel_id, normalized_due, payload, _utc_now()),
            )
            await conn.commit()
            return int(cur.lastrowid or 0)

    async def list_due(self, now: str | None = None) -> list[Reminder]:
        """``now`` ISO 시각 이하의 미발송 리마인더를 due_at 오름차순으로 반환한다.

        ``now`` 가 None 이면 현재 UTC 시각을 사용한다. 폴링 태스크가 만기 항목을
        한 번에 조회하는 용도다 (#26).
        """
        effective_now = now or _utc_now()
        conn = await self._ensure_conn()
        cur = await conn.execute(
            """
            SELECT id, user_id, guild_id, channel_id, due_at, payload, sent, created_at
            FROM reminders
            WHERE sent = 0 AND due_at <= ?
            ORDER BY due_at ASC, id ASC
            """,
            (effective_now,),
        )
        rows = await cur.fetchall()
        return [self._row_to_reminder(row) for row in rows]

    async def list_by_user(self, user_id: int, *, include_sent: bool = False) -> list[Reminder]:
        """특정 사용자의 리마인더를 due_at 오름차순으로 반환한다.

        기본적으로 미발송 항목만 반환한다(백워드 호환 기본값). ``include_sent`` 가
        True 이면 발송 완료 항목도 포함한다.
        """
        conn = await self._ensure_conn()
        if include_sent:
            cur = await conn.execute(
                """
                SELECT id, user_id, guild_id, channel_id, due_at, payload, sent, created_at
                FROM reminders WHERE user_id = ?
                ORDER BY due_at ASC, id ASC
                """,
                (user_id,),
            )
        else:
            cur = await conn.execute(
                """
                SELECT id, user_id, guild_id, channel_id, due_at, payload, sent, created_at
                FROM reminders WHERE user_id = ? AND sent = 0
                ORDER BY due_at ASC, id ASC
                """,
                (user_id,),
            )
        rows = await cur.fetchall()
        return [self._row_to_reminder(row) for row in rows]

    async def delete_reminder(self, reminder_id: int) -> bool:
        """리마인더를 삭제한다. 실제로 삭제됐으면 True 를 반환한다."""
        conn = await self._ensure_conn()
        async with self._lock:
            cur = await conn.execute("DELETE FROM reminders WHERE id = ?", (reminder_id,))
            await conn.commit()
            return bool(cur.rowcount and cur.rowcount > 0)

    async def mark_sent(self, reminder_id: int) -> bool:
        """리마인더를 발송 완료로 표시한다. 변경됐으면 True 를 반환한다."""
        conn = await self._ensure_conn()
        async with self._lock:
            cur = await conn.execute(
                "UPDATE reminders SET sent = 1 WHERE id = ?", (reminder_id,)
            )
            await conn.commit()
            return bool(cur.rowcount and cur.rowcount > 0)

    # ------------------------------------------------------------------
    # Audit log (#39)
    # ------------------------------------------------------------------

    async def record_audit(
        self,
        *,
        guild_id: int | None,
        user_id: int | None,
        action: str,
        target: str | None = None,
        before: str | None = None,
        after: str | None = None,
    ) -> int:
        """감사 로그 한 건을 기록하고 새 행의 id 를 반환한다 (#39)."""
        normalized_action = action.strip()
        if not normalized_action:
            raise ValueError("action cannot be empty")
        conn = await self._ensure_conn()
        async with self._lock:
            cur = await conn.execute(
                """
                INSERT INTO audit_log (guild_id, user_id, action, target, before, after, created_at)
                VALUES (?, ?, ?, ?, ?, ?, ?)
                """,
                (guild_id, user_id, normalized_action, target, before, after, _utc_now()),
            )
            await conn.commit()
            return int(cur.lastrowid or 0)

    async def list_audit(self, guild_id: int, *, limit: int = 50) -> list[AuditEntry]:
        """길드의 최근 감사 로그를 created_at 내림차순(최신 우선)으로 반환한다 (#39)."""
        if limit < 1:
            raise ValueError("limit must be >= 1")
        conn = await self._ensure_conn()
        cur = await conn.execute(
            """
            SELECT id, guild_id, user_id, action, target, before, after, created_at
            FROM audit_log
            WHERE guild_id = ?
            ORDER BY id DESC
            LIMIT ?
            """,
            (guild_id, limit),
        )
        rows = await cur.fetchall()
        return [
            AuditEntry(
                id=int(row["id"]),
                guild_id=(int(row["guild_id"]) if row["guild_id"] is not None else None),
                user_id=(int(row["user_id"]) if row["user_id"] is not None else None),
                action=str(row["action"]),
                target=row["target"],
                before=row["before"],
                after=row["after"],
                created_at=str(row["created_at"]),
            )
            for row in rows
        ]

    # ------------------------------------------------------------------
    # Data deletion / GDPR (#40)
    # ------------------------------------------------------------------

    async def delete_user_data(self, user_id: int) -> dict[str, int]:
        """한 사용자의 데이터를 모든 관련 테이블에서 삭제한다 (#40).

        반환값은 ``{"chat_history": N, "feedback": M, "usage_log": K,
        "reminders": L}`` 형태의 삭제 건수 dict 이다.
        """
        conn = await self._ensure_conn()
        deleted = {"chat_history": 0, "feedback": 0, "usage_log": 0, "reminders": 0}
        async with self._lock:
            # #78: autocommit(isolation_level=None) 이라 각 DELETE 가 즉시 커밋되어,
            # 루프 중간 실패 시 일부 테이블만 지워진 채 사용자 데이터가 잔존하는
            # GDPR 부분 삭제가 가능했다. 명시적 BEGIN/COMMIT 으로 전체 삭제를
            # 원자화하고, 실패 시 ROLLBACK 으로 일관성을 복구한다.
            await conn.execute("BEGIN")
            try:
                for table in ("chat_history", "feedback", "usage_log", "reminders"):
                    cur = await conn.execute(f"DELETE FROM {table} WHERE user_id = ?", (user_id,))
                    deleted[table] = cur.rowcount if cur.rowcount and cur.rowcount > 0 else 0
                await conn.commit()
            except Exception:
                await conn.rollback()
                raise
        return deleted

    async def delete_guild_data(self, guild_id: int) -> dict[str, int]:
        """한 길드의 데이터를 모든 관련 테이블에서 삭제한다 (#40).

        반환값은 ``{"guild_config": N, "usage_log": M, "feedback": K,
        "chat_history": L, "reminders": P}`` 형태의 삭제 건수 dict 이다.
        """
        conn = await self._ensure_conn()
        deleted = {
            "guild_config": 0,
            "usage_log": 0,
            "feedback": 0,
            "chat_history": 0,
            "reminders": 0,
        }
        async with self._lock:
            # #78: 사용자 삭제와 동일하게 다중 테이블 삭제를 명시적 트랜잭션으로
            # 원자화한다(부분 삭제 = GDPR 불완전 삭제 방지).
            await conn.execute("BEGIN")
            try:
                for table in ("guild_config", "usage_log", "feedback", "chat_history", "reminders"):
                    cur = await conn.execute(f"DELETE FROM {table} WHERE guild_id = ?", (guild_id,))
                    deleted[table] = cur.rowcount if cur.rowcount and cur.rowcount > 0 else 0
                await conn.commit()
            except Exception:
                await conn.rollback()
                raise
        return deleted
