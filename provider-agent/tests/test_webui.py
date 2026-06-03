"""로컬 웹 설정 UI 테스트(aiohttp 테스트 클라이언트 — 디스플레이 불필요)."""
from __future__ import annotations

import pytest
from aiohttp.test_utils import TestClient, TestServer

from provider_agent import webui
from provider_agent.config_file import load_config


@pytest.fixture
def key():
    return "test-session-key"


async def _client(key: str) -> TestClient:
    app = webui.build_app(key)
    client = TestClient(TestServer(app))
    await client.start_server()
    return client


@pytest.mark.asyncio
async def test_index_serves_page(key):
    client = await _client(key)
    try:
        r = await client.get("/")
        assert r.status == 200
        body = await r.text()
        assert "프로바이더 설정" in body
        assert key in body  # 세션 키가 페이지에 주입됨
    finally:
        await client.close()


@pytest.mark.asyncio
async def test_api_requires_session_key(key):
    client = await _client(key)
    try:
        assert (await client.get("/api/status")).status == 403  # 키 없음
        assert (await client.get("/api/status", headers={"X-Session": "wrong"})).status == 403
        assert (await client.get("/api/status", headers={"X-Session": key})).status == 200
    finally:
        await client.close()


@pytest.mark.asyncio
async def test_setup_saves_config_and_installs_service(monkeypatch, tmp_path, key):
    monkeypatch.setenv("XDG_CONFIG_HOME", str(tmp_path))
    called = {}
    monkeypatch.setattr("provider_agent.service.install_service", lambda: called.setdefault("svc", True) or "ok")
    client = await _client(key)
    try:
        r = await client.post(
            "/api/setup",
            headers={"X-Session": key},
            json={
                "token": "ABCDE-FGHIJ-KLMNP",
                "relayUrl": "wss://x/agent",
                "models": "llama3.1:8b, gemma4",
                "enableImage": True,
                "installService": True,
            },
        )
        d = await r.json()
        assert d["ok"] is True and d["serviceInstalled"] is True
        assert called.get("svc") is True
        saved = load_config()
        assert saved["token"] == "ABCDE-FGHIJ-KLMNP"
        assert saved["relay_url"] == "wss://x/agent"
        assert saved["models"] == ["llama3.1:8b", "gemma4"]
        assert saved["enable_image"] is True
    finally:
        await client.close()


@pytest.mark.asyncio
async def test_setup_rejects_empty_token(monkeypatch, tmp_path, key):
    monkeypatch.setenv("XDG_CONFIG_HOME", str(tmp_path))
    client = await _client(key)
    try:
        r = await client.post("/api/setup", headers={"X-Session": key}, json={"token": ""})
        d = await r.json()
        assert d["ok"] is False
    finally:
        await client.close()
