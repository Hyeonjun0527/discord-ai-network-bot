"""클라우드 RunPod Serverless 이미지 백엔드 — 직접 만든 diffusers 워커로 저비용 커스텀 생성.

provider 가 RunPod API 키 + Serverless 엔드포인트 ID 를 넣으면 ComfyUI/Stability 와 **동일한
``txt2img(prompt, options)->base64 PNG`` · ``health()`` 인터페이스**로 풀에 이미지 capability 를
광고한다. 워커는 ``runpod-worker/`` 의 diffusers 핸들러(사용자가 RunPod 에 배포)이며, 입력/출력
계약은 그 핸들러와 맞춘다(아래 _parse_output).

비용/커스텀성(전용 체크포인트·LoRA·애니 그림체)은 RunPod 가 유리하고, 평균 품질 안정성은
Stability 가 유리하다 — 둘 다 인터페이스로 지원해 서버가 골라 쓴다. 키는 provider PC 에만 저장.
"""
from __future__ import annotations

import asyncio
import base64
import binascii
import logging
from typing import Any, Callable

import aiohttp

from .image_backend import CloudImageBackendMixin, ImageBackendError

logger = logging.getLogger("provider_agent.runpod")

RUNPOD_API_BASE = "https://api.runpod.ai/v2"


class RunPodError(ImageBackendError):
    """RunPod 호출/응답 오류."""


