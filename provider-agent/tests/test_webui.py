"""로컬 웹 제어판 테스트(aiohttp 테스트 클라이언트 — 디스플레이 불필요)."""
from __future__ import annotations

import asyncio

import pytest
from aiohttp.test_utils import TestClient, TestServer

from provider_agent import webui
from provider_agent.config_file import load_config

KEY = "test-session-key"


@pytest.fixture(autouse=True)
def _reset(monkeypatch, tmp_path):
    from provider_agent import singleton

    monkeypatch.setenv("XDG_CONFIG_HOME", str(tmp_path))
    webui._state["agent"] = None
    webui._state["task"] = None
    webui._log_lines.clear()
    singleton.release()  # 락 보유 상태가 다음 테스트로 새지 않게
    yield
    singleton.release()


async def _client() -> TestClient:
    client = TestClient(TestServer(webui.build_app(KEY)))
    await client.start_server()
    return client


class FakeAgent:
    """실제 연결 없이 시작/중지 라이프사이클만 흉내(테스트용)."""

    def __init__(self, cfg):
        self._stop = asyncio.Event()
        self.processed = 3
        self.image_ready = False
        self.models = ["m"]

    async def run(self, install_signals=True):
        await self._stop.wait()
        return 0

    def request_stop(self):
        self._stop.set()

    def is_connected(self):
        return True


@pytest.mark.asyncio
async def test_index_and_auth():
    client = await _client()
    try:
        assert (await client.get("/")).status == 200
        assert (await client.get("/api/status")).status == 403  # 키 없음
        assert (await client.get("/api/status", headers={"X-Session": KEY})).status == 200
    finally:
        await client.close()


@pytest.mark.asyncio
async def test_mascot_served():
    client = await _client()
    try:
        r = await client.get("/mascot.png")  # 이미지는 인증 없이 제공(민감정보 아님)
        assert r.status == 200
        assert r.headers["Content-Type"] == "image/png"
        body = await r.read()
        assert body[:4] == b"\x89PNG"
    finally:
        await client.close()


@pytest.mark.asyncio
async def test_models_autodetected(monkeypatch):
    async def fake_detect():
        return ["llama3.1:8b", "gemma4"]

    monkeypatch.setattr(webui, "_detect_models", fake_detect)
    client = await _client()
    try:
        d = await (await client.get("/api/models", headers={"X-Session": KEY})).json()
        assert d["models"] == ["llama3.1:8b", "gemma4"]
    finally:
        await client.close()


@pytest.mark.asyncio
async def test_setup_uses_fixed_relay_and_model_list(monkeypatch):
    monkeypatch.setattr("provider_agent.service.install_service", lambda: "ok")
    client = await _client()
    try:
        r = await client.post(
            "/api/setup",
            headers={"X-Session": KEY},
            json={"token": "ABCDE-FGHIJ", "models": ["llama3.1:8b", "gemma4"], "enableImage": False, "installService": True},
        )
        d = await r.json()
        assert d["ok"] and d["serviceInstalled"]
        saved = load_config()
        assert saved["token"] == "ABCDE-FGHIJ"
        assert saved["relay_url"] == webui.DEFAULT_RELAY  # 유저가 안 친 고정 주소
        assert saved["models"] == ["llama3.1:8b", "gemma4"]
    finally:
        await client.close()


@pytest.mark.asyncio
async def test_start_requires_saved_token():
    client = await _client()
    try:
        d = await (await client.post("/api/start", headers={"X-Session": KEY})).json()
        assert d["ok"] is False  # 저장된 토큰 없음
    finally:
        await client.close()


def test_connect_base_derives_https():
    assert webui._connect_base("wss://discord-ai.yeon.world/agent") == "https://discord-ai.yeon.world"
    assert webui._connect_base("ws://localhost:8080/agent") == "http://localhost:8080"


def test_default_relay_env_override(monkeypatch):
    monkeypatch.delenv("RELAY_URL", raising=False)
    assert webui._default_relay() == webui.DEFAULT_RELAY  # 기본은 prod 고정
    monkeypatch.setenv("RELAY_URL", "ws://localhost:8085/agent")
    assert webui._default_relay() == "ws://localhost:8085/agent"  # 로컬 개발 우회


def test_webview_available_browser_optout(monkeypatch):
    monkeypatch.setenv("AGENT_GUI_BROWSER", "1")
    assert webui._webview_available() is False


