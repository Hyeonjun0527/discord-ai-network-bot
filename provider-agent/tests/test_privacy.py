"""개인정보 보호 테스트 — 프롬프트 원문/시크릿이 로그에 남지 않는지 검증."""
from __future__ import annotations

import logging

import pytest
from test_agent import FakeConn, FakeOllama

from provider_agent.agent import ProviderAgent
from provider_agent.config import AgentConfig
from provider_agent.logging_setup import RedactingFilter
from provider_agent.protocol import InferRequest

SECRET_PROMPT = "내-비밀-질문-AB12CD34-민감정보-기밀"


def test_redacting_filter_masks_secrets():
    f = RedactingFilter()
    rec = logging.LogRecord("x", logging.INFO, __file__, 1, "token=ABC123 password: hunter2", None, None)
    assert f.filter(rec) is True
    msg = rec.getMessage()
    assert "ABC123" not in msg
    assert "hunter2" not in msg
    assert "***" in msg


def test_redacting_filter_keeps_normal_text():
    f = RedactingFilter()
    rec = logging.LogRecord("x", logging.INFO, __file__, 1, "상태: 처리 3건", None, None)
    f.filter(rec)
    assert rec.getMessage() == "상태: 처리 3건"


@pytest.mark.asyncio
async def test_prompt_never_logged(monkeypatch, caplog):
    """추론 처리 전 과정에서 프롬프트 원문이 어떤 레벨 로그에도 찍히면 안 된다."""
    monkeypatch.setattr("provider_agent.sysinfo.load_level", lambda: "idle")
    monkeypatch.setattr("provider_agent.sysinfo.battery_state", lambda: "charging")
    agent = ProviderAgent(AgentConfig(token="T"), ollama=FakeOllama(text="ok"))  # type: ignore[arg-type]
    conn = FakeConn()
    with caplog.at_level(logging.DEBUG, logger="provider_agent"):
        await agent.handle_infer(conn, InferRequest(request_id="r1", prompt=SECRET_PROMPT))  # type: ignore[arg-type]
    blob = "\n".join(r.getMessage() for r in caplog.records)
    assert SECRET_PROMPT not in blob
    assert "민감정보" not in blob


@pytest.mark.asyncio
async def test_cancelled_request_logs_no_prompt(monkeypatch, caplog):
    monkeypatch.setattr("provider_agent.sysinfo.load_level", lambda: "idle")
    monkeypatch.setattr("provider_agent.sysinfo.battery_state", lambda: "charging")
    agent = ProviderAgent(AgentConfig(token="T", max_concurrency=1), ollama=FakeOllama(delay=1.0))  # type: ignore[arg-type]
    conn = FakeConn()
    with caplog.at_level(logging.DEBUG, logger="provider_agent"):
        import asyncio

        await agent._on_server_frame(conn, InferRequest(request_id="r1", prompt=SECRET_PROMPT))  # type: ignore[arg-type]
        await asyncio.sleep(0.02)
        from provider_agent.protocol import CancelFrame

        await agent._on_server_frame(conn, CancelFrame(request_id="r1"))  # type: ignore[arg-type]
        await asyncio.sleep(0.05)
    blob = "\n".join(r.getMessage() for r in caplog.records)
    assert SECRET_PROMPT not in blob
