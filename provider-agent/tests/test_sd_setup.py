"""앱 내 Stable Diffusion(A1111) 설치 테스트 — 순수 헬퍼 + 오케스트레이션(서브프로세스/다운로드/클라이언트 모킹)."""
from __future__ import annotations

import asyncio

from provider_agent import sd_setup as sd_mod


def test_install_dir_is_dot_free(monkeypatch, tmp_path):
    # gradio 3.43.2 는 경로에 '.'로 시작하는 컴포넌트가 있으면 /file= 정적자산을 403 으로 막는다.
    # → install_dir 의 어떤 컴포넌트도 점으로 시작하면 안 된다(WebUI JS 로드 보장). 이름은 sdnext.
    d = sd_mod.install_dir()
    assert d.name == "sdnext"
    assert not any(part.startswith(".") for part in d.parts), f"점으로 시작하는 경로 컴포넌트 금지: {d}"


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
    assert cmd[-1] == str(tmp_path) and sd_mod.SDNEXT_REPO in cmd  # SD.Next repo
    assert "sdnext" in sd_mod.SDNEXT_REPO  # A1111 아님


def test_launch_command_per_platform(tmp_path):
    # SD.Next: API 항상 켜짐(--api 불필요), MPS/정밀도 자체 처리(A1111 플래그 없음). 모델 없으면 --ckpt 없음.
    mac = sd_mod.launch_command("darwin", tmp_path)
    assert mac == ["bash", str(tmp_path / "webui.sh")]
    win = sd_mod.launch_command("win32", tmp_path)
    assert win[0] == "cmd" and str(tmp_path / "webui.bat") in win
    for cmd in (mac, win):
        assert "--no-half-vae" not in cmd and "--skip-torch-cuda-test" not in cmd and "--upcast-sampling" not in cmd


def test_launch_command_adds_ckpt_when_model_present(tmp_path):
    # 모델이 있으면 --ckpt 로 명시 로드(SD.Next 자동선택 실패 → "model not loaded" 방지).
    md = tmp_path / "models" / "Stable-diffusion"
    md.mkdir(parents=True)
    ckpt = md / "v1-5-pruned-emaonly.safetensors"
    ckpt.write_text("x")
    cmd = sd_mod.launch_command("darwin", tmp_path)
    assert "--ckpt" in cmd and str(ckpt) in cmd


def test_extra_pip_deps_includes_torchsde():
    # SD.Next 가 자동설치 안 하지만 Mac MPS startup 에 필요(실증) — 우리 setup 이 시드해야 함.
    assert "torchsde" in sd_mod.EXTRA_PIP_DEPS


def test_install_dir_is_sdnext():
    # SD.Next 는 기존 A1111(stable-diffusion-webui)과 분리된 경로(sdnext)에 설치.
    d = sd_mod.install_dir()
    assert d.name == "sdnext" and "stable-diffusion-webui" not in str(d)


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
    # PATH 에 있으면 명령 그대로 반환.
    monkeypatch.setattr(sd_mod.shutil, "which", lambda c: "/x/python3.11" if c == "python3.11" else None)
    assert sd_mod.compatible_python() == "python3.11"
    # PATH 에도 없고 절대 경로에도 없으면 None.
    monkeypatch.setattr(sd_mod.shutil, "which", lambda c: None)
    monkeypatch.setattr(sd_mod.os.path, "isfile", lambda p: False)
    assert sd_mod.compatible_python() is None
    # macOS GUI 앱(PATH 에 brew 경로 없음): 절대 경로에 있으면 그 경로 반환(no-python 회귀 방지).
    monkeypatch.setattr(sd_mod.sys, "platform", "darwin")
    monkeypatch.setattr(sd_mod.os.path, "isfile", lambda p: p == "/opt/homebrew/bin/python3.11")
    monkeypatch.setattr(sd_mod.os, "access", lambda p, m: True)
    assert sd_mod.compatible_python() == "/opt/homebrew/bin/python3.11"