@pytest.mark.asyncio
async def test_connect_open_opens_system_browser(monkeypatch):
    monkeypatch.setenv("AGENT_CONNECT_ENABLED", "1")
    opened = {}
    monkeypatch.setattr("webbrowser.open", lambda u: opened.setdefault("url", u))
    client = await _client()
    try:
        r = await client.post("/api/connect-open", headers={"X-Session": KEY}, json={"origin": "http://127.0.0.1:55555"})
        d = await r.json()
        assert d["ok"] is True
        # 시스템 브라우저를 중앙 서버 connect 로 열되, cb 는 로컬 콜백·state 는 세션키
        assert opened["url"].startswith("https://discord-ai.yeon.world/provider/connect?")
        assert "127.0.0.1%3A55555%2Fconnect%2Fcallback" in opened["url"]
    finally:
        await client.close()


@pytest.mark.asyncio
async def test_connect_open_rejects_non_localhost(monkeypatch):
    monkeypatch.setenv("AGENT_CONNECT_ENABLED", "1")
    monkeypatch.setattr("webbrowser.open", lambda u: (_ for _ in ()).throw(AssertionError("열면 안 됨")))
    client = await _client()
    try:
        r = await client.post("/api/connect-open", headers={"X-Session": KEY}, json={"origin": "https://evil.com"})
        d = await r.json()
        assert d["ok"] is False  # localhost 아니면 거부(브라우저 안 엶)
        assert (await client.post("/api/connect-open", json={"origin": "http://127.0.0.1:1"})).status == 403  # 키 없음
    finally:
        await client.close()


@pytest.mark.asyncio
async def test_connect_open_disabled_without_env(monkeypatch):
    monkeypatch.delenv("AGENT_CONNECT_ENABLED", raising=False)
    client = await _client()
    try:
        d = await (await client.post("/api/connect-open", headers={"X-Session": KEY}, json={"origin": "http://127.0.0.1:1"})).json()
        assert d["ok"] is False  # 미활성이면 안 엶
    finally:
        await client.close()


@pytest.mark.asyncio
async def test_connect_callback_shows_friendly_error():
    client = await _client()
    try:
        # 토큰 대신 error=pending → 400 아니라 친절한 안내 페이지(200), 토큰 저장 안 함
        r = await client.get("/connect/callback", params={"error": "pending", "state": KEY})
        assert r.status == 200
        assert "승인을 기다리는" in await r.text()
        assert not load_config().get("token")
    finally:
        await client.close()


@pytest.mark.asyncio
async def test_connect_callback_rejects_bad_state():
    client = await _client()
    try:
        r = await client.get("/connect/callback", params={"token": "T", "state": "wrong"})
        assert r.status == 403
    finally:
        await client.close()


@pytest.mark.asyncio
async def test_connect_callback_saves_token_and_autostarts(monkeypatch):
    monkeypatch.setattr("provider_agent.agent.ProviderAgent", FakeAgent)
    client = await _client()
    try:
        r = await client.get("/connect/callback", params={"token": "GOT-TOKEN", "state": KEY})
        assert r.status == 200
        assert load_config()["token"] == "GOT-TOKEN"  # 콜백이 토큰을 저장
        await asyncio.sleep(0.05)
        st = await (await client.get("/api/status", headers={"X-Session": KEY})).json()
        assert st["running"] is True  # 토큰 받은 직후 자동 연결(추가 클릭 불필요)
        await client.post("/api/stop", headers={"X-Session": KEY})
    finally:
        await client.close()


@pytest.mark.asyncio
async def test_start_blocked_when_another_instance_holds_lock(monkeypatch):
    from provider_agent import singleton

    monkeypatch.setattr("provider_agent.agent.ProviderAgent", FakeAgent)
    # 다른 인스턴스가 락을 잡고 있는 상황을 시뮬레이션
    monkeypatch.setattr(singleton, "acquire", lambda: False)
    client = await _client()
    try:
        await client.post("/api/setup", headers={"X-Session": KEY}, json={"token": "T", "models": ["m"]})
        d = await (await client.post("/api/start", headers={"X-Session": KEY})).json()
        assert d["ok"] is False and "인스턴스" in d["error"]  # 중복 연결 차단
        st = await (await client.get("/api/status", headers={"X-Session": KEY})).json()
        assert st["running"] is False
    finally:
        await client.close()


