"""이미지 엔진 **공용** 모듈 — 모델 카탈로그·생성 해상도 판정·다운로드·standalone Python·공용 헬퍼.

SD.Next(A1111)는 폐기됐고 이미지 엔진은 ComfyUI 전용이다([comfy_setup]). 이 모듈은 두 엔진이 공유하던
범용 부분만 남는다(파일명은 하위호환을 위해 sd_setup 유지):
- ``MODELS``/``DEFAULT_MODEL_*``/``model_by_id``/``custom_model_from_url`` — 기본 이미지 모델 카탈로그.
- ``resolution_for_checkpoint`` — 체크포인트 이름으로 생성 해상도(SDXL 1024 / SD1.5 512) 판정(에이전트가 사용).
- ``_download`` — 이어받기·HF 토큰 지원 스트리밍 다운로드(comfy_setup 이 모델 다운로드에 사용).
- standalone CPython 페치(``ensure_bundled_python`` 등) + ``_run``/``_spawn``/``launch_log_path``/``_augment_path``
  /``install_dir`` — comfy_setup 부트스트랩 공용.
"""
from __future__ import annotations

import asyncio
import logging
import os
import pathlib
import platform
import shutil
import sys
import tarfile

import aiohttp

logger = logging.getLogger("provider_agent.sd_setup")

