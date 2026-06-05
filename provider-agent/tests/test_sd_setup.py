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


def test_pkg_manager_and_install_tool(monkeypatch):
    # brew 있으면 mac 은 brew, git/python 명령 생성
    monkeypatch.setattr(sd_mod.shutil, "which", lambda c: "/x/brew" if c == "brew" else None)
    assert sd_mod.pkg_manager("darwin") == "brew"
    assert sd_mod.install_tool_command("git", "darwin") == ["brew", "install", "git"]
    assert sd_mod.install_tool_command("python", "darwin") == ["brew", "install", "python@3.11"]
    # 패키지 매니저 없으면 None
    monkeypatch.setattr(sd_mod.shutil, "which", lambda c: None)
    assert sd_mod.pkg_manager("darwin") is None
    assert sd_mod.install_tool_command("git", "darwin") is None
    # Windows winget
    monkeypatch.setattr(sd_mod.shutil, "which", lambda c: "/x/winget" if c == "winget" else None)
    cmd = sd_mod.install_tool_command("python", "win32")
    assert cmd is not None and cmd[0] == "winget" and "Python.Python.3.11" in cmd


def test_compatible_python(monkeypatch):
    monkeypatch.setattr(sd_mod.shutil, "which", lambda c: "/x/python3.11" if c == "python3.11" else None)
    assert sd_mod.compatible_python() == "python3.11"
    monkeypatch.setattr(sd_mod.shutil, "which", lambda c: None)
    assert sd_mod.compatible_python() is None


def test_launch_env_per_platform():
    assert sd_mod.launch_env("python3.11", "darwin") == {"python_cmd": "python3.11"}
    assert sd_mod.launch_env("python", "win32") == {"PYTHON": "python"}
    assert sd_mod.launch_env(None, "darwin") == {}


def test_stable_diffusion_repo_default_and_override(monkeypatch):
    monkeypatch.delenv("SD_STABLE_DIFFUSION_REPO", raising=False)
    assert sd_mod.stable_diffusion_repo() == sd_mod.DEFAULT_STABLE_DIFFUSION_REPO
    # 사용자 미러 지정
    monkeypatch.setenv("SD_STABLE_DIFFUSION_REPO", "https://example.com/x/stablediffusion.git")
    assert sd_mod.stable_diffusion_repo() == "https://example.com/x/stablediffusion.git"
    # 빈 값 → 비활성화(원본 URL 사용)
    monkeypatch.setenv("SD_STABLE_DIFFUSION_REPO", "")
    assert sd_mod.stable_diffusion_repo() == ""


def test_write_pip_constraints(tmp_path):
    path = sd_mod.write_pip_constraints(tmp_path / "sd")
    assert path.exists()
    content = path.read_text("utf-8")
    assert "setuptools<81" in content  # CLIP 빌드용 pkg_resources 보유 버전 핀


def test_bootstrap_env_includes_constraint_and_repo(monkeypatch, tmp_path):
    monkeypatch.delenv("SD_STABLE_DIFFUSION_REPO", raising=False)
    env = sd_mod.bootstrap_env(tmp_path / "sd")
    assert env["STABLE_DIFFUSION_REPO"] == sd_mod.DEFAULT_STABLE_DIFFUSION_REPO  # 업스트림 채택 fork
    assert env["PIP_CONSTRAINT"].endswith("pip-constraints.txt")
    from pathlib import Path
    assert Path(env["PIP_CONSTRAINT"]).exists()


def test_bootstrap_env_repo_disabled_when_blank(monkeypatch, tmp_path):
    monkeypatch.setenv("SD_STABLE_DIFFUSION_REPO", "")  # 사용자가 미러 끔
    env = sd_mod.bootstrap_env(tmp_path / "sd")
    assert "STABLE_DIFFUSION_REPO" not in env  # A1111 기본 URL 사용(오버라이드 안 함)
    assert "PIP_CONSTRAINT" in env  # setuptools 핀은 그대로


def test_model_by_id():
    assert sd_mod.model_by_id("sdxl")["filename"] == "sd_xl_base_1.0.safetensors"
    assert sd_mod.model_by_id("sd15")["id"] == "sd15"
    # 없는 id → 기본(첫 모델)
    assert sd_mod.model_by_id("nope")["id"] == sd_mod.MODELS[0]["id"]


