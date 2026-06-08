"""ComfyUI 설치/실행/정지 라이프사이클 — **1급 이미지 엔진**(SD.Next 는 레거시 폴백).

SD.Next(A1111 계열)는 사실상 유지보수가 멈춘 레거시라, 우리 서비스는 ComfyUI 를 적극 지원한다.
ComfyUI 는 활발히 유지보수되고(주간 릴리스) Python 3.13·최신 모델을 따라간다. 이 모듈은 ComfyUI 를
**앱이 직접 설치·실행·정지·웹UI 오픈**하게 만든다(사용자가 URI 만 붙여넣던 방식 탈피).

설계:
- 검증된 ComfyUI 커밋(v0.24.0)으로 핀 → 업스트림 부패 격리(sd_setup 과 동일 원칙).
- venv 는 **Python 3.13**(사용자 요구). 시스템에 3.13 이 없으면 standalone CPython 3.13 을 받아 쓴다
  (frozen 앱 Python 미번들 대응 — sd_setup 의 standalone 페치와 동일 전략).
- 실행은 ComfyUI ``main.py`` 를 localhost:8188 로 띄운다. 웹UI 는 같은 포트에서 ComfyUI 가 서빙하므로
  브라우저로 그 주소를 열면 된다(별도 게이트웨이 불필요).
- 추론(txt2img)·health 는 [comfy.ComfyClient] 가 담당(이 모듈은 '엔진을 살아있게' 만드는 역할).
"""
from __future__ import annotations

import asyncio
import logging
import os
import pathlib
import shutil
import sys
import tarfile

import aiohttp

from . import sd_setup
from .comfy import ComfyClient

logger = logging.getLogger("provider_agent.comfy_setup")

COMFY_REPO = "https://github.com/comfyanonymous/ComfyUI.git"
# 검증된 ComfyUI 릴리스로 핀(v0.24.0, 2026-06-03). 업스트림 master 의 self-update 부패를 피한다.
# 갱신 시 새 태그 커밋을 검증 후 교체할 것(sd_setup.SDNEXT_PIN 과 동일 운영).
COMFY_PIN = "f49bdb655707b97952dcef40e12e5af1f08d2007"  # v0.24.0
COMFY_PORT = 8188
COMFY_PYTHON_VERSION = "3.13.13"  # 사용자 요구: ComfyUI 는 Python 3.13 으로. standalone 페치도 이 버전.


def install_dir() -> pathlib.Path:
    """ComfyUI 설치 위치(SD 설치 디렉터리 옆: ~/Library/Nexa/comfyui 등)."""
    return sd_setup.install_dir().parent / "comfyui"


def model_dir(directory: pathlib.Path | None = None) -> pathlib.Path:
    return (directory or install_dir()) / "models" / "checkpoints"


def is_installed(directory: pathlib.Path | None = None) -> bool:
    """ComfyUI 가 설치돼 있는지(main.py 존재)."""
    return ((directory or install_dir()) / "main.py").is_file()


def webui_url() -> str:
    """ComfyUI 웹 UI 주소(같은 포트에서 ComfyUI 가 서빙)."""
    return f"http://127.0.0.1:{COMFY_PORT}"


def clone_commands(directory: pathlib.Path | None = None) -> list[list[str]]:
    """ComfyUI 를 핀 커밋으로 받는 명령 시퀀스(init+fetch --depth1 <sha>+checkout). sd_setup 과 동일 전략."""
    d = str(directory or install_dir())
    if not COMFY_PIN:
        return [["git", "clone", "--depth", "1", COMFY_REPO, d]]
    return [
        ["git", "init", d],
        ["git", "-C", d, "remote", "add", "origin", COMFY_REPO],
        ["git", "-C", d, "fetch", "--depth", "1", "origin", COMFY_PIN],
        ["git", "-C", d, "checkout", "FETCH_HEAD"],
    ]


# ── Python 3.13 (ComfyUI 전용) ───────────────────────────────────────────────
def comfy_python_dir() -> pathlib.Path:
    """ComfyUI 용 standalone CPython(3.13) 설치 위치(SD 의 3.11 과 분리)."""
    return sd_setup.install_dir().parent / "python313"


def _bundled_comfy_python() -> str | None:
    """받아둔 standalone CPython 3.13 경로(실행 가능하면). 없으면 None."""
    root = comfy_python_dir() / "python"
    p = (root / "python.exe") if sys.platform == "win32" else (root / "bin" / "python3.13")
    return str(p) if p.is_file() and os.access(p, os.X_OK) else None


