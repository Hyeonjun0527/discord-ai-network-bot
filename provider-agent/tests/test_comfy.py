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

    # 이미지 엔진은 ComfyUI 전용 — comfy_url 없어도 앱 관리 ComfyUI(localhost:8188)로 항상 comfyui.
    b = ProviderAgent(AgentConfig(token="T", enable_image=True), ollama=object())
    assert b._image_backend == "comfyui"


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


# Anima 정합 object_info(UNET/CLIP/VAE 파일 존재) — default.json 없을 때 번들 폴백 구성용.
_ANIMA_INFO = {
    "UNETLoader": {"input": {"required": {"unet_name": [["waiANIMA_v10Base10.safetensors"]]}}},
    "CLIPLoader": {"input": {"required": {"clip_name": [["qwen_3_06b_base.safetensors"]], "type": [["stable_diffusion", "anima"]]}}},
    "VAELoader": {"input": {"required": {"vae_name": [["qwen_image_vae.safetensors"]]}}},
}


@pytest.mark.asyncio
async def test_comfy_txt2img_template_then_fallback(monkeypatch, tmp_path):
    """default.json 이 없으면 Anima 번들 폴백으로 그래프를 만들어 제출→완성 PNG 를 받는다."""
    from provider_agent.comfy import ComfyClient

    png = b"\x89PNG\r\n\x1a\nfake"
    hist = {"PID1": {"outputs": {"9": {"images": [{"filename": "nexa_0001.png", "subfolder": "", "type": "output"}]}}}}

    def router(method, url, x):
        if url.endswith("/object_info"):
            return _Resp(200, _ANIMA_INFO)
        if url.endswith("/prompt"):
            # 폴백이 UNET/CLIP/VAE 기반 그래프를 보냈는지 확인
            assert x and "44" in x["prompt"] and x["prompt"]["44"]["class_type"] == "UNETLoader"
            return _Resp(200, {"prompt_id": "PID1"})
        if "/history/" in url:
            return _Resp(200, hist)
        if url.endswith("/view"):
            return _Resp(200, raw=png)
        return _Resp(404, {})

    _patch_session(monkeypatch, router)
    cl = ComfyClient("http://127.0.0.1:8188", workflows_dir=tmp_path)  # 빈 디렉터리 → default.json 없음
    out = await cl.txt2img("a cute cat", {"negative_prompt": "lowres", "width": 1024, "height": 1024})
    assert out == base64.b64encode(png).decode("ascii")


