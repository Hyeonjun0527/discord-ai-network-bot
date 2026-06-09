"""ComfyUI 라이프사이클(설치/실행/정지) 순수 함수·명령 구성 테스트. 네트워크/프로세스 미사용."""
from __future__ import annotations

from provider_agent import comfy_setup as cs


def test_clone_commands_pins_verified_commit(tmp_path):
    assert cs.COMFY_PIN, "검증된 ComfyUI 커밋 핀이 비면 안 됨"
    seq = cs.clone_commands(tmp_path)
    flat = [tok for step in seq for tok in step]
    assert ["git", "init", str(tmp_path)] in seq
    assert "fetch" in flat and "--depth" in flat and cs.COMFY_PIN in flat
    assert seq[-1][-1] == "FETCH_HEAD"


def test_clone_commands_falls_back_without_pin(monkeypatch, tmp_path):
    monkeypatch.setattr(cs, "COMFY_PIN", "")
    seq = cs.clone_commands(tmp_path)
    assert len(seq) == 1 and seq[0][1] == "clone"


def test_start_command_localhost_port(tmp_path):
    cmd = cs.start_command("/venv/bin/python", tmp_path)
    assert cmd[0] == "/venv/bin/python"
    assert str(tmp_path / "main.py") in cmd
    assert "--port" in cmd and str(cs.COMFY_PORT) in cmd
    assert "--listen" in cmd and "127.0.0.1" in cmd  # netguard: localhost 전용


def test_torch_install_command_per_platform(monkeypatch):
    monkeypatch.setattr(cs.sys, "platform", "darwin")
    mac = cs.torch_install_command("/p")
    assert "torch" in mac and "--index-url" not in mac  # mac=MPS 기본 휠
    monkeypatch.setattr(cs.sys, "platform", "linux")
    lin = cs.torch_install_command("/p")
    assert "--index-url" in lin and "cu124" in " ".join(lin)  # win/linux=CUDA 휠


def test_comfy_python_url_uses_313(monkeypatch):
    monkeypatch.setattr(cs.sd_setup.sys, "platform", "darwin")
    monkeypatch.setattr(cs.sd_setup.platform, "machine", lambda: "arm64")
    url = cs._comfy_python_url()
    assert url and cs.COMFY_PYTHON_VERSION in url and "aarch64-apple-darwin" in url
    assert "3.13" in cs.COMFY_PYTHON_VERSION  # 사용자 요구: ComfyUI 는 3.13


def test_comfy_python_prefers_bundled(monkeypatch):
    monkeypatch.setattr(cs, "_bundled_comfy_python", lambda: "/data/python313/python/bin/python3.13")
    assert cs.comfy_python() == "/data/python313/python/bin/python3.13"


def test_layout(monkeypatch, tmp_path):
    monkeypatch.setattr(cs.sd_setup, "install_dir", lambda: tmp_path / "sdnext")
    assert cs.install_dir() == tmp_path / "comfyui"
    assert cs.model_dir().as_posix().endswith("comfyui/models/checkpoints")
    assert cs.webui_url() == f"http://127.0.0.1:{cs.COMFY_PORT}"


async def test_ensure_comfy_python_returns_existing(monkeypatch):
    monkeypatch.setattr(cs, "comfy_python", lambda: "/x/python3.13")
    assert await cs.ensure_comfy_python() == "/x/python3.13"


async def test_ensure_comfy_python_none_on_unsupported(monkeypatch):
    monkeypatch.setattr(cs, "comfy_python", lambda: None)
    monkeypatch.setattr(cs, "_comfy_python_url", lambda: None)
    assert await cs.ensure_comfy_python() is None


async def test_run_setup_full_flow_mocked(monkeypatch, tmp_path):
    """clone(핀)→3.13 venv→torch/deps→기동→health 전 과정(부작용 목). 실제 네트워크/프로세스 없음."""
    directory = tmp_path / "comfyui"
    monkeypatch.setattr(cs.sd_setup, "install_dir", lambda: tmp_path / "sdnext")  # comfy = tmp_path/comfyui

    async def fake_health(url=None):
        return False  # 초기엔 미준비(진행하게)

    monkeypatch.setattr(cs, "health", fake_health)
    monkeypatch.setattr(cs, "ensure_comfy_python", lambda: _async("/py313/bin/python3.13"))

    ran: list = []

    async def fake_run(cmd, timeout):
        ran.append(cmd)
        if "checkout" in cmd or "clone" in cmd:
            directory.mkdir(parents=True, exist_ok=True)
            (directory / "main.py").write_text("#")
            (directory / "requirements.txt").write_text("")
        if "venv" in cmd:
            vp = cs._venv_python(directory)
            vp.parent.mkdir(parents=True, exist_ok=True)
            vp.write_text("#")
        return 0, "ok"

    monkeypatch.setattr(cs.sd_setup, "_run", fake_run)
    monkeypatch.setattr(cs, "start", lambda d=None: _async(True))

    async def health_true(self):
        return True

    monkeypatch.setattr(cs.ComfyClient, "health", health_true)  # 기동 후 health 폴링 → 즉시 통과

    ok = await cs.run_setup()
    assert ok is True
    assert cs.progress()["phase"] == "done"
    # 핀 커밋 fetch + torch(인덱스 URL) + 의존성 설치가 실제 실행 목록에 있다
    flat = [tok for c in ran for tok in c]
    assert cs.COMFY_PIN in flat and "torch" in flat


async def _async(value):
    return value


async def test_download_model_rejects_bad_url():
    """비-URL·비-safetensors 는 받지 않는다(잘못된 입력 방어)."""
    assert await cs.download_model("not-a-url") is False
    assert await cs.download_model("https://example.com/readme.txt") is False
    assert await cs.download_model("ftp://x/model.safetensors") is False
