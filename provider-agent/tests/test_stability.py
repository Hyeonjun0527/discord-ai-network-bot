"""클라우드 Stability 이미지 백엔드 — 응답 파싱·에러·health·인터페이스 호환."""
from __future__ import annotations

import base64

import pytest

from provider_agent import stability as smod
from provider_agent.stability import StabilityClient, StabilityError


class _Resp:
    def __init__(self, status=200, *, content_type="image/png", raw=b"", data=None, text=""):
        self._status = status
        self.content_type = content_type
        self._raw = raw
        self._data = data
        self._text = text

    @property
    def status(self):
        return self._status

    async def read(self):
        return self._raw

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

    async def __aenter__(self):
        return self

    async def __aexit__(self, *a):
        return False

    def post(self, url, data=None, headers=None):
        return self._resp

    def get(self, url, headers=None):
        return self._resp


def _patch(monkeypatch, resp):
    monkeypatch.setattr(smod.aiohttp, "ClientSession", lambda *a, **k: _Session(resp))


@pytest.mark.asyncio
async def test_txt2img_returns_base64_png(monkeypatch):
    png = b"\x89PNG\r\n\x1a\nFAKEDATA"
    _patch(monkeypatch, _Resp(200, content_type="image/png", raw=png))
    client = StabilityClient("sk-test")
    seen = []
    out = await client.txt2img("a calm cat", {"negative_prompt": "ugly", "seed": 7}, on_progress=seen.append)
    assert base64.b64decode(out) == png
    assert seen and seen[-1] == 100  # 완료 진행률 보고


@pytest.mark.asyncio
async def test_txt2img_error_raises_with_detail(monkeypatch):
    _patch(monkeypatch, _Resp(403, content_type="application/json", data={"errors": ["content moderation"]}))
    client = StabilityClient("sk-test")
    with pytest.raises(StabilityError) as ei:
        await client.txt2img("x")
    assert "content moderation" in str(ei.value)
    assert "403" in str(ei.value)


@pytest.mark.asyncio
async def test_txt2img_non_image_200_is_error(monkeypatch):
    # 200 이지만 image 가 아니면(예: JSON 오류 본문) 실패로 처리.
    _patch(monkeypatch, _Resp(200, content_type="application/json", data={"name": "bad"}))
    with pytest.raises(StabilityError):
        await StabilityClient("sk").txt2img("x")


@pytest.mark.asyncio
async def test_health(monkeypatch):
    _patch(monkeypatch, _Resp(200, content_type="application/json", data={}))
    assert await StabilityClient("sk").health() is True
    _patch(monkeypatch, _Resp(401, content_type="application/json", data={}))
    assert await StabilityClient("sk").health() is False


@pytest.mark.asyncio
async def test_interface_compat():
    c = StabilityClient("sk", model="ultra")
    assert c.default_resolution() == (1024, 1024)
    assert await c.interrupt() is False
    assert await c.set_output_png() is True
    assert await c.current_checkpoint() == "ultra"
    assert set(await c.list_checkpoints()) == {"core", "ultra", "sd3"}
    assert await c.set_checkpoint("core") is True
    assert await c.set_checkpoint("nope") is False
    assert await c.current_checkpoint() == "core"


def test_generate_url_model_paths():
    assert StabilityClient("sk", model="core")._generate_url().endswith("/generate/core")
    assert StabilityClient("sk", model="ultra")._generate_url().endswith("/generate/ultra")
    # 미지원 모델은 core 로 폴백
    assert StabilityClient("sk", model="weird")._generate_url().endswith("/generate/core")