def test_launch_env_uses_PYTHON_all_platforms():
    # SD.Next 는 모든 플랫폼에서 PYTHON 환경변수를 읽는다(python_cmd 아님 — 실증으로 확인).
    assert sd_mod.launch_env("python3.11", "darwin") == {"PYTHON": "python3.11"}
    assert sd_mod.launch_env("python3.11", "linux") == {"PYTHON": "python3.11"}
    assert sd_mod.launch_env("python", "win32") == {"PYTHON": "python"}
    assert sd_mod.launch_env(None, "darwin") == {}


# (A1111 우회 함수 테스트 stable_diffusion_repo/write_pip_constraints/bootstrap_env 는 제거됨 —
#  SD.Next 전환으로 해당 우회가 불필요해져 함수 자체를 삭제했다.)


def test_model_by_id():
    assert sd_mod.model_by_id("sdxl")["filename"] == "sd_xl_base_1.0.safetensors"
    assert sd_mod.model_by_id("sd15")["id"] == "sd15"
    # 애니 모델 카탈로그 등재 확인(서비스가 지원)
    assert sd_mod.model_by_id("anime")["base"] == "sd15"
    assert sd_mod.model_by_id("anime-xl")["base"] == "sdxl"
    # 없는 id → 기본(첫 모델)
    assert sd_mod.model_by_id("nope")["id"] == sd_mod.MODELS[0]["id"]


def test_installed_models_and_selection(tmp_path, monkeypatch):
    md = tmp_path / "models" / "Stable-diffusion"
    md.mkdir(parents=True)
    (md / "AnythingV5V3_v5PrtRE.safetensors").write_bytes(b"x")  # 카탈로그 모델
    (md / "my-custom-merge.safetensors").write_bytes(b"x")  # 커스텀(카탈로그 밖)
    inst = sd_mod.installed_models(tmp_path)
    by_file = {m["filename"]: m for m in inst}
    assert by_file["AnythingV5V3_v5PrtRE.safetensors"]["name"] == "Anything V5 (애니)"
    assert by_file["AnythingV5V3_v5PrtRE.safetensors"]["base"] == "sd15"
    # 커스텀 모델도 보인다(파일명 stem 으로)
    assert by_file["my-custom-merge.safetensors"]["name"] == "my-custom-merge"

    # selected_model_path: config sd_model 이 가리키는 파일이 있으면 그 경로, 없으면 None
    import provider_agent.config_file as cf
    monkeypatch.setattr(cf, "load_config", lambda *a, **k: {"sd_model": "my-custom-merge.safetensors"})
    assert sd_mod.selected_model_path(tmp_path) == md / "my-custom-merge.safetensors"
    monkeypatch.setattr(cf, "load_config", lambda *a, **k: {"sd_model": "nonexistent.safetensors"})
    assert sd_mod.selected_model_path(tmp_path) is None
    # launch_command 가 선택 모델을 우선 --ckpt 로 쓴다
    monkeypatch.setattr(cf, "load_config", lambda *a, **k: {"sd_model": "AnythingV5V3_v5PrtRE.safetensors"})
    cmd = sd_mod.launch_command("darwin", tmp_path)
    assert "--ckpt" in cmd
    assert cmd[cmd.index("--ckpt") + 1].endswith("AnythingV5V3_v5PrtRE.safetensors")


def test_custom_model_from_url():
    # resolve 직접 링크 → 모델 dict
    m = sd_mod.custom_model_from_url("https://huggingface.co/cagliostrolab/animagine-xl-4.0/resolve/main/animagine-xl-4.0-opt.safetensors")
    assert m is not None
    assert m["filename"] == "animagine-xl-4.0-opt.safetensors"
    assert m["url"].endswith("animagine-xl-4.0-opt.safetensors")
    # blob URL(페이지) → resolve 로 보정
    blob = sd_mod.custom_model_from_url("https://huggingface.co/x/y/blob/main/foo.safetensors")
    assert blob is not None and "/resolve/" in blob["url"] and blob["filename"] == "foo.safetensors"
    # .ckpt 허용
    assert sd_mod.custom_model_from_url("https://huggingface.co/a/b/resolve/main/m.ckpt") is not None
    # HF 아님 / 확장자 안 맞음 → None(임의 호스트 차단)
    assert sd_mod.custom_model_from_url("https://evil.com/m.safetensors") is None
    assert sd_mod.custom_model_from_url("https://huggingface.co/a/b/resolve/main/readme.txt") is None
    assert sd_mod.custom_model_from_url("") is None


