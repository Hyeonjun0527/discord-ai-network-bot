"""앱 내 Stable Diffusion(AUTOMATIC1111 WebUI) 설치 — 버튼 클릭 시 감지→설치→모델→기동(--api)→준비.

이미지 생성을 제공하려면 로컬에 SD 서버가 필요한데 대부분의 사용자는 없다. 그래서 데스크톱 앱이
**버튼 한 번**으로 A1111 WebUI 를 직접 받아 띄운다(기존 `sd.SDClient` 가 쓰는 ``/sdapi/v1`` 백엔드).

흐름(진행은 ``progress()`` 로 노출, Ollama 자동설치 `ollama_setup` 과 동일 패턴):

1. **감지** — SD 가 이미 떠 있으면(``SDClient.health``) 즉시 done.
2. **설치** — A1111 repo 를 데이터 디렉터리에 ``git clone``(얕은 클론).
3. **모델** — 체크포인트가 없으면 기본 SD1.5 모델을 ``models/Stable-diffusion`` 으로 내려받음
   (없으면 생성이 실패하므로 "유저가 제일 편한" 경험을 위해 같이 준비).
4. **기동** — ``webui`` 를 ``--api`` 로 백그라운드 기동(첫 실행 시 venv·torch 자동 부트스트랩).
5. **준비** — ``/sdapi/v1`` 가 응답할 때까지 폴링(첫 실행은 수~수십 분 걸릴 수 있음).

전제: ``git`` 과 (A1111 이 요구하는) Python 3.10/3.11 이 PATH 에 있어야 한다. SD 는 localhost 전용
(netguard) — 원격은 별도 옵션에서만. 외부 이미지 API 는 쓰지 않는다.

순수 헬퍼(``install_dir``/``is_installed``/``has_git``/``clone_command``/``launch_command``/
``has_model``)는 부수효과 없이 단위 테스트 가능하고, 실제 명령 실행은 호출부(GUI 버튼)에서만 한다.
"""
from __future__ import annotations

import asyncio
import logging
import os
import pathlib
import shutil
import sys

import aiohttp

from .sd import SDClient

logger = logging.getLogger("provider_agent.sd_setup")

# 로컬 이미지 생성 백엔드 = **SD.Next**(vladmandic/sdnext). A1111(AUTOMATIC1111)은 2025-02(v1.10.1)
# 이후 사실상 정체돼, 활발히 유지보수되고 최신 모델(Flux/SD3.5 등)을 지원하는 SD.Next 로 전환한다.
# SD.Next 는 A1111 의 `/sdapi/v1/*` API 를 그대로 유지(API 항상 켜짐, 기본 포트 7860) → 우리 SDClient
# (txt2img/progress/health) 그대로 사용. MPS/정밀도(맥)도 자체 자동 처리 → A1111 의 --no-half-vae 등
# 플래그·CLIP/setuptools/stablediffusion 우회가 불필요(자체 인스톨러가 처리).
# self-update 가 기본이라 default 브랜치를 클론하면 최신을 유지한다.
SDNEXT_REPO = "https://github.com/vladmandic/sdnext.git"

# 설치 마법사에서 고르는 로컬 이미지 모델(체크포인트). 명령어가 아니라 데이터라 여기서 SSOT.
MODELS: list[dict[str, str]] = [
    {
        "id": "sd15",
        "name": "Stable Diffusion 1.5",
        "desc": "가볍고 빠른 범용 모델. 일반 PC 권장.",
        "size": "약 4 GB",
        "filename": "v1-5-pruned-emaonly.safetensors",
        "url": "https://huggingface.co/stable-diffusion-v1-5/stable-diffusion-v1-5/resolve/main/v1-5-pruned-emaonly.safetensors",
    },
    {
        "id": "sdxl",
        "name": "Stable Diffusion XL 1.0",
        "desc": "고해상도·고품질. 무겁고 VRAM 8GB+ 권장.",
        "size": "약 6.6 GB",
        "filename": "sd_xl_base_1.0.safetensors",
        "url": "https://huggingface.co/stabilityai/stable-diffusion-xl-base-1.0/resolve/main/sd_xl_base_1.0.safetensors",
    },
]
DEFAULT_MODEL_ID = "sd15"
# 하위 호환(테스트/기존 참조).
DEFAULT_MODEL_NAME = MODELS[0]["filename"]
DEFAULT_MODEL_URL = MODELS[0]["url"]


