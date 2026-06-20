"""로컬 웹 제어판 테스트(aiohttp 테스트 클라이언트 — 디스플레이 불필요)."""
from __future__ import annotations

import asyncio

import pytest
from aiohttp.test_utils import TestClient, TestServer

from provider_agent import webui
from provider_agent.config_file import load_config, persist_partial
from provider_agent.constants import DEFAULT_TEXT_MODEL

KEY = "test-session-key"


@pytest.fixture(autouse=True)
def _reset(monkeypatch, tmp_path):
    from provider_agent import singleton

    lock = {"held": False}

    def fake_acquire():
        lock["held"] = True
        return True

    def fake_release():
        lock["held"] = False

    monkeypatch.setenv("XDG_CONFIG_HOME", str(tmp_path))
    # 개발 머신에 실제 Nexa 앱/서비스가 떠 있어도 테스트는 별도 락 포트를 써 자동연결 검증이 흔들리지 않게 한다.
    monkeypatch.setenv("NEXA_LOCK_PORT", "48669")
    monkeypatch.delenv("AGENT_CONNECT_ENABLED", raising=False)
    monkeypatch.setattr(singleton, "acquire", fake_acquire)
    monkeypatch.setattr(singleton, "release", fake_release)
    monkeypatch.setattr(singleton, "held_by_other", lambda: False)
    webui._state["agent"] = None
    webui._state["task"] = None
    webui._log_lines.clear()
    # 테스트는 실네트워크 금지: connectEnabled 캐시를 매번 비활성으로 리셋(probe 데몬은 GUI 에서만 시작).
    webui._connect_cache["enabled"] = False
    singleton.release()  # 락 보유 상태가 다음 테스트로 새지 않게
    yield
    singleton.release()


async def _client() -> TestClient:
    client = TestClient(TestServer(webui.build_app(KEY)))
    await client.start_server()
    return client


def _desktop_shapes() -> dict:
    import json
    import pathlib

    p = pathlib.Path(__file__).resolve().parents[2] / "prototypes" / "desktop" / "contract-shapes.json"
    return json.loads(p.read_text(encoding="utf-8"))


@pytest.mark.asyncio
async def test_desktop_shapes_passthrough() -> None:
    """실 webui passthrough 응답이 프로토타입 mock 이 선언한 필드를 모두 제공한다(real ⊇ mock, 필드명 드리프트 차단)."""
    shapes = _desktop_shapes()["passthrough"]
    client = await _client()
    try:
        for ep, expected in shapes.items():
            r = await (await client.get(ep, headers={"X-Session": KEY})).json()
            assert isinstance(r, dict), f"{ep}: 응답이 객체가 아님"
            missing = [k for k in expected if k not in r]
            assert not missing, f"{ep}: webui 응답에 mock 선언 필드 누락 {missing} — 프로토타입↔실구현 shape 드리프트!"
    finally:
        await client.close()


@pytest.mark.asyncio
async def test_desktop_shapes_consumed() -> None:
    """adapter 변환이 읽는 raw 필드를 실 webui 가 제공한다(servers 항목·models 객체)."""
    from provider_agent.config_file import add_connection

    add_connection("TS", guild_id=100, guild_name="S")
    shapes = _desktop_shapes()["consumed"]
    client = await _client()
    try:
        d = await (await client.get("/api/servers", headers={"X-Session": KEY})).json()
        assert d["servers"], "서버 목록이 비어 검증 불가"
        item = d["servers"][0]
        miss = [k for k in shapes["/api/servers"]["array"] if k not in item]
        assert not miss, f"/api/servers 항목에 adapter 가 읽는 필드 누락 {miss} — shape 드리프트!"
        m = await (await client.get("/api/models", headers={"X-Session": KEY})).json()
        miss2 = [k for k in shapes["/api/models"]["object"] if k not in m]
        assert not miss2, f"/api/models 에 adapter 가 읽는 필드 누락 {miss2} — shape 드리프트!"
    finally:
        await client.close()


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
async def test_app_icon_served():
    client = await _client()
    try:
        r = await client.get("/app-icon.png")  # 앱 헤더 로고는 인증 없이 제공(민감정보 아님)
        assert r.status == 200
        assert r.headers["Content-Type"] == "image/png"
        body = await r.read()
        assert body[:4] == b"\x89PNG"
    finally:
        await client.close()


@pytest.mark.asyncio
async def test_models_autodetected(monkeypatch):
    async def fake_detect():
        return {"installed": True, "ready": True, "models": ["llama3.1:8b", "gemma4"]}

    monkeypatch.setattr(webui, "_detect_ollama", fake_detect)
    client = await _client()
    try:
        d = await (await client.get("/api/models", headers={"X-Session": KEY})).json()
        assert d["models"] == ["llama3.1:8b", "gemma4"]
        assert d["selected"] == []
        assert d["default"] == DEFAULT_TEXT_MODEL
        assert d["defaultInstalled"] is False
        assert d["ollamaInstalled"] is True and d["ollamaReady"] is True
    finally:
        await client.close()


@pytest.mark.asyncio
async def test_models_select_default_exaone_when_detected(monkeypatch):
    async def fake_detect():
        return {"installed": True, "ready": True, "models": ["llama3.1:8b", DEFAULT_TEXT_MODEL, "gemma4"]}

    monkeypatch.setattr(webui, "_detect_ollama", fake_detect)
    client = await _client()
    try:
        d = await (await client.get("/api/models", headers={"X-Session": KEY})).json()
        assert d["selected"] == [DEFAULT_TEXT_MODEL]
        assert d["defaultInstalled"] is True
    finally:
        await client.close()


@pytest.mark.asyncio
async def test_models_preserve_saved_selection(monkeypatch):
    async def fake_detect():
        return {"installed": True, "ready": True, "models": ["llama3.1:8b", DEFAULT_TEXT_MODEL]}

    persist_partial({"models": ["llama3.1:8b"]})
    monkeypatch.setattr(webui, "_detect_ollama", fake_detect)
    client = await _client()
    try:
        d = await (await client.get("/api/models", headers={"X-Session": KEY})).json()
        assert d["selected"] == ["llama3.1:8b"]
    finally:
        await client.close()


@pytest.mark.asyncio
async def test_models_distinguishes_not_installed_from_daemon_down(monkeypatch):
    """P1 회귀 가드: /api/models 가 '미설치'와 'daemon 꺼짐'을 ollamaInstalled/ollamaReady 로 구분해야 한다.
    (과거엔 둘 다 OllamaError→[] 로 평탄화돼 UI 가 똑같이 '설치하세요'만 안내했다.)"""
    client = await _client()
    try:
        # (a) 설치됐지만 daemon 꺼짐
        async def fake_down():
            return {"installed": True, "ready": False, "models": []}

        monkeypatch.setattr(webui, "_detect_ollama", fake_down)
        d = await (await client.get("/api/models", headers={"X-Session": KEY})).json()
        assert d["models"] == [] and d["ollamaInstalled"] is True and d["ollamaReady"] is False

        # (b) 실행파일 자체 미설치
        async def fake_absent():
            return {"installed": False, "ready": False, "models": []}

        monkeypatch.setattr(webui, "_detect_ollama", fake_absent)
        d = await (await client.get("/api/models", headers={"X-Session": KEY})).json()
        assert d["ollamaInstalled"] is False and d["ollamaReady"] is False
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
            json={
                "token": "ABCDE-FGHIJ",
                "models": ["llama3.1:8b", "gemma4"],
                "enableImage": False,
                "installService": True,
            },
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
async def test_setup_defaults_empty_model_list_to_exaone_when_detected(monkeypatch):
    async def fake_detect():
        return ["llama3.1:8b", DEFAULT_TEXT_MODEL]

    monkeypatch.setattr(webui, "_detect_models", fake_detect)
    client = await _client()
    try:
        r = await client.post(
            "/api/setup",
            headers={"X-Session": KEY},
            json={"token": "ABCDE-FGHIJ", "models": [], "enableImage": False},
        )
        d = await r.json()
        assert d["ok"]
        assert load_config()["models"] == [DEFAULT_TEXT_MODEL]
    finally:
        await client.close()