class RunPodClient(CloudImageBackendMixin):
    def __init__(
        self,
        api_key: str,
        endpoint_id: str,
        timeout: float = 300.0,
        *,
        default_resolution: tuple[int, int] = (1024, 1024),
        steps: int = 30,
        cfg: float = 7.0,
    ) -> None:
        self._key = api_key
        self._endpoint = endpoint_id.strip()
        self._timeout = aiohttp.ClientTimeout(total=timeout)
        self._default_wh = default_resolution
        self._steps = steps
        self._cfg = cfg
        self._model = "runpod"

    def _headers(self) -> dict[str, str]:
        return {"Authorization": f"Bearer {self._key}", "Content-Type": "application/json"}

    async def txt2img(
        self,
        prompt: str,
        options: dict | None = None,
        on_progress: "Callable[[int], None] | None" = None,
    ) -> str:
        """RunPod diffusers 워커로 이미지를 생성해 base64 PNG 반환. 실패 시 RunPodError.

        runsync 는 동기 호출이지만, 워커가 콜드스타트/장시간이면 RunPod 가 IN_PROGRESS 로 즉시
        반환할 수 있다 → 그 경우 /status/<id> 를 폴링해 완료를 기다린다.
        """
        w, h = self._ensure_dims(options)
        opts = options or {}
        payload = {
            "input": {
                "prompt": prompt,
                "negative_prompt": str(opts.get("negative_prompt", "") or ""),
                "width": w,
                "height": h,
                "num_inference_steps": int(opts.get("steps", self._steps) or self._steps),
                "guidance_scale": float(opts.get("cfg", self._cfg) or self._cfg),
                "seed": int(opts.get("seed", 0) or 0),
            }
        }
        if on_progress:
            self._safe_progress(on_progress, 5)
        try:
            async with aiohttp.ClientSession(timeout=self._timeout) as s:
                async with s.post(f"{RUNPOD_API_BASE}/{self._endpoint}/runsync", json=payload, headers=self._headers()) as r:
                    data = await self._json_or_error(r, "runsync")
                result = await self._resolve(s, data, on_progress)
        except aiohttp.ClientError as exc:
            raise RunPodError(f"RunPod 연결 실패: {exc}") from exc
        if on_progress:
            self._safe_progress(on_progress, 100)
        return result

    async def _resolve(
        self,
        s: aiohttp.ClientSession,
        data: dict,
        on_progress: "Callable[[int], None] | None",
    ) -> str:
        """runsync 응답을 해석: 완료면 이미지 추출, 진행 중이면 /status 폴링."""
        status = str(data.get("status", "")).upper()
        if status in ("COMPLETED", "") and data.get("output") is not None:
            return self._parse_output(data.get("output"))
        if status == "FAILED":
            raise RunPodError(f"RunPod 작업 실패: {data.get('error') or data}")
        job_id = data.get("id")
        if not job_id:
            raise RunPodError(f"RunPod 응답에 작업 id/출력이 없습니다: {data}")
        # IN_QUEUE / IN_PROGRESS → 완료까지 폴링(전체 타임아웃 안에서).
        deadline = self._timeout.total or 300.0
        waited = 0.0
        while waited < deadline:
            await asyncio.sleep(2.0)
            waited += 2.0
            async with s.get(f"{RUNPOD_API_BASE}/{self._endpoint}/status/{job_id}", headers=self._headers()) as r:
                st = await self._json_or_error(r, "status")
            ss = str(st.get("status", "")).upper()
            if ss == "COMPLETED":
                return self._parse_output(st.get("output"))
            if ss == "FAILED":
                raise RunPodError(f"RunPod 작업 실패: {st.get('error') or st}")
            if on_progress and waited:
                self._safe_progress(on_progress, min(90, 5 + int(waited / deadline * 85)))
        raise RunPodError("RunPod 생성 시간 초과")

    @staticmethod
    def _parse_output(output: Any) -> str:
        """워커 출력에서 base64 PNG 추출. 계약: {"image_base64": "..."}.

        호환을 위해 {"images":[b64,...]}·{"image": b64}·문자열(b64) 형태도 받는다.
        """
        candidate: Any = None
        if isinstance(output, str):
            candidate = output
        elif isinstance(output, dict):
            candidate = output.get("image_base64") or output.get("image")
            if candidate is None:
                imgs = output.get("images")
                if isinstance(imgs, list) and imgs:
                    candidate = imgs[0]
        elif isinstance(output, list) and output:
            candidate = output[0]
        if not isinstance(candidate, str) or not candidate:
            raise RunPodError(f"RunPod 출력에서 이미지를 찾지 못했습니다: {output!r}"[:200])
        # data URL 접두(data:image/png;base64,) 제거.
        if candidate.startswith("data:"):
            candidate = candidate.split(",", 1)[-1]
        # 유효한 base64 인지 검증(잘못된 출력을 그대로 흘려보내지 않음).
        try:
            base64.b64decode(candidate, validate=True)
        except (binascii.Error, ValueError) as exc:
            raise RunPodError(f"RunPod 출력이 올바른 base64 가 아닙니다: {exc}") from exc
        return str(candidate)

    @staticmethod
    async def _json_or_error(r: aiohttp.ClientResponse, what: str) -> dict:
        try:
            data = await r.json()
        except (aiohttp.ClientError, ValueError) as exc:
            raise RunPodError(f"RunPod {what} 응답 파싱 실패(HTTP {r.status}): {exc}") from exc
        if r.status >= 400:
            msg = data.get("error") if isinstance(data, dict) else data
            raise RunPodError(f"RunPod {what} 오류(HTTP {r.status}): {msg}")
        return data if isinstance(data, dict) else {"output": data}

    @staticmethod
    def _safe_progress(on_progress: "Callable[[int], None]", pct: int) -> None:
        try:
            on_progress(pct)
        except Exception:  # noqa: BLE001 - 진행률 보고는 best-effort
            pass

    async def health(self) -> bool:
        """엔드포인트 health(워커 가용)로 광고 가능 여부 판단."""
        if not (self._key and self._endpoint):
            return False
        try:
            async with aiohttp.ClientSession(timeout=aiohttp.ClientTimeout(total=10)) as s:
                async with s.get(f"{RUNPOD_API_BASE}/{self._endpoint}/health", headers=self._headers()) as r:
                    return r.status == 200
        except aiohttp.ClientError:
            return False