def comfy_python() -> str | None:
    """ComfyUI venv 생성용 Python(3.13 우선). 받아둔 standalone → 시스템 3.13/3.12 순."""
    bundled = _bundled_comfy_python()
    if bundled:
        return bundled
    sd_setup._augment_path()
    for c in ("python3.13", "python3.12"):
        found = shutil.which(c)
        if found:
            return found
    return None


def _comfy_python_url() -> str | None:
    triple = sd_setup._standalone_python_triple()
    if triple is None:
        return None
    name = f"cpython-{COMFY_PYTHON_VERSION}+{sd_setup.BUNDLED_PYTHON_RELEASE}-{triple}-install_only.tar.gz"
    return f"{sd_setup._PBS_BASE}/{sd_setup.BUNDLED_PYTHON_RELEASE}/{name}"


async def ensure_comfy_python() -> str | None:
    """ComfyUI 용 Python 3.13 을 확보(시스템에 있으면 그걸, 없으면 standalone 다운로드). 실패 시 None."""
    existing = comfy_python()
    if existing:
        return existing
    url = _comfy_python_url()
    if url is None:
        return None
    dest_dir = comfy_python_dir()
    archive = dest_dir / "cpython313.tar.gz"
    try:
        dest_dir.mkdir(parents=True, exist_ok=True)
        timeout = aiohttp.ClientTimeout(total=None, sock_read=120)
        async with aiohttp.ClientSession(timeout=timeout) as s, s.get(url) as resp:
            resp.raise_for_status()
            with open(archive, "wb") as f:
                async for chunk in resp.content.iter_chunked(1 << 20):
                    f.write(chunk)
        with tarfile.open(archive, "r:gz") as tf:
            try:
                tf.extractall(dest_dir, filter="data")
            except TypeError:
                tf.extractall(dest_dir)
    except (aiohttp.ClientError, OSError, tarfile.TarError, asyncio.TimeoutError):
        shutil.rmtree(dest_dir, ignore_errors=True)
        return None
    finally:
        archive.unlink(missing_ok=True)
    return _bundled_comfy_python()


def _venv_python(directory: pathlib.Path) -> pathlib.Path:
    venv = directory / "venv"
    sub = "Scripts" if sys.platform == "win32" else "bin"
    return venv / sub / ("python.exe" if sys.platform == "win32" else "python")


def torch_install_command(venv_py: str) -> list[str]:
    """ComfyUI 용 torch 설치 명령(플랫폼별). mac=MPS 기본 휠, win/linux=CUDA 휠(GPU 없으면 런타임 CPU 폴백)."""
    if sys.platform == "darwin":
        return [venv_py, "-m", "pip", "install", "torch", "torchvision", "torchaudio"]
    return [
        venv_py, "-m", "pip", "install", "torch", "torchvision", "torchaudio",
        "--index-url", "https://download.pytorch.org/whl/cu124",
    ]


def start_command(venv_py: str, directory: pathlib.Path | None = None) -> list[str]:
    """ComfyUI 기동 명령(localhost 전용 — netguard 원칙)."""
    d = directory or install_dir()
    return [venv_py, str(d / "main.py"), "--port", str(COMFY_PORT), "--listen", "127.0.0.1"]


# ── 진행 상태(SD 와 분리) ─────────────────────────────────────────────────────
_state: dict[str, object] = {"phase": "idle", "percent": 0, "message": "", "error": None}
_proc: asyncio.subprocess.Process | None = None
_busy = False


def progress() -> dict:
    return dict(_state)


def is_busy() -> bool:
    """설치/기동이 진행 중인지(중복 실행 방지 가드)."""
    return _busy


def _set(phase: str | None = None, percent: int | None = None, message: str | None = None, error: str | None = None) -> None:
    if phase is not None:
        _state["phase"] = phase
    if percent is not None:
        _state["percent"] = percent
    if message is not None:
        _state["message"] = message
    _state["error"] = error


def is_running() -> bool:
    return _proc is not None and _proc.returncode is None


async def health(url: str | None = None) -> bool:
    return await ComfyClient(url or webui_url()).health()


