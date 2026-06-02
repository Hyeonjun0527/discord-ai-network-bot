"""자원 보호(자동 pause) + 응답 크기 제한 테스트."""
from __future__ import annotations

import pytest
from test_agent import FakeConn, FakeOllama

from provider_agent import sysinfo
from provider_agent.agent import ProviderAgent
from provider_agent.config import AgentConfig
from provider_agent.constants import MAX_RESPONSE_CHARS, ErrorCode
from provider_agent.protocol import ChunkFrame, InferError, InferRequest, InferResult, Usage


def test_should_pause_high_load(monkeypatch):
    monkeypatch.setattr(sysinfo, "load_level", lambda: "high")
    monkeypatch.setattr(sysinfo, "battery_state", lambda: "charging")
    paused, reason = sysinfo.should_pause()
    assert paused and reason == "high_load"


def test_should_pause_on_battery(monkeypatch):
    monkeypatch.setattr(sysinfo, "load_level", lambda: "idle")
    monkeypatch.setattr(sysinfo, "battery_state", lambda: "discharging")
    paused, reason = sysinfo.should_pause()
    assert paused and reason == "on_battery"


def test_no_pause_when_idle_and_plugged(monkeypatch):
    monkeypatch.setattr(sysinfo, "load_level", lambda: "idle")
    monkeypatch.setattr(sysinfo, "battery_state", lambda: "charging")
    assert sysinfo.should_pause() == (False, "")


def test_pause_flags_respected(monkeypatch):
    monkeypatch.setattr(sysinfo, "load_level", lambda: "high")
    monkeypatch.setattr(sysinfo, "battery_state", lambda: "discharging")
    # 둘 다 끄면 pause 하지 않는다.
    assert sysinfo.should_pause(pause_on_battery=False, pause_on_high_load=False) == (False, "")


@pytest.mark.asyncio
async def test_handle_infer_pauses_on_high_load(monkeypatch):
    monkeypatch.setattr(sysinfo, "load_level", lambda: "high")
    monkeypatch.setattr(sysinfo, "battery_state", lambda: "charging")
    agent = ProviderAgent(AgentConfig(token="T"), ollama=FakeOllama())  # type: ignore[arg-type]
    conn = FakeConn()
    await agent.handle_infer(conn, InferRequest(request_id="r1", prompt="x"))  # type: ignore[arg-type]
    assert isinstance(conn.sent[0], InferError)
    assert conn.sent[0].code == ErrorCode.BUSY
    # pause 로 반려된 요청은 일일 한도를 소모하지 않는다.
    assert agent.processed == 0


@pytest.mark.asyncio
async def test_response_truncated(monkeypatch):
    monkeypatch.setattr(sysinfo, "should_pause", lambda *a, **k: (False, ""))
    huge = "가" * (MAX_RESPONSE_CHARS + 5000)
    agent = ProviderAgent(AgentConfig(token="T"), ollama=FakeOllama(text=huge))  # type: ignore[arg-type]
    conn = FakeConn()
    await agent.handle_infer(conn, InferRequest(request_id="r1", prompt="x"))  # type: ignore[arg-type]
    res = conn.sent[0]
    assert isinstance(res, InferResult)
    assert len(res.text) == MAX_RESPONSE_CHARS


@pytest.mark.asyncio
async def test_stream_response_truncated(monkeypatch):
    monkeypatch.setattr(sysinfo, "should_pause", lambda *a, **k: (False, ""))

    class BigStream(FakeOllama):
        async def generate_stream(self, prompt, model):
            # 한 청크가 상한을 크게 초과
            yield ("chunk", "나" * (MAX_RESPONSE_CHARS + 1000))
            yield ("done", Usage())

    agent = ProviderAgent(AgentConfig(token="T"), ollama=BigStream())  # type: ignore[arg-type]
    conn = FakeConn()
    await agent.handle_infer(conn, InferRequest(request_id="r1", prompt="x", stream=True))  # type: ignore[arg-type]
    deltas = [f.delta for f in conn.sent if isinstance(f, ChunkFrame) and not f.done]
    assert sum(len(d) for d in deltas) == MAX_RESPONSE_CHARS