@pytest.mark.asyncio
async def test_ollama_setup_persists_default_model(monkeypatch):
    async def fake_run_setup(url):
        return True

    monkeypatch.setattr("provider_agent.ollama_setup.is_busy", lambda: False)
    monkeypatch.setattr("provider_agent.ollama_setup.run_setup", fake_run_setup)
    client = await _client()
    try:
        d = await (await client.post("/api/ollama/setup", headers={"X-Session": KEY})).json()
        assert d["ok"]
        await asyncio.sleep(0.03)
        assert load_config()["models"] == [DEFAULT_TEXT_MODEL]
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


def test_connect_enabled_env_override(monkeypatch):
    # env 강제(개발/오버라이드)면 캐시와 무관하게 활성.
    monkeypatch.setenv("AGENT_CONNECT_ENABLED", "1")
    assert webui._connect_enabled() is True


def test_connect_enabled_reflects_server(monkeypatch):
    # env 없으면 백그라운드로 갱신된 서버 상태(캐시)를 따른다.
    monkeypatch.delenv("AGENT_CONNECT_ENABLED", raising=False)
    webui._connect_cache["enabled"] = True
    assert webui._connect_enabled() is True
    webui._connect_cache["enabled"] = False
    assert webui._connect_enabled() is False


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
async def test_server_pause_persists_and_reflects(monkeypatch):
    # 이 서버 일시중지/재개 — 저장되고 /api/servers paused 로 반영(에이전트 미실행이면 config 저장).
    from provider_agent.config_file import add_connection

    add_connection("TA", guild_id=100, guild_name="서버A")
    client = await _client()
    try:
        r = await (await client.post("/api/servers/100/pause", headers={"X-Session": KEY}, json={"paused": True})).json()
        assert r["ok"] is True and r["paused"] is True
        d = await (await client.get("/api/servers", headers={"X-Session": KEY})).json()
        assert next(x for x in d["servers"] if x["guildName"] == "서버A")["paused"] is True
        await client.post("/api/servers/100/pause", headers={"X-Session": KEY}, json={"paused": False})
        d2 = await (await client.get("/api/servers", headers={"X-Session": KEY})).json()
        assert next(x for x in d2["servers"] if x["guildName"] == "서버A")["paused"] is False
    finally:
        await client.close()


@pytest.mark.asyncio
async def test_server_manage_requires_running_agent(monkeypatch):
    # 관리 채널 프록시 — 에이전트 미실행이면 안내, 잘못된 action 은 거부.
    client = await _client()
    try:
        d = await (await client.get("/api/servers/100/manage", headers={"X-Session": KEY})).json()
        assert d["ok"] is False  # 미실행
        d2 = await (await client.post("/api/servers/100/providers/nuke", headers={"X-Session": KEY}, json={"providerUserId": 1})).json()
        assert d2["ok"] is False  # 알 수 없는 action
        d3 = await (await client.post("/api/servers/100/manage/policy", headers={"X-Session": KEY}, json={"autoApprove": True})).json()
        assert d3["ok"] is False  # 미실행
    finally:
        await client.close()


@pytest.mark.asyncio
async def test_server_prompt_sets_require_running_agent(monkeypatch):
    # 전역 프롬프트셋 관리 프록시 — 키 없으면 403, 에이전트 미실행이면 안내.
    client = await _client()
    try:
        assert (await client.get("/api/servers/100/prompts")).status == 403  # 키 없음
        d = await (await client.get("/api/servers/100/prompts", headers={"X-Session": KEY})).json()
        assert d["ok"] is False  # 미실행
        d2 = await (
            await client.post(
                "/api/servers/100/prompts/add", headers={"X-Session": KEY}, json={"name": "비서", "content": "내용"},
            )
        ).json()
        assert d2["ok"] is False  # 미실행
        d3 = await (
            await client.post("/api/servers/100/prompts/default", headers={"X-Session": KEY}, json={"id": "nia"})
        ).json()
        assert d3["ok"] is False  # 미실행
        d4 = await (
            await client.post("/api/servers/100/prompts/delete", headers={"X-Session": KEY}, json={"id": "5"})
        ).json()
        assert d4["ok"] is False  # 미실행
    finally:
        await client.close()


@pytest.mark.asyncio
async def test_server_channels_require_running_agent(monkeypatch):
    # 채널 AI 허용 관리 프록시 — 키 없으면 403, 에이전트 미실행이면 안내, 잘못된 채널은 거부.
    client = await _client()
    try:
        assert (await client.get("/api/servers/100/channels")).status == 403  # 키 없음
        d = await (await client.get("/api/servers/100/channels", headers={"X-Session": KEY})).json()
        assert d["ok"] is False  # 미실행
        d2 = await (
            await client.post(
                "/api/servers/100/channels/toggle", headers={"X-Session": KEY}, json={"channelId": "9002", "allow": False},
            )
        ).json()
        assert d2["ok"] is False  # 미실행
        # 잘못된 channelId 는 라우팅 단계에서 거부(에이전트 도달 전).
        d3 = await (
            await client.post(
                "/api/servers/100/channels/toggle", headers={"X-Session": KEY}, json={"channelId": "x", "allow": True},
            )
        ).json()
        assert d3["ok"] is False
    finally:
        await client.close()


@pytest.mark.asyncio
async def test_server_readonly_tabs_require_running_agent(monkeypatch):
    # 읽기 전용 관리 탭(채널AI/RAG/프리셋) 프록시 — 키 없으면 403, 에이전트 미실행이면 안내.
    client = await _client()
    try:
        for path in ("channel-ai", "knowledge", "presets", "safety/reports"):
            assert (await client.get(f"/api/servers/100/{path}")).status == 403  # 키 없음
            d = await (await client.get(f"/api/servers/100/{path}", headers={"X-Session": KEY})).json()
            assert d["ok"] is False  # 미실행
    finally:
        await client.close()


@pytest.mark.asyncio
async def test_server_safety_reports_proxy_and_review(monkeypatch):
    # 안전 탭 신고 큐: webui 는 session/auth 와 running-agent 만 보고 central durable-token 브리지로 위임한다.
    calls: list[tuple] = []

    class _Agent:
        async def admin_safety_reports(self, guild_id):
            calls.append(("list", guild_id))
            return {"ok": True, "openReportCount": 1, "reports": [{"id": "51", "channelId": "20"}]}

        async def admin_safety_review(self, guild_id, report_id, decision, reason):
            calls.append(("review", guild_id, report_id, decision, reason))
            return {"ok": True, "openReportCount": 0, "reports": []}

    webui._state["agent"] = _Agent()
    webui._state["task"] = _AliveTask()
    client = await _client()
    try:
        listed = await (await client.get("/api/servers/100/safety/reports", headers={"X-Session": KEY})).json()
        assert listed["ok"] is True and listed["reports"][0]["id"] == "51"
        reviewed = await (
            await client.post(
                "/api/servers/100/safety/reports/review",
                headers={"X-Session": KEY},
                json={"reportId": "51", "decision": "dismissed", "reason": "확인"},
            )
        ).json()
        assert reviewed["ok"] is True and reviewed["openReportCount"] == 0
        assert calls == [("list", 100), ("review", 100, "51", "dismissed", "확인")]
    finally:
        await client.close()
        webui._state["agent"] = None
        webui._state["task"] = None


@pytest.mark.asyncio
async def test_nia_persona_requires_key_and_running_agent():
    # 니아 페르소나(프로젝트 관리자 전용) 프록시 — 키 없으면 403, 에이전트 미실행이면 안내(전문 미포함).
    client = await _client()
    try:
        assert (await client.get("/api/admin/nia-persona")).status == 403  # 세션 키 없음
        d = await (await client.get("/api/admin/nia-persona", headers={"X-Session": KEY})).json()
        assert d["ok"] is False  # 미실행
        assert "persona" not in d  # 전문은 절대 새어 나오지 않는다
    finally:
        await client.close()