@pytest.mark.asyncio
async def test_start_stop_lifecycle(monkeypatch):
    monkeypatch.setattr("provider_agent.service.install_service", lambda: "ok")
    monkeypatch.setattr("provider_agent.agent.ProviderAgent", FakeAgent)
    client = await _client()
    try:
        await client.post("/api/setup", headers={"X-Session": KEY}, json={"token": "T", "models": ["m"]})
        s = await (await client.post("/api/start", headers={"X-Session": KEY})).json()
        assert s["ok"] is True
        await asyncio.sleep(0.05)
        st = await (await client.get("/api/status", headers={"X-Session": KEY})).json()
        assert st["running"] is True and st["connected"] is True and st["processed"] == 3
        await client.post("/api/stop", headers={"X-Session": KEY})
        st2 = await (await client.get("/api/status", headers={"X-Session": KEY})).json()
        assert st2["running"] is False
    finally:
        await client.close()


@pytest.mark.asyncio
async def test_install_info_endpoint(monkeypatch):
    monkeypatch.setattr(
        "provider_agent.installer.install_info",
        lambda: {"platform": "mac", "supported": True, "installed": False, "label": "응용 프로그램에 추가하기"},
    )
    client = await _client()
    try:
        assert (await client.get("/api/install-info")).status == 403  # 키 없음
        d = await (await client.get("/api/install-info", headers={"X-Session": KEY})).json()
        assert d["platform"] == "mac" and d["supported"] is True
    finally:
        await client.close()


@pytest.mark.asyncio
async def test_install_endpoint_delegates(monkeypatch):
    monkeypatch.setattr(
        "provider_agent.installer.install_app",
        lambda: {"ok": True, "message": "응용 프로그램에 추가했어요."},
    )
    client = await _client()
    try:
        assert (await client.post("/api/install")).status == 403  # 키 없음
        d = await (await client.post("/api/install", headers={"X-Session": KEY})).json()
        assert d["ok"] is True and "추가" in d["message"]
    finally:
        await client.close()


@pytest.mark.asyncio
async def test_update_info_endpoint(monkeypatch):
    monkeypatch.setattr(
        "provider_agent.updater.check",
        lambda: {"current": "0.19.0", "latest": "0.20.0", "outdated": True, "supported": True, "error": None},
    )
    client = await _client()
    try:
        assert (await client.get("/api/update-info")).status == 403  # 키 없음
        d = await (await client.get("/api/update-info", headers={"X-Session": KEY})).json()
        assert d["outdated"] is True and d["latest"] == "0.20.0"
        assert "autoUpdate" in d  # 토글 현재값 포함
    finally:
        await client.close()


@pytest.mark.asyncio
async def test_auto_update_toggle_persists(monkeypatch):
    client = await _client()
    try:
        await client.post("/api/auto-update", headers={"X-Session": KEY}, json={"autoUpdate": False})
        assert load_config().get("auto_update") is False  # 저장됨
        await client.post("/api/auto-update", headers={"X-Session": KEY}, json={"autoUpdate": True})
        assert load_config().get("auto_update") is True
    finally:
        await client.close()


@pytest.mark.asyncio
async def test_update_apply_runs_in_background_and_schedules_restart(monkeypatch):
    monkeypatch.setattr("provider_agent.updater.is_updating", lambda: False)
    monkeypatch.setattr(
        "provider_agent.updater.apply_update",
        lambda: {"ok": True, "restarting": True, "message": "업데이트 중"},
    )
    exited = {}
    monkeypatch.setattr("provider_agent.webui._schedule_exit", lambda *a, **k: exited.setdefault("called", True))
    client = await _client()
    try:
        d = await (await client.post("/api/update", headers={"X-Session": KEY})).json()
        assert d["ok"] is True and d["started"] is True  # 즉시 시작 응답(다운로드는 백그라운드)
        await asyncio.sleep(0.1)  # 워커 스레드가 apply_update 를 끝낼 시간
        assert exited.get("called") is True  # 교체 위해 종료 예약됨
    finally:
        await client.close()