def test_resolution_for_checkpoint():
    # SDXL 계열 → 1024, SD1.5 계열 → 512. 체크포인트 문자열은 "name [hash]" 부분일치.
    assert sd_mod.resolution_for_checkpoint("animagine-xl-4.0-opt.safetensors [abc123]") == (1024, 1024)
    assert sd_mod.resolution_for_checkpoint("sd_xl_base_1.0.safetensors") == (1024, 1024)
    # SD.Next 는 확장자 없이 보고한다(실증: "AnythingV5V3_v5PrtRE") → stem 매칭 필수
    assert sd_mod.resolution_for_checkpoint("animagine-xl-4.0-opt") == (1024, 1024)
    assert sd_mod.resolution_for_checkpoint("AnythingV5V3_v5PrtRE") == (512, 512)
    assert sd_mod.resolution_for_checkpoint("AnythingV5V3_v5PrtRE.safetensors") == (512, 512)
    assert sd_mod.resolution_for_checkpoint("v1-5-pruned-emaonly.safetensors") == (512, 512)
    # 모르는 모델/None → 안전하게 512
    assert sd_mod.resolution_for_checkpoint("someones-custom-merge.safetensors") == (512, 512)
    assert sd_mod.resolution_for_checkpoint(None) == (512, 512)


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

    async def fake_download(url, dest, message_prefix="이미지 모델 내려받는 중…"):
        dest.parent.mkdir(parents=True, exist_ok=True)
        dest.write_text("m")

    spawned: dict = {}

    async def fake_spawn(cmd, env=None, log_path=None, cwd=None):
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
    assert spawned["env"] and spawned["env"].get("PYTHON")     # SD.Next 에 호환 Python(PYTHON) 전달


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

    async def fake_download(url, dest, message_prefix="이미지 모델 내려받는 중…"):
        dest.parent.mkdir(parents=True, exist_ok=True)
        dest.write_text("model")
        downloaded["url"] = url

    spawned: dict = {}

    async def fake_spawn(cmd, env=None, log_path=None, cwd=None):
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

    async def _noop_deps(*_a, **_k):  # 이 테스트는 webui-exit 에러 보고만 검증 — 실제 venv/pip 생략(CI 무관)
        return None

    monkeypatch.setattr(sd_mod, "ensure_extra_deps", _noop_deps)

    class _DeadProc:
        returncode = 128  # 예: Stability-AI repo 404 로 클론 실패

    async def fake_spawn(cmd, env=None, log_path=None, cwd=None):
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


def test_dl_percent_maps_into_download_band():
    """받은 바이트 비율을 다운로드 구간(35~95%)으로 매핑."""
    assert sd_mod._dl_percent(0, 100) == sd_mod._DL_START_PCT
    assert sd_mod._dl_percent(100, 100) == sd_mod._DL_END_PCT
    mid = sd_mod._dl_percent(50, 100)
    assert sd_mod._DL_START_PCT < mid < sd_mod._DL_END_PCT
    # 전체 크기 미상이면 시작점만(0 나눗셈 방지).
    assert sd_mod._dl_percent(123, None) == sd_mod._DL_START_PCT
    assert sd_mod._dl_percent(123, 0) == sd_mod._DL_START_PCT