@pytest.mark.asyncio
async def test_nia_persona_passes_through_central_403(monkeypatch):
    # 비관리자(central 403)면 webui 가 그 상태/메시지를 그대로 전달하고 전문은 응답에 없다.
    class _Agent:
        async def admin_nia_persona(self):
            return {"ok": False, "status": 403, "error": "프로젝트 관리자만 볼 수 있어요"}

    webui._state["agent"] = _Agent()
    webui._state["task"] = _AliveTask()
    client = await _client()
    try:
        d = await (await client.get("/api/admin/nia-persona", headers={"X-Session": KEY})).json()
        assert d["ok"] is False
        assert d["status"] == 403
        assert "persona" not in d  # 전문 미노출
    finally:
        await client.close()
        webui._state["agent"] = None


@pytest.mark.asyncio
async def test_nia_persona_admin_returns_full_persona(monkeypatch):
    # 프로젝트 관리자(central ok)면 전문(persona·fewshot)을 그대로 전달한다.
    class _Agent:
        async def admin_nia_persona(self):
            return {"ok": True, "persona": "니아 전문", "fewshot": "예시"}

    webui._state["agent"] = _Agent()
    webui._state["task"] = _AliveTask()
    client = await _client()
    try:
        d = await (await client.get("/api/admin/nia-persona", headers={"X-Session": KEY})).json()
        assert d["ok"] is True
        assert d["persona"] == "니아 전문"
        assert d["fewshot"] == "예시"
    finally:
        await client.close()
        webui._state["agent"] = None


@pytest.mark.asyncio
async def test_image_toggle_persists_without_touching_models(monkeypatch):
    # 전용 /api/image 토글: enable_image 만 저장(모델 선택 미변경). 에이전트 미실행 시 'saved'.
    from provider_agent.config import AgentConfig
    from provider_agent.config_file import load_config, save_config

    save_config(AgentConfig(token="T", models=("a", "b")))  # 모델 2개 선택 상태
    client = await _client()
    try:
        assert (await client.post("/api/image", json={"on": True})).status == 403  # 키 없음
        r = await (await client.post("/api/image", headers={"X-Session": KEY}, json={"on": True})).json()
        assert r["ok"] is True and r["on"] is True and r["applied"] == "saved"
        cfg = load_config()
        assert cfg.get("enable_image") is True
        assert list(cfg.get("models") or []) == ["a", "b"]  # 모델 선택이 보존됨(클로버 없음)
        r2 = await (await client.post("/api/image", headers={"X-Session": KEY}, json={"on": False})).json()
        assert r2["on"] is False and load_config().get("enable_image") is False
    finally:
        await client.close()


@pytest.mark.asyncio
async def test_server_policy_saves_override(monkeypatch):
    # 서버별 정책 override(G3) 저장 — camelCase 경계 → snake 저장. 실행 중 아니면 config 에 기록.
    from provider_agent.config_file import load_guild_policies

    client = await _client()
    try:
        assert (await client.post("/api/servers/100/policy", json={"dailyLimit": 30})).status == 403  # 키 없음
        r = await client.post(
            "/api/servers/100/policy", headers={"X-Session": KEY},
            json={"dailyLimit": 30, "maxConcurrency": 2, "maxSeconds": 600},
        )
        body = await r.json()
        assert body["ok"] is True
        # 응답은 저장값 readback(camelCase) — 화면이 이 값을 그대로 표시한다.
        assert body["policy"] == {"dailyLimit": 30, "maxConcurrency": 2, "maxSeconds": 600}
        pols = load_guild_policies()
        assert pols[100]["daily_limit"] == 30
        assert pols[100]["max_concurrency"] == 2 and pols[100]["max_seconds"] == 600
        # scope 는 더 이상 저장하지 않는다(앱에서 제거 — 길드 격리로 ALL 보장).
        assert "scope" not in pols[100]
        # GET readback: 저장값을 camelCase 로 그대로 돌려준다(하드코딩 아님).
        g = await (await client.get("/api/servers/100/policy", headers={"X-Session": KEY})).json()
        assert g["ok"] is True
        assert g["policy"] == {"dailyLimit": 30, "maxConcurrency": 2, "maxSeconds": 600}
        # 미저장 서버는 기본값 readback.
        g2 = await (await client.get("/api/servers/999/policy", headers={"X-Session": KEY})).json()
        assert g2["policy"] == {"dailyLimit": 0, "maxConcurrency": 1, "maxSeconds": 0}
    finally:
        await client.close()


@pytest.mark.asyncio
async def test_server_remove_deletes_saved(monkeypatch):
    from provider_agent.config_file import add_connection, load_connections

    add_connection("TA", guild_id=100, guild_name="서버A")
    add_connection("TB", guild_id=200, guild_name="서버B")
    client = await _client()
    try:
        # index 0(서버A) 해제
        await client.post("/api/server-remove", headers={"X-Session": KEY}, json={"index": 0})
        left = [c["guild_id"] for c in load_connections()]
        assert left == [200]  # 서버A 해제됨
        # 이름 바꾸기(토큰-추가 '이름 미상' 라벨링) — 남은 index 0
        await client.post("/api/server-rename", headers={"X-Session": KEY}, json={"index": 0, "name": "새이름"})
        assert load_connections()[0]["guild_name"] == "새이름"
        # 토큰으로 서버 추가(별명)
        await client.post("/api/server-add-token", headers={"X-Session": KEY}, json={"token": "TC", "name": "토큰서버"})
        assert any(c["guild_name"] == "토큰서버" for c in load_connections())
    finally:
        await client.close()


@pytest.mark.asyncio
async def test_server_remove_and_rename_accept_64bit_guild_id_string(monkeypatch):
    """데스크톱 UI 는 Discord 64bit guildId 를 문자열로 보낸다. Number 정밀도 손실 없이 대상 연결을 찾아야 한다."""
    from provider_agent.config_file import add_connection, load_connections

    big_gid = 1380395592336805928
    add_connection("TA", guild_id=big_gid, guild_name="큰서버")
    add_connection("TB", guild_id=200, guild_name="서버B")
    client = await _client()
    try:
        renamed = await (
            await client.post(
                "/api/server-rename",
                headers={"X-Session": KEY},
                json={"guildId": str(big_gid), "name": "정밀도보존"},
            )
        ).json()
        assert renamed["ok"] is True
        conns = load_connections()
        assert conns[0]["guild_id"] == big_gid
        assert conns[0]["guild_name"] == "정밀도보존"

        removed = await (
            await client.post("/api/server-remove", headers={"X-Session": KEY}, json={"guildId": str(big_gid)})
        ).json()
        assert removed["ok"] is True
        left = load_connections()
        assert [c["guild_id"] for c in left] == [200]
        assert left[0]["guild_name"] == "서버B"
    finally:
        await client.close()


def test_auto_update_once_reports_available_without_applying(monkeypatch):
    # 실행 중 주기 검사: 토글 ON + 구버전 + 지원이어도 곧바로 종료·교체하지 않고 UI 승인 대기로 둔다.
    monkeypatch.setattr(webui, "load_config", lambda: {"auto_update": True})
    monkeypatch.setattr("provider_agent.updater.is_updating", lambda: False)
    monkeypatch.setattr(
        "provider_agent.updater.check",
        lambda: {"current": "0.19.0", "latest": "0.20.0", "outdated": True, "supported": True, "error": None},
    )
    called = {}
    monkeypatch.setattr("provider_agent.updater.apply_update", lambda: called.setdefault("apply", True) or {})
    monkeypatch.setattr(webui, "_schedule_exit", lambda *a, **k: called.setdefault("exit", True))
    assert webui._auto_update_once() == "available"
    assert called == {}


