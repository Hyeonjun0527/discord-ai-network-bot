"""앱 내 Ollama 자동 셋업 테스트 — 순수 헬퍼 + 오케스트레이션(서브프로세스/클라이언트 모킹)."""
from __future__ import annotations

import asyncio

from provider_agent import ollama_setup as os_mod


def test_install_command_per_platform(monkeypatch):
    # macOS: brew 있으면 brew install, 없으면 None
    monkeypatch.setattr(os_mod.shutil, "which", lambda c: "/x/brew" if c == "brew" else None)
    assert os_mod.install_command("darwin") == ["brew", "install", "ollama"]
    monkeypatch.setattr(os_mod.shutil, "which", lambda c: None)
    assert os_mod.install_command("darwin") is None
    # Windows: winget
    monkeypatch.setattr(os_mod.shutil, "which", lambda c: "/x/winget" if c == "winget" else None)
    cmd = os_mod.install_command("win32")
    assert cmd is not None and cmd[0] == "winget" and "Ollama.Ollama" in cmd
    # Linux: 공식 스크립트
    assert os_mod.install_command("linux") == ["sh", "-c", "curl -fsSL https://ollama.com/install.sh | sh"]


def test_serve_command(monkeypatch):
    monkeypatch.setattr(os_mod.shutil, "which", lambda c: "/x/brew" if c == "brew" else None)
    assert os_mod.serve_command("darwin") == ["brew", "services", "start", "ollama"]
    monkeypatch.setattr(os_mod.shutil, "which", lambda c: None)
    assert os_mod.serve_command("linux") == ["ollama", "serve"]


def test_is_installed(monkeypatch):
    monkeypatch.setattr(os_mod.shutil, "which", lambda c: "/usr/bin/ollama" if c == "ollama" else None)
    assert os_mod.is_installed() is True
    monkeypatch.setattr(os_mod.shutil, "which", lambda c: None)
    assert os_mod.is_installed() is False


class _FakeClient:
    def __init__(self, healthy, models, *, pulled=None):
        self._healthy = healthy
        self._models = models
        self.pulled = pulled

    async def health(self):
        return self._healthy

    async def list_models(self):
        return self._models

    async def pull(self, model):
        self.pulled = model


def test_run_setup_already_ready(monkeypatch):
    """이미 모델이 있으면 설치 없이 done."""
    fake = _FakeClient(True, ["llama3.1:8b"])
    monkeypatch.setattr(os_mod, "OllamaClient", lambda url: fake)
    ok = asyncio.run(os_mod.run_setup("http://localhost:11434", "llama3.1:8b"))
    assert ok is True
    assert os_mod.progress()["phase"] == "done"


def test_run_setup_no_installer(monkeypatch):
    """미설치 + 설치수단 없음 → error."""
    fake = _FakeClient(False, [])
    monkeypatch.setattr(os_mod, "OllamaClient", lambda url: fake)
    monkeypatch.setattr(os_mod, "is_installed", lambda: False)
    monkeypatch.setattr(os_mod, "install_command", lambda platform=None: None)
    ok = asyncio.run(os_mod.run_setup("http://localhost:11434"))
    assert ok is False
    assert os_mod.progress()["phase"] == "error"


def test_run_setup_full_flow(monkeypatch):
    """미설치 → 설치 → 기동 → pull 전체 경로(서브프로세스/헬스 모킹)."""
    fake = _FakeClient(False, [])

    monkeypatch.setattr(os_mod, "OllamaClient", lambda url: fake)
    monkeypatch.setattr(os_mod, "is_installed", lambda: True)  # 설치 후 존재한다고 가정
    monkeypatch.setattr(os_mod, "install_command", lambda platform=None: ["echo", "install"])

    async def fake_run(cmd, timeout):
        return 0, "ok"

    async def fake_wait(client, attempts=30, delay=1.0):
        return True

    monkeypatch.setattr(os_mod, "_run", fake_run)
    monkeypatch.setattr(os_mod, "_wait_healthy", fake_wait)

    ok = asyncio.run(os_mod.run_setup("http://localhost:11434", "llama3.1:8b"))
    assert ok is True
    assert os_mod.progress()["phase"] == "done"
    assert fake.pulled == "llama3.1:8b"


def test_default_model_is_exaone(monkeypatch):
    """온보딩 기본 설치 모델 SSOT: 모델 인자 없이 run_setup → exaone3.5:7.8b 를 pull(가이드와 동일)."""
    assert os_mod.DEFAULT_MODEL == "exaone3.5:7.8b"
    fake = _FakeClient(False, [])
    monkeypatch.setattr(os_mod, "OllamaClient", lambda url: fake)
    monkeypatch.setattr(os_mod, "is_installed", lambda: True)
    monkeypatch.setattr(os_mod, "install_command", lambda platform=None: ["echo", "install"])

    async def fake_run(cmd, timeout):
        return 0, "ok"

    async def fake_wait(client, attempts=30, delay=1.0):
        return True

    monkeypatch.setattr(os_mod, "_run", fake_run)
    monkeypatch.setattr(os_mod, "_wait_healthy", fake_wait)

    ok = asyncio.run(os_mod.run_setup("http://localhost:11434"))  # 모델 인자 생략 → DEFAULT_MODEL
    assert ok is True
    assert fake.pulled == "exaone3.5:7.8b"