def model_by_id(model_id: str | None) -> dict[str, str]:
    """모델 id 로 레지스트리 항목을 찾는다(없으면 기본 모델)."""
    for m in MODELS:
        if m["id"] == model_id:
            return m
    return MODELS[0]


# phase: idle | installing | downloading | starting | done | error | cancelled
_progress: dict = {"phase": "idle", "percent": 0, "message": "", "error": None}
# 기동한 webui 프로세스 참조(GC 로 죽지 않게 보관). 포그라운드라 await 하지 않는다.
_proc: asyncio.subprocess.Process | None = None
# 진행 중인 자식 프로세스(clone/설치) — 취소 시 종료용.
_current_proc: asyncio.subprocess.Process | None = None
# 취소 요청 플래그(단계 경계·다운로드 청크에서 확인).
_cancel: bool = False


def progress() -> dict:
    return dict(_progress)


def _set(phase: str | None = None, percent: int | None = None, message: str | None = None, error: str | None = None) -> None:
    if phase is not None:
        _progress["phase"] = phase
    if percent is not None:
        _progress["percent"] = percent
    if message is not None:
        _progress["message"] = message
    _progress["error"] = error


def is_busy() -> bool:
    return _progress["phase"] in ("installing", "downloading", "starting")


def request_cancel() -> None:
    """설치 취소: 플래그를 세우고 진행 중인 자식 프로세스를 종료한다."""
    global _cancel
    _cancel = True
    for p in (_current_proc, _proc):
        if p is not None and p.returncode is None:
            try:
                p.kill()
            except ProcessLookupError:
                pass
    if is_busy():
        _set("cancelled", _progress["percent"], "설치를 취소했어요")


def install_dir() -> pathlib.Path:
    """SD.Next 를 설치할 데이터 디렉터리. ``XDG_DATA_HOME`` 을 따르고 없으면 ``~/.local/share``.

    경로를 ``sdnext`` 로 둬 기존 A1111(stable-diffusion-webui) 설치와 **분리**한다 — 기존 유저는
    A1111 디렉터리를 그대로 두고 SD.Next 를 새로 설치(혼선·충돌 방지). 옛 A1111 디렉터리는 방치돼도 무방.
    """
    base = os.getenv("XDG_DATA_HOME") or os.path.join(pathlib.Path.home(), ".local", "share")
    return pathlib.Path(base) / "nexa" / "sdnext"


def _has(cmd: str) -> bool:
    return shutil.which(cmd) is not None


def has_git() -> bool:
    return _has("git")


def pkg_manager(platform: str | None = None) -> str | None:
    """전제 도구(git·Python) 자동설치에 쓸 패키지 매니저(없으면 None → 사용자 안내로 폴백)."""
    p = platform or sys.platform
    if p == "darwin":
        return "brew" if _has("brew") else None
    if p == "win32":
        return "winget" if _has("winget") else None
    return None  # Linux 는 배포판마다 달라 전제 자동설치는 생략(안내)


# A1111 은 Python 3.10/3.11 을 요구한다(3.12+ 비호환). 자동 설치 시 3.11 을 받는다.
_PKG: dict[str, dict[str, list[str]]] = {
    "brew": {"git": ["brew", "install", "git"], "python": ["brew", "install", "python@3.11"]},
    "winget": {
        "git": ["winget", "install", "--id", "Git.Git", "-e", "--accept-source-agreements"],
        "python": ["winget", "install", "--id", "Python.Python.3.11", "-e", "--accept-source-agreements"],
    },
}


def install_tool_command(tool: str, platform: str | None = None) -> list[str] | None:
    """git/python 등 전제 도구를 관리자 권한 없이 설치하는 명령(없으면 None)."""
    pm = pkg_manager(platform)
    return _PKG.get(pm, {}).get(tool) if pm else None