def test_auto_update_once_skips_when_latest(monkeypatch):
    monkeypatch.setattr(webui, "load_config", lambda: {"auto_update": True})
    monkeypatch.setattr("provider_agent.updater.is_updating", lambda: False)
    monkeypatch.setattr(
        "provider_agent.updater.check",
        lambda: {"current": "0.20.0", "latest": "0.20.0", "outdated": False, "supported": True, "error": None},
    )
    called = {}
    monkeypatch.setattr("provider_agent.updater.apply_update", lambda: called.setdefault("apply", True) or {})
    assert webui._auto_update_once() == "uptodate"  # 최신이면 긴 간격
    assert "apply" not in called  # 최신이면 교체 시도 안 함


def test_auto_update_once_skips_when_toggle_off(monkeypatch):
    monkeypatch.setattr(webui, "load_config", lambda: {"auto_update": False})
    called = {}
    monkeypatch.setattr("provider_agent.updater.check", lambda: called.setdefault("checked", True) or {})
    assert webui._auto_update_once() == "uptodate"  # 토글 OFF → 긴 간격(자주 재시도 안 함)
    assert "checked" not in called  # 토글 OFF 면 검사조차 안 함


def test_auto_update_once_retries_on_check_error(monkeypatch):
    # 네트워크 전이 실패(check error)면 "pending" → 호출부가 짧게 재시도(2시간 스트랜드 방지, 실증 원인).
    monkeypatch.setattr(webui, "load_config", lambda: {"auto_update": True})
    monkeypatch.setattr("provider_agent.updater.is_updating", lambda: False)
    monkeypatch.setattr(
        "provider_agent.updater.check",
        lambda: {"current": "0.19.0", "latest": None, "outdated": False, "supported": True, "error": "최신 버전 확인 실패(네트워크)"},
    )
    called = {}
    monkeypatch.setattr("provider_agent.updater.apply_update", lambda: called.setdefault("apply", True) or {})
    assert webui._auto_update_once() == "pending"
    assert "apply" not in called  # 확인 실패면 적용 시도 안 함(다음 짧은 재시도에서)


def test_brand_icon_png_returns_png_bytes():
    from provider_agent.webui import _brand_icon_png

    png = _brand_icon_png()
    # PIL 있으면 PNG 시그니처, 없으면 None(둘 다 허용 — dock 아이콘은 선택적)
    assert png is None or png[:8] == b"\x89PNG\r\n\x1a\n"


def test_set_macos_app_identity_is_safe_everywhere():
    from provider_agent.webui import _set_macos_app_identity

    # 비-macOS 는 no-op, macOS 면 dock 이름/아이콘 설정 — 어느 쪽이든 예외 없이 끝나야 한다.
    _set_macos_app_identity("Nexa")


class _AliveTask:
    def done(self) -> bool:
        return False


class _FakeWindow:
    def __init__(self) -> None:
        self.hidden = False

    def hide(self) -> None:
        self.hidden = True


def test_webview_close_hides_window_when_background_running() -> None:
    """macOS 표준: 백그라운드 상주 ON + 에이전트 실행 중이면 **빨간X 는 창만 숨기고 앱은 살린다**.

    (Cmd+Q/Dock-종료의 완전 종료는 _install_macos_app_delegate 의 applicationShouldTerminate 가
    별도로 보장한다 — events.closing veto 와 무관하게 항상 종료. 여기 veto 는 빨간X 에만 적용.)
    """
    persist_partial({"background": True, "tray": True, "token": "T"})
    webui._state["agent"] = object()
    webui._state["task"] = _AliveTask()
    window = _FakeWindow()

    assert webui._handle_webview_closing(window) is False  # 닫기 취소(창만 숨김)
    assert window.hidden is True  # 창은 숨고 프로세스는 살아서 계속 기여


def test_webview_close_allows_exit_when_background_off() -> None:
    """백그라운드 상주 OFF면 닫기 버튼은 기존처럼 실제 종료를 허용한다."""
    persist_partial({"background": False, "tray": False, "token": "T"})
    webui._state["agent"] = object()
    webui._state["task"] = _AliveTask()
    window = _FakeWindow()

    assert webui._handle_webview_closing(window) is None
    assert window.hidden is False


def test_webview_close_does_not_hide_settings_only_window() -> None:
    """제공 중인 에이전트가 없는 설정 전용 창은 상주 ON이어도 숨겨 둬 봐야 의미가 없으므로 닫기를 허용한다."""
    persist_partial({"background": True, "tray": True, "token": "T"})
    window = _FakeWindow()

    assert webui._handle_webview_closing(window) is None
    assert window.hidden is False


def test_close_handoff_respects_background_toggle(monkeypatch) -> None:
    """상주 OFF면 서비스가 설치돼 있어도 창 닫기 후 백그라운드 kickstart 를 하지 않는다."""
    persist_partial({"background": False, "tray": False, "token": "T"})
    monkeypatch.setattr("provider_agent.service.is_installed", lambda: True)
    monkeypatch.setattr(
        "provider_agent.service.kickstart",
        lambda: (_ for _ in ()).throw(AssertionError("background off 인데 kickstart 하면 안 됨")),
    )

    assert webui._handoff_to_service_on_close() is False


@pytest.mark.asyncio
async def test_onboard_apply_installs_service_and_persists(monkeypatch):
    """온보딩 적용: autostart 면 install_service 를 실제 호출하고, 토글을 설정에 반영해야 한다.
    (회귀: 과거엔 persist_partial 로 저장만 하고 어떤 동작도 하지 않아 토글이 무의미했다.)"""
    installed = {}
    monkeypatch.setattr("provider_agent.service.install_service", lambda: installed.setdefault("done", True))
    monkeypatch.setattr("provider_agent.singleton.acquire", lambda: True)
    client = await _client()
    try:
        r = await client.post(
            "/api/onboard-apply",
            headers={"X-Session": KEY},
            json={"enableImage": True, "autostart": True, "autoConnect": True, "background": True},
        )
        d = await r.json()
        assert d["ok"] and d["serviceInstalled"]
        assert installed.get("done") is True  # 자동시작 서비스 실제 등록
        saved = load_config()
        assert saved["enable_image"] is True
        assert saved["auto_connect"] is True
        assert saved["tray"] is True  # background → 트레이 상주
        assert saved["autostart_pref"] is True
    finally:
        await client.close()


@pytest.mark.asyncio
async def test_onboard_apply_no_service_when_autostart_off(monkeypatch):
    """autostart=false 면 install_service 를 호출하지 않아야 한다(원치 않는 자동시작 등록 방지)."""
    monkeypatch.setattr(
        "provider_agent.service.install_service",
        lambda: (_ for _ in ()).throw(AssertionError("autostart off 인데 등록하면 안 됨")),
    )
    client = await _client()
    try:
        r = await client.post(
            "/api/onboard-apply",
            headers={"X-Session": KEY},
            json={"enableImage": False, "autostart": False, "autoConnect": False, "background": False},
        )
        d = await r.json()
        assert d["ok"] and d["serviceInstalled"] is False
        saved = load_config()
        assert saved["auto_connect"] is False and saved["tray"] is False
    finally:
        await client.close()


@pytest.mark.asyncio
async def test_autoconnect_on_startup_when_enabled(monkeypatch):
    """auto_connect 가 켜져 있고 저장된 서버가 있으면 GUI 기동(on_startup) 시 자동 연결돼야 한다."""
    monkeypatch.setattr("provider_agent.agent.ProviderAgent", FakeAgent)
    from provider_agent.config_file import persist_partial, save_connections

    # 실제 OAuth 연동(save_connections)과 동일하게 token 도 미러된 상태를 만든다.
    save_connections([{"token": "T", "guild_id": None, "guild_name": None}])
    persist_partial({"auto_connect": True})
    client = await _client()  # build_app + start_server → on_startup 훅 실행
    try:
        await asyncio.sleep(0.05)
        st = await (await client.get("/api/status", headers={"X-Session": KEY})).json()
        assert st["running"] is True  # 추가 클릭 없이 자동 연결
        await client.post("/api/stop", headers={"X-Session": KEY})
    finally:
        await client.close()


