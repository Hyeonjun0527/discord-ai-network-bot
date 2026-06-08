"""ComfyUI 이미지 백엔드 — 워크플로 빌드 + 응답 파싱(순수 함수)."""
from __future__ import annotations

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

    b = ProviderAgent(AgentConfig(token="T", enable_image=True), ollama=object())  # comfy_url 없음 → SD.Next
    assert b._image_backend == "sdnext"
