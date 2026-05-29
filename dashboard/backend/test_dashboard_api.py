"""대시보드 백엔드 라우트 스모크/통합 테스트 (#78, #79, #85).

TestClient(fastapi/httpx) 로 새/변경 라우트를 검증한다:
  - #78 GET /api/guilds/{id}/feedback 집계 응답
  - #79 PUT config / api-key 라우트의 Administrator 권한 가드
  - #85 POST /auth/refresh 토큰 재발급

인메모리 sqlite 를 임시 파일로 만들어 main.py 의 DB 경로를 가리키게 한다.
한국어 주석.
"""
from __future__ import annotations

import importlib
import sqlite3
from collections.abc import Iterator
from pathlib import Path

import pytest
from fastapi.testclient import TestClient


@pytest.fixture()
def client(tmp_path: Path, monkeypatch: pytest.MonkeyPatch) -> Iterator[TestClient]:
    """임시 sqlite DB 와 테스트용 SECRET_KEY 를 세팅한 TestClient 를 만든다."""
    db_file = tmp_path / "test.db"
    _seed_db(db_file)

    monkeypatch.setenv("DATABASE_URL", f"sqlite:///{db_file}")
    monkeypatch.setenv("SECRET_KEY", "test-secret-key")

    # 환경변수가 반영된 모듈을 새로 로드한다.
    from dashboard.backend import auth as auth_mod
    from dashboard.backend import main as main_mod

    importlib.reload(auth_mod)
    importlib.reload(main_mod)

    # lifespan 이 _DB_PATH 를 임시 파일로 잡도록 with 블록을 사용한다.
    with TestClient(main_mod.app) as c:
        c._auth_mod = auth_mod  # type: ignore[attr-defined]
        yield c


def _seed_db(db_file: Path) -> None:
    """봇 스키마와 동일한 테이블을 만들고 피드백/사용로그 샘플을 넣는다."""
    conn = sqlite3.connect(db_file)
    conn.executescript(
        """
        CREATE TABLE guild_config (
            guild_id INTEGER PRIMARY KEY, model TEXT NOT NULL,
            summary_limit INTEGER NOT NULL, language TEXT NOT NULL,
            admin_role_id INTEGER, provider TEXT NOT NULL DEFAULT 'ollama',
            api_key_encrypted TEXT, auto_summary_interval INTEGER, persona TEXT,
            custom_summarize_prompt TEXT, custom_ask_prompt TEXT,
            allowed_role_id INTEGER, updated_at TEXT NOT NULL
        );
        CREATE TABLE usage_log (
            id INTEGER PRIMARY KEY AUTOINCREMENT, guild_id INTEGER, channel_id INTEGER,
            user_id INTEGER, command TEXT NOT NULL, status TEXT NOT NULL,
            latency_ms INTEGER, error TEXT, created_at TEXT NOT NULL
        );
        CREATE TABLE feedback (
            id INTEGER PRIMARY KEY AUTOINCREMENT, guild_id INTEGER,
            message_id INTEGER NOT NULL, user_id INTEGER NOT NULL,
            rating INTEGER NOT NULL, command TEXT, created_at TEXT NOT NULL,
            UNIQUE(message_id, user_id)
        );
        """
    )
    # 피드백 샘플: ask 긍정 2 / 부정 1, summarize 긍정 1
    rows = [
        (123, 1, 11, 1, "ask", "2026-05-01T00:00:00+00:00"),
        (123, 2, 12, 1, "ask", "2026-05-02T00:00:00+00:00"),
        (123, 3, 13, -1, "ask", "2026-05-03T00:00:00+00:00"),
        (123, 4, 14, 1, "summarize", "2026-05-04T00:00:00+00:00"),
    ]
    conn.executemany(
        "INSERT INTO feedback (guild_id, message_id, user_id, rating, command, created_at) "
        "VALUES (?, ?, ?, ?, ?, ?)",
        rows,
    )
    conn.commit()
    conn.close()


def _admin_token(client: TestClient, guild_id: int = 123, admin: bool = True) -> str:
    auth_mod = client._auth_mod  # type: ignore[attr-defined]
    return auth_mod.create_jwt(
        "999",
        [{"id": str(guild_id), "name": "Test", "icon": None, "owner": admin}],
    )


def _headers(token: str) -> dict[str, str]:
    return {"Authorization": f"Bearer {token}"}


# ---------------------------------------------------------------------------
# #78 feedback
# ---------------------------------------------------------------------------


def test_feedback_aggregation(client: TestClient) -> None:
    token = _admin_token(client)
    resp = client.get("/api/guilds/123/feedback", headers=_headers(token))
    assert resp.status_code == 200, resp.text
    body = resp.json()
    assert body["total"] == 4
    assert body["positive"] == 3
    assert body["negative"] == 1
    assert body["satisfaction"] == 75.0
    # rating 분포에 +1 과 -1 이 모두 존재
    dist = {d["rating"]: d["count"] for d in body["rating_distribution"]}
    assert dist == {1: 3, -1: 1}
    # command 별 집계: ask 가 긍정2/부정1
    by_cmd = {c["command"]: c for c in body["by_command"]}
    assert by_cmd["ask"]["positive"] == 2
    assert by_cmd["ask"]["negative"] == 1
    assert by_cmd["summarize"]["positive"] == 1
    # 최근 목록은 최신순(내림차순 id)
    assert body["recent"][0]["command"] == "summarize"
    assert len(body["recent"]) == 4