def test_request_cancel_sets_cancelled():
    sd_mod._set("installing", 20, "x")
    sd_mod.request_cancel()
    assert sd_mod.progress()["phase"] == "cancelled"
    sd_mod._cancel = False  # 다른 테스트 영향 방지


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
    """git 없음 + 자동 설치 수단(brew/winget) 없음 → error(no-git)."""
    monkeypatch.setattr(sd_mod, "SDClient", lambda url: _FakeClient(False))
    monkeypatch.setattr(sd_mod, "install_dir", lambda: tmp_path / "sd")
    monkeypatch.setattr(sd_mod, "has_git", lambda: False)
    monkeypatch.setattr(sd_mod, "install_tool_command", lambda tool, platform=None: None)
    ok = asyncio.run(sd_mod.run_setup("http://127.0.0.1:7860"))
    assert ok is False
    assert sd_mod.progress()["phase"] == "error"
    assert sd_mod.progress()["error"] == "no-git"


def test_run_setup_installs_prereqs(monkeypatch, tmp_path):
    """설치 마법사: git·Python 이 없어도 패키지 매니저로 설치하고 전체 경로를 끝낸다."""
    directory = tmp_path / "sd"
    monkeypatch.setattr(sd_mod, "SDClient", lambda url: _FakeClient(False))
    monkeypatch.setattr(sd_mod, "install_dir", lambda: directory)
    git_state = {"has": False}
    monkeypatch.setattr(sd_mod, "has_git", lambda: git_state["has"])
    monkeypatch.setattr(sd_mod, "compatible_python", lambda: None)  # 설치 후에도 PATH 미갱신 가정 → best-effort
    monkeypatch.setattr(sd_mod, "install_tool_command", lambda tool, platform=None: ["echo", tool])

    ran: list = []

    async def fake_run(cmd, timeout):
        ran.append(cmd)
        if cmd == ["echo", "git"]:
            git_state["has"] = True
        if "clone" in cmd:
            directory.mkdir(parents=True, exist_ok=True)
            (directory / "webui.sh").write_text("#!/bin/sh\n")
        return 0, "ok"

    async def fake_download(url, dest):
        dest.parent.mkdir(parents=True, exist_ok=True)
        dest.write_text("m")

    spawned: dict = {}

    async def fake_spawn(cmd, env=None, log_path=None):
        spawned["env"] = env
        return object()

    async def fake_wait(client, proc=None, attempts=600, delay=2.0):
        return True

    monkeypatch.setattr(sd_mod, "_run", fake_run)
    monkeypatch.setattr(sd_mod, "_download", fake_download)
    monkeypatch.setattr(sd_mod, "_spawn", fake_spawn)
    monkeypatch.setattr(sd_mod, "_wait_healthy", fake_wait)

    ok = asyncio.run(sd_mod.run_setup("http://127.0.0.1:7860"))
    assert ok is True
    assert sd_mod.progress()["phase"] == "done"
    assert ["echo", "git"] in ran and ["echo", "python"] in ran  # 전제 도구 설치됨
    assert spawned["env"] and spawned["env"].get("python_cmd")     # webui 에 호환 Python 전달


def test_run_setup_full_flow(monkeypatch, tmp_path):
    """미설치 → clone → 모델 다운로드 → 기동 → 준비 전체 경로(서브프로세스/다운로드/헬스 모킹)."""
    directory = tmp_path / "sd"
    monkeypatch.setattr(sd_mod, "SDClient", lambda url: _FakeClient(False))
    monkeypatch.setattr(sd_mod, "install_dir", lambda: directory)
    monkeypatch.setattr(sd_mod, "has_git", lambda: True)
    monkeypatch.setattr(sd_mod, "compatible_python", lambda: "python3.11")

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

    async def fake_spawn(cmd, env=None, log_path=None):
        spawned["cmd"] = cmd
        return object()

    async def fake_wait(client, proc=None, attempts=600, delay=2.0):
        return True

    monkeypatch.setattr(sd_mod, "_run", fake_run)
    monkeypatch.setattr(sd_mod, "_download", fake_download)
    monkeypatch.setattr(sd_mod, "_spawn", fake_spawn)
    monkeypatch.setattr(sd_mod, "_wait_healthy", fake_wait)

    ok = asyncio.run(sd_mod.run_setup("http://127.0.0.1:7860", "sdxl"))
    assert ok is True
    assert sd_mod.progress()["phase"] == "done"
    assert cloned.get("ran") is True
    # 선택한 모델(sdxl)의 URL 로 다운로드됐다
    assert downloaded.get("url") == sd_mod.model_by_id("sdxl")["url"]
    assert spawned.get("cmd") and spawned["cmd"][0] == "bash"