@pytest.mark.asyncio
async def test_status_reports_background_running(monkeypatch):
    """다른 인스턴스(백그라운드 자동시작 서비스)가 락을 잡고 있으면 status.backgroundRunning=True."""
    from provider_agent import singleton

    monkeypatch.setattr(singleton, "held_by_other", lambda: True)
    client = await _client()
    try:
        st = await (await client.get("/api/status", headers={"X-Session": KEY})).json()
        assert st["running"] is False
        assert st["backgroundRunning"] is True
    finally:
        await client.close()


@pytest.mark.asyncio
async def test_setup_surfaces_service_install_error(monkeypatch):
    """서비스 등록이 실패하면 조용히 삼키지 않고 serviceError 로 사유를 돌려준다(설치 자체는 ok)."""
    def _boom():
        raise RuntimeError("launchctl 권한 없음")

    monkeypatch.setattr("provider_agent.service.install_service", _boom)
    monkeypatch.setattr("provider_agent.singleton.acquire", lambda: True)
    client = await _client()
    try:
        r = await client.post(
            "/api/setup",
            headers={"X-Session": KEY},
            json={"token": "T", "models": ["m"], "installService": True},
        )
        d = await r.json()
        assert d["ok"] is True
        assert d["serviceInstalled"] is False
        assert "launchctl" in (d["serviceError"] or "")
    finally:
        await client.close()


@pytest.mark.asyncio
async def test_service_stop_endpoint(monkeypatch):
    """'백그라운드 중지' 엔드포인트: stop_service 를 위임하고 결과를 돌려준다(키 필요)."""
    monkeypatch.setattr("provider_agent.service.stop_service", lambda: True)
    client = await _client()
    try:
        assert (await client.post("/api/service-stop")).status == 403  # 키 없음
        d = await (await client.post("/api/service-stop", headers={"X-Session": KEY})).json()
        assert d["ok"] is True
    finally:
        await client.close()


@pytest.mark.asyncio
async def test_no_autoconnect_when_disabled(monkeypatch):
    """auto_connect 키가 없으면(온보딩 미완료 기존 사용자) 자동 연결하지 않아야 한다(깜짝 연결 방지)."""
    monkeypatch.setattr("provider_agent.agent.ProviderAgent", FakeAgent)
    from provider_agent.config_file import save_connections

    # 토큰/연결은 있지만 auto_connect 미설정 → 자동연결 안 함(연결 가능한데도 안 하는지 확인)
    save_connections([{"token": "T", "guild_id": None, "guild_name": None}])
    client = await _client()
    try:
        await asyncio.sleep(0.05)
        st = await (await client.get("/api/status", headers={"X-Session": KEY})).json()
        assert st["running"] is False  # 자동 연결 안 함
    finally:
        await client.close()


# ── P4: 이미지 토글의 라이브 전파 + SD 미설치 가시화 ─────────────────────────────


@pytest.mark.asyncio
async def test_setup_apply_to_background_restarts_service(monkeypatch):
    """백그라운드 서비스가 연결을 담당 중이면(held_by_other), applyToBackground 저장이 그 서비스를
    kickstart 로 재시작해 새 enable_image 를 라이브로 반영해야 한다.
    (회귀: 과거엔 config 파일만 저장돼 백그라운드는 시작 시점 설정으로 영원히 image 미광고 → /imagine provider 없음.)"""
    monkeypatch.setattr("provider_agent.singleton.held_by_other", lambda: True)
    monkeypatch.setattr("provider_agent.service.is_installed", lambda: True)
    kicked: dict = {}
    monkeypatch.setattr("provider_agent.service.kickstart", lambda: kicked.setdefault("done", True))
    client = await _client()
    try:
        r = await client.post(
            "/api/setup",
            headers={"X-Session": KEY},
            json={"models": ["m"], "enableImage": True, "applyToBackground": True},
        )
        d = await r.json()
        assert d["ok"] and d["serviceRestarted"] is True
        assert kicked.get("done") is True  # 라이브 서비스 실제 재시작
        assert load_config()["enable_image"] is True
    finally:
        await client.close()


@pytest.mark.asyncio
async def test_setup_apply_to_background_noop_without_background(monkeypatch):
    """백그라운드 서비스가 없으면(held_by_other=False) applyToBackground 라도 kickstart 하지 않는다."""
    monkeypatch.setattr("provider_agent.singleton.held_by_other", lambda: False)
    monkeypatch.setattr("provider_agent.service.is_installed", lambda: True)
    monkeypatch.setattr(
        "provider_agent.service.kickstart",
        lambda: (_ for _ in ()).throw(AssertionError("백그라운드 없는데 kickstart 하면 안 됨")),
    )
    client = await _client()
    try:
        r = await client.post(
            "/api/setup",
            headers={"X-Session": KEY},
            json={"models": ["m"], "enableImage": True, "applyToBackground": True},
        )
        d = await r.json()
        assert d["ok"] and d["serviceRestarted"] is False
    finally:
        await client.close()


# ── P3: 추천 모델 카탈로그 + 임의 모델 설치/선택 ──────────────────────────────


@pytest.mark.asyncio
async def test_ollama_catalog_lists_recommended_with_status(monkeypatch):
    """/api/ollama/catalog 가 exaone 을 기본/추천으로 주고, 설치됨/선택됨 상태를 모델별로 표기한다(P3)."""
    async def fake_detect():
        return {"installed": True, "ready": True, "models": ["llama3.1:8b"]}

    monkeypatch.setattr(webui, "_detect_ollama", fake_detect)
    persist_partial({"models": ["llama3.1:8b"]})
    client = await _client()
    try:
        d = await (await client.get("/api/ollama/catalog", headers={"X-Session": KEY})).json()
        assert d["default"] == DEFAULT_TEXT_MODEL
        by_id = {m["id"]: m for m in d["models"]}
        assert by_id[DEFAULT_TEXT_MODEL]["default"] is True and by_id[DEFAULT_TEXT_MODEL]["recommended"] is True
        assert by_id[DEFAULT_TEXT_MODEL]["installed"] is False  # exaone 미설치
        assert by_id["llama3.1:8b"]["installed"] is True and by_id["llama3.1:8b"]["selected"] is True
    finally:
        await client.close()


@pytest.mark.asyncio
async def test_ollama_setup_installs_and_selects_arbitrary_model(monkeypatch):
    """본문에 model 이 있으면 그 모델을 pull 하고 제공 대상에 추가한다(P3).
    (회귀: 과거엔 /api/ollama/setup 이 본문을 무시하고 기본 모델만 받았다.)"""
    captured: dict = {}

    async def fake_run_setup(url, model=None):
        captured["model"] = model
        return True

    monkeypatch.setattr("provider_agent.ollama_setup.run_setup", fake_run_setup)
    monkeypatch.setattr("provider_agent.ollama_setup.is_busy", lambda: False)
    persist_partial({"models": ["llama3.1:8b"]})
    client = await _client()
    try:
        r = await client.post(
            "/api/ollama/setup", headers={"X-Session": KEY}, json={"model": "qwen2.5:7b", "select": True}
        )
        assert (await r.json())["ok"] is True
        await asyncio.sleep(0.05)  # fire-and-forget 설치 태스크 실행 대기
        assert captured["model"] == "qwen2.5:7b"
        assert "qwen2.5:7b" in load_config()["models"]  # 설치 후 제공 대상 추가
    finally:
        await client.close()


@pytest.mark.asyncio
async def test_runtime_health_contract_reports_runtimes(monkeypatch):
    """계획서 endpoint: provider 연결 상태와 Ollama/이미지 런타임 health 를 분리해서 반환한다."""
    async def fake_detect():
        return {"installed": True, "ready": True, "models": [DEFAULT_TEXT_MODEL], "modelsDetail": []}

    async def fake_comfy():
        return {
            "enabled": True,
            "installed": True,
            "ready": True,
            "advertised": False,
            "installedModels": ["sdxl.safetensors"],
            "selectedModel": "sdxl.safetensors",
            "busy": False,
            "error": None,
            "needsReconnect": True,
        }

    monkeypatch.setattr(webui, "_detect_ollama", fake_detect)
    monkeypatch.setattr(webui, "_detect_comfy_runtime", fake_comfy)
    client = await _client()
    try:
        d = await (await client.get("/api/runtime-health", headers={"X-Session": KEY})).json()
        assert d["ollama"]["defaultModel"] == DEFAULT_TEXT_MODEL
        assert d["ollama"]["defaultInstalled"] is True
        assert d["ollama"]["recommendedModels"]
        assert d["stableDiffusion"]["installedModels"] == ["sdxl.safetensors"]
        assert d["stableDiffusion"]["needsReconnect"] is True
    finally:
        await client.close()


