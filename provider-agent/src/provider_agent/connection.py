"""WS 연결·인증·재연결 (차수 2).

중앙 서버로 outbound WebSocket 연결을 열고, auth → auth_ok → provider_hello 핸드셰이크 후
수신 루프를 돈다. ping→pong, heartbeat 만료 감지, 끊김 시 지수 백오프 재연결. auth_err 는
토큰 문제이므로 재시도하지 않고 종료한다.
"""
from __future__ import annotations

import asyncio
import logging
import ssl
import time
from collections.abc import Awaitable
from typing import Callable

import aiohttp
import certifi

from .config import AgentConfig
from .constants import MAX_FRAME_BYTES
from .protocol import (
    AuthErrFrame,
    AuthFrame,
    AuthOkFrame,
    CancelFrame,
    Frame,
    InferRequest,
    PingFrame,
    PongFrame,
    ProtocolError,
    ProviderHelloFrame,
    dumps_frame,
    loads_frame,
)

logger = logging.getLogger("provider_agent.connection")


class AuthFailedError(Exception):
    """토큰 인증 실패 — 재연결하지 않는다."""


# (connection, frame) -> None. 서버가 보낸 infer/cancel 등을 에이전트(차수 3)가 처리.
ServerFrameHandler = Callable[["AgentConnection", Frame], Awaitable[None]]
HelloProvider = Callable[[], ProviderHelloFrame]


class AgentConnection:
    def __init__(
        self,
        cfg: AgentConfig,
        on_server_frame: ServerFrameHandler,
        hello_provider: HelloProvider,
    ) -> None:
        self._cfg = cfg
        self._on_server_frame = on_server_frame
        self._hello_provider = hello_provider
        self._ws: aiohttp.ClientWebSocketResponse | None = None
        self._stopped = False
        self._last_recv = 0.0
        self._authed = asyncio.Event()

    @property
    def authed(self) -> bool:
        return self._authed.is_set()

    async def send(self, frame: Frame) -> None:
        ws = self._ws
        if ws is None or ws.closed:
            raise ConnectionError("연결되어 있지 않습니다")
        await ws.send_str(dumps_frame(frame))

    async def run(self) -> None:
        """연결 루프(지수 백오프 재연결). stop() 또는 auth 실패 시 종료."""
        backoff = 1.0
        while not self._stopped:
            try:
                ssl_context = ssl.create_default_context(cafile=certifi.where())
                connector = aiohttp.TCPConnector(ssl=ssl_context)
                async with aiohttp.ClientSession(connector=connector) as session:
                    async with session.ws_connect(
                        self._cfg.relay_url, max_msg_size=MAX_FRAME_BYTES, heartbeat=None
                    ) as ws:
                        self._ws = ws
                        logger.info("중앙 서버 연결: %s", self._cfg.relay_url)
                        await self._session(ws)
                backoff = 1.0  # 정상 세션 종료 → 백오프 리셋
            except AuthFailedError as exc:
                logger.error("인증 실패(%s) — 토큰을 확인하세요. 재시도하지 않습니다.", exc)
                return
            except asyncio.CancelledError:
                raise
            except Exception as exc:  # noqa: BLE001 - 모든 연결 오류는 재연결 대상
                if self._stopped:
                    break
                logger.warning("연결 끊김(%s) — %.0f초 후 재연결", exc, backoff)
            finally:
                self._ws = None
                self._authed.clear()
            if self._stopped:
                break
            await asyncio.sleep(backoff)
            backoff = min(self._cfg.reconnect_max_seconds, backoff * 2)

    async def _session(self, ws: aiohttp.ClientWebSocketResponse) -> None:
        await ws.send_str(
            dumps_frame(
                AuthFrame(
                    token=self._cfg.token,
                    agent_version=self._cfg.agent_version,
                    platform=self._cfg.platform,
                )
            )
        )
        self._last_recv = time.monotonic()
        hb = asyncio.create_task(self._heartbeat(ws))
        try:
            async for msg in ws:
                if msg.type == aiohttp.WSMsgType.TEXT:
                    self._last_recv = time.monotonic()
                    try:
                        frame = loads_frame(msg.data)
                    except ProtocolError as exc:
                        # unknown/malformed 프레임은 거부하고 로그에 남긴다(조용히 무시하지 않음).
                        logger.warning("거부: 알 수 없거나 잘못된 프레임 — %s", exc)
                        continue
                    await self._dispatch(frame)
                elif msg.type in (
                    aiohttp.WSMsgType.CLOSE,
                    aiohttp.WSMsgType.CLOSING,
                    aiohttp.WSMsgType.CLOSED,
                    aiohttp.WSMsgType.ERROR,
                ):
                    break
        finally:
            hb.cancel()

    async def _dispatch(self, frame: Frame) -> None:
        if isinstance(frame, AuthOkFrame):
            self._authed.set()
            logger.info("인증 성공(session=%s) — provider_hello 전송", frame.session_id)
            await self.send(self._hello_provider())
        elif isinstance(frame, AuthErrFrame):
            raise AuthFailedError(frame.message or frame.code)
        elif isinstance(frame, PingFrame):
            await self.send(PongFrame())
        elif isinstance(frame, PongFrame):
            pass
        elif isinstance(frame, (InferRequest, CancelFrame)):
            # 서버가 에이전트에게 지시할 수 있는 명령은 infer/cancel(+ ping) 뿐이다.
            await self._on_server_frame(self, frame)
        else:
            # 그 외 타입(result/status/hello 등 에이전트→서버 전용)을 서버가 보내면 거부+로그.
            logger.warning("거부: 서버가 보낼 수 없는 프레임 type=%s", getattr(frame, "type", "?"))

    async def _heartbeat(self, ws: aiohttp.ClientWebSocketResponse) -> None:
        interval = self._cfg.heartbeat_seconds
        timeout = interval * 3
        try:
            while True:
                await asyncio.sleep(interval)
                if time.monotonic() - self._last_recv > timeout:
                    logger.warning("heartbeat 만료 — 연결 종료")
                    await ws.close()
                    return
                if self._authed.is_set():
                    try:
                        await self.send(PingFrame())
                    except Exception:  # noqa: BLE001
                        return
        except asyncio.CancelledError:
            return

    async def stop(self) -> None:
        self._stopped = True
        ws = self._ws
        if ws is not None and not ws.closed:
            await ws.close()
