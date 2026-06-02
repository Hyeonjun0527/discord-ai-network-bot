"""중앙 서버 명령 제한 테스트 — 허용되지 않은/알 수 없는 프레임은 거부+로그.

서버가 에이전트에게 지시할 수 있는 명령은 infer/cancel(+ ping) 뿐이다. shell/파일/URL/모델
다운로드를 지시하는 프레임 타입은 프로토콜에 아예 존재하지 않으며, 에이전트→서버 전용
프레임(result/status/hello 등)을 서버가 보내면 처리하지 않고 거부한다.
"""
from __future__ import annotations

import logging

import pytest

from provider_agent import protocol as p
from provider_agent.config import AgentConfig
from provider_agent.connection import AgentConnection
from provider_agent.protocol import ProtocolError, loads_frame


async def _noop(conn, frame):  # pragma: no cover - 사용되지 않아야 정상
    raise AssertionError(f"허용되지 않은 프레임이 핸들러로 전달됨: {frame}")


def _conn(handler):
    return AgentConnection(AgentConfig(token="T"), handler, lambda: p.ProviderHelloFrame())


def test_unknown_frame_type_rejected():
    with pytest.raises(ProtocolError):
        loads_frame('{"type":"shell_exec","cmd":"rm -rf /"}')
    with pytest.raises(ProtocolError):
        loads_frame('{"type":"read_file","path":"/etc/passwd"}')
    with pytest.raises(ProtocolError):
        loads_frame('{"type":"pull","model":"evil"}')


@pytest.mark.asyncio
async def test_disallowed_server_frame_dropped_and_logged(caplog):
    conn = _conn(_noop)
    with caplog.at_level(logging.WARNING, logger="provider_agent.connection"):
        # result 는 에이전트→서버 전용. 서버가 보내면 거부되어야 한다(핸들러 미호출).
        await conn._dispatch(p.InferResult(request_id="r1", text="x"))
        await conn._dispatch(p.ProviderStatusFrame())
    msgs = "\n".join(r.getMessage() for r in caplog.records)
    assert "거부" in msgs


@pytest.mark.asyncio
async def test_infer_and_cancel_pass_to_handler():
    seen = []

    async def handler(conn, frame):
        seen.append(frame)

    conn = _conn(handler)
    await conn._dispatch(p.InferRequest(request_id="r1", prompt="x"))
    await conn._dispatch(p.CancelFrame(request_id="r1"))
    assert len(seen) == 2
    assert isinstance(seen[0], p.InferRequest)
    assert isinstance(seen[1], p.CancelFrame)
