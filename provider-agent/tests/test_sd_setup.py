"""앱 내 Stable Diffusion(A1111) 설치 테스트 — 순수 헬퍼 + 오케스트레이션(서브프로세스/다운로드/클라이언트 모킹)."""
from __future__ import annotations

import asyncio

from provider_agent import sd_setup as sd_mod


def test_install_dir_follows_xdg(monkeypatch, tmp_path):
    monkeypatch.setenv("XDG_DATA_HOME", str(tmp_path))
    d = sd_mod.install_dir()
    assert d == tmp_path / "discord-ai-network-bot" / "stable-diffusion-webui"


def test_has_git(monkeypatch):
    monkeypatch.setattr(sd_mod.shutil, "which", lambda c: "/usr/bin/git" if c == "git" else None)
    assert sd_mod.has_git() is True
    monkeypatch.setattr(sd_mod.shutil, "which", lambda c: None)
    assert sd_mod.has_git() is False


def test_is_installed(tmp_path):
    assert sd_mod.is_installed(tmp_path) is False
    (tmp_path / "webui.sh").write_text("#!/bin/sh\n")
    assert sd_mod.is_installed(tmp_path) is True


def test_is_installed_windows_bat(tmp_path):
    (tmp_path / "webui.bat").write_text("@echo off\n")
    assert sd_mod.is_installed(tmp_path) is True


def test_has_model(tmp_path):
    assert sd_mod.has_model(tmp_path) is False
    md = sd_mod.model_dir(tmp_path)
    md.mkdir(parents=True)
    (md / "readme.txt").write_text("x")  # 체크포인트 아님
    assert sd_mod.has_model(tmp_path) is False
    (md / "model.safetensors").write_text("x")
    assert sd_mod.has_model(tmp_path) is True


def test_clone_command(tmp_path):
    cmd = sd_mod.clone_command(tmp_path)
    assert cmd[0] == "git" and cmd[1] == "clone" and "--depth" in cmd
    assert cmd[-1] == str(tmp_path) and sd_mod.A1111_REPO in cmd


def test_launch_command_per_platform(tmp_path):
    mac = sd_mod.launch_command("darwin", tmp_path)
    assert mac[0] == "bash" and mac[1] == str(tmp_path / "webui.sh") and "--api" in mac
    win = sd_mod.launch_command("win32", tmp_path)
    assert win[0] == "cmd" and str(tmp_path / "webui.bat") in win and "--api" in win


class _FakeClient:
    def __init__(self, healthy):
        self._healthy = healthy

    async def health(self):
        return self._healthy


def test_run_setup_already_ready(monkeypatch):
    """이미 SD 가 떠 있으면 설치 없이 done."""
    monkeypatch.setattr(sd_mod, "SDClient", lambda url: _FakeClient(True))
    ok = asyncio.run(sd_mod.run_setup("http://127.0.0.1:7860"))
    assert ok is True
    assert sd_mod.progress()["phase"] == "done"


def test_run_setup_no_git(monkeypatch, tmp_path):
    """미설치 + git 없음 → error."""
    monkeypatch.setattr(sd_mod, "SDClient", lambda url: _FakeClient(False))
    monkeypatch.setattr(sd_mod, "install_dir", lambda: tmp_path / "sd")
    monkeypatch.setattr(sd_mod, "has_git", lambda: False)
    ok = asyncio.run(sd_mod.run_setup("http://127.0.0.1:7860"))
    assert ok is False
    assert sd_mod.progress()["phase"] == "error"
    assert sd_mod.progress()["error"] == "no-git"


def test_run_setup_full_flow(monkeypatch, tmp_path):
    """미설치 → clone → 모델 다운로드 → 기동 → 준비 전체 경로(서브프로세스/다운로드/헬스 모킹)."""
    directory = tmp_path / "sd"
    monkeypatch.setattr(sd_mod, "SDClient", lambda url: _FakeClient(False))
    monkeypatch.setattr(sd_mod, "install_dir", lambda: directory)
    monkeypatch.setattr(sd_mod, "has_git", lambda: True)

    cloned: dict = {}

    async def fake_run(cmd, timeout):
        # clone 흉내: 런처 파일 생성 → is_installed True
        directory.mkdir(parents=True, exist_ok=True)
        (directory / "webui.sh").write_text("#!/bin/sh\n")
        cloned["ran"] = True
        return 0, "ok"

    downloaded: dict = {}

    async def fake_download(url, dest):
        dest.parent.mkdir(parents=True, exist_ok=True)
        dest.write_text("model")
        downloaded["url"] = url

    spawned: dict = {}

    async def fake_spawn(cmd):
        spawned["cmd"] = cmd
        return object()

    async def fake_wait(client, attempts=600, delay=2.0):
        return True

    monkeypatch.setattr(sd_mod, "_run", fake_run)
    monkeypatch.setattr(sd_mod, "_download", fake_download)
    monkeypatch.setattr(sd_mod, "_spawn", fake_spawn)
    monkeypatch.setattr(sd_mod, "_wait_healthy", fake_wait)

    ok = asyncio.run(sd_mod.run_setup("http://127.0.0.1:7860"))
    assert ok is True
    assert sd_mod.progress()["phase"] == "done"
    assert cloned.get("ran") is True
    assert downloaded.get("url") == sd_mod.DEFAULT_MODEL_URL
    assert spawned.get("cmd") and spawned["cmd"][0] == "bash"