def compatible_python() -> str | None:
    """A1111 호환(3.10/3.11) Python 실행 명령을 찾는다(없으면 None → 설치 필요)."""
    for c in ("python3.11", "python3.10"):
        if _has(c):
            return c
    return None


def launch_env(python_cmd: str | None, platform: str | None = None) -> dict[str, str]:
    """webui 에 호환 Python 을 알려주는 환경변수(없으면 빈 dict).

    - mac/Linux: webui.sh 가 읽는 ``python_cmd``.
    - Windows: webui.bat 가 읽는 ``PYTHON``.
    """
    if not python_cmd:
        return {}
    p = platform or sys.platform
    return {"PYTHON": python_cmd} if p == "win32" else {"python_cmd": python_cmd}


def is_installed(directory: pathlib.Path | None = None) -> bool:
    """A1111 런처가 설치돼 있는지(webui.sh/webui.bat 존재)."""
    d = directory or install_dir()
    return (d / "webui.sh").exists() or (d / "webui.bat").exists()


def model_dir(directory: pathlib.Path | None = None) -> pathlib.Path:
    return (directory or install_dir()) / "models" / "Stable-diffusion"


def has_model(directory: pathlib.Path | None = None) -> bool:
    """체크포인트(.safetensors/.ckpt)가 하나라도 있는지."""
    md = model_dir(directory)
    if not md.exists():
        return False
    return any(p.suffix in (".safetensors", ".ckpt") for p in md.iterdir() if p.is_file())


def clone_command(directory: pathlib.Path | None = None) -> list[str]:
    """SD.Next repo 를 얕게 클론하는 명령(default 브랜치 = 최신·self-update 라인)."""
    return ["git", "clone", "--depth", "1", SDNEXT_REPO, str(directory or install_dir())]


def launch_command(platform: str | None = None, directory: pathlib.Path | None = None) -> list[str]:
    """SD.Next webui 를 기동하는 명령(첫 실행 시 venv·torch·deps 자동 설치).

    - Windows: ``webui.bat``(cmd 경유). mac/Linux: ``bash webui.sh``.
    SD.Next 는 **API 가 항상 켜져** 있어 ``--api`` 불필요(기본 포트 7860). MPS/정밀도(맥)·CUDA 미존재
    환경을 자체 자동 처리하므로 A1111 의 ``--skip-torch-cuda-test``/``--no-half-vae``/``--upcast-sampling``
    같은 플래그를 넣지 않는다(미인식·에러 위험). 즉 깨진 이미지 문제도 SD.Next 가 자체적으로 막는다.
    """
    p = platform or sys.platform
    d = directory or install_dir()
    if p == "win32":
        return ["cmd", "/c", str(d / "webui.bat")]
    return ["bash", str(d / "webui.sh")]


# (A1111 우회 함수 stable_diffusion_repo/write_pip_constraints/bootstrap_env 는 제거됨 —
#  SD.Next 는 자체 인스톨러가 의존성(CLIP/setuptools/stablediffusion)을 처리해 불필요.)


async def _run(cmd: list[str], timeout: float) -> tuple[int, str]:
    """명령을 실행하고 (exit code, 합쳐진 출력)을 반환. 타임아웃 시 예외. 취소 시 종료 가능하게 추적."""
    global _current_proc
    proc = await asyncio.create_subprocess_exec(
        *cmd, stdout=asyncio.subprocess.PIPE, stderr=asyncio.subprocess.STDOUT
    )
    _current_proc = proc
    try:
        out, _ = await asyncio.wait_for(proc.communicate(), timeout=timeout)
    except asyncio.TimeoutError:
        proc.kill()
        raise
    finally:
        _current_proc = None
    return proc.returncode or 0, (out or b"").decode("utf-8", "replace")


def launch_log_path(directory: pathlib.Path | None = None) -> pathlib.Path:
    """webui 첫 실행 로그 경로(부트스트랩 실패 진단용)."""
    return (directory or install_dir()) / "webui-launch.log"


