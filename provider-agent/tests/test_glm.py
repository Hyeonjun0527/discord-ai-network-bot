"""클라우드 GLM(z.ai) 백엔드 — 응답 파싱·generate·translate·review·health·에러 경로."""
from __future__ import annotations

import pytest

from provider_agent import glm as gmod
from provider_agent.glm import (
    DEFAULT_GLM_MODEL,
    GLM_API_BASE,
    GlmClient,
    GlmError,
    _extract_content,
    _extract_usage,
    parse_image_prompt_review,
)
from provider_agent.protocol import Usage


class _Resp:
    def __init__(self, status=200, *, data=None, text=""):
        self._status = status
        self._data = data
        self._text = text

    @property
    def status(self):
        return self._status

    async def json(self):
        if self._data is None:
            raise ValueError("no json")
        return self._data

    async def text(self):
        return self._text

    async def __aenter__(self):
        return self

    async def __aexit__(self, *a):
        return False


class _Session:
    def __init__(self, resp):
        self._resp = resp
        self.posts: list[tuple[str, dict | None, dict | None]] = []
        self.gets: list[tuple[str, dict | None]] = []

    async def __aenter__(self):
        return self

    async def __aexit__(self, *a):
        return False

    def post(self, url, json=None, headers=None):
        self.posts.append((url, json, headers))
        return self._resp

    def get(self, url, headers=None):
        self.gets.append((url, headers))
        return self._resp


def _patch(monkeypatch, resp) -> _Session:
    session = _Session(resp)
    monkeypatch.setattr(gmod.aiohttp, "ClientSession", lambda *a, **k: session)
    return session


def _chat_ok(content: str, *, prompt_tokens=3, completion_tokens=5) -> dict:
    return {
        "choices": [{"message": {"role": "assistant", "content": content}}],
        "usage": {"prompt_tokens": prompt_tokens, "completion_tokens": completion_tokens},
    }


def test_extract_content_shapes():
    assert _extract_content(_chat_ok("안녕")) == "안녕"
    # 비정상/빈 응답 → None
    assert _extract_content({"choices": []}) is None
    assert _extract_content({"choices": [{"message": {"content": ""}}]}) is None
    assert _extract_content({"choices": [{"message": {}}]}) is None
    assert _extract_content({}) is None
    assert _extract_content(None) is None


def test_extract_usage_maps_openai_fields():
    u = _extract_usage({"usage": {"prompt_tokens": 11, "completion_tokens": 22}})
    assert u == Usage(prompt_tokens=11, completion_tokens=22)
    assert _extract_usage({}) == Usage(prompt_tokens=0, completion_tokens=0)


def test_parse_image_prompt_review():
    allowed = parse_image_prompt_review('{"allowed":true,"category":"safe","reason":"정상 SFW 요청"}')
    assert allowed.allowed is True
    assert allowed.category == "safe"

    blocked = parse_image_prompt_review('```json\n{"allowed":false,"category":"minor","reason":"미성년자 성적화"}\n```')
    assert blocked.allowed is False
    assert blocked.category == "minor"

    with pytest.raises(GlmError):
        parse_image_prompt_review('{"category":"safe"}')


@pytest.mark.asyncio
async def test_generate_returns_text_and_usage(monkeypatch):
    session = _patch(monkeypatch, _Resp(200, data=_chat_ok("안녕하세요")))
    text, usage = await GlmClient("zai-key").generate("hi", DEFAULT_GLM_MODEL)
    assert text == "안녕하세요"
    assert usage == Usage(prompt_tokens=3, completion_tokens=5)
    # OpenAI 호환: chat/completions 로 Bearer 헤더 + {model, messages} 전송
    url, body, headers = session.posts[0]
    assert url == f"{GLM_API_BASE}/chat/completions"
    assert body == {"model": DEFAULT_GLM_MODEL, "messages": [{"role": "user", "content": "hi"}]}
    assert headers["Authorization"] == "Bearer zai-key"


@pytest.mark.asyncio
async def test_generate_defaults_model_when_none(monkeypatch):
    session = _patch(monkeypatch, _Resp(200, data=_chat_ok("ok")))
    await GlmClient("k").generate("hi", None)
    assert session.posts[0][1]["model"] == DEFAULT_GLM_MODEL


