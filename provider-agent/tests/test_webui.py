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
    monkeypatch.setenv("XDG_CONFIG_HOME", str(tmp_path))
    webui._state["agent"] = None
    webui._state["task"] = None
    webui._log_lines.clear()
    yield


async def _client() -> TestClient:
    client = TestClient(TestServer(webui.build_app(KEY)))
    await client.start_server()
    return client


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
async def test_connect_callback_rejects_bad_state():
    client = await _client()
    try:
        r = await client.get("/connect/callback", params={"token": "T", "state": "wrong"})
        assert r.status == 403
    finally:
        await client.close()


@pytest.mark.asyncio
async def test_connect_callback_saves_token():
    client = await _client()
    try:
        r = await client.get("/connect/callback", params={"token": "GOT-TOKEN", "state": KEY})
        assert r.status == 200
        assert load_config()["token"] == "GOT-TOKEN"  # 콜백이 토큰을 저장
    finally:
        await client.close()


@pytest.mark.asyncio
async def test_start_stop_lifecycle(monkeypatch):
    # 토큰 저장
    monkeypatch.setattr("provider_agent.service.install_service", lambda: "ok")
    started = {}

    class FakeAgent:
        def __init__(self, cfg):
            self._stop = asyncio.Event()
            self.processed = 3
            self.image_ready = False
            self.models = ["m"]

        async def run(self, install_signals=True):
            started["ran"] = True
            await self._stop.wait()
            return 0

        def request_stop(self):
            self._stop.set()

        def is_connected(self):
            return True

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
