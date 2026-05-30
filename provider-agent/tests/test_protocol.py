"""프로토콜 테스트 — Kotlin 중앙 서버와 동일 와이어(camelCase) 검증 + round-trip."""
from __future__ import annotations

import json

import pytest

from provider_agent import protocol as p


def test_camelcase_wire_keys_match_kotlin():
    # 에이전트가 보내는 프레임의 JSON 키가 Kotlin Jackson 출력(camelCase)과 정확히 일치해야 한다.
    assert json.loads(p.dumps_frame(p.AuthFrame(token="t", agent_version="0.1", platform="mac"))) == {
        "type": "auth",
        "token": "t",
        "protocolVersion": "1.0",
        "agentVersion": "0.1",
        "platform": "mac",
    }
    assert json.loads(p.dumps_frame(p.ProviderHelloFrame(models=["llama3.1:8b"], max_concurrency=2, remaining_daily_requests=42))) == {
        "type": "provider_hello",
        "models": ["llama3.1:8b"],
        "maxConcurrency": 2,
        "remainingDailyRequests": 42,
    }
    assert json.loads(p.dumps_frame(p.InferResult(request_id="r1", text="답", usage=p.Usage(3, 4)))) == {
        "type": "result",
        "requestId": "r1",
        "text": "답",
        "usage": {"promptTokens": 3, "completionTokens": 4},
    }
    assert json.loads(p.dumps_frame(p.ProviderStatusFrame(load="high", battery="discharging", online=True, busy=True))) == {
        "type": "provider_status",
        "load": "high",
        "battery": "discharging",
        "online": True,
        "busy": True,
    }
    assert json.loads(p.dumps_frame(p.PongFrame())) == {"type": "pong"}


def test_parse_server_frames():
    # 중앙 서버(Kotlin)가 보내는 프레임(camelCase)을 파싱할 수 있어야 한다.
    infer = p.loads_frame('{"type":"infer","requestId":"r1","model":"m","prompt":"안녕","options":{"temperature":0.3}}')
    assert isinstance(infer, p.InferRequest)
    assert infer.request_id == "r1" and infer.prompt == "안녕" and infer.options == {"temperature": 0.3}

    ok = p.loads_frame('{"type":"auth_ok","protocolVersion":"1.0","sessionId":"s1"}')
    assert isinstance(ok, p.AuthOkFrame) and ok.session_id == "s1"

    cancel = p.loads_frame('{"type":"cancel","requestId":"r1"}')
    assert isinstance(cancel, p.CancelFrame) and cancel.request_id == "r1"

    assert isinstance(p.loads_frame('{"type":"ping"}'), p.PingFrame)


def test_round_trip_all_frames():
    frames = [
        p.AuthFrame(token="secret", agent_version="0.1", platform="mac"),
        p.AuthOkFrame(session_id="s"),
        p.AuthErrFrame(code="AUTH_FAILED", message="bad"),
        p.InferRequest(request_id="r", model="m", prompt="안녕", options={"temperature": 0.2}),
        p.InferResult(request_id="r", text="결과", usage=p.Usage(1, 2)),
        p.InferError(request_id="r", code="OLLAMA_ERROR", message="x"),
        p.ChunkFrame(request_id="r", delta="부분", done=False),
        p.PingFrame(),
        p.PongFrame(),
        p.CancelFrame(request_id="r"),
        p.ProviderHelloFrame(models=["m"], max_concurrency=1, remaining_daily_requests=5),
        p.ProviderStatusFrame(load="idle"),
    ]
    for f in frames:
        assert p.loads_frame(p.dumps_frame(f)) == f


def test_korean_preserved():
    assert "안녕하세요" in p.dumps_frame(p.InferResult(request_id="r", text="안녕하세요"))


def test_option_whitelist():
    ir = p.InferRequest(request_id="r", options={"temperature": 0.3, "evil": "x"})
    assert ir.options == {"temperature": 0.3}


def test_token_masked():
    s = repr(p.AuthFrame(token="secret-abc"))
    assert "secret-abc" not in s and "***" in s


def test_unknown_and_missing():
    with pytest.raises(p.ProtocolError):
        p.loads_frame('{"type":"nope"}')
    with pytest.raises(p.ProtocolError):
        p.loads_frame('{"type":"infer"}')  # requestId 누락
    with pytest.raises(p.ProtocolError):
        p.loads_frame("{not json")


def test_prompt_length_limit():
    with pytest.raises(p.ProtocolError):
        p.InferRequest(request_id="r", prompt="a" * (p.MAX_PROMPT_CHARS + 1))