@pytest.mark.asyncio
async def test_runtime_health_tolerates_probe_errors(monkeypatch):
    """런타임 probe 하나가 예외를 내도 health endpoint 전체가 500 으로 번지면 안 된다."""
    async def fail_ollama():
        raise RuntimeError("ollama probe exploded")

    async def fail_comfy():
        raise RuntimeError("comfy probe exploded")

    persist_partial({"enable_image": True, "comfy_model": "sdxl.safetensors"})
    monkeypatch.setattr(webui, "_detect_ollama", fail_ollama)
    monkeypatch.setattr(webui, "_detect_comfy_runtime", fail_comfy)
    client = await _client()
    try:
        r = await client.get("/api/runtime-health", headers={"X-Session": KEY})
        assert r.status == 200
        d = await r.json()
        assert d["ollama"]["ready"] is False
        assert d["ollama"]["installedModels"] == []
        assert d["stableDiffusion"]["enabled"] is True
        assert d["stableDiffusion"]["selectedModel"] == "sdxl.safetensors"
        assert d["stableDiffusion"]["error"] == "probe-failed"
    finally:
        await client.close()


@pytest.mark.asyncio
async def test_plan_aliases_install_progress_and_model_select(monkeypatch):
    """계획서 route 명칭도 기존 /api/ollama/setup 구현과 같은 동작을 제공한다."""
    captured: dict = {}

    async def fake_run_setup(url, model=None):
        captured["model"] = model
        return True

    async def fake_detect():
        return {"installed": True, "ready": True, "models": ["qwen2.5:7b"], "modelsDetail": []}

    monkeypatch.setattr("provider_agent.ollama_setup.run_setup", fake_run_setup)
    monkeypatch.setattr("provider_agent.ollama_setup.is_busy", lambda: False)
    monkeypatch.setattr(webui, "_detect_ollama", fake_detect)
    client = await _client()
    try:
        r = await client.post(
            "/api/ollama/model-install",
            headers={"X-Session": KEY},
            json={"model": "qwen2.5:7b", "select": True},
        )
        assert (await r.json())["ok"] is True
        await asyncio.sleep(0.05)
        assert captured["model"] == "qwen2.5:7b"

        progress = await (await client.get("/api/ollama/model-install-progress", headers={"X-Session": KEY})).json()
        assert "phase" in progress and "percent" in progress

        selected = await (
            await client.post(
                "/api/models/select",
                headers={"X-Session": KEY},
                json={"models": ["qwen2.5:7b"], "defaultModel": "qwen2.5:7b"},
            )
        ).json()
        assert selected["ok"] is True
        assert selected["models"] == ["qwen2.5:7b"]
        assert load_config()["models"] == ["qwen2.5:7b"]
        assert load_config()["default_model"] == "qwen2.5:7b"
    finally:
        await client.close()


@pytest.mark.asyncio
async def test_sd_and_image_provider_plan_aliases(monkeypatch):
    """문서의 sd/image-provider route 명칭은 현재 ComfyUI 기반 구현으로 응답한다."""
    async def fake_comfy():
        return {
            "enabled": False,
            "installed": True,
            "ready": False,
            "advertised": False,
            "installedModels": ["realistic.safetensors"],
            "selectedModel": "realistic.safetensors",
            "busy": False,
            "error": None,
            "needsReconnect": False,
        }

    called: dict = {}

    async def fake_apply(on: bool):
        called["on"] = on
        return {"ok": True, "on": on, "imageReady": False, "applied": "saved"}

    async def fake_download(url: str):
        called["url"] = url
        return True

    monkeypatch.setattr(webui, "_detect_comfy_runtime", fake_comfy)
    monkeypatch.setattr(webui, "_apply_image_receiving", fake_apply)
    monkeypatch.setattr("provider_agent.comfy_setup.download_model", fake_download)
    client = await _client()
    try:
        status = await (await client.get("/api/sd/status", headers={"X-Session": KEY})).json()
        assert status["installed"] is True and status["installedModels"] == ["realistic.safetensors"]
        models = await (await client.get("/api/sd/models/installed", headers={"X-Session": KEY})).json()
        assert models == {"models": ["realistic.safetensors"], "active": "realistic.safetensors"}

        installed = await (
            await client.post(
                "/api/sd/model-install",
                headers={"X-Session": KEY},
                json={"url": "https://example.com/realistic.safetensors"},
            )
        ).json()
        assert installed["ok"] is True and called["url"] == "https://example.com/realistic.safetensors"

        toggled = await (
            await client.post("/api/image-provider", headers={"X-Session": KEY}, json={"enabled": True})
        ).json()
        assert toggled["ok"] is True and called["on"] is True
    finally:
        await client.close()


@pytest.mark.asyncio
async def test_settings_requires_session_key():
    """통합 설정 GET/POST 모두 세션 키 없으면 403(다른 라우트와 동일 게이트)."""
    client = await _client()
    try:
        assert (await client.get("/api/settings")).status == 403
        assert (await client.post("/api/settings", json={"autoUpdate": False})).status == 403
    finally:
        await client.close()


@pytest.mark.asyncio
async def test_settings_get_returns_defaults():
    """저장된 설정이 없을 때 합리적 기본값을 camelCase 로 통합 반환한다."""
    client = await _client()
    try:
        d = await (await client.get("/api/settings", headers={"X-Session": KEY})).json()
        assert d["autostart"] is False
        assert d["background"] is False
        assert d["autoConnect"] is False
        assert d["autoUpdate"] is True  # 기본 자동업데이트 on
        assert d["enableImage"] is False
        assert d["ollamaUrl"] == "http://localhost:11434"
        assert d["relayUrl"]  # 기본 중앙 서버 주소
        assert d["allowRemoteOllama"] is False
        assert d["hasToken"] is False
    finally:
        await client.close()


@pytest.mark.asyncio
async def test_settings_post_persists_and_get_reflects():
    """POST 로 부분 변경하면 저장되고 GET 에 반영된다(snake↔camel 매핑 검증)."""
    client = await _client()
    try:
        r = await client.post(
            "/api/settings",
            headers={"X-Session": KEY},
            json={"autoUpdate": False, "autoConnect": True, "background": True},
        )
        body = await r.json()
        assert body["ok"] is True
        assert body["needsRestart"] is False  # 이 키들은 재시작 불필요
        # config 에는 snake 키로 저장된다.
        saved = load_config()
        assert saved["auto_update"] is False
        assert saved["auto_connect"] is True
        assert saved["background"] is True
        assert saved["tray"] is True  # background 상주는 실제 에이전트가 읽는 tray 와 동기화
        # GET 은 camelCase 로 다시 반영해 보여준다.
        d = await (await client.get("/api/settings", headers={"X-Session": KEY})).json()
        assert d["autoUpdate"] is False
        assert d["autoConnect"] is True
        assert d["background"] is True
    finally:
        await client.close()


@pytest.mark.asyncio
async def test_settings_post_needs_restart_for_reconnect_keys():
    """enableImage·relayUrl·ollamaUrl·allowRemoteOllama 변경은 needsRestart=true(즉시반영 흉내 금지)."""
    client = await _client()
    try:
        for key, value in (
            ("enableImage", True),
            ("relayUrl", "wss://example.test/agent"),
            ("ollamaUrl", "http://localhost:22000"),
            ("allowRemoteOllama", True),
        ):
            r = await client.post("/api/settings", headers={"X-Session": KEY}, json={key: value})
            body = await r.json()
            assert body["ok"] is True
            assert body["needsRestart"] is True, key
        # 저장은 그대로 됐는지 확인.
        saved = load_config()
        assert saved["enable_image"] is True
        assert saved["relay_url"] == "wss://example.test/agent"
        assert saved["ollama_url"] == "http://localhost:22000"
        assert saved["allow_remote_ollama"] is True
    finally:
        await client.close()


