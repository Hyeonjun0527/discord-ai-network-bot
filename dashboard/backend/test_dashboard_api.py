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
            latency_ms INTEGER, error TEXT,
            prompt_tokens INTEGER NOT NULL DEFAULT 0,
            completion_tokens INTEGER NOT NULL DEFAULT 0,
            created_at TEXT NOT NULL
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

    # 사용 로그 샘플 (#83: 명령별 평균 응답시간 집계 검증용).
    #   ask:       ok 100 / ok 300  → avg 200
    #   summarize: ok 1000          → avg 1000
    #   error 행과 latency NULL 행은 평균에서 제외돼야 한다.
    # 토큰 컬럼(prompt_tokens, completion_tokens)도 함께 넣어 #82 집계를 검증한다.
    #   prompt 합계 = 10+20+30+0+0 = 60, completion 합계 = 5+15+25+0+0 = 45, total 105
    usage_rows = [
        (123, 1, 11, "ask", "ok", 100, None, 10, 5, "2026-05-01T00:00:00+00:00"),
        (123, 1, 11, "ask", "ok", 300, None, 20, 15, "2026-05-02T00:00:00+00:00"),
        (123, 1, 11, "summarize", "ok", 1000, None, 30, 25, "2026-05-03T00:00:00+00:00"),
        (123, 1, 11, "ask", "error", None, "boom", 0, 0, "2026-05-04T00:00:00+00:00"),
        (123, 1, 11, "ask", "ok", None, None, 0, 0, "2026-05-05T00:00:00+00:00"),
    ]
    conn.executemany(
        "INSERT INTO usage_log "
        "(guild_id, channel_id, user_id, command, status, latency_ms, error, "
        "prompt_tokens, completion_tokens, created_at) "
        "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
        usage_rows,
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
# #83 stats: 명령별 평균 응답시간
# ---------------------------------------------------------------------------


def test_stats_latency_by_command(client: TestClient) -> None:
    """명령별 평균 응답시간(latency_by_command)이 정상 행만으로 집계된다 (#83)."""
    token = _admin_token(client)
    resp = client.get("/api/guilds/123/stats", headers=_headers(token))
    assert resp.status_code == 200, resp.text
    body = resp.json()

    # 기존 응답 필드는 그대로 보존되어야 한다(추가만, 백워드 호환).
    assert "by_command" in body
    assert "avg_latency_ms" in body
    assert "error_rate" in body
    assert "daily" in body

    # 새 필드: 명령별 평균. ask=200(=(100+300)/2), summarize=1000.
    # error 행과 latency NULL 행은 평균에서 제외된다.
    assert "latency_by_command" in body
    by_cmd = {r["command"]: r["avg_latency_ms"] for r in body["latency_by_command"]}
    assert by_cmd == {"ask": 200, "summarize": 1000}
    # 느린 명령부터 내림차순 정렬
    assert body["latency_by_command"][0]["command"] == "summarize"


def test_stats_latency_empty_guild(client: TestClient) -> None:
    """사용 로그가 없는 길드는 latency_by_command 가 빈 리스트다 (#83)."""
    token = client._auth_mod.create_jwt(  # type: ignore[attr-defined]
        "999", [{"id": "777", "name": "Empty", "icon": None, "owner": True}]
    )
    resp = client.get("/api/guilds/777/stats", headers=_headers(token))
    assert resp.status_code == 200, resp.text
    assert resp.json()["latency_by_command"] == []


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


# ---------------------------------------------------------------------------
# #34 cookie 인증 (httpOnly 쿠키 발급/사용)
# ---------------------------------------------------------------------------


def test_protected_route_accepts_cookie(client: TestClient) -> None:
    """Authorization 헤더 없이 httpOnly 쿠키만으로도 보호 라우트가 통과한다 (#34)."""
    token = _admin_token(client)
    # 쿠키 이름은 auth.JWT_COOKIE_NAME 과 동일해야 한다.
    cookie_name = client._auth_mod.JWT_COOKIE_NAME  # type: ignore[attr-defined]
    client.cookies.set(cookie_name, token)
    try:
        resp = client.get("/api/guilds/123/config")
        assert resp.status_code == 200, resp.text
    finally:
        client.cookies.clear()


def test_refresh_sets_cookie(client: TestClient) -> None:
    """refresh 응답이 httpOnly 쿠키를 Set-Cookie 로 내려준다 (#34)."""
    token = _admin_token(client)
    resp = client.post("/auth/refresh", headers=_headers(token))
    assert resp.status_code == 200, resp.text
    cookie_name = client._auth_mod.JWT_COOKIE_NAME  # type: ignore[attr-defined]
    set_cookie = resp.headers.get("set-cookie", "")
    assert cookie_name in set_cookie
    assert "httponly" in set_cookie.lower()


# ---------------------------------------------------------------------------
# #44 로그아웃 시 토큰 무효화(블랙리스트)
# ---------------------------------------------------------------------------


def test_logout_revokes_token(client: TestClient) -> None:
    """로그아웃하면 같은 토큰이 즉시 무효화되어 보호 라우트가 401 을 받는다 (#44)."""
    auth_mod = client._auth_mod  # type: ignore[attr-defined]
    token = auth_mod.create_jwt(
        "1", [{"id": "123", "name": "T", "icon": None, "permissions": str(0x8)}]
    )
    # 로그아웃 전에는 통과
    assert client.get("/api/guilds/123/config", headers=_headers(token)).status_code == 200

    logout = client.post("/auth/logout", headers=_headers(token))
    assert logout.status_code == 200, logout.text
    assert logout.json()["logged_out"] is True

    # 로그아웃 후에는 만료 전이라도 무효화되어 401
    after = client.get("/api/guilds/123/config", headers=_headers(token))
    assert after.status_code == 401


def test_logout_clears_cookie(client: TestClient) -> None:
    """로그아웃 응답이 인증 쿠키를 삭제(만료)한다 (#34)."""
    token = _admin_token(client)
    resp = client.post("/auth/logout", headers=_headers(token))
    assert resp.status_code == 200
    cookie_name = client._auth_mod.JWT_COOKIE_NAME  # type: ignore[attr-defined]
    set_cookie = resp.headers.get("set-cookie", "")
    assert cookie_name in set_cookie


def test_logout_is_idempotent_without_token(client: TestClient) -> None:
    """토큰 없이 로그아웃해도 멱등하게 성공한다 (#34)."""
    resp = client.post("/auth/logout")
    assert resp.status_code == 200
    assert resp.json()["logged_out"] is True


# ---------------------------------------------------------------------------
# #35 JWT 서명 키 분리(JWT_SECRET_KEY) — SECRET_KEY 와 독립
# ---------------------------------------------------------------------------


def test_jwt_secret_key_overrides_secret_key(
    tmp_path: Path, monkeypatch: pytest.MonkeyPatch
) -> None:
    """JWT_SECRET_KEY 가 설정되면 그것으로 서명/검증하고, SECRET_KEY 와 분리된다 (#35)."""
    monkeypatch.setenv("SECRET_KEY", "fernet-key-not-for-jwt")
    monkeypatch.setenv("JWT_SECRET_KEY", "dedicated-jwt-signing-key")
    import importlib

    from dashboard.backend import auth as auth_mod

    importlib.reload(auth_mod)
    try:
        token = auth_mod.create_jwt("1", [])
        # 전용 키로 디코드 성공
        assert auth_mod.decode_jwt(token) is not None
        # SECRET_KEY(=Fernet 키)로는 검증되지 않아야 한다(키 분리 증명).
        import jwt as pyjwt

        try:
            pyjwt.decode(token, "fernet-key-not-for-jwt", algorithms=["HS256"])
            assert False, "SECRET_KEY 로 JWT 가 검증되면 안 된다"
        except pyjwt.PyJWTError:
            pass
    finally:
        monkeypatch.delenv("JWT_SECRET_KEY", raising=False)
        importlib.reload(auth_mod)


# ---------------------------------------------------------------------------
# #97 운영 환경에서 기본 JWT 서명 키 사용 거부 (보안 가드)
# ---------------------------------------------------------------------------


def test_secret_key_rejects_default_in_production(
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    """운영 환경(production)에서 SECRET_KEY/JWT_SECRET_KEY 누락 시 기본 키 사용을 거부한다 (#97)."""
    monkeypatch.delenv("JWT_SECRET_KEY", raising=False)
    monkeypatch.delenv("SECRET_KEY", raising=False)
    monkeypatch.setenv("ENVIRONMENT", "production")
    import importlib

    from dashboard.backend import auth as auth_mod

    importlib.reload(auth_mod)
    try:
        with pytest.raises(RuntimeError):
            auth_mod._secret_key()
        # JWT 발급도 같은 가드에 의해 거부되어야 한다(인증 우회 차단).
        with pytest.raises(RuntimeError):
            auth_mod.create_jwt("1", [])
    finally:
        monkeypatch.delenv("ENVIRONMENT", raising=False)
        importlib.reload(auth_mod)


def test_secret_key_default_allowed_outside_production(
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    """비운영(local/dev) 환경에서는 기본 키 폴백이 경고만 하고 동작은 유지된다 (#97, 백워드 호환)."""
    monkeypatch.delenv("JWT_SECRET_KEY", raising=False)
    monkeypatch.delenv("SECRET_KEY", raising=False)
    monkeypatch.delenv("ENVIRONMENT", raising=False)
    monkeypatch.delenv("APP_ENV", raising=False)
    import importlib

    from dashboard.backend import auth as auth_mod

    importlib.reload(auth_mod)
    try:
        # 운영이 아니므로 raise 없이 기본 키를 돌려주고 토큰 발급/검증이 가능하다.
        assert auth_mod._secret_key() == "change-me-in-production"
        token = auth_mod.create_jwt("1", [])
        assert auth_mod.decode_jwt(token) is not None
    finally:
        importlib.reload(auth_mod)


# ---------------------------------------------------------------------------
# #80 auto_summary_interval 저장/검증
# ---------------------------------------------------------------------------


def test_update_auto_summary_interval(client: TestClient) -> None:
    """auto_summary_interval 이 저장되고 GET 으로 다시 읽힌다 (#80)."""
    token = _admin_token(client)
    resp = client.put(
        "/api/guilds/123/config",
        headers=_headers(token),
        json={"auto_summary_interval": 30},
    )
    assert resp.status_code == 200, resp.text
    assert resp.json()["auto_summary_interval"] == 30

    got = client.get("/api/guilds/123/config", headers=_headers(token))
    assert got.json()["auto_summary_interval"] == 30


def test_auto_summary_interval_rejects_too_small(client: TestClient) -> None:
    """5분 미만은 400 으로 거절된다 (#80)."""
    token = _admin_token(client)
    resp = client.put(
        "/api/guilds/123/config",
        headers=_headers(token),
        json={"auto_summary_interval": 3},
    )
    assert resp.status_code == 400


def test_auto_summary_interval_none_disables(client: TestClient) -> None:
    """None 을 명시 전송하면 자동 요약 비활성화로 저장된다 (#80)."""
    token = _admin_token(client)
    # 먼저 켜둔 뒤
    client.put(
        "/api/guilds/123/config",
        headers=_headers(token),
        json={"auto_summary_interval": 30},
    )
    # None 으로 끈다
    resp = client.put(
        "/api/guilds/123/config",
        headers=_headers(token),
        json={"auto_summary_interval": None},
    )
    assert resp.status_code == 200, resp.text
    assert resp.json()["auto_summary_interval"] is None


def test_config_update_without_interval_preserves_existing(client: TestClient) -> None:
    """interval 필드를 보내지 않으면 기존 값이 보존된다(부분 갱신) (#80)."""
    token = _admin_token(client)
    client.put(
        "/api/guilds/123/config",
        headers=_headers(token),
        json={"auto_summary_interval": 45},
    )
    # interval 미포함으로 다른 필드만 갱신
    resp = client.put(
        "/api/guilds/123/config",
        headers=_headers(token),
        json={"summary_limit": 20},
    )
    assert resp.status_code == 200, resp.text
    assert resp.json()["auto_summary_interval"] == 45


# ---------------------------------------------------------------------------
# #82 토큰 사용량 집계
# ---------------------------------------------------------------------------


def test_stats_token_aggregation(client: TestClient) -> None:
    """stats 가 prompt/completion/total 토큰 합계를 반환한다 (#82)."""
    token = _admin_token(client)
    resp = client.get("/api/guilds/123/stats", headers=_headers(token))
    assert resp.status_code == 200, resp.text
    tokens = resp.json()["tokens"]
    assert tokens["prompt"] == 60
    assert tokens["completion"] == 45
    assert tokens["total"] == 105


def test_stats_token_aggregation_empty_guild(client: TestClient) -> None:
    """사용 로그가 없는 길드는 토큰 합계가 모두 0 이다 (#82)."""
    token = client._auth_mod.create_jwt(  # type: ignore[attr-defined]
        "999", [{"id": "777", "name": "Empty", "icon": None, "owner": True}]
    )
    resp = client.get("/api/guilds/777/stats", headers=_headers(token))
    assert resp.json()["tokens"] == {"prompt": 0, "completion": 0, "total": 0}


# ---------------------------------------------------------------------------
# #100 레이트리밋 저장소 누수 회수
# ---------------------------------------------------------------------------


def test_prune_rate_limit_store_drops_stale_buckets() -> None:
    """윈도 밖 타임스탬프만 남은 IP 버킷은 prune 으로 제거된다 (#100)."""
    from dashboard.backend import main as main_mod

    main_mod._rate_limit_store.clear()
    now = 1000.0
    window = main_mod._RATE_LIMIT_WINDOW
    # stale: 마지막 요청이 윈도보다 한참 전 → 제거 대상
    main_mod._rate_limit_store["stale-ip"] = [now - window - 5]
    # fresh: 윈도 안 타임스탬프 보유 → 보존
    main_mod._rate_limit_store["fresh-ip"] = [now - 1]
    # empty: 빈 버킷 → 제거 대상
    main_mod._rate_limit_store["empty-ip"] = []

    main_mod._prune_rate_limit_store(now)

    assert "stale-ip" not in main_mod._rate_limit_store
    assert "empty-ip" not in main_mod._rate_limit_store
    assert "fresh-ip" in main_mod._rate_limit_store
    main_mod._rate_limit_store.clear()


def test_check_rate_limit_periodically_prunes(monkeypatch: pytest.MonkeyPatch) -> None:
    """_check_rate_limit 이 주기적으로 오래된 IP 버킷을 일괄 회수한다 (#100)."""
    from types import SimpleNamespace

    from dashboard.backend import main as main_mod

    main_mod._rate_limit_store.clear()
    main_mod._rate_limit_last_prune = 0.0

    # 다시 등장하지 않을 오래된 IP 의 버킷을 미리 심어둔다.
    fake_now = 10_000.0
    main_mod._rate_limit_store["ghost-ip"] = [fake_now - main_mod._RATE_LIMIT_WINDOW - 100]

    monkeypatch.setattr(main_mod.time, "monotonic", lambda: fake_now)
    req = SimpleNamespace(
        client=SimpleNamespace(host="1.2.3.4"),
        headers={},
    )
    # 다른 IP 의 요청 한 번으로 전역 prune 이 트리거되어 ghost-ip 가 사라진다.
    main_mod._check_rate_limit(req)  # type: ignore[arg-type]

    assert "ghost-ip" not in main_mod._rate_limit_store
    assert "1.2.3.4" in main_mod._rate_limit_store
    main_mod._rate_limit_store.clear()


# ---------------------------------------------------------------------------
# #101 신뢰 프록시 헤더 기반 클라이언트 IP 해석
# ---------------------------------------------------------------------------


def test_client_ip_ignores_forwarded_when_not_trusted(
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    """opt-in 안 했으면 X-Forwarded-For 를 무시하고 peer IP 만 쓴다(스푸핑 차단) (#101)."""
    from types import SimpleNamespace

    from dashboard.backend import main as main_mod

    monkeypatch.setattr(main_mod, "_TRUST_PROXY_HEADERS", False)
    req = SimpleNamespace(
        client=SimpleNamespace(host="10.0.0.1"),
        headers={"x-forwarded-for": "9.9.9.9"},
    )
    assert main_mod._client_ip(req) == "10.0.0.1"  # type: ignore[arg-type]


def test_client_ip_uses_first_forwarded_when_trusted(
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    """TRUST_PROXY_HEADERS opt-in 시 X-Forwarded-For 의 첫 클라이언트 IP 를 쓴다 (#101)."""
    from types import SimpleNamespace

    from dashboard.backend import main as main_mod

    monkeypatch.setattr(main_mod, "_TRUST_PROXY_HEADERS", True)
    req = SimpleNamespace(
        client=SimpleNamespace(host="10.0.0.1"),  # 프록시 IP
        headers={"x-forwarded-for": "203.0.113.7, 10.0.0.1"},
    )
    # 가장 바깥(원 클라이언트) 항목이 키가 되어 사용자별로 버킷이 분리된다.
    assert main_mod._client_ip(req) == "203.0.113.7"  # type: ignore[arg-type]


def test_client_ip_falls_back_to_peer_without_forwarded(
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    """opt-in 했어도 X-Forwarded-For 가 없으면 peer IP 로 폴백한다 (#101)."""
    from types import SimpleNamespace

    from dashboard.backend import main as main_mod

    monkeypatch.setattr(main_mod, "_TRUST_PROXY_HEADERS", True)
    req = SimpleNamespace(client=SimpleNamespace(host="10.0.0.1"), headers={})
    assert main_mod._client_ip(req) == "10.0.0.1"  # type: ignore[arg-type]


# ---------------------------------------------------------------------------
# #98 인라인 폴백 PUT 이 daily_token_budget 컬럼을 보존(스키마 drift 방지)
# ---------------------------------------------------------------------------


def test_inline_fallback_put_includes_daily_token_budget(
    tmp_path: Path, monkeypatch: pytest.MonkeyPatch
) -> None:
    """storage import 불가(폴백) 시 PUT 신규행이 daily_token_budget 컬럼을 포함한다 (#98).

    봇의 ConfigStore._upsert(14컬럼)와 컬럼 집합을 일치시켜, 폴백 경로가
    daily_token_budget 을 SELECT/INSERT 목록에서 누락하지 않음을 검증한다.
    """
    import importlib

    # 봇 스키마(daily_token_budget 포함)로 DB 를 만든다.
    db_file = tmp_path / "fallback.db"
    conn = sqlite3.connect(db_file)
    conn.executescript(
        """
        CREATE TABLE guild_config (
            guild_id INTEGER PRIMARY KEY, model TEXT NOT NULL,
            summary_limit INTEGER NOT NULL, language TEXT NOT NULL,
            admin_role_id INTEGER, provider TEXT NOT NULL DEFAULT 'ollama',
            api_key_encrypted TEXT, auto_summary_interval INTEGER, persona TEXT,
            custom_summarize_prompt TEXT, custom_ask_prompt TEXT,
            allowed_role_id INTEGER, daily_token_budget INTEGER, updated_at TEXT NOT NULL
        );
        """
    )
    conn.commit()
    conn.close()

    monkeypatch.setenv("DATABASE_URL", f"sqlite:///{db_file}")
    monkeypatch.setenv("SECRET_KEY", "test-secret-key")

    from dashboard.backend import auth as auth_mod
    from dashboard.backend import main as main_mod

    importlib.reload(auth_mod)
    importlib.reload(main_mod)
    # storage import 실패(폴백) 상황을 강제한다.
    monkeypatch.setattr(main_mod, "_STORAGE_AVAILABLE", False)
    monkeypatch.setattr(main_mod, "_config_store", None)

    with TestClient(main_mod.app) as c:
        token = auth_mod.create_jwt(
            "1", [{"id": "555", "name": "T", "icon": None, "permissions": str(0x8)}]
        )
        resp = c.put(
            "/api/guilds/555/config",
            headers={"Authorization": f"Bearer {token}"},
            json={"summary_limit": 20},
        )
        assert resp.status_code == 200, resp.text

    # 신규행이 INSERT 되었고, daily_token_budget 컬럼이 NULL 로 정상 존재한다.
    conn = sqlite3.connect(db_file)
    conn.row_factory = sqlite3.Row
    row = conn.execute(
        "SELECT summary_limit, daily_token_budget FROM guild_config WHERE guild_id = 555"
    ).fetchone()
    conn.close()
    assert row is not None
    assert row["summary_limit"] == 20
    assert row["daily_token_budget"] is None