# 로컬 이미지 생성 백엔드 = **SD.Next**(vladmandic/sdnext). A1111(AUTOMATIC1111)은 2025-02(v1.10.1)
# 이후 사실상 정체돼, 활발히 유지보수되고 최신 모델(Flux/SD3.5 등)을 지원하는 SD.Next 로 전환한다.
# SD.Next 는 A1111 의 `/sdapi/v1/*` API 를 그대로 유지(API 항상 켜짐, 기본 포트 7860) → 우리 SDClient
# (txt2img/progress/health) 그대로 사용. MPS/정밀도(맥)도 자체 자동 처리 → A1111 의 --no-half-vae 등
# 플래그·CLIP/setuptools/stablediffusion 우회가 불필요(자체 인스톨러가 처리).
# self-update 가 기본이라 default 브랜치를 클론하면 최신을 유지한다.
MODELS: list[dict[str, str]] = [
    {
        "id": "sd15",
        "name": "Stable Diffusion 1.5",
        "desc": "가볍고 빠른 범용 모델. 일반 PC 권장.",
        "size": "약 4 GB",
        "filename": "v1-5-pruned-emaonly.safetensors",
        "url": "https://huggingface.co/stable-diffusion-v1-5/stable-diffusion-v1-5/resolve/main/v1-5-pruned-emaonly.safetensors",
        "base": "sd15",
    },
    {
        "id": "anime",
        "name": "Anything V5 (애니)",
        "desc": "일본 애니·일러스트 특화. 가볍고 빠름(SD1.5 기반).",
        "size": "약 2 GB",
        "filename": "AnythingV5V3_v5PrtRE.safetensors",
        "url": "https://huggingface.co/ckpt/anything-v5.0/resolve/main/AnythingV5V3_v5PrtRE.safetensors",
        "base": "sd15",
    },
    {
        "id": "anime-xl",
        "name": "Animagine XL 4.0 (애니·고품질)",
        "desc": "일본 애니 미소녀 최고 품질(SDXL). 통합메모리 12GB+ 권장, 생성은 느림.",
        "size": "약 6.5 GB",
        "filename": "animagine-xl-4.0-opt.safetensors",
        "url": "https://huggingface.co/cagliostrolab/animagine-xl-4.0/resolve/main/animagine-xl-4.0-opt.safetensors",
        "base": "sdxl",
    },
    {
        "id": "illustrious-xl",
        "name": "Illustrious XL (애니·고품질)",
        "desc": "Danbooru 기반 애니 일러스트 특화 SDXL. 캐릭터·태그 충실, 커뮤니티 표준 베이스. VRAM 8GB+ 권장.",
        "size": "약 6.9 GB",
        "filename": "Illustrious-XL-v0.1.safetensors",
        "url": "https://huggingface.co/OnomaAIResearch/Illustrious-xl-early-release-v0/resolve/main/Illustrious-XL-v0.1.safetensors",
        "base": "sdxl",
    },
    {
        "id": "sdxl",
        "name": "Stable Diffusion XL 1.0",
        "desc": "고해상도·고품질 범용. 무겁고 VRAM 8GB+ 권장.",
        "size": "약 6.6 GB",
        "filename": "sd_xl_base_1.0.safetensors",
        "url": "https://huggingface.co/stabilityai/stable-diffusion-xl-base-1.0/resolve/main/sd_xl_base_1.0.safetensors",
        "base": "sdxl",
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


def custom_model_from_url(url: str) -> dict[str, str] | None:
    """HuggingFace .safetensors/.ckpt 직접 링크 → 모델 dict(카탈로그 밖, 유저 자율 설치). 형식 안 맞으면 None.

    유저가 허깅페이스에서 받은 **어떤 체크포인트든** 설치할 수 있게 한다(카탈로그 강요 제거). blob URL 도
    resolve 로 자동 변환. base 는 미상(""): 해상도는 512 기본(SDXL 커스텀이면 앱에서 1024 로 바꿔 쓰면 됨).
    """
    u = url.strip()
    if "huggingface.co" not in u or not (u.endswith(".safetensors") or u.endswith(".ckpt")):
        return None
    u = u.replace("/blob/", "/resolve/")  # 페이지 URL 을 붙여도 다운로드 링크로 보정
    filename = u.rsplit("/", 1)[-1].split("?")[0]
    if not filename:
        return None
    return {
        "id": "custom",
        "name": filename.rsplit(".", 1)[0],
        "desc": "직접 추가(HuggingFace)",
        "size": "",
        "filename": filename,
        "url": u,
        "base": "",
    }


def resolution_for_checkpoint(checkpoint: str | None) -> tuple[int, int]:
    """현재 로드된 체크포인트 이름으로 권장 생성 해상도를 정한다.

    SDXL 계열(Animagine 등)은 native 1024, SD1.5 계열은 512 에서 품질이 가장 좋다(512 SDXL·1024 SD1.5
    모두 품질 저하). 카탈로그(MODELS)의 filename 으로 base 를 찾아 매핑하고, 모르는 모델이면 안전하게 512.
    checkpoint 는 SD.Next 가 주는 "name.safetensors [hash]" 형태일 수 있어 부분일치로 본다.
    """
    if checkpoint:
        for m in MODELS:
            fn = m["filename"]
            if not fn:
                continue
            stem = fn.rsplit(".", 1)[0]  # SD.Next 는 확장자 없이 "AnythingV5V3_v5PrtRE" 처럼 보고한다
            if fn in checkpoint or stem in checkpoint:
                return (1024, 1024) if m.get("base") == "sdxl" else (512, 512)
        # 커스텀(카탈로그 밖) 모델: 설치 시 저장한 base 로 판정(SDXL 커스텀도 1024 로).
        from .config_file import load_config

        bases = load_config().get("custom_bases") or {}
        if isinstance(bases, dict):
            for fn, base in bases.items():
                stem = str(fn).rsplit(".", 1)[0]
                if str(fn) in checkpoint or (stem and stem in checkpoint):
                    return (1024, 1024) if base == "sdxl" else (512, 512)
    return (512, 512)


# phase: idle | installing | downloading | starting | done | error | cancelled
def install_dir() -> pathlib.Path:
    """SD.Next 를 설치할 데이터 디렉터리. **경로에 점(.)으로 시작하는 폴더가 없어야 한다.**

    gradio 3.43.2(SD.Next 가 핀)의 정적파일 라우트는 경로 컴포넌트 중 하나라도 ``.`` 로 시작하면
    ``is_dotfile`` 로 보고 **403(File not allowed)** 으로 막는다(실증). 그래서 표준 XDG 인 ``~/.local/share``
    (``.local`` 포함)에 설치하면 SD.Next WebUI 의 JS/CSS 가 전부 403 으로 죽는다. 점 없는 경로를 쓴다.
    - macOS: ``~/Library/Nexa/sdnext`` · Windows: ``%LOCALAPPDATA%/Nexa/sdnext`` · Linux: ``~/nexa/sdnext``.
    """
    home = pathlib.Path.home()
    if sys.platform == "win32":
        base = pathlib.Path(os.getenv("LOCALAPPDATA") or (home / "AppData" / "Local"))
    elif sys.platform == "darwin":
        base = home / "Library" / "Nexa"  # 점·공백 없음(Application Support 는 공백이라 회피)
    else:
        base = home / "nexa"  # XDG 기본(~/.local/share)은 .local 점 때문에 gradio 가 막아 회피
    return base / "sdnext"


def _augment_path() -> None:
    """macOS GUI 앱(Finder/Dock 실행)은 셸 PATH 를 상속받지 않아 ``/opt/homebrew/bin`` 등이 빠진다.
    그 결과 ``shutil.which('python3.11')`` 이 None → '설치 실패 no-python'(실증: brew python 이 있어도).
    homebrew·python.org 표준 bin 을 PATH 앞에 보강해 python·git·brew·webui.sh 서브프로세스가 모두 동작.
    멱등(이미 있으면 건너뜀). darwin 전용(Win/Linux 는 GUI 도 PATH 정상).
    """
    if sys.platform != "darwin":
        return
    extra = [
        "/opt/homebrew/bin",  # Apple Silicon homebrew
        "/usr/local/bin",  # Intel homebrew
        "/Library/Frameworks/Python.framework/Versions/3.11/bin",  # python.org installer
        "/Library/Frameworks/Python.framework/Versions/3.12/bin",
    ]
    parts = os.environ.get("PATH", "").split(os.pathsep)
    add = [p for p in extra if p not in parts and os.path.isdir(p)]
    if add:
        os.environ["PATH"] = os.pathsep.join(add + parts)


BUNDLED_PYTHON_RELEASE = "20260602"
BUNDLED_PYTHON_VERSION = "3.11.15"
_PBS_BASE = "https://github.com/astral-sh/python-build-standalone/releases/download"


def _standalone_python_triple() -> str | None:
    """현재 OS/아키텍처에 맞는 python-build-standalone 타깃 트리플(미지원이면 None)."""
    mach = platform.machine().lower()
    arm = mach in ("arm64", "aarch64")
    if sys.platform == "darwin":
        return "aarch64-apple-darwin" if arm else "x86_64-apple-darwin"
    if sys.platform == "win32":
        return "x86_64-pc-windows-msvc"  # arm64 Windows 는 x64 에뮬레이션으로 동작
    if sys.platform.startswith("linux"):
        return "aarch64-unknown-linux-gnu" if arm else "x86_64-unknown-linux-gnu"
    return None


def _standalone_python_url() -> str | None:
    triple = _standalone_python_triple()
    if triple is None:
        return None
    name = f"cpython-{BUNDLED_PYTHON_VERSION}+{BUNDLED_PYTHON_RELEASE}-{triple}-install_only.tar.gz"
    return f"{_PBS_BASE}/{BUNDLED_PYTHON_RELEASE}/{name}"


def python_runtime_dir() -> pathlib.Path:
    """런타임에 받은 standalone CPython 설치 위치(SD 설치 디렉터리 옆: ~/Library/Nexa/python 등)."""
    return install_dir().parent / "python"


def bundled_python_path() -> pathlib.Path:
    """받은 standalone CPython 실행기 경로. install_only 는 ``<dest>/python/`` 으로 풀린다."""
    root = python_runtime_dir() / "python"
    minor = BUNDLED_PYTHON_VERSION.rsplit(".", 1)[0]  # "3.11"
    return (root / "python.exe") if sys.platform == "win32" else (root / "bin" / f"python{minor}")


def _bundled_python_ready() -> str | None:
    """이미 받아둔 standalone CPython 의 경로(실행 가능하면). 없으면 None."""
    p = bundled_python_path()
    return str(p) if p.is_file() and os.access(p, os.X_OK) else None


async def ensure_bundled_python() -> str | None:
    """호환 Python 이 없을 때 standalone CPython 3.11 을 받아 그 경로를 반환. 이미 있으면 즉시 반환.

    실패(미지원 OS·네트워크·추출 오류)면 None → 호출부가 패키지 매니저 폴백으로 넘어간다.
    """
    ready = _bundled_python_ready()
    if ready:
        return ready
    url = _standalone_python_url()
    if url is None:
        return None  # 미지원 OS/arch
    dest_dir = python_runtime_dir()
    archive = dest_dir / "cpython.tar.gz"
    try:
        dest_dir.mkdir(parents=True, exist_ok=True)
        timeout = aiohttp.ClientTimeout(total=None, sock_read=120)
        async with aiohttp.ClientSession(timeout=timeout) as s, s.get(url) as resp:
            resp.raise_for_status()
            with open(archive, "wb") as f:
                async for chunk in resp.content.iter_chunked(1 << 20):
                    f.write(chunk)
        with tarfile.open(archive, "r:gz") as tf:  # GitHub 릴리스(신뢰) → <dest>/python/ 추출
            try:
                tf.extractall(dest_dir, filter="data")  # py3.12+ 안전 추출 필터
            except TypeError:
                tf.extractall(dest_dir)  # 구버전 폴백
    except (aiohttp.ClientError, OSError, tarfile.TarError, asyncio.TimeoutError):
        shutil.rmtree(dest_dir, ignore_errors=True)  # 부분 추출 잔해 정리(다음 시도 깨끗하게)
        return None
    finally:
        archive.unlink(missing_ok=True)
    return _bundled_python_ready()


def compatible_python() -> str | None:
    """SD.Next 호환(3.10~3.12) Python 실행 명령을 찾는다(없으면 None → 설치/다운로드 필요).

    시스템 기본 python3 가 너무 최신(예: 3.13/3.14 — torch 미지원)일 수 있으므로, 지원 버전을
    명시적으로 찾아 PYTHON 으로 강제한다(실증: 강제 안 하면 SD.Next 가 3.14 로 venv 를 만들어 깨짐).

    우선순위: ① 이미 받아둔 standalone CPython(있으면 최우선·머신 무관) → ② PATH 명령 →
    ③ 잘 알려진 절대 경로(특히 macOS GUI 앱).
    """
    bundled = _bundled_python_ready()  # 한 번 받아두면 이후 항상 이걸 쓴다(시스템 Python 무관)
    if bundled:
        return bundled
    _augment_path()  # macOS GUI PATH 보강(no-python 원인 제거)
    for c in ("python3.11", "python3.12", "python3.10"):
        found = shutil.which(c)
        if found:
            return c
    # PATH 보강에도 없으면(드묾) 절대 경로·pyenv 직접 탐색.
    if sys.platform == "darwin":
        home = pathlib.Path.home()
        for ver in ("3.11", "3.12", "3.10"):
            cands = [
                f"/opt/homebrew/bin/python{ver}",
                f"/usr/local/bin/python{ver}",
                f"/Library/Frameworks/Python.framework/Versions/{ver}/bin/python{ver}",
            ]
            cands += [str(p) for p in sorted(home.glob(f".pyenv/versions/{ver}.*/bin/python{ver}"), reverse=True)]
            for c in cands:
                if os.path.isfile(c) and os.access(c, os.X_OK):
                    return c
    return None


async def _run(cmd: list[str], timeout: float) -> tuple[int, str]:
    """명령을 실행하고 (exit code, 합쳐진 출력)을 반환. 타임아웃 시 kill 후 예외."""
    proc = await asyncio.create_subprocess_exec(
        *cmd, stdout=asyncio.subprocess.PIPE, stderr=asyncio.subprocess.STDOUT
    )
    try:
        out, _ = await asyncio.wait_for(proc.communicate(), timeout=timeout)
    except asyncio.TimeoutError:
        proc.kill()
        raise
    return proc.returncode or 0, (out or b"").decode("utf-8", "replace")


def launch_log_path(directory: pathlib.Path | None = None) -> pathlib.Path:
    """webui 첫 실행 로그 경로(부트스트랩 실패 진단용)."""
    return (directory or install_dir()) / "webui-launch.log"


async def _spawn(
    cmd: list[str],
    env: dict[str, str] | None = None,
    log_path: pathlib.Path | None = None,
    cwd: pathlib.Path | None = None,
) -> asyncio.subprocess.Process:
    """포그라운드 webui 를 백그라운드로 띄운다(await 하지 않음).

    출력은 ``log_path`` 파일로 캡처한다(없으면 버림). 첫 실행은 의존성 설치·저장소 클론에서
    실패할 수 있는데(예: 업스트림 repo 소멸·setuptools 비호환), 그 원인을 파일에 남겨야 진단 가능하다.

    ``cwd`` = SD.Next 설치 디렉터리. **반드시 install_dir 로 띄워야** gradio 가 자기 정적자산
    (``/file=javascript/...``)을 서빙한다 — 다른 CWD 면 gradio 가 그 경로를 allowed 밖으로 보고
    403 으로 막아 WebUI JS 가 안 떠 화면이 죽는다(실증).
    """
    cwd_str = str(cwd) if cwd is not None else None
    if log_path is not None:
        log_path.parent.mkdir(parents=True, exist_ok=True)
        out = open(log_path, "wb")  # noqa: SIM115 - 자식 수명 동안 열어 둔다(프로세스 종료 시 OS 가 정리)
        return await asyncio.create_subprocess_exec(*cmd, stdout=out, stderr=out, env=env, cwd=cwd_str)
    return await asyncio.create_subprocess_exec(
        *cmd, stdout=asyncio.subprocess.DEVNULL, stderr=asyncio.subprocess.DEVNULL, env=env, cwd=cwd_str
    )


async def _download(url: str, dest: pathlib.Path, message_prefix: str = "이미지 모델 내려받는 중…") -> None:
    """대용량 파일을 스트리밍으로 받아 dest 에 저장 — 이어받기(HTTP Range) 지원.

    - ``.part`` 가 이미 있으면 그 크기 N 으로 ``Range: bytes=N-`` 요청.
        · 206(Partial) → append('ab')로 이어받기. 200(Range 미지원) → 처음부터(절단). 416 → 이미 받음, rename.
    - gated/비공개 HuggingFace 모델은 저장된 HF 토큰을 Authorization 으로 주입.
    - 예외 시 ``.part`` 를 **보존**(삭제 금지) → 다음 시도가 이어받음. 완료 시에만 ``.part`` → dest 로 rename.
    (진행률 표시는 호출부(comfy_setup 등)가 자체 phase 로 관리 — 이 함수는 순수 다운로드.)
    """
    dest.parent.mkdir(parents=True, exist_ok=True)
    part = dest.with_suffix(dest.suffix + ".part")
    resume_from = part.stat().st_size if part.exists() else 0

    timeout = aiohttp.ClientTimeout(total=None, sock_read=120)
    headers = {"Range": f"bytes={resume_from}-"} if resume_from else {}
    # gated/비공개 HuggingFace 모델은 익명 GET 이 401/403 → 저장된 HF 토큰이 있으면 Authorization 주입.
    if "huggingface.co" in url:
        from .config_file import load_config

        token = str(load_config().get("hf_token") or "").strip()
        if token:
            headers["Authorization"] = f"Bearer {token}"
    # Civitai 다운로드는 2024 말부터 거의 항상 API 키 필요(무토큰 → presigned 403). 저장된 키를 Bearer 로.
    elif "civitai.com" in url:
        from .config_file import load_config

        token = str(load_config().get("civitai_token") or "").strip()
        if token:
            headers["Authorization"] = f"Bearer {token}"
    async with aiohttp.ClientSession(timeout=timeout) as s, s.get(url, headers=headers) as r:
        # 416: 이미 다 받음(서버가 범위 초과로 판단) → 그대로 rename 시도하고 종료.
        if r.status == 416:
            if part.exists():
                part.replace(dest)
            return
        if r.status not in (200, 206):
            raise RuntimeError(f"모델 다운로드 실패(HTTP {r.status})")
        mode = "ab" if r.status == 206 else "wb"  # 206=이어받기 append, 200=처음부터 절단
        with open(part, mode) as f:
            async for chunk in r.content.iter_chunked(1 << 20):
                f.write(chunk)
    part.replace(dest)


