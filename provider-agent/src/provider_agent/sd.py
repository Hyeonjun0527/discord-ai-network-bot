"""로컬 Stable Diffusion 이미지 생성 (SD Phase 1).

프로바이더 PC 의 로컬 SD 서버(기본: AUTOMATIC1111 WebUI API)에 붙어 txt2img 로 이미지를 생성한다.
Ollama 와 동일하게 **localhost 전용**(netguard) — 원격은 명시 옵션에서만. 외부 이미지 API 미사용.

A1111 API: ``POST {base}/sdapi/v1/txt2img`` → ``{"images": ["<base64 png>", ...]}``.
"""
from __future__ import annotations

import logging

import aiohttp

logger = logging.getLogger("provider_agent.sd")

# 안전 상한(무거운/폭주 요청 방지) — 서버 옵션이 더 커도 이 값으로 클램프.
MAX_IMAGE_DIM = 1024
MAX_STEPS = 50
# 허용 옵션 화이트리스트(서버가 임의 키를 SD 에 주입하지 못하게).
ALLOWED_SD_OPTION_KEYS = frozenset(
    {"negative_prompt", "width", "height", "steps", "cfg_scale", "sampler_name", "seed"}
)


class SDError(Exception):
    """SD 호출/응답 오류."""


def filter_sd_options(options: dict | None) -> dict:
    """허용 키만 남기고 크기/steps 를 안전 상한으로 클램프."""
    if not options:
        return {}
    out = {k: v for k, v in options.items() if k in ALLOWED_SD_OPTION_KEYS}
    for dim in ("width", "height"):
        if dim in out:
            try:
                out[dim] = max(64, min(MAX_IMAGE_DIM, int(out[dim])))
            except (TypeError, ValueError):
                out.pop(dim, None)
    if "steps" in out:
        try:
            out["steps"] = max(1, min(MAX_STEPS, int(out["steps"])))
        except (TypeError, ValueError):
            out.pop("steps", None)
    return out


class SDClient:
    def __init__(self, base_url: str, timeout: float = 180.0) -> None:
        self._base = base_url.rstrip("/")
        self._timeout = aiohttp.ClientTimeout(total=timeout)

    async def txt2img(self, prompt: str, options: dict | None = None) -> str:
        """프롬프트로 이미지를 생성해 **base64 PNG**(첫 장)을 반환한다. 오류 시 SDError.

        options 는 화이트리스트로 걸러지고 크기/steps 가 안전 상한으로 클램프된다.
        """
        payload: dict[str, object] = {"prompt": prompt}
        payload.update(filter_sd_options(options))
        payload.setdefault("steps", 20)
        url = f"{self._base}/sdapi/v1/txt2img"
        try:
            async with aiohttp.ClientSession(timeout=self._timeout) as s:
                async with s.post(url, json=payload) as r:
                    data = await r.json()
        except aiohttp.ClientError as exc:
            raise SDError(f"SD 연결 실패: {exc}") from exc
        if isinstance(data, dict) and data.get("error"):
            raise SDError(str(data["error"]))
        images = data.get("images") if isinstance(data, dict) else None
        if not images or not isinstance(images, list) or not isinstance(images[0], str):
            raise SDError("SD 응답에 images 가 없습니다")
        return images[0]

    async def health(self) -> bool:
        """SD 서버가 응답하는지(capability 광고 판단용)."""
        url = f"{self._base}/sdapi/v1/sd-models"
        try:
            async with aiohttp.ClientSession(timeout=aiohttp.ClientTimeout(total=10)) as s:
                async with s.get(url) as r:
                    return r.status == 200
        except aiohttp.ClientError:
            return False

    async def progress(self) -> float:
        """현재 생성 작업의 진행률(0.0~1.0). A1111 ``/sdapi/v1/progress``. 실패/미상이면 0.0."""
        url = f"{self._base}/sdapi/v1/progress"
        try:
            async with aiohttp.ClientSession(timeout=aiohttp.ClientTimeout(total=5)) as s:
                async with s.get(url) as r:
                    data = await r.json()
        except (aiohttp.ClientError, ValueError):
            return 0.0
        p = data.get("progress") if isinstance(data, dict) else None
        if isinstance(p, (int, float)):
            return max(0.0, min(1.0, float(p)))
        return 0.0