@pytest.mark.asyncio
async def test_generate_http_error_raises(monkeypatch):
    _patch(monkeypatch, _Resp(401, text='{"error":"unauthorized"}'))
    with pytest.raises(GlmError) as ei:
        await GlmClient("bad").generate("hi", None)
    assert "401" in str(ei.value)


@pytest.mark.asyncio
async def test_generate_empty_content_raises(monkeypatch):
    _patch(monkeypatch, _Resp(200, data={"choices": [{"message": {"content": ""}}]}))
    with pytest.raises(GlmError):
        await GlmClient("k").generate("hi", None)


@pytest.mark.asyncio
async def test_generate_client_error_becomes_glm_error(monkeypatch):
    import aiohttp

    def boom(*a, **k):
        raise aiohttp.ClientError("connection reset")

    monkeypatch.setattr(gmod.aiohttp, "ClientSession", boom)
    with pytest.raises(GlmError) as ei:
        await GlmClient("k").generate("hi", None)
    assert "연결 실패" in str(ei.value)


@pytest.mark.asyncio
async def test_translate_uses_system_message(monkeypatch):
    session = _patch(monkeypatch, _Resp(200, data=_chat_ok("a cute cat, safe")))
    out = await GlmClient("k").translate("귀여운 고양이", "be safe", model=DEFAULT_GLM_MODEL)
    assert out == "a cute cat, safe"
    body = session.posts[0][1]
    assert body["messages"][0] == {"role": "system", "content": "be safe"}
    assert body["messages"][1] == {"role": "user", "content": "귀여운 고양이"}
    assert body["temperature"] == 0.7


@pytest.mark.asyncio
async def test_review_image_prompt_parses_json(monkeypatch):
    review_json = '{"allowed":false,"category":"sexual","reason":"성적 요청"}'
    session = _patch(monkeypatch, _Resp(200, data=_chat_ok(review_json)))
    review = await GlmClient("k").review_image_prompt("야한 사진")
    assert review.allowed is False
    assert review.category == "sexual"
    # JSON 응답 강제(response_format) + 온도 0
    body = session.posts[0][1]
    assert body["response_format"] == {"type": "json_object"}
    assert body["temperature"] == 0.0


@pytest.mark.asyncio
async def test_health(monkeypatch):
    session = _patch(monkeypatch, _Resp(200, data={}))
    assert await GlmClient("k").health() is True
    assert session.gets[0][0] == f"{GLM_API_BASE}/models"
    _patch(monkeypatch, _Resp(401, data={}))
    assert await GlmClient("k").health() is False


@pytest.mark.asyncio
async def test_base_url_override(monkeypatch):
    session = _patch(monkeypatch, _Resp(200, data=_chat_ok("ok")))
    await GlmClient("k", base_url="https://proxy.example/v4/").generate("hi", None)
    assert session.posts[0][0] == "https://proxy.example/v4/chat/completions"


# ── 에이전트 라우팅(glm-* 모델 → GLM, 그 외 → Ollama) ─────────────────────
import asyncio  # noqa: E402

from provider_agent.agent import ProviderAgent  # noqa: E402
from provider_agent.config import AgentConfig  # noqa: E402
from provider_agent.protocol import ChunkFrame, Frame, InferRequest, InferResult  # noqa: E402


class FakeConn:
    def __init__(self) -> None:
        self.sent: list[Frame] = []
        self.authed = True

    async def send(self, frame: Frame) -> None:
        self.sent.append(frame)


class FakeGlm:
    def __init__(self, text: str = "GLM 응답", error: str | None = None) -> None:
        self._text, self._error = text, error
        self.calls: list[tuple[str, str | None]] = []

    async def generate(self, prompt: str, model: str | None):
        self.calls.append((prompt, model))
        if self._error:
            raise GlmError(self._error)
        return self._text, Usage(prompt_tokens=3, completion_tokens=5)


def test_config_advertises_glm_when_key_present():
    cfg = AgentConfig(token="T", glm_api_key="zai-xxxx")
    agent = ProviderAgent(cfg, ollama=object())  # ollama 미사용(glm 경로만)
    assert agent._glm is not None


