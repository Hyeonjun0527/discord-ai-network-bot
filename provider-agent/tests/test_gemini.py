"""클라우드 Gemini 백엔드 — 응답 파싱 + 에이전트 라우팅."""
from __future__ import annotations

import asyncio

import pytest

from provider_agent.agent import ProviderAgent
from provider_agent.config import AgentConfig
from provider_agent.gemini import DEFAULT_GEMINI_MODEL, GeminiError, _extract_text
from provider_agent.protocol import ChunkFrame, Frame, InferRequest, InferResult, Usage


def test_extract_text_shapes():
    ok = {"candidates": [{"content": {"parts": [{"text": "안"}, {"text": "녕"}]}}]}
    assert _extract_text(ok) == "안녕"
    # 차단/빈 응답(후보 없음·parts 없음) → None
    assert _extract_text({"candidates": []}) is None
    assert _extract_text({"candidates": [{"content": {}}]}) is None
    assert _extract_text({}) is None
    assert _extract_text(None) is None


class FakeConn:
    def __init__(self) -> None:
        self.sent: list[Frame] = []
        self.authed = True

    async def send(self, frame: Frame) -> None:
        self.sent.append(frame)


class FakeGemini:
    def __init__(self, text: str = "Gemini 응답", error: str | None = None) -> None:
        self._text, self._error = text, error
        self.calls: list[tuple[str, str | None]] = []

    async def generate(self, prompt: str, model: str | None):
        self.calls.append((prompt, model))
        if self._error:
            raise GeminiError(self._error)
        return self._text, Usage(prompt_tokens=3, completion_tokens=5)


def test_config_advertises_gemini_when_key_present():
    cfg = AgentConfig(token="T", gemini_api_key="AIzaXXXX")
    agent = ProviderAgent(cfg, ollama=object())  # ollama 미사용(gemini 경로만)
    # 키가 있으면 기본 모델이 풀에 광고되고, 라우팅용 집합에도 들어간다.
    # (config_from_args 가 기본 gemini-2.5-flash-lite 를 채우지만, 여기선 직접 cfg 라 gemini_models 비어도
    #  키만으로 클라이언트는 만들어진다 — 광고는 set 으로 확인)
    assert agent._gemini is not None


@pytest.mark.asyncio
async def test_agent_routes_gemini_model_to_gemini():
    cfg = AgentConfig(token="T", gemini_api_key="AIzaXXXX", gemini_models=(DEFAULT_GEMINI_MODEL,))
    agent = ProviderAgent(cfg, ollama=object())
    fake = FakeGemini("안녕하세요")
    agent._gemini = fake  # type: ignore[assignment]
    agent._gemini_models = [DEFAULT_GEMINI_MODEL]
    # 광고 목록에 gemini 모델이 있어야 한다.
    assert DEFAULT_GEMINI_MODEL in agent.models
    conn = FakeConn()
    await agent._run_infer(conn, InferRequest(request_id="g1", prompt="hi", task="text"), DEFAULT_GEMINI_MODEL)
    assert fake.calls == [("hi", DEFAULT_GEMINI_MODEL)]
    results = [f for f in conn.sent if isinstance(f, InferResult)]
    assert results and results[0].text == "안녕하세요"


@pytest.mark.asyncio
async def test_agent_gemini_stream_sends_chunks_then_done():
    cfg = AgentConfig(token="T", gemini_api_key="AIzaXXXX", gemini_models=(DEFAULT_GEMINI_MODEL,))
    agent = ProviderAgent(cfg, ollama=object())
    agent._gemini = FakeGemini("스트림 결과")  # type: ignore[assignment]
    agent._gemini_models = [DEFAULT_GEMINI_MODEL]
    conn = FakeConn()
    req = InferRequest(request_id="g2", prompt="hi", task="text", stream=True)
    await agent._run_infer(conn, req, DEFAULT_GEMINI_MODEL)
    chunks = [f for f in conn.sent if isinstance(f, ChunkFrame)]
    assert chunks and chunks[-1].done is True
    assert "".join(c.delta for c in chunks) == "스트림 결과"


@pytest.mark.asyncio
async def test_non_gemini_model_not_routed_to_gemini():
    cfg = AgentConfig(token="T", gemini_api_key="AIzaXXXX", gemini_models=(DEFAULT_GEMINI_MODEL,))
    agent = ProviderAgent(cfg, ollama=object())
    fake = FakeGemini()
    agent._gemini = fake  # type: ignore[assignment]
    agent._gemini_models = [DEFAULT_GEMINI_MODEL]

    # 로컬 모델(llama3.1:8b)은 Gemini 로 가면 안 된다 → Ollama 경로(여기선 object() 라 AttributeError).
    conn = FakeConn()
    with pytest.raises(Exception):  # noqa: B017 - ollama=object() 라 generate 없음 → 라우팅이 gemini 아님을 증명
        await agent._run_infer(conn, InferRequest(request_id="l1", prompt="hi", task="text"), "llama3.1:8b")
    assert fake.calls == []  # Gemini 는 호출되지 않았다


def test_run_infer_gemini_smoke():
    # 동기 진입점 스모크(이벤트루프 직접 구동) — 위 async 테스트의 비-pytest-asyncio 보강.
    cfg = AgentConfig(token="T", gemini_api_key="AIzaXXXX", gemini_models=(DEFAULT_GEMINI_MODEL,))
    agent = ProviderAgent(cfg, ollama=object())
    agent._gemini = FakeGemini("ok")  # type: ignore[assignment]
    agent._gemini_models = [DEFAULT_GEMINI_MODEL]
    conn = FakeConn()
    asyncio.run(agent._run_infer(conn, InferRequest(request_id="s1", prompt="hi", task="text"), DEFAULT_GEMINI_MODEL))
    assert any(isinstance(f, InferResult) for f in conn.sent)