class _FakeStreamResp:
    """aiohttp 응답 스트리밍 모킹: status·content_length·청크 시퀀스를 흉내낸다."""

    def __init__(self, status, body=b"", content_length=None):
        self.status = status
        self._body = body
        self.content_length = content_length

    async def __aenter__(self):
        return self

    async def __aexit__(self, *a):
        return False

    @property
    def content(self):
        body = self._body

        class _Content:
            @staticmethod
            async def iter_chunked(n):
                for i in range(0, len(body), n):
                    yield body[i : i + n]

        return _Content()


class _FakeSession:
    """aiohttp.ClientSession 모킹: get(url, headers) 호출을 기록하고 미리 준비한 응답을 돌려준다."""

    def __init__(self, response, captured):
        self._response = response
        self._captured = captured

    async def __aenter__(self):
        return self

    async def __aexit__(self, *a):
        return False

    def get(self, url, headers=None):
        self._captured["url"] = url
        self._captured["headers"] = dict(headers or {})
        return self._response


def _patch_session(monkeypatch, response, captured):
    monkeypatch.setattr(
        sd_mod.aiohttp, "ClientSession", lambda timeout=None: _FakeSession(response, captured)
    )


def test_download_reports_progress(monkeypatch, tmp_path):
    """(a) 다운로드 중 진행률이 35~95% 구간에서 갱신되고 message 에 MB 가 표시된다."""
    sd_mod._cancel = False
    dest = tmp_path / "model.safetensors"
    body = b"x" * (40 << 20)  # 40MB — throttle(16MB) 경계를 여러 번 넘김
    captured: dict = {}
    _patch_session(monkeypatch, _FakeStreamResp(200, body, content_length=len(body)), captured)

    seen: list = []
    orig_set = sd_mod._set

    def spy_set(phase=None, percent=None, message=None, error=None):
        if phase == "downloading":
            seen.append((percent, message))
        orig_set(phase, percent, message, error)

    monkeypatch.setattr(sd_mod, "_set", spy_set)
    asyncio.run(sd_mod._download("http://x/model", dest, "이미지 모델 내려받는 중…"))

    assert dest.read_bytes() == body  # 완료 시 .part → dest rename
    assert not dest.with_suffix(dest.suffix + ".part").exists()
    assert seen, "다운로드 중 진행률 갱신이 일어나야 한다"
    # 갱신된 percent 는 모두 다운로드 구간 안, 단조 증가.
    pcts = [p for p, _ in seen]
    assert all(sd_mod._DL_START_PCT <= p <= sd_mod._DL_END_PCT for p in pcts)
    assert pcts == sorted(pcts)
    assert "MB" in seen[-1][1]  # message 에 받은/전체 MB 표시


def test_download_resumes_with_range_206(monkeypatch, tmp_path):
    """(b) 이어받기: 기존 .part 가 있으면 Range 요청 → 206 응답을 append 로 이어붙인다."""
    sd_mod._cancel = False
    dest = tmp_path / "model.safetensors"
    part = dest.with_suffix(dest.suffix + ".part")
    part.write_bytes(b"AAAA")  # 이미 4바이트 받음
    rest = b"BBBBBB"  # 남은 6바이트
    captured: dict = {}
    # 206: content_length 는 '남은 분량'(6)만.
    _patch_session(monkeypatch, _FakeStreamResp(206, rest, content_length=len(rest)), captured)

    asyncio.run(sd_mod._download("http://x/model", dest))

    assert captured["headers"].get("Range") == "bytes=4-"  # 받은 크기로 Range 요청
    assert dest.read_bytes() == b"AAAA" + rest  # 기존 + 남은 분량 = 완성
    assert not part.exists()


