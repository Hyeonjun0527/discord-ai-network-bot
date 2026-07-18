"""이미지 백엔드 선택(config) + 공통 예외 계층 + 클라우드 안전 게이팅."""
from __future__ import annotations

import pytest

from provider_agent import config as cfgmod
from provider_agent.agent import ProviderAgent
from provider_agent.comfy import ComfyError
from provider_agent.config import AgentConfig, config_from_args
from provider_agent.image_backend import ImageBackendError
from provider_agent.runpod import RunPodClient, RunPodError
from provider_agent.stability import StabilityClient, StabilityError


def test_error_hierarchy():
    # 모든 백엔드 예외가 공통 베이스를 상속 → 에이전트 재시도 로직이 한 번에 잡는다.
    for exc in (ComfyError, StabilityError, RunPodError):
        assert issubclass(exc, ImageBackendError)


def test_agent_selects_stability_backend():
    cfg = AgentConfig(token="T", image_backend="stability", stability_api_key="sk-x", stability_model="ultra")
    agent = ProviderAgent(cfg, ollama=object())
    assert agent._image_backend == "stability"
    assert isinstance(agent._sd, StabilityClient)


def test_agent_selects_runpod_backend():
    cfg = AgentConfig(token="T", image_backend="runpod", runpod_api_key="rk", runpod_endpoint_id="ep")
    agent = ProviderAgent(cfg, ollama=object())
    assert agent._image_backend == "runpod"
    assert isinstance(agent._sd, RunPodClient)


def test_agent_runpod_requires_endpoint():
    # 엔드포인트 없으면 runpod 클라이언트를 만들지 않는다(키만으론 불충분).
    cfg = AgentConfig(token="T", image_backend="runpod", runpod_api_key="rk")
    agent = ProviderAgent(cfg, ollama=object())
    assert agent._sd is None


def test_agent_comfyui_default_no_keys():
    cfg = AgentConfig(token="T")  # enable_image=False, 키 없음
    agent = ProviderAgent(cfg, ollama=object())
    assert agent._image_backend == "comfyui"
    assert agent._sd is None


@pytest.mark.asyncio
async def test_cloud_resolution_uses_default(monkeypatch):
    # 클라우드 백엔드는 자기 기본 해상도를 쓴다(ComfyUI 체크포인트 추정 우회).
    agent = ProviderAgent(
        AgentConfig(token="T", image_backend="stability", stability_api_key="sk"), ollama=object()
    )
    assert await agent._resolution() == (1024, 1024)


def test_cloud_image_advertises_when_backend_is_ready():
    # 프롬프트 안전심사는 central이 담당하므로 이미지 백엔드 준비 상태만 광고 게이트다.
    agent = ProviderAgent(
        AgentConfig(token="T", image_backend="stability", stability_api_key="sk"), ollama=object()
    )
    agent._image_ready = True
    assert agent._image_for(100) is True


def _isolate_config(monkeypatch):
    monkeypatch.setattr(cfgmod, "_load_dotenv", lambda: None)  # 실제 .env 간섭 차단
    monkeypatch.setattr("provider_agent.config_file.load_config", lambda: {})
    for k in ("STABILITY_API_KEY", "RUNPOD_API_KEY", "RUNPOD_ENDPOINT_ID", "IMAGE_BACKEND"):
        monkeypatch.delenv(k, raising=False)


def test_config_auto_selects_stability_from_env(monkeypatch):
    _isolate_config(monkeypatch)
    monkeypatch.setenv("STABILITY_API_KEY", "sk-env")
    cfg, _ = config_from_args(["--token", "T"])
    assert cfg.image_backend == "stability"
    assert cfg.stability_api_key == "sk-env"


def test_config_auto_selects_runpod_when_both_keys(monkeypatch):
    _isolate_config(monkeypatch)
    monkeypatch.setenv("RUNPOD_API_KEY", "rk")
    monkeypatch.setenv("RUNPOD_ENDPOINT_ID", "ep")
    cfg, _ = config_from_args(["--token", "T"])
    assert cfg.image_backend == "runpod"


def test_config_defaults_to_comfyui(monkeypatch):
    _isolate_config(monkeypatch)
    cfg, _ = config_from_args(["--token", "T"])
    assert cfg.image_backend == "comfyui"


def test_config_explicit_backend_wins(monkeypatch):
    _isolate_config(monkeypatch)
    monkeypatch.setenv("STABILITY_API_KEY", "sk")  # 키는 stability 지만
    monkeypatch.setenv("IMAGE_BACKEND", "comfyui")  # 명시값이 우선
    cfg, _ = config_from_args(["--token", "T"])
    assert cfg.image_backend == "comfyui"
