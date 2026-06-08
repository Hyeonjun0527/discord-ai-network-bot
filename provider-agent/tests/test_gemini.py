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


def test_per_guild_model_selection():
    """서버별 제공 모델: guild_policy chatModels override 가 있으면 그 길드엔 선택 모델만 광고."""
    from provider_agent.agent import ProviderAgent
    from provider_agent.config import AgentConfig

    agent = ProviderAgent(AgentConfig(token="T", models=("a", "b", "c")), ollama=object())
    # override 없으면 전체
    assert set(agent._models_for(100)) == {"a", "b", "c"}
    # 길드 100 엔 a,b 만(없는 z 는 무시)
    agent._guild_policy[100] = {"chatModels": ["a", "b", "z"]}
    assert agent._models_for(100) == ["a", "b"]
    # 다른 길드(200)는 영향 없음(전체)
    assert set(agent._models_for(200)) == {"a", "b", "c"}
    # 빈 선택은 전체로(실수로 전부 차단 방지)
    agent._guild_policy[100] = {"chatModels": []}
    assert set(agent._models_for(100)) == {"a", "b", "c"}


def test_per_guild_image_toggle():
    from provider_agent.agent import ProviderAgent
    from provider_agent.config import AgentConfig

    agent = ProviderAgent(AgentConfig(token="T"), ollama=object())
    agent._image_ready = True
    assert agent._image_for(100) is True  # 기본 허용
    agent._guild_policy[100] = {"imageEnabled": False}
    assert agent._image_for(100) is False  # 이 서버만 이미지 끔
    assert agent._image_for(200) is True  # 다른 서버는 그대로
    agent._image_ready = False
    assert agent._image_for(200) is False  # SD 미준비면 어디든 False


@pytest.mark.asyncio
async def test_set_gemini_key_live(monkeypatch):
    """앱 설정에서 Gemini 키 입력 → 라이브로 gemini 모델 광고, 빈 키 → 제거."""
    from provider_agent import gemini as gmod
    from provider_agent.agent import ProviderAgent
    from provider_agent.config import AgentConfig

    class FakeG:
        def __init__(self, *a, **k):
            pass

        async def health(self):
            return True

    monkeypatch.setattr(gmod, "GeminiClient", FakeG)
    agent = ProviderAgent(AgentConfig(token="T", models=("a",)), ollama=object())
    assert not any(m.startswith("gemini-") for m in agent.models)
    ok = await agent.set_gemini_key("AIzaXXX")
    assert ok is True
    assert "gemini-2.5-flash-lite" in agent.models and "a" in agent.models
    await agent.set_gemini_key("")  # 빈 키 → 제거
    assert not any(m.startswith("gemini-") for m in agent.models)
    assert "a" in agent.models
