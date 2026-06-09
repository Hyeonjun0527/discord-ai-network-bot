"""ComfyUI 이미지 백엔드 — 워크플로 빌드 + 응답 파싱(순수 함수) + HTTP 흐름(mock)."""
from __future__ import annotations

import base64

import pytest

from provider_agent.comfy import _first_image_ref, build_workflow


def test_build_workflow_injects_prompt_and_checkpoint():
    wf = build_workflow("귀여운 고양이", "anything-v5.safetensors", width=768, height=512, steps=25)
    # 표준 노드가 모두 있고 연결돼 있다
    assert wf["4"]["class_type"] == "CheckpointLoaderSimple"
    assert wf["4"]["inputs"]["ckpt_name"] == "anything-v5.safetensors"
    assert wf["6"]["inputs"]["text"] == "귀여운 고양이"  # positive
    assert wf["5"]["inputs"]["width"] == 768 and wf["5"]["inputs"]["height"] == 512
    assert wf["3"]["inputs"]["steps"] == 25
    # KSampler 가 model/positive/negative/latent 를 올바른 노드에 연결
    assert wf["3"]["inputs"]["model"] == ["4", 0]
    assert wf["3"]["inputs"]["positive"] == ["6", 0]
    assert wf["3"]["inputs"]["latent_image"] == ["5", 0]
    assert wf["8"]["class_type"] == "VAEDecode" and wf["9"]["class_type"] == "SaveImage"


def test_first_image_ref():
    entry = {
        "outputs": {
            "9": {"images": [{"filename": "nexa_0001.png", "subfolder": "", "type": "output"}]},
        }
    }
    assert _first_image_ref(entry) == ("nexa_0001.png", "", "output")
    # 이미지 없음 → None
    assert _first_image_ref({"outputs": {"9": {}}}) is None
    assert _first_image_ref({}) is None
    assert _first_image_ref({"outputs": {}}) is None


def test_agent_selects_comfy_backend_when_url_set():
    from provider_agent.agent import ProviderAgent
    from provider_agent.comfy import ComfyClient
    from provider_agent.config import AgentConfig

    a = ProviderAgent(AgentConfig(token="T", enable_image=True, comfy_url="http://127.0.0.1:8188"), ollama=object())
    assert a._image_backend == "comfyui"
    assert isinstance(a._sd, ComfyClient)

    b = ProviderAgent(AgentConfig(token="T", enable_image=True), ollama=object())  # comfy_url 없음·미설치 → SD.Next
    assert b._image_backend == "sdnext"


def test_managed_comfyui_is_default_when_installed(monkeypatch):
    """comfy_url 이 없어도 앱이 관리하는 ComfyUI 가 설치돼 있으면 자동으로 1급 엔진(localhost:8188)을 쓴다."""
    from provider_agent import comfy_setup
    from provider_agent.agent import ProviderAgent
    from provider_agent.comfy import ComfyClient
    from provider_agent.config import AgentConfig

    monkeypatch.setattr(comfy_setup, "is_installed", lambda directory=None: True)
    a = ProviderAgent(AgentConfig(token="T", enable_image=True), ollama=object())
    assert a._image_backend == "comfyui"
    assert a._comfy_url == comfy_setup.webui_url()
    assert isinstance(a._sd, ComfyClient)


class _Resp:
    def __init__(self, status=200, data=None, raw=b""):
        self._status, self._data, self._raw = status, data, raw

    @property
    def status(self):
        return self._status

    async def json(self):
        return self._data

    async def read(self):
        return self._raw

    async def __aenter__(self):
        return self

    async def __aexit__(self, *a):
        return False


class _Session:
    def __init__(self, router):
        self._router = router

    async def __aenter__(self):
        return self

    async def __aexit__(self, *a):
        return False

    def get(self, url, params=None):
        return self._router("GET", url, params)

    def post(self, url, json=None):
        return self._router("POST", url, json)