@pytest.mark.asyncio
async def test_agent_routes_glm_model_to_glm():
    cfg = AgentConfig(token="T", glm_api_key="zai-xxxx", glm_models=(DEFAULT_GLM_MODEL,))
    agent = ProviderAgent(cfg, ollama=object())
    fake = FakeGlm("안녕하세요")
    agent._glm = fake  # type: ignore[assignment]
    agent._glm_models = [DEFAULT_GLM_MODEL]
    assert DEFAULT_GLM_MODEL in agent.models
    conn = FakeConn()
    await agent._run_infer(conn, InferRequest(request_id="g1", prompt="hi", task="text"), DEFAULT_GLM_MODEL)
    assert fake.calls == [("hi", DEFAULT_GLM_MODEL)]
    results = [f for f in conn.sent if isinstance(f, InferResult)]
    assert results and results[0].text == "안녕하세요"


@pytest.mark.asyncio
async def test_agent_glm_stream_sends_chunks_then_done():
    cfg = AgentConfig(token="T", glm_api_key="zai-xxxx", glm_models=(DEFAULT_GLM_MODEL,))
    agent = ProviderAgent(cfg, ollama=object())
    agent._glm = FakeGlm("스트림 결과")  # type: ignore[assignment]
    agent._glm_models = [DEFAULT_GLM_MODEL]
    conn = FakeConn()
    req = InferRequest(request_id="g2", prompt="hi", task="text", stream=True)
    await agent._run_infer(conn, req, DEFAULT_GLM_MODEL)
    chunks = [f for f in conn.sent if isinstance(f, ChunkFrame)]
    assert chunks and chunks[-1].done is True
    assert "".join(c.delta for c in chunks) == "스트림 결과"


@pytest.mark.asyncio
async def test_non_glm_model_not_routed_to_glm():
    cfg = AgentConfig(token="T", glm_api_key="zai-xxxx", glm_models=(DEFAULT_GLM_MODEL,))
    agent = ProviderAgent(cfg, ollama=object())
    fake = FakeGlm()
    agent._glm = fake  # type: ignore[assignment]
    agent._glm_models = [DEFAULT_GLM_MODEL]
    # 로컬 모델(llama3.1:8b)은 GLM 으로 가면 안 된다 → Ollama 경로(여기선 object() 라 AttributeError).
    conn = FakeConn()
    with pytest.raises(Exception):  # noqa: B017 - ollama=object() 라 generate 없음 → 라우팅이 glm 아님을 증명
        await agent._run_infer(conn, InferRequest(request_id="l1", prompt="hi", task="text"), "llama3.1:8b")
    assert fake.calls == []  # GLM 은 호출되지 않았다


def test_run_infer_glm_smoke():
    cfg = AgentConfig(token="T", glm_api_key="zai-xxxx", glm_models=(DEFAULT_GLM_MODEL,))
    agent = ProviderAgent(cfg, ollama=object())
    agent._glm = FakeGlm("ok")  # type: ignore[assignment]
    agent._glm_models = [DEFAULT_GLM_MODEL]
    conn = FakeConn()
    asyncio.run(agent._run_infer(conn, InferRequest(request_id="s1", prompt="hi", task="text"), DEFAULT_GLM_MODEL))
    assert any(isinstance(f, InferResult) for f in conn.sent)


@pytest.mark.asyncio
async def test_set_glm_key_live(monkeypatch):
    """앱 설정에서 GLM 키 입력 → 라이브로 glm 모델 광고, 빈 키 → 제거."""
    class FakeG:
        def __init__(self, *a, **k):
            pass

        async def health(self):
            return True

    monkeypatch.setattr(gmod, "GlmClient", FakeG)
    agent = ProviderAgent(AgentConfig(token="T", models=("a",)), ollama=object())
    assert not any(m.startswith("glm-") for m in agent.models)
    ok = await agent.set_glm_key("zai-xxx")
    assert ok is True
    assert DEFAULT_GLM_MODEL in agent.models and "a" in agent.models
    await agent.set_glm_key("")  # 빈 키 → 제거
    assert not any(m.startswith("glm-") for m in agent.models)
    assert "a" in agent.models