def test_wait_healthy_fast_fails_when_proc_dies():
    """webui 프로세스가 이미 종료(returncode 세팅)됐으면, health 가 안 떠도 즉시 False(20분 폴링 금지)."""
    class _DeadProc:
        returncode = 1

    # attempts 를 크게 줘도, 죽은 proc 을 보면 첫 폴링에서 바로 False 가 나와야 한다.
    ok = asyncio.run(sd_mod._wait_healthy(_FakeClient(False), _DeadProc(), attempts=100000, delay=0.0))
    assert ok is False


def test_run_setup_reports_webui_exit_with_log(monkeypatch, tmp_path):
    """첫 실행 webui 가 죽으면(부트스트랩 실패) error phase 로 떨어지고, 로그 꼬리를 error 에 담는다."""
    directory = tmp_path / "sd"
    directory.mkdir(parents=True)
    (directory / "webui.sh").write_text("#!/bin/sh\n")  # is_installed True
    md = directory / "models" / "Stable-diffusion"
    md.mkdir(parents=True)
    (md / "m.safetensors").write_text("model")  # has_model True
    monkeypatch.setattr(sd_mod, "SDClient", lambda url: _FakeClient(False))
    monkeypatch.setattr(sd_mod, "install_dir", lambda: directory)
    monkeypatch.setattr(sd_mod, "has_git", lambda: True)
    monkeypatch.setattr(sd_mod, "compatible_python", lambda: "python3.11")

    class _DeadProc:
        returncode = 128  # 예: Stability-AI repo 404 로 클론 실패

    async def fake_spawn(cmd, env=None, log_path=None):
        # 실제 webui 처럼 로그 파일에 실패 원인을 남기고 죽은 프로세스를 돌려준다.
        if log_path is not None:
            log_path.write_text("fatal: repository 'https://github.com/Stability-AI/stablediffusion.git/' not found")
        return _DeadProc()

    monkeypatch.setattr(sd_mod, "_spawn", fake_spawn)

    ok = asyncio.run(sd_mod.run_setup("http://127.0.0.1:7860"))
    assert ok is False
    p = sd_mod.progress()
    assert p["phase"] == "error"
    assert "stablediffusion" in (p["error"] or "")  # 로그 꼬리가 error 에 실림(진단 가능)
    assert "webui-launch.log" in p["message"]  # 사용자에게 로그 경로 안내


def test_run_setup_cancel_after_clone(monkeypatch, tmp_path):
    """clone 도중 취소 요청이 들어오면 단계 경계에서 cancelled 로 종료."""
    directory = tmp_path / "sd"
    monkeypatch.setattr(sd_mod, "SDClient", lambda url: _FakeClient(False))
    monkeypatch.setattr(sd_mod, "install_dir", lambda: directory)
    monkeypatch.setattr(sd_mod, "has_git", lambda: True)
    monkeypatch.setattr(sd_mod, "compatible_python", lambda: "python3.11")

    async def fake_run(cmd, timeout):
        directory.mkdir(parents=True, exist_ok=True)
        (directory / "webui.sh").write_text("#!/bin/sh\n")
        sd_mod._cancel = True  # clone 진행 중 취소 요청
        return 0, "ok"

    monkeypatch.setattr(sd_mod, "_run", fake_run)
    ok = asyncio.run(sd_mod.run_setup("http://127.0.0.1:7860"))
    assert ok is False
    assert sd_mod.progress()["phase"] == "cancelled"