@pytest.mark.asyncio
async def test_comfy_txt2img_uses_default_template(monkeypatch, tmp_path):
    """유저 default.json(UI 그래프)이 있으면 그걸 변환·주입해 제출한다(번들 폴백 아님)."""
    import json as _json

    from provider_agent.comfy import ComfyClient

    # 최소 UI 그래프: CLIPTextEncode(긍/부) → KSampler → 출력. object_info 로 위젯 매핑.
    graph = {
        "nodes": [
            {"id": 11, "type": "CLIPTextEncode", "inputs": [{"name": "clip", "link": None}], "widgets_values": [""]},
            {"id": 12, "type": "CLIPTextEncode", "inputs": [{"name": "clip", "link": None}], "widgets_values": ["old neg"]},
            {
                "id": 19, "type": "KSampler",
                "inputs": [
                    {"name": "model", "link": None}, {"name": "positive", "link": 39},
                    {"name": "negative", "link": 40}, {"name": "latent_image", "link": None},
                ],
                "widgets_values": [111, "randomize", 30, 4, "er_sde", "simple", 1],
            },
        ],
        "links": [[39, 11, 0, 19, 1, "COND"], [40, 12, 0, 19, 2, "COND"]],
    }
    (tmp_path / "default.json").write_text(_json.dumps(graph), encoding="utf-8")
    oinfo = {
        "CLIPTextEncode": {"input": {"required": {"text": ["STRING", {}]}}},
        "KSampler": {"input": {"required": {
            "seed": ["INT", {"control_after_generate": True}], "steps": ["INT", {}], "cfg": ["FLOAT", {}],
            "sampler_name": [["er_sde"]], "scheduler": [["simple"]], "denoise": ["FLOAT", {}],
        }}},
    }
    png = b"\x89PNG"
    hist = {"PID1": {"outputs": {"x": {"images": [{"filename": "f.png", "subfolder": "", "type": "output"}]}}}}
    captured = {}

    def router(method, url, x):
        if url.endswith("/object_info"):
            return _Resp(200, oinfo)
        if url.endswith("/prompt"):
            captured["graph"] = x["prompt"]
            return _Resp(200, {"prompt_id": "PID1"})
        if "/history/" in url:
            return _Resp(200, hist)
        if url.endswith("/view"):
            return _Resp(200, raw=png)
        return _Resp(404, {})

    _patch_session(monkeypatch, router)
    cl = ComfyClient("http://127.0.0.1:8188", workflows_dir=tmp_path)
    await cl.txt2img("POS", {"negative_prompt": "NEG", "seed": 777})
    g = captured["graph"]
    assert g["11"]["inputs"]["text"] == "POS"  # 긍정 주입
    assert g["12"]["inputs"]["text"] == "NEG"  # 부정 가드 주입(템플릿 'old neg' 대체)
    assert g["19"]["inputs"]["seed"] == 777  # 시드 주입
    # control_after_generate 오프셋: steps/cfg/sampler 가 밀리지 않고 정확히 매핑
    assert g["19"]["inputs"]["steps"] == 30 and g["19"]["inputs"]["cfg"] == 4 and g["19"]["inputs"]["sampler_name"] == "er_sde"


@pytest.mark.asyncio
async def test_comfy_txt2img_no_model_files(monkeypatch, tmp_path):
    """default.json 도 없고 설치된 UNET/CLIP/VAE 도 없으면 ComfyError(폴백 구성 불가)."""
    from provider_agent.comfy import ComfyClient, ComfyError

    _patch_session(monkeypatch, lambda m, u, x: _Resp(200, {}))
    with pytest.raises(ComfyError):
        await ComfyClient("http://127.0.0.1:8188", workflows_dir=tmp_path).txt2img("x")


def test_ui_graph_to_api_and_inject():
    """검증된 변환기: UI 그래프→API + 긍정/부정/시드 주입(순수 함수)."""
    from provider_agent.comfy import inject_prompt, ui_graph_to_api

    graph = {
        "nodes": [
            {"id": 11, "type": "CLIPTextEncode", "inputs": [{"name": "clip", "link": None}], "widgets_values": [""]},
            {"id": 12, "type": "CLIPTextEncode", "inputs": [{"name": "clip", "link": None}], "widgets_values": ["neg"]},
            {"id": 19, "type": "KSampler",
             "inputs": [{"name": "positive", "link": 39}, {"name": "negative", "link": 40}],
             "widgets_values": [5, "fixed", 28]},
        ],
        "links": [[39, 11, 0, 19, 0, "C"], [40, 12, 0, 19, 1, "C"]],
    }
    oinfo = {
        "CLIPTextEncode": {"input": {"required": {"text": ["STRING", {}]}}},
        "KSampler": {"input": {"required": {"seed": ["INT", {"control_after_generate": True}], "steps": ["INT", {}]}}},
    }
    api = ui_graph_to_api(graph, oinfo)
    assert api["19"]["inputs"]["positive"] == ["11", 0]
    assert api["19"]["inputs"]["seed"] == 5 and api["19"]["inputs"]["steps"] == 28  # control 오프셋
    inject_prompt(api, "POS", "NEGGUARD", 999)
    assert api["11"]["inputs"]["text"] == "POS"
    assert api["12"]["inputs"]["text"] == "NEGGUARD"
    assert api["19"]["inputs"]["seed"] == 999