@pytest.mark.asyncio
async def test_settings_post_ignores_unknown_keys():
    """허용되지 않은 키는 조용히 무시(저장 안 됨)하고 ok 반환."""
    client = await _client()
    try:
        r = await client.post(
            "/api/settings", headers={"X-Session": KEY}, json={"token": "leak", "bogus": 1}
        )
        body = await r.json()
        assert body["ok"] is True
        assert body["needsRestart"] is False
        # 토큰 등 비-허용 키는 통합 설정으로 바꿀 수 없다(시크릿 보호).
        assert load_config() == {}
    finally:
        await client.close()


@pytest.mark.asyncio
async def test_license_requires_session_key_and_durable_token():
    """라이선스 로컬 API 는 세션키 + durable 토큰이 있어야 central 로 프록시한다."""
    client = await _client()
    try:
        assert (await client.get("/api/license")).status == 403
        d = await (await client.get("/api/license", headers={"X-Session": KEY})).json()
        assert d["ok"] is False
        assert "연동" in d["error"]
        c = await (await client.post("/api/license/checkout", headers={"X-Session": KEY}, json={})).json()
        assert c["ok"] is False
    finally:
        await client.close()


@pytest.mark.asyncio
async def test_license_routes_proxy_to_central(monkeypatch):
    """durable 토큰 보유 시 라이선스 조회/구매/이벤트 신청을 central 라이선스 API 로 위임한다."""
    from provider_agent.config_file import add_connection

    add_connection("dv1.fake", guild_id=100, guild_name="S")
    calls: list = []
    monkeypatch.setattr(
        "provider_agent.agent._post_license_me",
        lambda base, token: calls.append(("me", base, token))
        or {"userId": "1", "status": "TRIAL", "trialEndsAt": "2026-09-01T00:00:00Z", "hasPaidAccess": True},
    )
    monkeypatch.setattr(
        "provider_agent.agent._get_license_event_status",
        lambda base: calls.append(("event", base)) or {"open": True, "granted": 3},
    )
    monkeypatch.setattr(
        "provider_agent.agent._post_license_checkout",
        lambda base, token: calls.append(("checkout", base, token)) or {"url": "https://checkout.example/1"},
    )
    monkeypatch.setattr(
        "provider_agent.agent._post_license_event_claim",
        lambda base, token: calls.append(("claim", base, token))
        or {
            "outcome": "GRANTED",
            "entitlement": {"userId": "1", "status": "EVENT_FREE", "trialEndsAt": None, "hasPaidAccess": True},
        },
    )
    client = await _client()
    try:
        s = await (await client.get("/api/license", headers={"X-Session": KEY})).json()
        assert s["ok"] is True
        assert s["entitlement"]["status"] == "TRIAL"
        assert s["event"]["granted"] == 3
        checkout = await (await client.post("/api/license/checkout", headers={"X-Session": KEY}, json={})).json()
        assert checkout == {"ok": True, "url": "https://checkout.example/1"}
        claim = await (await client.post("/api/license/event-claim", headers={"X-Session": KEY}, json={})).json()
        assert claim["ok"] is True
        assert claim["outcome"] == "GRANTED"
        assert ("checkout", "https://discord-ai.yeon.world", "dv1.fake") in calls
        assert ("claim", "https://discord-ai.yeon.world", "dv1.fake") in calls
    finally:
        await client.close()


def _make_assets(tmp_path, monkeypatch):
    """임시 webui_assets 디렉토리를 만들어 _assets_dir 가 그것을 가리키게 한다(실 자산 비의존)."""
    assets = tmp_path / "webui_assets"
    assets.mkdir()
    # index.html: 세션키 자리 + 프로토타입 마커(data-view="logs").
    (assets / "index.html").write_text(
        '<!doctype html><html><head>\n'
        '  <script>window.__SESSION_KEY="__SESSION_KEY__";</script>\n'
        '</head><body><section class="view" data-view="logs">로그</section></body></html>',
        encoding="utf-8",
    )
    (assets / "adapter.js").write_text("export const USE_MOCK = false;\n", encoding="utf-8")
    (assets / "img").mkdir()
    (assets / "img" / "nexa-logo.png").write_bytes(b"\x89PNG\r\n\x1a\nfake")
    monkeypatch.setattr(webui, "_assets_dir", lambda: assets)
    return assets


@pytest.mark.asyncio
async def test_index_serves_synced_assets_with_session_key(tmp_path, monkeypatch):
    """webui_assets/index.html 이 있으면 세션키가 치환되고 프로토타입 마커가 포함된다."""
    _make_assets(tmp_path, monkeypatch)
    client = await _client()
    try:
        r = await client.get("/")
        assert r.status == 200
        html = await r.text()
        assert f'window.__SESSION_KEY="{KEY}"' in html  # 세션키 치환됨
        assert "__SESSION_KEY__" not in html  # 플레이스홀더 잔여 없음
        assert 'data-view="logs"' in html  # 이식된 시안 마커
    finally:
        await client.close()


@pytest.mark.asyncio
async def test_index_missing_assets_shows_guidance(tmp_path, monkeypatch):
    """webui_assets/index.html 이 없으면(sync-desktop 미실행) 조용히 깨지지 않고 안내 HTML 을 반환한다."""
    empty = tmp_path / "webui_assets"
    empty.mkdir()  # index.html 없는 빈 디렉토리
    monkeypatch.setattr(webui, "_assets_dir", lambda: empty)
    client = await _client()
    try:
        r = await client.get("/")
        assert r.status == 200  # 200(빈 화면 아님) — 무엇을 해야 하는지 안내
        html = await r.text()
        assert "make sync-desktop" in html  # 안내 문구
        assert "__SESSION_KEY__" not in html  # 안내 페이지엔 세션키 자리표시 없음
    finally:
        await client.close()


@pytest.mark.asyncio
async def test_asset_js_served_as_javascript(tmp_path, monkeypatch):
    """/adapter.js 가 200 + text/javascript 로 인증 없이 서빙된다."""
    _make_assets(tmp_path, monkeypatch)
    client = await _client()
    try:
        r = await client.get("/adapter.js")  # 코드는 비민감 — 인증 불필요
        assert r.status == 200
        assert r.headers["Content-Type"] == "text/javascript"
        assert "USE_MOCK = false" in (await r.text())
    finally:
        await client.close()


@pytest.mark.asyncio
async def test_asset_img_served_and_missing_404(tmp_path, monkeypatch):
    """/img/<name> 이 PNG 로 서빙되고, 없는 파일은 404."""
    _make_assets(tmp_path, monkeypatch)
    client = await _client()
    try:
        r = await client.get("/img/nexa-logo.png")
        assert r.status == 200
        assert r.headers["Content-Type"] == "image/png"
        assert (await client.get("/img/nope.png")).status == 404
    finally:
        await client.close()


@pytest.mark.asyncio
async def test_cloud_settings_endpoint(monkeypatch):
    """POST /api/cloud — GLM(z.ai) 키·ComfyUI 주소 저장(에이전트 미실행 시 persist 만).

    wire 키명(geminiApiKey/geminiConfigured)은 데스크톱 계약 호환으로 유지, 내부 저장은 glm_api_key.
    """
    saved: dict = {}
    monkeypatch.setattr(webui, "persist_partial", lambda d: saved.update(d))
    client = await _client()
    try:
        r = await (await client.post("/api/cloud", json={"geminiApiKey": "zai-XXX"}, headers={"X-Session": KEY})).json()
        assert r["ok"] is True and r["geminiConfigured"] is True
        assert saved.get("glm_api_key") == "zai-XXX"
        r2 = await (await client.post("/api/cloud", json={"comfyUrl": "http://127.0.0.1:8188/"}, headers={"X-Session": KEY})).json()
        assert r2["comfyUrl"] == "http://127.0.0.1:8188" and r2["needsRestart"] is True
        assert saved.get("comfy_url") == "http://127.0.0.1:8188"
    finally:
        await client.close()