def test_download_range_unsupported_restarts_200(monkeypatch, tmp_path):
    """(c) Range 미지원: .part 있어도 서버가 200(전체 재전송)이면 처음부터(절단)."""
    sd_mod._cancel = False
    dest = tmp_path / "model.safetensors"
    part = dest.with_suffix(dest.suffix + ".part")
    part.write_bytes(b"OLD-GARBAGE")  # 이전 부분 — 200 이면 버려야 함
    full = b"FULLBODY12"
    captured: dict = {}
    _patch_session(monkeypatch, _FakeStreamResp(200, full, content_length=len(full)), captured)

    asyncio.run(sd_mod._download("http://x/model", dest))

    assert captured["headers"].get("Range") == "bytes=11-"  # 시도는 했으나
    assert dest.read_bytes() == full  # 200 → 절단 후 전체 = 정확히 full(이전 garbage 없음)
    assert not part.exists()


def test_download_416_treats_as_complete(monkeypatch, tmp_path):
    """416(범위 초과): 이미 다 받은 것으로 보고 .part 를 rename 한다."""
    sd_mod._cancel = False
    dest = tmp_path / "model.safetensors"
    part = dest.with_suffix(dest.suffix + ".part")
    part.write_bytes(b"COMPLETE")
    captured: dict = {}
    _patch_session(monkeypatch, _FakeStreamResp(416), captured)

    asyncio.run(sd_mod._download("http://x/model", dest))
    assert dest.read_bytes() == b"COMPLETE"
    assert not part.exists()


def test_download_preserves_part_on_error(monkeypatch, tmp_path):
    """(d) 네트워크 끊김 등 예외 시 .part 를 보존(삭제 금지) → 다음 시도가 이어받음."""
    sd_mod._cancel = False
    dest = tmp_path / "model.safetensors"
    part = dest.with_suffix(dest.suffix + ".part")

    class _BoomResp(_FakeStreamResp):
        @property
        def content(self):
            class _Content:
                @staticmethod
                async def iter_chunked(n):
                    yield b"PARTIAL"  # 일부 받고
                    raise ConnectionResetError("끊김")  # 도중 끊김
            return _Content()

    captured: dict = {}
    _patch_session(monkeypatch, _BoomResp(200, content_length=100), captured)

    import pytest as _pt

    with _pt.raises(ConnectionResetError):
        asyncio.run(sd_mod._download("http://x/model", dest))
    assert part.exists() and part.read_bytes() == b"PARTIAL"  # 받은 만큼 보존
    assert not dest.exists()


def test_download_preserves_part_on_cancel(monkeypatch, tmp_path):
    """취소(_cancel) 시에도 .part 를 보존한다(다음 run_setup 이 이어받음)."""
    dest = tmp_path / "model.safetensors"
    part = dest.with_suffix(dest.suffix + ".part")
    body = b"y" * (4 << 20)
    captured: dict = {}
    _patch_session(monkeypatch, _FakeStreamResp(200, body, content_length=len(body)), captured)

    sd_mod._cancel = True  # 첫 청크에서 즉시 취소
    import pytest as _pt

    try:
        with _pt.raises(asyncio.CancelledError):
            asyncio.run(sd_mod._download("http://x/model", dest))
        assert part.exists()  # 취소돼도 .part 보존
        assert not dest.exists()
    finally:
        sd_mod._cancel = False  # 다른 테스트 영향 방지


def test_launch_only_already_running(monkeypatch):
    """SD 가 이미 떠 있으면 기동 없이 done."""
    monkeypatch.setattr(sd_mod, "SDClient", lambda url: _FakeClient(True))
    ok = asyncio.run(sd_mod.launch_only("http://127.0.0.1:7860"))
    assert ok is True
    assert sd_mod.progress()["phase"] == "done"


def test_launch_only_refuses_when_not_installed(monkeypatch, tmp_path):
    """설치 안 됨 → 대용량 다운로드 없이 즉시 not-installed 에러(전체 설치 마법사로 유도)."""
    monkeypatch.setattr(sd_mod, "SDClient", lambda url: _FakeClient(False))
    monkeypatch.setattr(sd_mod, "install_dir", lambda: tmp_path / "sd")  # 존재하지 않음
    ok = asyncio.run(sd_mod.launch_only("http://127.0.0.1:7860"))
    assert ok is False
    assert sd_mod.progress()["error"] == "not-installed"


