"""앱 내 Ollama 자동 셋업 (온보딩 UX: 앱 먼저 → 앱에서 Ollama 설치/모델).

대부분의 사용자는 Ollama 가 없으므로, 데스크톱 앱이 직접 **감지 → 설치 → 서비스 기동 → 모델 다운로드**
까지 처리한다. 관리자 권한이 필요 없는 경로(brew/winget/공식 스크립트)를 우선한다.

- 순수 헬퍼(`is_installed`/`install_command`/`serve_command`)는 부수효과 없이 단위 테스트 가능.
- `run_setup`은 진행상태(`progress()`)를 갱신하며 오케스트레이션한다(업데이터와 동일 패턴).
- 실제 설치 명령 실행은 비대화형 환경/CI 에서 막혀선 안 되므로 호출부(GUI 버튼)에서만 트리거한다.
"""
from __future__ import annotations

import asyncio
import logging
import shutil
import sys

from .constants import DEFAULT_TEXT_MODEL
from .ollama import OllamaClient

logger = logging.getLogger("provider_agent.ollama_setup")

# 하위호환 alias: 실제 SSOT 는 constants.DEFAULT_TEXT_MODEL.
DEFAULT_MODEL = DEFAULT_TEXT_MODEL

# 앱 내 '추천 텍스트 모델' 카탈로그(SSOT). 명령이 아니라 데이터라 여기서 SSOT — webui 가 참조.
# id 는 `ollama pull` 에 그대로 쓰는 모델 태그. 기본 모델은 constants.DEFAULT_TEXT_MODEL(리터럴 중복 금지).
RECOMMENDED_TEXT_MODELS: list[dict] = [
    {
        "id": DEFAULT_TEXT_MODEL,
        "name": "EXAONE 3.5 7.8B",
        "size": "약 4.8 GB",
        "desc": "한국어에 강한 기본 추천 모델. 일반 PC 권장.",
        "recommended": True,
    },
    {
        "id": "llama3.1:8b",
        "name": "Llama 3.1 8B",
        "size": "약 4.7 GB",
        "desc": "범용 영어 모델. 품질/속도 균형.",
        "recommended": True,
    },
    {
        "id": "qwen2.5:7b",
        "name": "Qwen 2.5 7B",
        "size": "약 4.7 GB",
        "desc": "다국어·코딩에 강함.",
        "recommended": False,
    },
    {
        "id": "gemma2:9b",
        "name": "Gemma 2 9B",
        "size": "약 5.4 GB",
        "desc": "구글 경량 고품질 모델.",
        "recommended": False,
    },
    {
        "id": "llama3.2:3b",
        "name": "Llama 3.2 3B",
        "size": "약 2.0 GB",
        "desc": "가벼운 저사양 PC 용.",
        "recommended": False,
    },
]


def catalog() -> list[dict]:
    """추천 모델 카탈로그(기본 모델에 ``default=True`` 표시). webui /api/ollama/catalog 가 쓴다."""
    return [{**m, "default": m["id"] == DEFAULT_TEXT_MODEL} for m in RECOMMENDED_TEXT_MODELS]

# phase: idle | installing | starting | pulling | done | error
_progress: dict = {"phase": "idle", "percent": 0, "message": "", "error": None}


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
    return _progress["phase"] in ("installing", "starting", "pulling")


def is_installed() -> bool:
    """`ollama` 실행파일이 PATH 에 있는지."""
    return shutil.which("ollama") is not None


def _has(cmd: str) -> bool:
    return shutil.which(cmd) is not None


def install_command(platform: str | None = None) -> list[str] | None:
    """현재 플랫폼에서 관리자 권한 없이 Ollama 를 설치하는 명령(없으면 None).

    - macOS: Homebrew (`brew install ollama`).
    - Windows: winget (`Ollama.Ollama`).
    - Linux: 공식 설치 스크립트(curl | sh).
    """
    p = platform or sys.platform
    if p == "darwin":
        return ["brew", "install", "ollama"] if _has("brew") else None
    if p == "win32":
        return ["winget", "install", "--id", "Ollama.Ollama", "-e", "--accept-source-agreements"] if _has("winget") else None
    if p.startswith("linux"):
        return ["sh", "-c", "curl -fsSL https://ollama.com/install.sh | sh"]
    return None


def serve_command(platform: str | None = None) -> list[str]:
    """Ollama 데몬을 기동하는 명령. macOS+brew 는 services, 그 외는 `ollama serve`."""
    p = platform or sys.platform
    if p == "darwin" and _has("brew"):
        return ["brew", "services", "start", "ollama"]
    return ["ollama", "serve"]


async def _run(cmd: list[str], timeout: float) -> tuple[int, str]:
    """명령을 실행하고 (exit code, 합쳐진 출력)을 반환. 타임아웃 시 예외."""
    proc = await asyncio.create_subprocess_exec(
        *cmd, stdout=asyncio.subprocess.PIPE, stderr=asyncio.subprocess.STDOUT
    )
    try:
        out, _ = await asyncio.wait_for(proc.communicate(), timeout=timeout)
    except asyncio.TimeoutError:
        proc.kill()
        raise
    return proc.returncode or 0, (out or b"").decode("utf-8", "replace")


async def _wait_healthy(client: OllamaClient, attempts: int = 30, delay: float = 1.0) -> bool:
    """Ollama 데몬이 응답할 때까지 폴링."""
    for _ in range(attempts):
        if await client.health():
            return True
        await asyncio.sleep(delay)
    return False


async def run_setup(base_url: str, model: str = DEFAULT_MODEL) -> bool:
    """감지 → (필요 시)설치 → 기동 → 모델 pull. 성공 True. 진행은 progress()로 노출.

    이미 모델이 있으면 즉시 done. 설치 수단이 없으면 error.
    """
    client = OllamaClient(base_url)
    try:
        # 0) 이미 준비됐는지
        if await client.health():
            models = await client.list_models()
            if any(m == model or m.startswith(model.split(":")[0]) for m in models):
                _set("done", 100, "이미 준비됨")
                return True

        # 1) 설치
        if not is_installed():
            cmd = install_command()
            if cmd is None:
                _set("error", 0, "자동 설치 수단을 찾지 못했어요", error="no-installer")
                return False
            _set("installing", 10, "Ollama 설치 중… (1~2분)")
            code, log = await _run(cmd, timeout=900)
            if code != 0 or not is_installed():
                _set("error", 10, "Ollama 설치 실패", error=log[-400:] or "install-failed")
                return False

        # 2) 데몬 기동
        _set("starting", 55, "Ollama 시작 중…")
        try:
            await _run(serve_command(), timeout=20)
        except asyncio.TimeoutError:
            pass  # `ollama serve` 는 포그라운드라 타임아웃이 정상(백그라운드로 떠 있음)
        if not await _wait_healthy(client):
            _set("error", 55, "Ollama 가 시작되지 않았어요", error="not-serving")
            return False

        # 3) 모델 다운로드
        _set("pulling", 70, f"모델 내려받는 중… ({model})")
        await client.pull(model)
        _set("done", 100, "준비 완료")
        return True
    except Exception as exc:  # noqa: BLE001 — 어떤 실패든 GUI 에 표면화
        logger.warning("ollama setup 실패: %s", exc)
        _set("error", _progress["percent"], "설치 중 오류", error=str(exc)[:400])
        return False