async def _spawn(cmd: list[str], env: dict[str, str] | None = None, log_path: pathlib.Path | None = None) -> asyncio.subprocess.Process:
    """포그라운드 webui 를 백그라운드로 띄운다(await 하지 않음).

    출력은 ``log_path`` 파일로 캡처한다(없으면 버림). 첫 실행은 의존성 설치·저장소 클론에서
    실패할 수 있는데(예: 업스트림 repo 소멸·setuptools 비호환), 그 원인을 파일에 남겨야 진단 가능하다.
    """
    if log_path is not None:
        log_path.parent.mkdir(parents=True, exist_ok=True)
        out = open(log_path, "wb")  # noqa: SIM115 - 자식 수명 동안 열어 둔다(프로세스 종료 시 OS 가 정리)
        return await asyncio.create_subprocess_exec(*cmd, stdout=out, stderr=out, env=env)
    return await asyncio.create_subprocess_exec(
        *cmd, stdout=asyncio.subprocess.DEVNULL, stderr=asyncio.subprocess.DEVNULL, env=env
    )


# 다운로드 구간을 차지하는 진행률 범위(앞단계 clone=~15%, downloading=35~95%, starting=70~).
# downloading phase 안에서 실제 바이트 비율을 이 구간으로 매핑한다.
_DL_START_PCT = 35
_DL_END_PCT = 95
# 진행률 갱신 throttle: 1% 또는 ~16MB 마다 한 번만 _set(과도한 호출 방지).
_DL_THROTTLE_BYTES = 16 << 20


def _dl_percent(received: int, total: int | None) -> int:
    """받은 누적 바이트를 다운로드 구간(35~95%) 안의 진행률로 매핑."""
    if not total or total <= 0:
        return _DL_START_PCT
    frac = min(1.0, received / total)
    return _DL_START_PCT + int(frac * (_DL_END_PCT - _DL_START_PCT))


async def _download(url: str, dest: pathlib.Path, message_prefix: str = "이미지 모델 내려받는 중…") -> None:
    """대용량 파일을 스트리밍으로 받아 dest 에 저장 — 이어받기(HTTP Range)·실시간 진행률 지원.

    - ``.part`` 가 이미 있으면 그 크기 N 으로 ``Range: bytes=N-`` 요청.
        · 206(Partial) → append('ab')로 이어받고 진행률 시작점에 N 반영.
        · 200(Range 미지원/전체 재전송) → ``.part`` 를 새로 절단해 처음(0)부터.
        · 416(범위 초과) → 이미 받은 것으로 간주, rename 시도.
    - 받은 누적 바이트를 다운로드 구간(35~95%)으로 매핑해 chunk 마다 진행률 갱신
      (1% 또는 ~16MB throttle, message 에 "받은MB / 전체MB" 표시).
    - 예외·취소 시 ``.part`` 를 **보존**(삭제 금지) → 다음 run_setup 이 이어받음.
      완료 시에만 ``.part`` → dest 로 rename.
    """
    dest.parent.mkdir(parents=True, exist_ok=True)
    part = dest.with_suffix(dest.suffix + ".part")
    resume_from = part.stat().st_size if part.exists() else 0

    timeout = aiohttp.ClientTimeout(total=None, sock_read=120)
    headers = {"Range": f"bytes={resume_from}-"} if resume_from else {}
    async with aiohttp.ClientSession(timeout=timeout) as s:
        async with s.get(url, headers=headers) as r:
            # 416: 이미 다 받음(서버가 범위 초과로 판단) → 그대로 rename 시도하고 종료.
            if r.status == 416:
                if part.exists():
                    part.replace(dest)
                return
            if r.status not in (200, 206):
                raise RuntimeError(f"모델 다운로드 실패(HTTP {r.status})")

            if r.status == 206:
                mode = "ab"          # 이어받기: 기존 .part 에 append
                received = resume_from
            else:
                mode = "wb"          # 200: Range 무시·전체 재전송 → 처음부터(절단)
                received = 0

            # 전체 크기: 206 이면 Content-Length 는 남은 분량이라 시작점을 더한다.
            content_len = r.content_length
            total = (content_len + received) if content_len is not None else None
            total_mb = f"{total / (1 << 20):.0f}MB" if total else "?"

            last_reported = received
            with open(part, mode) as f:
                async for chunk in r.content.iter_chunked(1 << 20):
                    if _cancel:
                        # 취소 시에도 .part 보존(삭제 금지) → 다음 시도가 이어받음.
                        raise asyncio.CancelledError()
                    f.write(chunk)
                    received += len(chunk)
                    if received - last_reported >= _DL_THROTTLE_BYTES:
                        last_reported = received
                        got_mb = f"{received / (1 << 20):.0f}MB"
                        _set("downloading", _dl_percent(received, total), f"{message_prefix} ({got_mb} / {total_mb})")
    part.replace(dest)


