"""RunPod Serverless diffusers 이미지 워커 — Nexa RunPodClient 의 백엔드.

provider-agent 의 ``RunPodClient`` 가 ``POST /v2/<endpoint>/runsync`` 로 보내는 입력을 받아 diffusers
파이프라인으로 이미지를 생성하고, base64 PNG 를 ``{"image_base64": ...}`` 로 돌려준다(클라이언트 계약).

입력(input):
    prompt(str, 필수) · negative_prompt(str) · width(int) · height(int) ·
    num_inference_steps(int) · guidance_scale(float) · seed(int, 0=랜덤)
출력(output):
    {"image_base64": "<png base64>", "model": "<MODEL_ID>", "width": w, "height": h, "seed": s}

모델은 환경변수 MODEL_ID 로 바꾼다(기본 SDXL base). 전용 체크포인트·애니 그림체로 교체하면 그 스타일이
Stability 보다 유리할 수 있다(README 참고). 파이프라인은 콜드스타트 1회만 로드하고 이후 재사용한다.
"""
from __future__ import annotations

import base64
import io
import os

import runpod
import torch
from diffusers import AutoPipelineForText2Image

MODEL_ID = os.getenv("MODEL_ID", "stabilityai/stable-diffusion-xl-base-1.0")
_DTYPE = torch.float16 if torch.cuda.is_available() else torch.float32
_DEVICE = "cuda" if torch.cuda.is_available() else "cpu"
_MAX_STEPS = int(os.getenv("MAX_STEPS", "60"))
_MAX_DIM = int(os.getenv("MAX_DIM", "1536"))

# 콜드스타트 1회 로드 → 이후 요청 재사용(워커 프로세스 생존 동안 상주).
_pipe = AutoPipelineForText2Image.from_pretrained(MODEL_ID, torch_dtype=_DTYPE).to(_DEVICE)
_pipe.set_progress_bar_config(disable=True)


def _clamp(v: int, lo: int, hi: int, default: int) -> int:
    try:
        n = int(v)
    except (TypeError, ValueError):
        return default
    return max(lo, min(hi, n))


def _dim(v: object, default: int) -> int:
    """해상도를 8 의 배수로 정규화(diffusers 요건)."""
    n = _clamp(v if isinstance(v, int) else default, 256, _MAX_DIM, default)  # type: ignore[arg-type]
    return n - (n % 8)


def handler(job: dict) -> dict:
    """RunPod 작업 핸들러. job["input"] 으로 생성하고 base64 PNG 를 반환한다."""
    inp = job.get("input") or {}
    prompt = str(inp.get("prompt") or "").strip()
    if not prompt:
        return {"error": "prompt 가 비어 있습니다"}
    negative = str(inp.get("negative_prompt") or "") or None
    width = _dim(inp.get("width"), 1024)
    height = _dim(inp.get("height"), 1024)
    steps = _clamp(inp.get("num_inference_steps", 30), 1, _MAX_STEPS, 30)
    guidance = float(inp.get("guidance_scale", 7.0) or 7.0)
    seed = int(inp.get("seed", 0) or 0)
    generator = None
    if seed:
        generator = torch.Generator(device=_DEVICE).manual_seed(seed)

    image = _pipe(
        prompt=prompt,
        negative_prompt=negative,
        width=width,
        height=height,
        num_inference_steps=steps,
        guidance_scale=guidance,
        generator=generator,
    ).images[0]

    buf = io.BytesIO()
    image.save(buf, format="PNG")
    b64 = base64.b64encode(buf.getvalue()).decode("ascii")
    return {"image_base64": b64, "model": MODEL_ID, "width": width, "height": height, "seed": seed}


runpod.serverless.start({"handler": handler})
