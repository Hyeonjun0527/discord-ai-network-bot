"""Ollama 클라이언트 테스트 — 가짜 aiohttp Ollama 서버."""
from __future__ import annotations

import pytest
from aiohttp import web
from aiohttp.test_utils import TestServer

from provider_agent.ollama import OllamaClient, OllamaError


async def _make(generate_resp=None, error=None, models=None) -> tuple[TestServer, str]:
    app = web.Application()

    async def gen(request: web.Request) -> web.Response:
        if error:
            return web.json_response({"error": error})
        return web.json_response(generate_resp or {"response": "hi ", "prompt_eval_count": 3, "eval_count": 5})

    async def tags(request: web.Request) -> web.Response:
        return web.json_response({"models": [{"name": m} for m in (models or ["m1", "m2"])]})

    async def pull(request: web.Request) -> web.Response:
        return web.json_response({"status": "success"})

    app.router.add_post("/api/generate", gen)
    app.router.add_get("/api/tags", tags)
    app.router.add_post("/api/pull", pull)
    server = TestServer(app)
    await server.start_server()
    return server, f"http://{server.host}:{server.port}"


@pytest.mark.asyncio
async def test_generate():
    server, url = await _make()
    try:
        text, usage = await OllamaClient(url).generate("안녕", "m")
        assert text == "hi"  # strip 됨
        assert usage.prompt_tokens == 3 and usage.completion_tokens == 5
    finally:
        await server.close()


@pytest.mark.asyncio
async def test_generate_error():
    server, url = await _make(error="model 'x' not found")
    try:
        with pytest.raises(OllamaError):
            await OllamaClient(url).generate("안녕", "x")
    finally:
        await server.close()


@pytest.mark.asyncio
async def test_list_models():
    server, url = await _make(models=["llama3.1:8b", "qwen2.5:7b"])
    try:
        assert await OllamaClient(url).list_models() == ["llama3.1:8b", "qwen2.5:7b"]
    finally:
        await server.close()


@pytest.mark.asyncio
async def test_connect_failure():
    # 닫힌 포트 → OllamaError
    with pytest.raises(OllamaError):
        await OllamaClient("http://127.0.0.1:1").generate("x", "m")


@pytest.mark.asyncio
async def test_health_and_pull():
    server, url = await _make()
    try:
        assert await OllamaClient(url).health() is True
        await OllamaClient(url).pull("test-model")  # 예외 없으면 성공
    finally:
        await server.close()
    assert await OllamaClient("http://127.0.0.1:1").health() is False


@pytest.mark.asyncio
async def test_self_test_ok():
    from provider_agent.agent import _self_test
    from provider_agent.config import AgentConfig

    server, url = await _make()
    try:
        cfg = AgentConfig(token="", ollama_url=url, models=("test-model",), self_test=True)
        assert await _self_test(cfg) == 0
    finally:
        await server.close()


@pytest.mark.asyncio
async def test_generate_stream():
    """스트리밍(#35): NDJSON chunk → ('chunk', piece)*, 마지막 ('done', Usage)."""
    app = web.Application()

    async def gen_stream(request: web.Request) -> web.StreamResponse:
        resp = web.StreamResponse()
        resp.content_type = "application/x-ndjson"
        await resp.prepare(request)
        for piece in ("안녕", "하세", "요"):
            await resp.write((f'{{"response": "{piece}", "done": false}}\n').encode())
        await resp.write(b'{"response": "", "done": true, "prompt_eval_count": 2, "eval_count": 7}\n')
        await resp.write_eof()
        return resp

    app.router.add_post("/api/generate", gen_stream)
    server = TestServer(app)
    await server.start_server()
    url = f"http://{server.host}:{server.port}"
    try:
        chunks = []
        usage = None
        async for kind, val in OllamaClient(url).generate_stream("안녕", "m"):
            if kind == "chunk":
                chunks.append(val)
            else:
                usage = val
        assert "".join(chunks) == "안녕하세요"
        assert usage.completion_tokens == 7
    finally:
        await server.close()
