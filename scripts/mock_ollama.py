"""E2E용 가짜 Ollama 서버 — /api/generate(에코)·/api/tags. 실제 Ollama 없이 실연동 검증.

포트는 OLLAMA_MOCK_PORT(기본 11434). 실제 ollama 와 충돌을 피하려면 다른 포트를 지정한다.
"""
from __future__ import annotations

import os

from aiohttp import web


async def generate(request: web.Request) -> web.Response:
    data = await request.json()
    prompt = data.get("prompt", "")
    return web.json_response(
        {"response": f"[mock] {prompt}", "prompt_eval_count": 5, "eval_count": 7}
    )


async def tags(request: web.Request) -> web.Response:
    return web.json_response({"models": [{"name": "test-model"}]})


def main() -> None:
    port = int(os.getenv("OLLAMA_MOCK_PORT", "11434"))
    app = web.Application()
    app.router.add_post("/api/generate", generate)
    app.router.add_get("/api/tags", tags)
    web.run_app(app, host="127.0.0.1", port=port, print=None)


if __name__ == "__main__":
    main()
