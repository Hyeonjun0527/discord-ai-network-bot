"""ComfyUI 웹UI 실행 결과를 디스코드로 자동 전달하는 브리지.

생성은 유저가 ComfyUI 웹UI 에서 자유롭게(어떤 워크플로/모델이든) 하고, **완성된 출력 이미지만**
우리가 받아서 디스코드 채널로 보낸다. ComfyUI 의 WebSocket(``/ws``)을 구독해 실행 완료(``executed``)
이벤트의 **최종 출력**(SaveImage = type 'output')만 회수한다(미리보기 temp 는 제외).

netguard: localhost ComfyUI 만. 연결이 끊기면(앱이 ComfyUI 를 재시작 등) 자동 재구독한다.
"""
from __future__ import annotations

import asyncio
import logging
from collections.abc import Awaitable, Callable

import aiohttp

logger = logging.getLogger("provider_agent.comfy_watch")


def _ws_url(base: str) -> str:
    b = base.rstrip("/").replace("https://", "wss://").replace("http://", "ws://")
    return f"{b}/ws?clientId=nexa-bridge"


def _output_images(ev: object) -> list[dict]:
    """ComfyUI 이벤트에서 **최종 저장 출력**(executed + type='output', SaveImage) 이미지 참조만 골라낸다.

    순수 함수(테스트 가능). executed 가 아니거나 미리보기(temp)·중간 결과면 빈 목록 — 디스코드로 보내지 않는다.
    """
    if not isinstance(ev, dict) or ev.get("type") != "executed":
        return []
    images = ((ev.get("data") or {}).get("output") or {}).get("images") or []
    return [im for im in images if isinstance(im, dict) and str(im.get("type", "output")) == "output"]


async def _fetch_output(s: aiohttp.ClientSession, base: str, img: dict) -> bytes | None:
    """executed 이벤트의 이미지 참조(filename/subfolder/type)를 /view 로 회수."""
    try:
        params = {
            "filename": str(img.get("filename", "")),
            "subfolder": str(img.get("subfolder", "")),
            "type": str(img.get("type", "output")),
        }
        async with s.get(base.rstrip("/") + "/view", params=params) as r:
            if r.status == 200:
                return await r.read()
    except (aiohttp.ClientError, OSError):
        return None
    return None


async def watch(
    base: str,
    on_image: Callable[[bytes], Awaitable[None]],
    *,
    stop: asyncio.Event,
) -> None:
    """ComfyUI(base=http://127.0.0.1:8188) 의 실행 완료 출력 이미지를 on_image 로 넘긴다.

    on_image(png_bytes): 회수된 PNG 바이트(최종 출력만). 끊기면 5초 후 재구독. stop 으로 종료.
    """
    ws_url = _ws_url(base)
    while not stop.is_set():
        try:
            timeout = aiohttp.ClientTimeout(total=None, sock_read=None)
            async with aiohttp.ClientSession(timeout=timeout) as s, s.ws_connect(ws_url, heartbeat=30) as ws:
                logger.info("ComfyUI 출력 브리지 연결됨(%s) — 웹UI 실행 결과를 디스코드로 전달", base)
                async for msg in ws:
                    if stop.is_set():
                        break
                    if msg.type is not aiohttp.WSMsgType.TEXT:
                        continue
                    try:
                        ev = msg.json()
                    except (ValueError, TypeError):
                        continue
                    for im in _output_images(ev):  # executed + 최종 저장본(type='output')만
                        png = await _fetch_output(s, base, im)
                        if png:
                            try:
                                await on_image(png)
                            except Exception as exc:  # noqa: BLE001 - 한 장 전달 실패가 브리지를 끊지 않게(다음 장 계속)
                                logger.warning("ComfyUI 출력 전달 콜백 실패: %s", exc)
        except (aiohttp.ClientError, OSError, asyncio.TimeoutError) as exc:
            logger.debug("ComfyUI 브리지 연결 끊김(재시도 예정): %s", exc)
        if not stop.is_set():
            await asyncio.sleep(5.0)
