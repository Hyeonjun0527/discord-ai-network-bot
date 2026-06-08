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
        크기 기본은 **512×512** — SD.Next 기본(1024)은 SD 1.5 에서 느리고(맥 MPS ~2분/장) 품질도 나쁘다.
        SD 1.5 native 인 512 로 두면 ~4배 빠르고 품질도 좋다(옵션으로 width/height 주면 재정의).
        """
        payload: dict[str, object] = {"prompt": prompt}
        payload.update(filter_sd_options(options))
        payload.setdefault("steps", 20)
        payload.setdefault("width", 512)
        payload.setdefault("height", 512)
        # 샘플러 기본은 DPM++ 2M — Apple Silicon(MPS) 권장(웹 리서치): SDE/Heun 대비 빠르고 품질 좋음.
        # 실측(맥 MPS, 512, 20steps): DPM++ 2M ~14s vs 기본 ~30s vs 1024 ~121s. 옵션으로 재정의 가능.
        payload.setdefault("sampler_name", "DPM++ 2M")
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

    async def current_checkpoint(self) -> str | None:
        """현재 로드된 체크포인트 이름(예: 'animagine-xl-4.0-opt.safetensors [hash]'). 실패 시 None.
        생성 해상도(SDXL 1024 vs SD1.5 512)를 정하는 데 쓴다. **생성 중에는 호출 금지**(MPS 동시접근 크래시)."""
        url = f"{self._base}/sdapi/v1/options"
        try:
            async with aiohttp.ClientSession(timeout=aiohttp.ClientTimeout(total=10)) as s:
                async with s.get(url) as r:
                    data = await r.json()
        except (aiohttp.ClientError, ValueError):
            return None
        ckpt = data.get("sd_model_checkpoint") if isinstance(data, dict) else None
        return ckpt if isinstance(ckpt, str) else None

    async def set_checkpoint(self, name: str) -> bool:
        """활성 체크포인트를 **핫스왑**한다(POST /sdapi/v1/options sd_model_checkpoint). 재기동 없이 즉시 전환.
        모델 로드는 수십 초 걸릴 수 있어 timeout 을 넉넉히. **생성 중에는 호출 금지**(MPS 동시접근 크래시)."""
        url = f"{self._base}/sdapi/v1/options"
        try:
            async with aiohttp.ClientSession(timeout=aiohttp.ClientTimeout(total=180)) as s:
                async with s.post(url, json={"sd_model_checkpoint": name}) as r:
                    return r.status == 200
        except aiohttp.ClientError:
            return False

    async def set_output_png(self) -> bool:
        """API 반환 이미지 포맷을 PNG 로 설정 + **라이브 프리뷰 비활성화**. SD 준비 직후 1회 호출.

        - samples_format=png: SD.Next 기본 JPEG → 우리 파이프라인의 PNG 가정과 일치.
        - live_previews_enable=false: /sdapi/v1/progress 폴링이 매번 현재 latent 를 GPU 로 디코딩(프리뷰)
          하는데, 이게 생성 중인 diffusion 과 Metal command encoder 를 동시에 점유해 Apple Silicon(MPS)
          에서 드라이버 세그폴트(AGXMetal SIGSEGV)로 SD.Next 프로세스가 죽는다(실증: 크래시 리포트
          relu_mps→MPSStream::commandEncoder). 프리뷰는 우리가 안 쓰므로 끈다(진행률 숫자는 그대로).
        실패는 비치명적(JPEG 도 디스코드는 렌더). """
        url = f"{self._base}/sdapi/v1/options"
        try:
            async with aiohttp.ClientSession(timeout=aiohttp.ClientTimeout(total=15)) as s:
                async with s.post(url, json={"samples_format": "png", "live_previews_enable": False}) as r:
                    return r.status == 200
        except aiohttp.ClientError:
            return False

    async def progress(self) -> float:
        """현재 생성 작업의 진행률(0.0~1.0). A1111 ``/sdapi/v1/progress``. 실패/미상이면 0.0.

        ``skip_current_image=true``: 프리뷰 이미지를 만들지 않게 한다 — 폴링 때마다 GPU 로 latent 를
        디코딩하면 생성과 Metal 인코더가 경합해 MPS 크래시를 유발한다(set_output_png 참고). 숫자만 받는다.
        """
        url = f"{self._base}/sdapi/v1/progress?skip_current_image=true"
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
