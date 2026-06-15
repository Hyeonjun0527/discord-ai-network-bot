"""클라우드 Stability AI 이미지 백엔드 — 관리자 키 1개로 서버 전체 무료 이미지 제공.

provider 가 Stability API 키(https://platform.stability.ai/account/keys) 하나를 넣으면 ComfyUI/RunPod
와 **동일한 ``txt2img(prompt, options)->base64 PNG`` · ``health()`` 인터페이스**로 풀에 이미지
capability 를 광고한다(클라우드 GLM 텍스트 백엔드와 같은 '관리자 키 1개' 모델).

Stability 가 직접 튜닝한 모델/파이프라인이라 기본값만으로 평균 품질이 안정적이다(초기 MVP·상업용
일반 이미지에 유리). 키는 provider PC 에만 저장하고 central 엔 절대 올리지 않는다.
프롬프트 안전 심사·SFW 변환은 이 파일이 아니라 클라우드 GLM(z.ai) 텍스트 백엔드가 수행한다.
"""
from __future__ import annotations

import base64
import logging
from typing import Callable

import aiohttp

from . import sslutil
from .image_backend import CloudImageBackendMixin, ImageBackendError

logger = logging.getLogger("provider_agent.stability")

STABILITY_API_BASE = "https://api.stability.ai"
DEFAULT_STABILITY_MODEL = "core"
# 모델 라벨 → /v2beta/stable-image/generate/<path>. core=빠르고 저렴, ultra=고품질, sd3=SD3.5.
_MODEL_PATHS = {"core": "core", "ultra": "ultra", "sd3": "sd3"}


class StabilityError(ImageBackendError):
    """Stability 호출/응답 오류."""


class StabilityClient(CloudImageBackendMixin):
    def __init__(self, api_key: str, timeout: float = 180.0, *, model: str = DEFAULT_STABILITY_MODEL) -> None:
        self._key = api_key
        self._timeout = aiohttp.ClientTimeout(total=timeout)
        self._model = (model or DEFAULT_STABILITY_MODEL).strip() or DEFAULT_STABILITY_MODEL
        # Stability 는 aspect_ratio 로 크기를 잡는다(폭/높이 직접 지정 아님) → 기본 1:1(≈1MP).
        self._default_wh = (1024, 1024)

    def _generate_url(self) -> str:
        path = _MODEL_PATHS.get(self._model, "core")
        return f"{STABILITY_API_BASE}/v2beta/stable-image/generate/{path}"

    async def txt2img(
        self,
        prompt: str,
        options: dict | None = None,
        on_progress: "Callable[[int], None] | None" = None,
    ) -> str:
        """Stability 로 이미지를 생성해 base64 PNG 반환. 실패 시 StabilityError.

        Stability 는 요청/응답형(스트리밍 진행률 없음)이라 on_progress 는 시작·완료 두 지점만 보고한다.
        """
        opts = options or {}
        negative = str(opts.get("negative_prompt", "") or "")
        aspect_ratio = str(opts.get("aspect_ratio", "1:1") or "1:1")
        seed = int(opts.get("seed", 0) or 0)
        if on_progress:
            self._safe_progress(on_progress, 5)  # 전송 시작(클라우드라 중간 진행률 없음)

        form = aiohttp.FormData()
        form.add_field("prompt", prompt)
        if negative:
            form.add_field("negative_prompt", negative)
        form.add_field("aspect_ratio", aspect_ratio)
        form.add_field("seed", str(seed))
        form.add_field("output_format", "png")
        if self._model == "sd3":
            form.add_field("model", "sd3.5-large")
        headers = {"Authorization": f"Bearer {self._key}", "Accept": "image/*"}

        try:
            async with aiohttp.ClientSession(
                timeout=self._timeout, connector=aiohttp.TCPConnector(ssl=sslutil.ssl_context())
            ) as s:
                async with s.post(self._generate_url(), data=form, headers=headers) as r:
                    if r.status == 200 and (r.content_type or "").startswith("image/"):
                        raw = await r.read()
                        if on_progress:
                            self._safe_progress(on_progress, 100)
                        return base64.b64encode(raw).decode("ascii")
                    # 비-200 또는 JSON 오류 본문(content moderation·잔액 부족·키 오류 등)
                    detail = await self._error_detail(r)
                    raise StabilityError(f"Stability 생성 실패(HTTP {r.status}): {detail}")
        except aiohttp.ClientError as exc:
            raise StabilityError(f"Stability 연결 실패: {exc}") from exc

    @staticmethod
    async def _error_detail(r: aiohttp.ClientResponse) -> str:
        """오류 응답에서 사람이 읽을 메시지 추출(JSON errors[] 우선, 아니면 본문 앞부분)."""
        try:
            data = await r.json()
        except (aiohttp.ClientError, ValueError):
            try:
                return (await r.text())[:200]
            except aiohttp.ClientError:
                return "(본문 없음)"
        if isinstance(data, dict):
            errs = data.get("errors")
            if isinstance(errs, list) and errs:
                return "; ".join(str(e) for e in errs)
            return str(data.get("message") or data.get("name") or data)
        return str(data)

    @staticmethod
    def _safe_progress(on_progress: "Callable[[int], None]", pct: int) -> None:
        try:
            on_progress(pct)
        except Exception:  # noqa: BLE001 - 진행률 보고는 best-effort(생성엔 무영향)
            pass

    async def health(self) -> bool:
        """키가 유효한지 가벼운 호출(계정 조회)로 확인 — capability 광고 판단용."""
        url = f"{STABILITY_API_BASE}/v1/user/account"
        try:
            async with aiohttp.ClientSession(
                timeout=aiohttp.ClientTimeout(total=10), connector=aiohttp.TCPConnector(ssl=sslutil.ssl_context())
            ) as s:
                async with s.get(url, headers={"Authorization": f"Bearer {self._key}"}) as r:
                    return r.status == 200
        except aiohttp.ClientError:
            return False

    async def list_checkpoints(self) -> list[str]:
        """선택 가능한 Stability 모델 라벨."""
        return list(_MODEL_PATHS.keys())

    async def set_checkpoint(self, name: str) -> bool:
        """모델 라벨 전환(core/ultra/sd3)."""
        if name in _MODEL_PATHS:
            self._model = name
            return True
        return False
