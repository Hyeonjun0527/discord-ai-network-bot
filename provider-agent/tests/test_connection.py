"""WS 연결·인증·재연결 테스트 — 가짜 aiohttp 릴레이 서버로 실소켓 검증."""
from __future__ import annotations

import asyncio

import aiohttp
import pytest
from aiohttp import web
from aiohttp.test_utils import TestServer

from provider_agent import protocol as p
from provider_agent.config import AgentConfig
from provider_agent.connection import AgentConnection


class FakeRelay:
    """중앙 서버 흉내. mode 에 따라 시나리오를 바꾼다."""

    def __init__(self, mode: str = "ok", durable_token: str = "") -> None:
        self.mode = mode
        self.durable_token = durable_token
        self.received: list[p.Frame] = []
        self.connections = 0
        self.got_hello = asyncio.Event()
        self.got_pong = asyncio.Event()
        self.app = web.Application()
        self.app.router.add_get("/agent", self._handler)

    async def _handler(self, request: web.Request) -> web.WebSocketResponse:
        ws = web.WebSocketResponse()
        await ws.prepare(request)
        self.connections += 1
        async for msg in ws:
            if msg.type != aiohttp.WSMsgType.TEXT:
                break
            frame = p.loads_frame(msg.data)
            self.received.append(frame)
            if isinstance(frame, p.AuthFrame):
                if self.mode == "authfail":
                    await ws.send_str(p.dumps_frame(p.AuthErrFrame(message="bad token")))
                    await ws.close()
                    break
                await ws.send_str(
                    p.dumps_frame(p.AuthOkFrame(session_id="s1", provider_token=self.durable_token))
                )
            elif isinstance(frame, p.ProviderHelloFrame):
                self.got_hello.set()
                if self.mode == "infer":
                    await ws.send_str(p.dumps_frame(p.InferRequest(request_id="r1", model="m", prompt="안녕")))
                elif self.mode == "ping":
                    await ws.send_str(p.dumps_frame(p.PingFrame()))
                elif self.mode == "dropafterhello":
                    await ws.close()
                    break
            elif isinstance(frame, p.PongFrame):
                self.got_pong.set()
        return ws


async def _start(relay: FakeRelay) -> tuple[TestServer, str]:
    server = TestServer(relay.app)
    await server.start_server()
    return server, f"ws://{server.host}:{server.port}/agent"


async def _noop(conn: AgentConnection, frame: p.Frame) -> None:
    return None


@pytest.mark.asyncio
async def test_auth_and_hello():
    relay = FakeRelay("ok")
    server, url = await _start(relay)
    cfg = AgentConfig(token="T", relay_url=url, heartbeat_seconds=5)
    conn = AgentConnection(cfg, _noop, lambda _g=None: p.ProviderHelloFrame(models=["m"], max_concurrency=2))
    task = asyncio.create_task(conn.run())
    try:
        await asyncio.wait_for(relay.got_hello.wait(), 3)
        assert conn.authed
        hello = next(f for f in relay.received if isinstance(f, p.ProviderHelloFrame))
        assert hello.models == ["m"] and hello.max_concurrency == 2
    finally:
        await conn.stop()
        task.cancel()
        await server.close()


@pytest.mark.asyncio
async def test_infer_delivered_to_handler():
    relay = FakeRelay("infer")
    server, url = await _start(relay)
    got: asyncio.Queue[p.Frame] = asyncio.Queue()

    async def handler(conn: AgentConnection, frame: p.Frame) -> None:
        await got.put(frame)

    cfg = AgentConfig(token="T", relay_url=url, heartbeat_seconds=5)
    conn = AgentConnection(cfg, handler, lambda _g=None: p.ProviderHelloFrame(models=["m"]))
    task = asyncio.create_task(conn.run())
    try:
        frame = await asyncio.wait_for(got.get(), 3)
        assert isinstance(frame, p.InferRequest) and frame.prompt == "안녕"
    finally:
        await conn.stop()
        task.cancel()
        await server.close()


@pytest.mark.asyncio
async def test_ping_pong():
    relay = FakeRelay("ping")
    server, url = await _start(relay)
    cfg = AgentConfig(token="T", relay_url=url, heartbeat_seconds=5)
    conn = AgentConnection(cfg, _noop, lambda _g=None: p.ProviderHelloFrame())
    task = asyncio.create_task(conn.run())
    try:
        await asyncio.wait_for(relay.got_pong.wait(), 3)
    finally:
        await conn.stop()
        task.cancel()
        await server.close()


@pytest.mark.asyncio
async def test_auth_fail_stops_no_retry():
    relay = FakeRelay("authfail")
    server, url = await _start(relay)
    cfg = AgentConfig(token="BAD", relay_url=url)
    conn = AgentConnection(cfg, _noop, lambda _g=None: p.ProviderHelloFrame())
    try:
        # run() 은 인증 실패 시 무한 재시도하지 않고 반환해야 한다.
        await asyncio.wait_for(conn.run(), 3)
        assert not conn.authed
        assert relay.connections == 1  # 재연결 시도 없음
    finally:
        await server.close()


@pytest.mark.asyncio
async def test_reconnect_after_drop():
    relay = FakeRelay("dropafterhello")
    server, url = await _start(relay)
    cfg = AgentConfig(token="T", relay_url=url, heartbeat_seconds=5, reconnect_max_seconds=0.3)
    conn = AgentConnection(cfg, _noop, lambda _g=None: p.ProviderHelloFrame())
    task = asyncio.create_task(conn.run())
    try:
        for _ in range(40):
            if relay.connections >= 2:
                break
            await asyncio.sleep(0.1)
        assert relay.connections >= 2  # 끊긴 뒤 재연결함
    finally:
        await conn.stop()
        task.cancel()
        await server.close()


@pytest.mark.asyncio
async def test_durable_token_persisted_and_reused(monkeypatch, tmp_path):
    """auth_ok 의 providerToken 을 저장하고 이후 인증에 재사용한다(재연결·재시작 set-and-forget)."""
    monkeypatch.setenv("XDG_CONFIG_HOME", str(tmp_path))
    from provider_agent.config_file import load_config

    relay = FakeRelay("ok", durable_token="dv1.DURABLE.TOKEN")
    server, url = await _start(relay)
    cfg = AgentConfig(token="ONETIME", relay_url=url, heartbeat_seconds=5)
    conn = AgentConnection(cfg, _noop, lambda _g=None: p.ProviderHelloFrame(models=["m"]))
    task = asyncio.create_task(conn.run())
    try:
        await asyncio.wait_for(relay.got_hello.wait(), 3)
        # durable 토큰이 in-memory 교체 + config 에 저장됨
        assert conn._token == "dv1.DURABLE.TOKEN"
        assert load_config().get("token") == "dv1.DURABLE.TOKEN"
    finally:
        await conn.stop()
        task.cancel()
        await server.close()