async def _wait_healthy(
    client: SDClient, proc: asyncio.subprocess.Process | None = None, attempts: int = 600, delay: float = 2.0
) -> bool:
    """SD API 가 응답할 때까지 폴링(첫 실행 부트스트랩이 길어 넉넉히).

    ``proc`` 가 주어지면 매 폴링마다 그 프로세스가 살아 있는지 확인해, webui 가 조기 종료(부트스트랩
    실패)하면 더 기다리지 않고 즉시 False 를 돌려준다. (과거엔 죽은 webui 를 20분 폴링한 뒤에야
    'not-serving' 으로 떨어져, 사용자에게 70% 에서 수십 분 멈춘 것처럼 보였다.)
    """
    for _ in range(attempts):
        if await client.health():
            return True
        if proc is not None and proc.returncode is not None:
            return False  # webui 가 죽었으면 health 가 영영 안 뜬다 → 즉시 실패
        await asyncio.sleep(delay)
    return False


def _cancelled() -> bool:
    """취소 요청이 들어왔으면 phase 를 cancelled 로 바꾸고 True."""
    if _cancel:
        _set("cancelled", _progress["percent"], "설치를 취소했어요")
        return True
    return False


async def run_setup(sd_url: str, model_id: str | None = None) -> bool:
    """감지 → (전제 git·Python)설치 → A1111 clone → 선택 모델 → 기동 → 준비. 성공 True.

    진행은 progress()로 노출. 이미 SD 가 떠 있으면 즉시 done. 단계 경계마다 취소를 확인한다.
    """
    global _proc, _cancel
    _cancel = False
    model = model_by_id(model_id)
    client = SDClient(sd_url)
    directory = install_dir()
    try:
        # 0) 이미 준비됐는지
        if await client.health():
            _set("done", 100, "이미 준비됨")
            return True

        # 1) 전제 도구(git·Python 3.10/3.11) — 없으면 패키지 매니저로 자동 설치(설치 마법사 역할).
        if not has_git():
            cmd = install_tool_command("git")
            if cmd is None:
                _set("error", 0, "git 이 없고 자동 설치 수단(brew/winget)도 없어요. git 설치 후 다시 시도하세요.", error="no-git")
                return False
            _set("installing", 5, "git 설치 중…")
            await _run(cmd, timeout=600)
            if not has_git():
                _set("error", 5, "git 설치 실패", error="git-install-failed")
                return False
        if _cancelled():
            return False

        python_cmd = compatible_python()
        if python_cmd is None:
            cmd = install_tool_command("python")
            if cmd is None:
                _set("error", 5, "A1111 에 필요한 Python 3.10/3.11 이 없고 자동 설치 수단도 없어요.", error="no-python")
                return False
            _set("installing", 8, "이미지 엔진용 Python(3.11) 설치 중…")
            await _run(cmd, timeout=900)
            # 설치 직후 PATH 갱신이 늦을 수 있어, 못 찾으면 best-effort 명령으로 진행한다.
            python_cmd = compatible_python() or ("python" if sys.platform == "win32" else "python3.11")
        if _cancelled():
            return False

        # 2) 설치(clone)
        if not is_installed(directory):
            directory.parent.mkdir(parents=True, exist_ok=True)
            _set("installing", 15, "Stable Diffusion 내려받는 중… (git clone)")
            code, log = await _run(clone_command(directory), timeout=1800)
            if _cancelled():
                return False
            if code != 0 or not is_installed(directory):
                _set("error", 15, "Stable Diffusion 설치 실패", error=log[-400:] or "clone-failed")
                return False

        # 3) 선택한 모델 준비
        if not has_model(directory):
            # 초기 진행률은 35%(다운로드 구간 시작). 이후 _download 가 받은 바이트 비율로 35~95% 를 갱신한다.
            prefix = f"이미지 모델 내려받는 중… ({model['name']})"
            _set("downloading", _DL_START_PCT, f"{prefix} ({model['size']})")
            await _download(model["url"], model_dir(directory) / model["filename"], prefix)
        if _cancelled():
            return False

        # 4) 기동(백그라운드). 첫 실행은 venv·torch·deps 부트스트랩으로 오래 걸린다.
        #    SD.Next webui 가 호환 Python 을 쓰도록 env(python_cmd)로 전달(SD.Next 자체 인스톨러가 의존성 처리).
        _set("starting", 70, "Stable Diffusion(SD.Next) 시작 중… (첫 실행은 수~수십 분)")
        env = {**os.environ, **launch_env(python_cmd)}
        log_path = launch_log_path(directory)
        _proc = await _spawn(launch_command(directory=directory), env=env, log_path=log_path)
        if not await _wait_healthy(client, _proc):
            if _cancelled():
                return False
            # webui 가 죽었으면(첫 실행 의존성 설치/저장소 클론 실패 등) 원인을 로그 꼬리와 함께 표면화.
            if _proc.returncode is not None:
                tail = ""
                try:
                    tail = log_path.read_text("utf-8", "replace")[-400:]
                except OSError:
                    pass
                _set(
                    "error", 70,
                    "Stable Diffusion 첫 실행 준비가 실패했어요(의존성 설치/저장소 클론 오류). "
                    f"자세한 내용: {log_path}",
                    error=(tail or f"webui-exited:{_proc.returncode}"),
                )
            else:
                _set("error", 70, "Stable Diffusion 이 시작되지 않았어요", error="not-serving")
            return False

        _set("done", 100, "준비 완료")
        return True
    except asyncio.CancelledError:
        _set("cancelled", _progress["percent"], "설치를 취소했어요")
        return False
    except Exception as exc:  # noqa: BLE001 — 어떤 실패든 GUI 에 표면화
        logger.warning("sd setup 실패: %s", exc)
        _set("error", _progress["percent"], "설치 중 오류", error=str(exc)[:400])
        return False