def test_launch_only_spawns_when_installed(monkeypatch, tmp_path):
    """설치돼 있으면 clone/다운로드 없이 webui 만 기동(재부팅 후 다시 켜기)."""
    directory = tmp_path / "sd"
    directory.mkdir(parents=True)
    (directory / "webui.sh").write_text("#!/bin/sh\n")  # is_installed True
    md = directory / "models" / "Stable-diffusion"
    md.mkdir(parents=True)
    (md / "m.safetensors").write_text("model")  # has_model True
    monkeypatch.setattr(sd_mod, "SDClient", lambda url: _FakeClient(False))
    monkeypatch.setattr(sd_mod, "install_dir", lambda: directory)
    monkeypatch.setattr(sd_mod, "compatible_python", lambda: "python3.11")

    spawned: dict = {}
    ran: list = []

    async def fake_spawn(cmd, env=None, log_path=None, cwd=None):
        spawned["cmd"] = cmd
        return object()

    async def fake_run(cmd, timeout):  # clone/install 이 호출되면 안 됨(기동 전용)
        ran.append(cmd)
        return 0, "ok"

    async def fake_wait(client, proc=None, attempts=600, delay=2.0):
        return True

    monkeypatch.setattr(sd_mod, "_spawn", fake_spawn)
    monkeypatch.setattr(sd_mod, "_run", fake_run)
    monkeypatch.setattr(sd_mod, "_wait_healthy", fake_wait)
    ok = asyncio.run(sd_mod.launch_only("http://127.0.0.1:7860"))
    assert ok is True
    assert sd_mod.progress()["phase"] == "done"
    assert spawned["cmd"][0] == "bash"  # webui.sh 기동
    # clone/모델 다운로드는 안 함(기동 전용). venv 의존성 보강(ensure_extra_deps)은 허용.
    assert not any("clone" in " ".join(str(x) for x in c) for c in ran)


def test_resolution_custom_base(monkeypatch):
    import provider_agent.config_file as cf
    monkeypatch.setattr(cf, "load_config", lambda *a, **k: {"custom_bases": {"my-sdxl-merge.safetensors": "sdxl"}})
    # 커스텀 SDXL → 1024(확장자 없이 보고돼도 stem 매칭)
    assert sd_mod.resolution_for_checkpoint("my-sdxl-merge [abc]") == (1024, 1024)
    assert sd_mod.resolution_for_checkpoint("my-sdxl-merge.safetensors") == (1024, 1024)
    # 등록 안 된 커스텀 → 512
    assert sd_mod.resolution_for_checkpoint("unknown-merge") == (512, 512)


def test_download_custom_model_bad_url():
    import asyncio as _a
    ok = _a.run(sd_mod.download_custom_model("not-a-hf-url", "http://127.0.0.1:7860"))
    assert ok is False
    assert sd_mod.progress()["error"] == "bad-url"


def test_download_custom_model_already_present(tmp_path, monkeypatch):
    import asyncio as _a
    md = tmp_path / "models" / "Stable-diffusion"
    md.mkdir(parents=True)
    (md / "foo.safetensors").write_bytes(b"x")
    monkeypatch.setattr(sd_mod, "install_dir", lambda: tmp_path)
    monkeypatch.setattr(sd_mod, "is_installed", lambda *a, **k: True)
    import provider_agent.config_file as cf
    saved = {}
    monkeypatch.setattr(cf, "load_config", lambda *a, **k: dict(saved))
    monkeypatch.setattr(cf, "persist_partial", lambda d, *a, **k: saved.update(d))
    ok = _a.run(sd_mod.download_custom_model("https://huggingface.co/a/b/resolve/main/foo.safetensors", "http://127.0.0.1:7860", base="sdxl"))
    assert ok is True
    assert sd_mod.progress()["phase"] == "done"
    assert saved.get("custom_bases", {}).get("foo.safetensors") == "sdxl"  # base 저장됨