async def start(directory: pathlib.Path | None = None) -> bool:
    """설치된 ComfyUI 를 백그라운드로 띄운다. 이미 떠 있으면 그대로 True."""
    global _proc
    d = directory or install_dir()
    if not is_installed(d):
        return False
    if await health():
        return True
    venv_py = _venv_python(d)
    if not venv_py.exists():
        return False
    log_path = sd_setup.launch_log_path(d)
    _proc = await sd_setup._spawn(start_command(str(venv_py), d), env={**os.environ}, log_path=log_path, cwd=d)
    return True


async def stop() -> None:
    """띄운 ComfyUI 프로세스를 종료한다(있으면)."""
    global _proc
    if _proc is not None and _proc.returncode is None:
        try:
            _proc.terminate()
            try:
                await asyncio.wait_for(_proc.wait(), timeout=10)
            except asyncio.TimeoutError:
                _proc.kill()
        except ProcessLookupError:
            pass
    _proc = None


async def run_setup(default_model_url: str | None = None) -> bool:
    """ComfyUI 설치 전 과정: clone(핀) → Python3.13 venv → requirements+torch → (선택)모델 → 기동→health.

    실패는 _set(error) 로 표면화하고 False. 이미 떠 있으면 즉시 done.
    """
    global _proc, _busy
    directory = install_dir()
    _busy = True
    try:
        if await health():
            _set("done", 100, "이미 준비됨")
            return True

        _set("installing", 5, "ComfyUI 용 Python(3.13) 준비 중… (한 번만 내려받아요)")
        python_cmd = await ensure_comfy_python()
        if python_cmd is None:
            _set("error", 5, "ComfyUI 용 Python(3.13)을 준비하지 못했어요(다운로드 실패·미지원).", error="no-python")
            return False

        if not is_installed(directory):
            directory.parent.mkdir(parents=True, exist_ok=True)
            _set("installing", 15, "ComfyUI 내려받는 중… (git)")
            last = ""
            for step in clone_commands(directory):
                code, last = await sd_setup._run(step, timeout=1800)
                if code != 0:
                    break
            if not is_installed(directory):
                _set("error", 15, "ComfyUI 설치 실패", error=last[-400:] or "clone-failed")
                return False

        venv = directory / "venv"
        if not venv.exists():
            _set("installing", 30, "ComfyUI 가상환경(Python 3.13) 만드는 중…")
            code, log = await sd_setup._run([python_cmd, "-m", "venv", str(venv)], timeout=300)
            if code != 0:
                _set("error", 30, "가상환경 생성 실패", error=log[-400:] or "venv-failed")
                return False
        venv_py = str(_venv_python(directory))

        _set("installing", 45, "PyTorch 설치 중… (수 분 소요)")
        code, log = await sd_setup._run(torch_install_command(venv_py), timeout=2400)
        if code != 0:
            _set("error", 45, "PyTorch 설치 실패", error=log[-400:] or "torch-failed")
            return False

        _set("installing", 60, "ComfyUI 의존성 설치 중…")
        req = directory / "requirements.txt"
        if req.is_file():
            code, log = await sd_setup._run([venv_py, "-m", "pip", "install", "-r", str(req)], timeout=1800)
            if code != 0:
                _set("error", 60, "의존성 설치 실패", error=log[-400:] or "deps-failed")
                return False

        if default_model_url and not any(model_dir(directory).glob("*.safetensors")):
            _set("downloading", 70, "이미지 모델 내려받는 중…")
            fn = default_model_url.rsplit("/", 1)[-1].split("?")[0] or "model.safetensors"
            await sd_setup._download(default_model_url, model_dir(directory) / fn, "이미지 모델 내려받는 중…")

        _set("starting", 90, "ComfyUI 시작 중… (첫 실행은 수~수십 분)")
        if not await start(directory):
            _set("error", 90, "ComfyUI 시작 실패", error="start-failed")
            return False
        client = ComfyClient(webui_url())
        healthy = False
        for _ in range(600):  # 최대 ~20분(첫 부팅: 노드/모델 로딩)
            if _proc is not None and _proc.returncode is not None:
                break  # 프로세스가 죽었으면 fast-fail(무한 대기 금지)
            if await client.health():
                healthy = True
                break
            await asyncio.sleep(2.0)
        if not healthy:
            _set("error", 90, "ComfyUI 가 응답하지 않아요", error="unhealthy")
            return False
        _set("done", 100, "ComfyUI 준비 완료")
        return True
    except (OSError, aiohttp.ClientError) as exc:
        _set("error", None, "ComfyUI 설치 중 오류", error=str(exc)[-400:])
        return False
    finally:
        _busy = False