async def launch_only(sd_url: str) -> bool:
    """**이미 설치된** A1111 을 기동만 한다(clone/모델 다운로드 없이). 성공/이미떠있음 True.

    재부팅·앱 종료 후 SD 가 꺼져 있을 때 다시 띄우는 경로(GUI 'SD 시작' 버튼·에이전트 자동기동).
    설치/모델이 없으면 받아오지 않고 곧바로 False(예상치 못한 대용량 다운로드 방지) — 그 경우는
    전체 설치 마법사(``run_setup``)를 써야 한다.
    """
    global _proc, _cancel
    _cancel = False
    client = SDClient(sd_url)
    directory = install_dir()
    try:
        if await client.health():
            _set("done", 100, "이미 준비됨")
            return True
        if not is_installed(directory) or not has_model(directory):
            _set("error", 0, "아직 설치되지 않았어요. 먼저 설치하세요.", error="not-installed")
            return False
        python_cmd = compatible_python() or ("python" if sys.platform == "win32" else "python3.11")
        _set("starting", 70, "Stable Diffusion(SD.Next) 시작 중… (첫 실행 이후라 보통 1~2분)")
        env = {**os.environ, **launch_env(python_cmd)}
        log_path = launch_log_path(directory)
        _proc = await _spawn(launch_command(directory=directory), env=env, log_path=log_path)
        if await _wait_healthy(client, _proc):
            _set("done", 100, "준비 완료")
            return True
        _set("error", 70, "Stable Diffusion 이 시작되지 않았어요", error="not-serving")
        return False
    except asyncio.CancelledError:
        _set("cancelled", _progress["percent"], "시작을 취소했어요")
        return False
    except Exception as exc:  # noqa: BLE001 — 어떤 실패든 GUI 에 표면화
        logger.warning("sd 시작 실패: %s", exc)
        _set("error", _progress["percent"], "시작 중 오류", error=str(exc)[:400])
        return False
