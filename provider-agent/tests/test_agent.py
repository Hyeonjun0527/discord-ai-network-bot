"""에이전트 처리 테스트 — 가짜 ollama·연결 주입(빠르고 결정적)."""
from __future__ import annotations

import asyncio

import pytest

from provider_agent.agent import ProviderAgent
from provider_agent.config import AgentConfig
from provider_agent.constants import ErrorCode
from provider_agent.ollama import OllamaError
from provider_agent.protocol import (
    CancelFrame,
    Frame,
    InferError,
    InferRequest,
    InferResult,
    Usage,
)


@pytest.fixture(autouse=True)
def _no_auto_pause(monkeypatch):
    """이 모듈의 테스트는 자원 자동 pause 와 무관하게 결정적이어야 한다(하드웨어 독립)."""
    monkeypatch.setattr("provider_agent.sysinfo.load_level", lambda: "idle")
    monkeypatch.setattr("provider_agent.sysinfo.battery_state", lambda: "charging")


class FakeConn:
    def __init__(self) -> None:
        self.sent: list[Frame] = []
        self.authed = True

    async def send(self, frame: Frame) -> None:
        self.sent.append(frame)


class FakeOllama:
    def __init__(self, text: str = "결과", usage: Usage | None = None, error: str | None = None, delay: float = 0.0):
        self.text = text
        self.usage = usage or Usage(1, 2)
        self.error = error
        self.delay = delay

    async def generate(self, prompt: str, model: str | None) -> tuple[str, Usage]:
        if self.delay:
            await asyncio.sleep(self.delay)
        if self.error:
            raise OllamaError(self.error)
        return self.text, self.usage

    async def list_models(self) -> list[str]:
        return ["m1"]

    async def generate_stream(self, prompt: str, model: str | None):
        for piece in ("안", "녕", "!"):
            yield ("chunk", piece)
        yield ("done", self.usage)


@pytest.mark.asyncio
async def test_handle_infer_streaming_emits_chunks():
    """스트리밍(#142): req.stream 시 ChunkFrame 점진 전송 + done 종료."""
    from provider_agent.protocol import ChunkFrame

    agent = ProviderAgent(AgentConfig(token="T"), ollama=FakeOllama())  # type: ignore[arg-type]
    conn = FakeConn()
    await agent.handle_infer(conn, InferRequest(request_id="s1", prompt="안녕", stream=True))  # type: ignore[arg-type]
    chunks = [f for f in conn.sent if isinstance(f, ChunkFrame)]
    deltas = [c.delta for c in chunks if not c.done]
    assert "".join(deltas) == "안녕!"
    assert chunks[-1].done is True
    assert agent.processed == 1


@pytest.mark.asyncio
async def test_handle_infer_success():
    agent = ProviderAgent(AgentConfig(token="T"), ollama=FakeOllama(text="답", usage=Usage(3, 4)))  # type: ignore[arg-type]
    conn = FakeConn()
    await agent.handle_infer(conn, InferRequest(request_id="r1", prompt="안녕"))  # type: ignore[arg-type]
    res = conn.sent[0]
    assert isinstance(res, InferResult) and res.text == "답" and res.usage.prompt_tokens == 3


@pytest.mark.asyncio
async def test_handle_infer_ollama_error():
    agent = ProviderAgent(AgentConfig(token="T"), ollama=FakeOllama(error="boom"))  # type: ignore[arg-type]
    conn = FakeConn()
    await agent.handle_infer(conn, InferRequest(request_id="r1", prompt="x"))  # type: ignore[arg-type]
    err = conn.sent[0]
    assert isinstance(err, InferError) and err.code == ErrorCode.OLLAMA_ERROR


@pytest.mark.asyncio
async def test_daily_limit():
    agent = ProviderAgent(AgentConfig(token="T", daily_limit=1), ollama=FakeOllama())  # type: ignore[arg-type]
    conn = FakeConn()
    await agent.handle_infer(conn, InferRequest(request_id="r1", prompt="a"))  # type: ignore[arg-type]
    await agent.handle_infer(conn, InferRequest(request_id="r2", prompt="b"))  # type: ignore[arg-type]
    assert isinstance(conn.sent[0], InferResult)
    assert isinstance(conn.sent[1], InferError) and conn.sent[1].code == ErrorCode.BUSY


@pytest.mark.asyncio
async def test_cancel_no_result():
    agent = ProviderAgent(AgentConfig(token="T", max_concurrency=1), ollama=FakeOllama(delay=2.0))  # type: ignore[arg-type]
    conn = FakeConn()
    await agent._on_server_frame(conn, InferRequest(request_id="r1", prompt="x"))  # type: ignore[arg-type]
    await asyncio.sleep(0.05)
    await agent._on_server_frame(conn, CancelFrame(request_id="r1"))  # type: ignore[arg-type]
    await asyncio.sleep(0.1)
    assert not any(isinstance(f, InferResult) for f in conn.sent)


@pytest.mark.asyncio
async def test_concurrency_limit():
    seen: list[int] = []
    agent = ProviderAgent(AgentConfig(token="T", max_concurrency=1))  # ollama 교체
    conn = FakeConn()

    class Probe(FakeOllama):
        async def generate(self, prompt: str, model: str | None) -> tuple[str, Usage]:
            seen.append(agent._inflight)
            await asyncio.sleep(0.05)
            return "x", Usage()

    agent._ollama = Probe()  # type: ignore[assignment]
    await asyncio.gather(
        agent.handle_infer(conn, InferRequest(request_id="a", prompt="1")),  # type: ignore[arg-type]
        agent.handle_infer(conn, InferRequest(request_id="b", prompt="2")),  # type: ignore[arg-type]
    )
    assert max(seen) == 1  # 동시 1개만 처리


def test_build_hello():
    agent = ProviderAgent(AgentConfig(token="T", models=("m1", "m2"), max_concurrency=3))
    hello = agent._build_hello()
    assert hello.models == ["m1", "m2"] and hello.max_concurrency == 3


@pytest.mark.asyncio
async def test_model_default_to_own():
    # 서버가 모델을 안 주면(model=None) 에이전트가 자기 첫 모델로 처리한다(E2E 회귀 방지).
    class RecOllama(FakeOllama):
        last_model: str | None = None

        async def generate(self, prompt: str, model: str | None) -> tuple[str, Usage]:
            RecOllama.last_model = model
            return "ok", Usage()

    agent = ProviderAgent(AgentConfig(token="T", models=("mymodel",)), ollama=RecOllama())  # type: ignore[arg-type]
    await agent.handle_infer(FakeConn(), InferRequest(request_id="r", prompt="x"))  # type: ignore[arg-type]
    assert RecOllama.last_model == "mymodel"


def test_reload_models_hot_reload(monkeypatch, tmp_path):
    """SIGHUP hot-reload(#129): 저장 설정에서 models 재적용."""
    monkeypatch.setenv("XDG_CONFIG_HOME", str(tmp_path))
    from provider_agent.config_file import save_config
    agent = ProviderAgent(AgentConfig(token="T", models=("old",)))  # type: ignore[arg-type]
    assert agent._models == ["old"]
    # 새 모델로 설정 저장 후 reload
    save_config(AgentConfig(token="T", models=("a", "b")))
    assert agent.reload_models() == ["a", "b"]
    assert agent._models == ["a", "b"]
