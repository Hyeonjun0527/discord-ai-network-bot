"""클라우드 RunPod 이미지 백엔드 — 출력 파싱·runsync/폴링·에러·health."""
from __future__ import annotations

import base64

import pytest

from provider_agent import runpod as rmod
from provider_agent.runpod import RunPodClient, RunPodError

_B64 = base64.b64encode(b"PNGBYTES").decode("ascii")


class _Resp:
    def __init__(self, status=200, data=None):
        self._status = status
        self._data = data

    @property
    def status(self):
        return self._status

    async def json(self):
        return self._data

    async def __aenter__(self):
        return self

    async def __aexit__(self, *a):
        return False


class _Session:
    """post(runsync) 1회 + 이후 get(status) 응답들을 순서대로 돌려주는 페이크."""

    def __init__(self, post_resp, status_resps=None):
        self._post = post_resp
        self._status = list(status_resps or [])

    async def __aenter__(self):
        return self

    async def __aexit__(self, *a):
        return False

    def post(self, url, json=None, headers=None):
        return self._post

    def get(self, url, headers=None):
        return self._status.pop(0) if self._status else _Resp(200, {"status": "COMPLETED", "output": {}})


def _patch(monkeypatch, session):
    monkeypatch.setattr(rmod.aiohttp, "ClientSession", lambda *a, **k: session)


# ── 순수 출력 파싱(HTTP 불필요) ────────────────────────────────────────────
def test_parse_output_shapes():
    assert RunPodClient._parse_output({"image_base64": _B64}) == _B64
    assert RunPodClient._parse_output({"image": _B64}) == _B64
    assert RunPodClient._parse_output({"images": [_B64]}) == _B64
    assert RunPodClient._parse_output(_B64) == _B64
    assert RunPodClient._parse_output([_B64]) == _B64
    # data URL 접두 제거
    assert RunPodClient._parse_output({"image_base64": f"data:image/png;base64,{_B64}"}) == _B64


def test_parse_output_invalid():
    with pytest.raises(RunPodError):
        RunPodClient._parse_output({"nope": 1})
    with pytest.raises(RunPodError):
        RunPodClient._parse_output({"image_base64": "not!base64!"})


def test_ensure_dims_defaults():
    c = RunPodClient("k", "ep", default_resolution=(1024, 1024))
    assert c._ensure_dims(None) == (1024, 1024)
    assert c._ensure_dims({"width": 768, "height": 512}) == (768, 512)
    # 256 미만(예: ComfyUI 폴백 잔재)은 기본 해상도로
    assert c._ensure_dims({"width": 64, "height": 64}) == (1024, 1024)


@pytest.mark.asyncio
async def test_txt2img_runsync_completed(monkeypatch):
    _patch(monkeypatch, _Session(_Resp(200, {"status": "COMPLETED", "output": {"image_base64": _B64}})))
    out = await RunPodClient("k", "ep").txt2img("a fox", {"seed": 1})
    assert out == _B64


@pytest.mark.asyncio
async def test_txt2img_polls_status_until_complete(monkeypatch):
    # runsync 가 IN_PROGRESS + id 반환 → /status 폴링으로 완료
    post = _Resp(200, {"status": "IN_PROGRESS", "id": "job1"})
    statuses = [_Resp(200, {"status": "IN_PROGRESS"}), _Resp(200, {"status": "COMPLETED", "output": {"image_base64": _B64}})]
    monkeypatch.setattr(rmod.asyncio, "sleep", _noop_sleep)
    _patch(monkeypatch, _Session(post, statuses))
    out = await RunPodClient("k", "ep").txt2img("a fox")
    assert out == _B64


@pytest.mark.asyncio
async def test_txt2img_failed_raises(monkeypatch):
    _patch(monkeypatch, _Session(_Resp(200, {"status": "FAILED", "error": "OOM"})))
    with pytest.raises(RunPodError) as ei:
        await RunPodClient("k", "ep").txt2img("x")
    assert "OOM" in str(ei.value)


@pytest.mark.asyncio
async def test_txt2img_http_error_raises(monkeypatch):
    _patch(monkeypatch, _Session(_Resp(401, {"error": "unauthorized"})))
    with pytest.raises(RunPodError):
        await RunPodClient("k", "ep").txt2img("x")


@pytest.mark.asyncio
async def test_health(monkeypatch):
    _patch(monkeypatch, _Session(_Resp(200, {"workers": {"ready": 1}})))
    assert await RunPodClient("k", "ep").health() is True
    assert await RunPodClient("", "").health() is False  # 키/엔드포인트 없으면 즉시 False


async def _noop_sleep(_s):
    return None
