"""로컬 ComfyUI 이미지 생성 백엔드 — SD.Next(A1111) 대안.

유저 자율: 어떤 로컬 이미지 도구든 쓰게 한다. ComfyUI 는 워크플로 그래프 API(/prompt)라 A1111
(/sdapi)과 다르지만, **SDClient 와 동일한 ``txt2img(prompt, options)->base64 PNG`` · ``health()``**
인터페이스를 제공해 에이전트가 백엔드를 그대로 갈아끼울 수 있다(_handle_image 변경 없음).

기본 txt2img 워크플로를 내장하고, 체크포인트 이름은 ComfyUI 에 설치된 것을 자동 조회해 채운다
(유저가 어떤 모델을 넣었든 동작). localhost 전용(netguard 원칙) — 원격은 명시 옵션에서만.
"""
from __future__ import annotations

import asyncio
import base64
import logging

import aiohttp

logger = logging.getLogger("provider_agent.comfy")


class ComfyError(Exception):
    """ComfyUI 호출/응답 오류."""


def build_workflow(
    prompt: str,
    ckpt_name: str,
    *,
    negative: str = "",
    width: int = 512,
    height: int = 512,
    steps: int = 20,
    cfg: float = 7.0,
    seed: int = 0,
) -> dict:
    """기본 txt2img 워크플로(ComfyUI API 그래프). 순수 함수(테스트 가능).

    표준 노드(CheckpointLoaderSimple→CLIPTextEncode×2→EmptyLatentImage→KSampler→VAEDecode→SaveImage).
    """
    return {
        "4": {"class_type": "CheckpointLoaderSimple", "inputs": {"ckpt_name": ckpt_name}},
        "5": {"class_type": "EmptyLatentImage", "inputs": {"width": width, "height": height, "batch_size": 1}},
        "6": {"class_type": "CLIPTextEncode", "inputs": {"text": prompt, "clip": ["4", 1]}},
        "7": {"class_type": "CLIPTextEncode", "inputs": {"text": negative, "clip": ["4", 1]}},
        "3": {
            "class_type": "KSampler",
            "inputs": {
                "seed": seed,
                "steps": steps,
                "cfg": cfg,
                "sampler_name": "euler",
                "scheduler": "normal",
                "denoise": 1.0,
                "model": ["4", 0],
                "positive": ["6", 0],
                "negative": ["7", 0],
                "latent_image": ["5", 0],
            },
        },
        "8": {"class_type": "VAEDecode", "inputs": {"samples": ["3", 0], "vae": ["4", 2]}},
        "9": {"class_type": "SaveImage", "inputs": {"filename_prefix": "nexa", "images": ["8", 0]}},
    }