def _patch_session(monkeypatch, router):
    from provider_agent import comfy as cmod

    monkeypatch.setattr(cmod.aiohttp, "ClientSession", lambda timeout=None: _Session(router))


@pytest.mark.asyncio
async def test_comfy_health(monkeypatch):
    from provider_agent.comfy import ComfyClient

    _patch_session(monkeypatch, lambda m, u, x: _Resp(200, {}))
    assert await ComfyClient("http://127.0.0.1:8188").health() is True
    _patch_session(monkeypatch, lambda m, u, x: _Resp(500, {}))
    assert await ComfyClient("http://127.0.0.1:8188").health() is False


@pytest.mark.asyncio
async def test_comfy_first_checkpoint(monkeypatch):
    from provider_agent.comfy import ComfyClient

    info = {"CheckpointLoaderSimple": {"input": {"required": {"ckpt_name": [["a.safetensors", "b.safetensors"]]}}}}
    _patch_session(monkeypatch, lambda m, u, x: _Resp(200, info))
    assert await ComfyClient("http://127.0.0.1:8188").first_checkpoint() == "a.safetensors"
    # current_checkpoint(=호환)도 첫 체크포인트
    assert await ComfyClient("http://127.0.0.1:8188").current_checkpoint() == "a.safetensors"


@pytest.mark.asyncio
async def test_comfy_list_and_set_checkpoint(monkeypatch):
    """폴더 스캔(/object_info)으로 체크포인트 전체 목록을 주고, 선택값이 active 로 고정된다."""
    from provider_agent.comfy import ComfyClient

    info = {"CheckpointLoaderSimple": {"input": {"required": {"ckpt_name": [["a.safetensors", "b.safetensors", "c.safetensors"]]}}}}
    _patch_session(monkeypatch, lambda m, u, x: _Resp(200, info))
    cl = ComfyClient("http://127.0.0.1:8188")
    assert await cl.list_checkpoints() == ["a.safetensors", "b.safetensors", "c.safetensors"]
    # 선택 전엔 첫 모델
    assert await cl.current_checkpoint() == "a.safetensors"
    # 목록에 있는 모델로 전환 → active 고정
    assert await cl.set_checkpoint("b.safetensors") is True
    assert await cl.current_checkpoint() == "b.safetensors"
    # 목록에 없는 모델은 거부(active 유지)
    assert await cl.set_checkpoint("zzz.safetensors") is False
    assert await cl.current_checkpoint() == "b.safetensors"


@pytest.mark.asyncio
async def test_comfy_txt2img_full_flow(monkeypatch):
    from provider_agent.comfy import ComfyClient

    png = b"\x89PNG\r\n\x1a\nfake"
    info = {"CheckpointLoaderSimple": {"input": {"required": {"ckpt_name": [["m.safetensors"]]}}}}
    hist = {"PID1": {"outputs": {"9": {"images": [{"filename": "nexa_0001.png", "subfolder": "", "type": "output"}]}}}}

    def router(method, url, x):
        if url.endswith("/object_info/CheckpointLoaderSimple"):
            return _Resp(200, info)
        if url.endswith("/prompt"):
            return _Resp(200, {"prompt_id": "PID1"})
        if "/history/" in url:
            return _Resp(200, hist)
        if url.endswith("/view"):
            return _Resp(200, raw=png)
        return _Resp(404, {})

    _patch_session(monkeypatch, router)
    out = await ComfyClient("http://127.0.0.1:8188").txt2img("고양이", {"width": 1024, "height": 1024})
    assert out == base64.b64encode(png).decode("ascii")


@pytest.mark.asyncio
async def test_comfy_txt2img_no_checkpoint(monkeypatch):
    from provider_agent.comfy import ComfyClient, ComfyError

    _patch_session(monkeypatch, lambda m, u, x: _Resp(200, {"CheckpointLoaderSimple": {"input": {"required": {"ckpt_name": [[]]}}}}))
    with pytest.raises(ComfyError):
        await ComfyClient("http://127.0.0.1:8188").txt2img("x")