@pytest.mark.asyncio
async def test_server_models_endpoints(monkeypatch):
    """GET/POST /api/servers/{g}/models — 서버별 제공 모델 readback·저장."""
    import provider_agent.config_file as cf

    store: dict = {"models": ["a", "b"]}
    policies: dict = {}
    monkeypatch.setattr(webui, "load_config", lambda: dict(store))
    monkeypatch.setattr(cf, "load_guild_policies", lambda *a, **k: dict(policies))
    monkeypatch.setattr(cf, "set_guild_policy", lambda g, p, *a, **k: policies.setdefault(g, {}).update(p))
    client = await _client()
    try:
        g = await (await client.get("/api/servers/100/models", headers={"X-Session": KEY})).json()
        assert g["ok"] is True and set(g["available"]) == {"a", "b"} and g["chatModels"] == []
        r = await (await client.post("/api/servers/100/models", json={"chatModels": ["a"], "imageEnabled": False}, headers={"X-Session": KEY})).json()
        assert r["ok"] is True
        assert policies[100]["chatModels"] == ["a"] and policies[100]["imageEnabled"] is False
    finally:
        await client.close()


@pytest.mark.asyncio
async def test_comfy_lifecycle_endpoints(monkeypatch):
    """ComfyUI 라이프사이클 엔드포인트(상태/시작/정지/열기/진행) — 설치·실행 안 된 상태의 정직한 응답."""
    from provider_agent import comfy_setup

    monkeypatch.setattr(comfy_setup, "is_installed", lambda directory=None: False)

    async def no_health(url=None):
        return False

    monkeypatch.setattr(comfy_setup, "health", no_health)
    client = await _client()
    try:
        st = await (await client.get("/api/comfy/status", headers={"X-Session": KEY})).json()
        assert st == {"installed": False, "running": False, "busy": False}
        # 미설치 → 시작 거부(정직)
        r = await (await client.post("/api/comfy/start", headers={"X-Session": KEY})).json()
        assert r["ok"] is False
        # 미실행 → 웹UI 열기 거부(정직)
        r = await (await client.post("/api/comfy/open", headers={"X-Session": KEY})).json()
        assert r["ok"] is False
        # 정지는 no-op 으로 성공
        r = await (await client.post("/api/comfy/stop", headers={"X-Session": KEY})).json()
        assert r["ok"] is True
        # 진행 상태는 항상 phase 를 제공
        p = await (await client.get("/api/comfy/setup-progress", headers={"X-Session": KEY})).json()
        assert "phase" in p
    finally:
        await client.close()


@pytest.mark.asyncio
async def test_comfy_start_enables_image_on_running_agent(monkeypatch):
    """로컬 실행 탭의 ComfyUI 시작 버튼은 --enable-image 와 같은 효과로 라이브 적용한다."""
    from provider_agent import comfy_setup
    from provider_agent.config import AgentConfig
    from provider_agent.config_file import load_config, save_config

    class Agent:
        image_ready = False

        async def set_image_enabled(self, on: bool) -> bool:
            self.enabled = on
            self.image_ready = on
            return self.image_ready

    async def start() -> bool:
        return True

    save_config(AgentConfig(token="T", enable_image=False))
    agent = Agent()
    monkeypatch.setattr(comfy_setup, "is_installed", lambda directory=None: True)
    monkeypatch.setattr(comfy_setup, "start", start)
    webui._state["agent"] = agent
    webui._state["task"] = _AliveTask()

    client = await _client()
    try:
        r = await (await client.post("/api/comfy/start", headers={"X-Session": KEY})).json()
        assert r == {"ok": True, "on": True, "imageReady": True, "applied": "live"}
        assert agent.enabled is True
        assert load_config()["enable_image"] is True
    finally:
        await client.close()


@pytest.mark.asyncio
async def test_comfy_start_enables_image_for_background_service(monkeypatch):
    """이미 백그라운드 서비스가 제공 중이면 ComfyUI 시작 후 서비스를 재기동해 이미지 설정을 반영한다."""
    from provider_agent import comfy_setup, service, singleton
    from provider_agent.config import AgentConfig
    from provider_agent.config_file import load_config, save_config

    kicked = {"called": False}

    async def start() -> bool:
        return True

    def kickstart() -> bool:
        kicked["called"] = True
        return True

    save_config(AgentConfig(token="T", enable_image=False))
    monkeypatch.setattr(comfy_setup, "is_installed", lambda directory=None: True)
    monkeypatch.setattr(comfy_setup, "start", start)
    monkeypatch.setattr(singleton, "held_by_other", lambda: True)
    monkeypatch.setattr(service, "is_installed", lambda: True)
    monkeypatch.setattr(service, "kickstart", kickstart)

    client = await _client()
    try:
        r = await (await client.post("/api/comfy/start", headers={"X-Session": KEY})).json()
        assert r == {"ok": True, "on": True, "imageReady": False, "applied": "service"}
        assert kicked["called"] is True
        assert load_config()["enable_image"] is True
    finally:
        await client.close()


@pytest.mark.asyncio
async def test_comfy_setup_start_persists_enable_image(monkeypatch):
    """설치 버튼도 긴 설치가 끝나기 전부터 다음 에이전트 시작값을 --enable-image 로 저장한다."""
    from provider_agent import comfy_setup
    from provider_agent.config import AgentConfig
    from provider_agent.config_file import load_config, save_config

    async def run_setup(_url=None) -> bool:
        return False

    save_config(AgentConfig(token="T", enable_image=False))
    monkeypatch.setattr(comfy_setup, "is_busy", lambda: False)
    monkeypatch.setattr(comfy_setup, "run_setup", run_setup)

    client = await _client()
    try:
        r = await (await client.post("/api/comfy/setup", headers={"X-Session": KEY})).json()
        await asyncio.sleep(0)
        assert r == {"ok": True, "on": True}
        assert load_config()["enable_image"] is True
    finally:
        await client.close()


@pytest.mark.asyncio
async def test_comfy_models_and_select(monkeypatch):
    """ComfyUI 체크포인트 목록(폴더 스캔) + 선택 저장. 미설치/미실행이면 빈 목록·정직한 응답."""

    async def no_list(self):
        return []  # ComfyUI 미실행 → 빈 목록

    monkeypatch.setattr("provider_agent.comfy.ComfyClient.list_checkpoints", no_list)
    saved = {}
    monkeypatch.setattr(webui, "load_config", lambda: saved)
    monkeypatch.setattr("provider_agent.config_file.persist_partial", lambda d, *a, **k: saved.update(d))
    client = await _client()
    try:
        m = await (await client.get("/api/comfy/models", headers={"X-Session": KEY})).json()
        assert m["models"] == [] and m["active"] is None
        # 모델 미지정 → 거부
        r = await (await client.post("/api/comfy/select", json={}, headers={"X-Session": KEY})).json()
        assert r["ok"] is False
        # 모델 지정 → 저장(active)
        r = await (await client.post("/api/comfy/select", json={"model": "x.safetensors"}, headers={"X-Session": KEY})).json()
        assert r["ok"] is True and r["active"] == "x.safetensors"
        assert saved.get("comfy_model") == "x.safetensors"
    finally:
        await client.close()


@pytest.mark.asyncio
async def test_comfy_install_model_validates(monkeypatch):
    """임의 모델 URL 설치 — 빈/잘못된 URL 은 정직하게 거부."""
    client = await _client()
    try:
        r = await (await client.post("/api/comfy/install-model", json={}, headers={"X-Session": KEY})).json()
        assert r["ok"] is False
        r = await (await client.post("/api/comfy/install-model", json={"url": "https://x/readme.txt"}, headers={"X-Session": KEY})).json()
        assert r["ok"] is False
    finally:
        await client.close()
