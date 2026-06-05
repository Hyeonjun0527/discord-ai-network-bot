"""로컬 SD(A1111) 클라이언트 + capability 광고 테스트 (SD Phase 1)."""
from __future__ import annotations

import pytest
from aiohttp import web
from aiohttp.test_utils import TestServer

from provider_agent.config import AgentConfig
from provider_agent.sd import (
    MAX_IMAGE_DIM,
    MAX_STEPS,
    SDClient,
    SDError,
    filter_sd_options,
)


class FakeA1111:
    def __init__(self, mode: str = "ok") -> None:
        self.mode = mode
        self.last_payload: dict | None = None
        self.app = web.Application()
        self.app.router.add_post("/sdapi/v1/txt2img", self._txt2img)
        self.app.router.add_get("/sdapi/v1/sd-models", self._models)

    async def _txt2img(self, request: web.Request) -> web.Response:
        self.last_payload = await request.json()
        if self.mode == "error":
            return web.json_response({"error": "boom"})
        return web.json_response({"images": ["QkFTRTY0UE5H"], "info": "{}"})

    async def _models(self, request: web.Request) -> web.Response:
        return web.json_response([{"title": "sd_xl_base"}])


async def _start(fake: FakeA1111) -> tuple[TestServer, str]:
    server = TestServer(fake.app)
    await server.start_server()
    return server, f"http://{server.host}:{server.port}"


def test_filter_sd_options_clamps():
    out = filter_sd_options({"width": 99999, "height": 0, "steps": 999, "evil": "x", "seed": 5})
    assert out["width"] == MAX_IMAGE_DIM
    assert out["height"] == 64  # 하한
    assert out["steps"] == MAX_STEPS
    assert "evil" not in out  # 화이트리스트 밖 제거
    assert out["seed"] == 5


@pytest.mark.asyncio
async def test_txt2img_returns_base64():
    fake = FakeA1111("ok")
    server, url = await _start(fake)
    try:
        img = await SDClient(url).txt2img("a cat", {"steps": 5, "width": 256, "height": 256})
        assert img == "QkFTRTY0UE5H"
        assert fake.last_payload["prompt"] == "a cat"
        assert fake.last_payload["steps"] == 5
    finally:
        await server.close()


@pytest.mark.asyncio
async def test_txt2img_error():
    fake = FakeA1111("error")
    server, url = await _start(fake)
    try:
        with pytest.raises(SDError):
            await SDClient(url).txt2img("x")
    finally:
        await server.close()


@pytest.mark.asyncio
async def test_health():
    fake = FakeA1111("ok")
    server, url = await _start(fake)
    try:
        assert await SDClient(url).health() is True
    finally:
        await server.close()
    # 닫힌 뒤엔 False
    assert await SDClient(url).health() is False


@pytest.mark.asyncio
async def test_agent_advertises_image_capability_when_sd_ready():
    """SD 가 health OK 면 provider_hello 에 'image' capability 가 붙는다."""
    from provider_agent.agent import ProviderAgent

    class FakeSD:
        async def health(self) -> bool:
            return True

    agent = ProviderAgent(AgentConfig(token="T", enable_image=True), sd=FakeSD())
    # 광고 전(health 미실행)엔 text 만
    assert agent._build_hello().capabilities == ["text"]
    agent._image_ready = await agent._sd.health()
    hello = agent._build_hello()
    assert "image" in hello.capabilities
    assert "text" in hello.capabilities


def test_image_disabled_by_default():
    cfg = AgentConfig(token="T")
    assert cfg.enable_image is False
    agent_cfg_default_sd = cfg.sd_url
    assert agent_cfg_default_sd.startswith("http://127.0.0.1")


@pytest.mark.asyncio
async def test_handle_image_sends_chunks_then_done(monkeypatch):
    """task=image 면 SD 생성 base64 를 ChunkFrame 으로 분할 전송 후 done."""
    monkeypatch.setattr("provider_agent.sysinfo.should_pause", lambda *a, **k: (False, ""))
    from test_agent import FakeConn

    from provider_agent.agent import ProviderAgent
    from provider_agent.constants import IMAGE_CHUNK_CHARS
    from provider_agent.protocol import ChunkFrame, InferRequest

    big_b64 = "Q" * (IMAGE_CHUNK_CHARS + 100)  # 2조각 나야 함

    class FakeSD:
        async def health(self):
            return True

        async def txt2img(self, prompt, options=None):
            return big_b64

    agent = ProviderAgent(AgentConfig(token="T", enable_image=True), sd=FakeSD())
    agent._image_ready = True
    conn = FakeConn()
    await agent.handle_infer(conn, InferRequest(request_id="img1", prompt="이미지", task="image"))  # type: ignore[arg-type]
    chunks = [f for f in conn.sent if isinstance(f, ChunkFrame)]
    assert len(chunks) == 3  # 2 data + 1 done
    assert "".join(c.delta for c in chunks if not c.done) == big_b64
    assert chunks[-1].done is True
    assert agent.processed == 1


@pytest.mark.asyncio
async def test_handle_image_unsupported_errors(monkeypatch):
    monkeypatch.setattr("provider_agent.sysinfo.should_pause", lambda *a, **k: (False, ""))
    from test_agent import FakeConn

    from provider_agent.agent import ProviderAgent
    from provider_agent.protocol import InferError, InferRequest

    agent = ProviderAgent(AgentConfig(token="T"))  # enable_image=False → SD 없음
    conn = FakeConn()
    await agent.handle_infer(conn, InferRequest(request_id="img1", prompt="x", task="image"))  # type: ignore[arg-type]
    assert isinstance(conn.sent[0], InferError)


def test_infer_request_task_roundtrip():
    from provider_agent.protocol import InferRequest, dumps_frame, loads_frame
    f = InferRequest(request_id="r", prompt="p", task="image")
    assert f.to_dict()["task"] == "image"
    f2 = loads_frame(dumps_frame(f))
    assert f2.task == "image"
    # 기본값 text
    assert loads_frame('{"type":"infer","requestId":"r"}').task == "text"