def test_feedback_empty_guild(client: TestClient) -> None:
    """피드백이 없는 길드는 빈 집계와 satisfaction=None 을 돌려준다."""
    token = client._auth_mod.create_jwt(  # type: ignore[attr-defined]
        "999", [{"id": "777", "name": "Empty", "icon": None, "owner": True}]
    )
    resp = client.get("/api/guilds/777/feedback", headers=_headers(token))
    assert resp.status_code == 200, resp.text
    body = resp.json()
    assert body["total"] == 0
    assert body["satisfaction"] is None
    assert body["by_command"] == []
    assert body["recent"] == []


def test_feedback_forbidden_for_non_member(client: TestClient) -> None:
    token = client._auth_mod.create_jwt(  # type: ignore[attr-defined]
        "999", [{"id": "456", "name": "Other", "icon": None, "owner": True}]
    )
    resp = client.get("/api/guilds/123/feedback", headers=_headers(token))
    assert resp.status_code == 403


# ---------------------------------------------------------------------------
# #79 admin guard
# ---------------------------------------------------------------------------


def test_put_config_requires_admin(client: TestClient) -> None:
    """관리자가 아닌 멤버는 설정 변경(PUT)에서 403 을 받는다."""
    auth_mod = client._auth_mod  # type: ignore[attr-defined]
    member_token = auth_mod.create_jwt(
        "1", [{"id": "123", "name": "T", "icon": None, "permissions": "0", "owner": False}]
    )
    resp = client.put(
        "/api/guilds/123/config",
        headers=_headers(member_token),
        json={"summary_limit": 20},
    )
    assert resp.status_code == 403


def test_put_config_allows_admin(client: TestClient) -> None:
    """Administrator 비트(0x8)를 가진 멤버는 설정 변경이 허용된다."""
    auth_mod = client._auth_mod  # type: ignore[attr-defined]
    admin_token = auth_mod.create_jwt(
        "1", [{"id": "123", "name": "T", "icon": None, "permissions": str(0x8)}]
    )
    resp = client.put(
        "/api/guilds/123/config",
        headers=_headers(admin_token),
        json={"summary_limit": 20},
    )
    assert resp.status_code == 200, resp.text
    assert resp.json()["summary_limit"] == 20


def test_api_key_status_requires_admin(client: TestClient) -> None:
    auth_mod = client._auth_mod  # type: ignore[attr-defined]
    member_token = auth_mod.create_jwt(
        "1", [{"id": "123", "name": "T", "icon": None, "permissions": "0"}]
    )
    resp = client.get("/api/guilds/123/api-key", headers=_headers(member_token))
    assert resp.status_code == 403


def test_get_config_allows_plain_member(client: TestClient) -> None:
    """조회(GET config)는 관리자가 아니어도 멤버면 허용된다(백워드 호환)."""
    auth_mod = client._auth_mod  # type: ignore[attr-defined]
    member_token = auth_mod.create_jwt(
        "1", [{"id": "123", "name": "T", "icon": None, "permissions": "0"}]
    )
    resp = client.get("/api/guilds/123/config", headers=_headers(member_token))
    assert resp.status_code == 200, resp.text


# ---------------------------------------------------------------------------
# #85 refresh
# ---------------------------------------------------------------------------


def test_refresh_reissues_token_and_preserves_admin(client: TestClient) -> None:
    """유효 토큰으로 refresh 하면 새 토큰이 나오고 admin 플래그가 보존된다."""
    auth_mod = client._auth_mod  # type: ignore[attr-defined]
    token = auth_mod.create_jwt(
        "1", [{"id": "123", "name": "T", "icon": None, "permissions": str(0x8)}]
    )
    resp = client.post("/auth/refresh", headers=_headers(token))
    assert resp.status_code == 200, resp.text
    new_token = resp.json()["token"]
    assert isinstance(new_token, str) and new_token

    # 새 토큰으로도 관리자 전용 라우트가 통과해야 한다(admin 플래그 보존).
    resp2 = client.get("/api/guilds/123/api-key", headers=_headers(new_token))
    assert resp2.status_code == 200, resp2.text

    decoded = auth_mod.decode_jwt(new_token)
    assert decoded is not None
    assert decoded["guilds"][0]["admin"] is True


def test_refresh_rejects_invalid_token(client: TestClient) -> None:
    resp = client.post("/auth/refresh", headers=_headers("not-a-jwt"))
    assert resp.status_code == 401


def test_refresh_requires_bearer(client: TestClient) -> None:
    resp = client.post("/auth/refresh")
    assert resp.status_code == 401