@pytest.mark.asyncio
async def test_update_progress_endpoint(monkeypatch):
    monkeypatch.setattr(
        "provider_agent.updater.update_progress",
        lambda: {"phase": "downloading", "downloaded": 5, "total": 10, "percent": 50, "message": "내려받는 중", "error": None},
    )
    client = await _client()
    try:
        assert (await client.get("/api/update-progress")).status == 403  # 키 없음
        d = await (await client.get("/api/update-progress", headers={"X-Session": KEY})).json()
        assert d["phase"] == "downloading" and d["percent"] == 50
    finally:
        await client.close()


@pytest.mark.asyncio
async def test_servers_lists_saved_when_not_running(monkeypatch):
    # 실행 중이 아니면 저장된 연결 목록을 connected=False 로 보여준다.
    from provider_agent.config_file import add_connection

    add_connection("TA", guild_id=100, guild_name="서버A")
    add_connection("TB", guild_id=200, guild_name="서버B")
    client = await _client()
    try:
        assert (await client.get("/api/servers")).status == 403  # 키 없음
        d = await (await client.get("/api/servers", headers={"X-Session": KEY})).json()
        names = {s["guildName"] for s in d["servers"]}
        assert names == {"서버A", "서버B"}
        assert all(s["connected"] is False for s in d["servers"])
    finally:
        await client.close()


@pytest.mark.asyncio
async def test_server_remove_deletes_saved(monkeypatch):
    from provider_agent.config_file import add_connection, load_connections

    add_connection("TA", guild_id=100, guild_name="서버A")
    add_connection("TB", guild_id=200, guild_name="서버B")
    client = await _client()
    try:
        await client.post("/api/server-remove", headers={"X-Session": KEY}, json={"guildId": 100})
        left = [c["guild_id"] for c in load_connections()]
        assert left == [200]  # 길드 100 해제됨
    finally:
        await client.close()


def test_auto_update_once_applies_when_outdated(monkeypatch):
    # 실행 중 주기 검사: 토글 ON + 구버전 + 지원이면 받아 적용하고 종료 예약(껐다 켜지 않아도 적용).
    monkeypatch.setattr(webui, "load_config", lambda: {"auto_update": True})
    monkeypatch.setattr("provider_agent.updater.is_updating", lambda: False)
    monkeypatch.setattr(
        "provider_agent.updater.check",
        lambda: {"current": "0.19.0", "latest": "0.20.0", "outdated": True, "supported": True, "error": None},
    )
    monkeypatch.setattr("provider_agent.updater.apply_update", lambda: {"ok": True, "restarting": True})
    exited = {}
    monkeypatch.setattr(webui, "_schedule_exit", lambda *a, **k: exited.setdefault("called", True))
    assert webui._auto_update_once() is True
    assert exited.get("called") is True


def test_auto_update_once_skips_when_latest(monkeypatch):
    monkeypatch.setattr(webui, "load_config", lambda: {"auto_update": True})
    monkeypatch.setattr("provider_agent.updater.is_updating", lambda: False)
    monkeypatch.setattr(
        "provider_agent.updater.check",
        lambda: {"current": "0.20.0", "latest": "0.20.0", "outdated": False, "supported": True, "error": None},
    )
    called = {}
    monkeypatch.setattr("provider_agent.updater.apply_update", lambda: called.setdefault("apply", True) or {})
    assert webui._auto_update_once() is False
    assert "apply" not in called  # 최신이면 교체 시도 안 함


def test_auto_update_once_skips_when_toggle_off(monkeypatch):
    monkeypatch.setattr(webui, "load_config", lambda: {"auto_update": False})
    called = {}
    monkeypatch.setattr("provider_agent.updater.check", lambda: called.setdefault("checked", True) or {})
    assert webui._auto_update_once() is False
    assert "checked" not in called  # 토글 OFF 면 검사조차 안 함


def test_brand_icon_png_returns_png_bytes():
    from provider_agent.webui import _brand_icon_png

    png = _brand_icon_png()
    # PIL 있으면 PNG 시그니처, 없으면 None(둘 다 허용 — dock 아이콘은 선택적)
    assert png is None or png[:8] == b"\x89PNG\r\n\x1a\n"


def test_set_macos_app_identity_is_safe_everywhere():
    from provider_agent.webui import _set_macos_app_identity

    # 비-macOS 는 no-op, macOS 면 dock 이름/아이콘 설정 — 어느 쪽이든 예외 없이 끝나야 한다.
    _set_macos_app_identity("냥시스턴트")