class ComfyClient:
    def __init__(self, base_url: str, timeout: float = 180.0) -> None:
        self._base = base_url.rstrip("/")
        self._timeout = aiohttp.ClientTimeout(total=timeout)
        self._active: str | None = None  # 선택된 체크포인트(없으면 첫 모델 자동)

    async def health(self) -> bool:
        """ComfyUI 가 응답하는지(capability 광고 판단용). /object_info 200."""
        try:
            async with aiohttp.ClientSession(timeout=aiohttp.ClientTimeout(total=10)) as s:
                async with s.get(f"{self._base}/object_info") as r:
                    return r.status == 200
        except aiohttp.ClientError:
            return False

    async def set_output_png(self) -> bool:
        """SDClient 호환 no-op(ComfyUI 는 SaveImage 가 PNG 로 저장). 항상 성공."""
        return True

    async def current_checkpoint(self) -> str | None:
        """SDClient 호환 — 활성 체크포인트(해상도 판정용). 선택값 있으면 그것, 없으면 첫 모델."""
        if self._active:
            return self._active
        return await self.first_checkpoint()

    async def set_checkpoint(self, name: str) -> bool:
        """활성 체크포인트를 전환(SDClient 호환). 설치 목록에 있으면 _active 로 두고 True.

        ComfyUI 는 워크플로의 ckpt_name 으로 모델을 고르므로 핫스왑이 즉시(다음 생성부터) 반영된다.
        """
        if not name:
            return False
        ckpts = await self.list_checkpoints()
        if name in ckpts:
            self._active = name
            return True
        return False

    async def list_checkpoints(self) -> list[str]:
        """ComfyUI 에 설치된 체크포인트 전체 목록(폴더 스캔 결과 — 유저가 넣은 .safetensors 다 포함)."""
        try:
            async with aiohttp.ClientSession(timeout=aiohttp.ClientTimeout(total=10)) as s, s.get(
                f"{self._base}/object_info/CheckpointLoaderSimple"
            ) as r:
                data = await r.json()
        except (aiohttp.ClientError, ValueError):
            return []
        try:
            names = data["CheckpointLoaderSimple"]["input"]["required"]["ckpt_name"][0]
            return [str(n) for n in names] if isinstance(names, list) else []
        except (KeyError, IndexError, TypeError):
            return []

    async def first_checkpoint(self) -> str | None:
        """ComfyUI 에 설치된 첫 체크포인트 이름(선택값 없을 때 자동 선택)."""
        ckpts = await self.list_checkpoints()
        return ckpts[0] if ckpts else None

    async def txt2img(self, prompt: str, options: dict | None = None) -> str:
        """프롬프트로 이미지를 생성해 base64 PNG(첫 장) 반환. 오류 시 ComfyError. SDClient 와 동일 인터페이스."""
        opts = options or {}
        ckpt = self._active or await self.first_checkpoint()
        if not ckpt:
            raise ComfyError("ComfyUI 에 설치된 체크포인트가 없습니다(모델 폴더에 .safetensors 를 넣어주세요)")
        workflow = build_workflow(
            prompt,
            ckpt,
            negative=str(opts.get("negative_prompt", "")),
            width=int(opts.get("width", 512)),
            height=int(opts.get("height", 512)),
            steps=int(opts.get("steps", 20)),
        )
        try:
            async with aiohttp.ClientSession(timeout=self._timeout) as s:
                async with s.post(f"{self._base}/prompt", json={"prompt": workflow}) as r:
                    sub = await r.json()
                prompt_id = sub.get("prompt_id") if isinstance(sub, dict) else None
                if not prompt_id:
                    raise ComfyError(f"ComfyUI 작업 제출 실패: {sub}")
                img = await self._await_image(s, str(prompt_id))
        except aiohttp.ClientError as exc:
            raise ComfyError(f"ComfyUI 연결 실패: {exc}") from exc
        return img

    async def _await_image(self, s: aiohttp.ClientSession, prompt_id: str) -> str:
        """history 를 폴링해 완료된 이미지(첫 장)를 받아 base64 PNG 로 반환."""
        deadline = self._timeout.total or 180.0
        waited = 0.0
        while waited < deadline:
            async with s.get(f"{self._base}/history/{prompt_id}") as r:
                hist = await r.json()
            entry = hist.get(prompt_id) if isinstance(hist, dict) else None
            if entry:
                images = _first_image_ref(entry)
                if images is not None:
                    fn, sub, typ = images
                    async with s.get(f"{self._base}/view", params={"filename": fn, "subfolder": sub, "type": typ}) as ir:
                        raw = await ir.read()
                    return base64.b64encode(raw).decode("ascii")
            await asyncio.sleep(1.0)
            waited += 1.0
        raise ComfyError("ComfyUI 생성 시간 초과")


def _first_image_ref(entry: dict) -> tuple[str, str, str] | None:
    """history 항목에서 첫 출력 이미지의 (filename, subfolder, type). 없으면 None."""
    outputs = entry.get("outputs") if isinstance(entry, dict) else None
    if not isinstance(outputs, dict):
        return None
    for node in outputs.values():
        imgs = node.get("images") if isinstance(node, dict) else None
        if isinstance(imgs, list) and imgs and isinstance(imgs[0], dict):
            i = imgs[0]
            return (str(i.get("filename", "")), str(i.get("subfolder", "")), str(i.get("type", "output")))
    return None
