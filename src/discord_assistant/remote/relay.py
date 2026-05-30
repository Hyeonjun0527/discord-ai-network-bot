"""WebSocket 릴레이 서버 (ADR 0002, 차수 4).

중앙 봇 프로세스 안에서 aiohttp 로 WebSocket 엔드포인트를 띄워, 유저/프로바이더 PC 의
에이전트가 outbound 로 접속하게 한다. 인증된 연결만 레지스트리에 등록하고, 봇의
``RemoteAgentClient`` 가 그 연결로 추론 요청을 내려보낸다.

보안(ADR 0002): 봇은 임의 URL 로 나가지 않고 이미 인증된 연결로만 통신한다(SSRF 불가).
TLS/``wss`` 는 운영 시 앞단 리버스 프록시(nginx/cloudflare 등)에서 종단하는 것을 전제로 한다
(항목 116). 같은 신뢰 경계 안에서 동작하므로 Origin 검증은 토큰 인증으로 대체한다(항목 117).

aiohttp 미설치 시(이론상) 서버 기동만 건너뛴다(health.py 패턴, 항목 87).
"""

from __future__ import annotations

import asyncio
import logging
import time
from collections.abc import AsyncIterator
from dataclasses import dataclass
from typing import Any, Awaitable, Callable, Literal

from ..settings import AppSettings
from .constants import MAX_FRAME_BYTES, ErrorCode
from .errors import (
    AgentBusyError,
    ConnectionClosedError,
    RemoteInferError,
    RemoteTimeoutError,
)
from .protocol import (
    AuthErrFrame,
    AuthFrame,
    AuthOkFrame,
    CancelFrame,
    ChunkFrame,
    Frame,
    InferError,
    InferRequest,
    InferResult,
    PingFrame,
    PongFrame,
    ProtocolError,
    dumps_frame,
    loads_frame,
    new_request_id,
)
from .registry import ConnectionRegistry, OwnerKey

logger = logging.getLogger(__name__)

try:  # pragma: no cover - aiohttp 는 discord.py 런타임 의존성이라 보통 존재
    from aiohttp import WSMsgType, web

    _HAVE_AIOHTTP = True
except ImportError:  # pragma: no cover - aiohttp 미설치(이론상)
    _HAVE_AIOHTTP = False

__all__ = [
    "OwnerBinding",
    "TokenVerifier",
    "RelayConnection",
    "RelayServer",
    "maybe_start_relay",
]


@dataclass(frozen=True, slots=True)
class OwnerBinding:
    """토큰 검증 결과(항목 92). 어떤 소유자에 연결을 묶을지 알려준다.

    - kind="user": 개인 모드 연결(user_id 의 PC).
    - kind="guild": 공유 모드 호스트 연결(host_user_id 가 방장).
    """

    kind: Literal["user", "guild"]
    owner_id: int
    host_user_id: int | None = None
    agent_version: str = ""


# 토큰 문자열 → OwnerBinding|None (None = 인증 실패). 차수 6 tokens 모듈이 실제 구현을 주입한다.
TokenVerifier = Callable[[str], Awaitable["OwnerBinding | None"]]


class RelayConnection:
    """단일 에이전트 WebSocket 연결(항목 95). ``AgentConnection`` 프로토콜을 구현한다.

    request_id ↔ 응답 future 를 보관하고, 동시 처리 슬롯(세마포어)과 대기 큐 상한으로
    호스트 과부하를 막는다(항목 99~108).
    """

    def __init__(
        self,
        ws: Any,
        *,
        max_concurrency: int,
        request_timeout: float,
        max_queue: int = 16,
    ) -> None:
        self._ws = ws
        self.owner_key: OwnerKey | None = None
        self.agent_version: str = ""
        self.platform: str = ""
        self.connected_at: float = time.monotonic()
        self.last_seen_monotonic: float = time.monotonic()
        self._request_timeout = request_timeout
        self._sem = asyncio.Semaphore(max_concurrency)
        self._max_queue = max_queue
        self._waiters = 0  # 세마포어 대기 중인 요청 수(큐 길이 추정)
        self._pending: dict[str, asyncio.Future[InferResult]] = {}
        self._streams: dict[str, asyncio.Queue[ChunkFrame]] = {}
        self._send_lock = asyncio.Lock()
        self._closed = False

    # ── AgentConnection 인터페이스 ──────────────────────────────────────
    async def send(self, frame: Frame) -> None:
        if self._closed:
            raise ConnectionClosedError("연결이 닫혀 있습니다")
        async with self._send_lock:  # ws 동시 전송 직렬화
            await self._ws.send_str(dumps_frame(frame))

    async def close(self, reason: str = "") -> None:
        if self._closed:
            return
        self._closed = True
        # 대기 중인 모든 요청을 실패 처리(항목 105).
        for fut in list(self._pending.values()):
            if not fut.done():
                fut.set_exception(ConnectionClosedError(reason or "연결 종료"))
        self._pending.clear()
        for q in list(self._streams.values()):
            q.put_nowait(ChunkFrame(request_id="", delta="", done=True))
        self._streams.clear()
        try:
            await self._ws.close(message=(reason or "")[:120].encode("utf-8"))
        except Exception:  # pragma: no cover - 종료 경로 best-effort
            logger.debug("ws close 실패(무시)")

    # ── 추론 송신(항목 100/101/106~108) ────────────────────────────────
    async def send_infer(
        self, *, prompt: str, model: str | None = None, options: dict[str, Any] | None = None
    ) -> InferResult:
        """추론 요청을 보내고 결과를 기다린다. 큐 초과는 BUSY, 무응답은 TIMEOUT."""
        if self._closed:
            raise ConnectionClosedError("연결이 닫혀 있습니다")
        if self._waiters >= self._max_queue:
            raise AgentBusyError("대기 큐가 가득 찼습니다. 잠시 후 다시 시도해 주세요.")
        self._waiters += 1
        try:
            async with self._sem:  # per-host 동시 처리 제한(자연스러운 큐잉)
                return await self._do_infer(prompt, model, options, stream=False)
        finally:
            self._waiters -= 1

    async def _do_infer(
        self,
        prompt: str,
        model: str | None,
        options: dict[str, Any] | None,
        *,
        stream: bool,
    ) -> InferResult:
        request_id = new_request_id()
        loop = asyncio.get_running_loop()
        fut: asyncio.Future[InferResult] = loop.create_future()
        self._pending[request_id] = fut
        req = InferRequest(request_id=request_id, model=model, prompt=prompt, options=options or {})
        try:
            await self.send(req)
            return await asyncio.wait_for(fut, timeout=self._request_timeout)
        except (TimeoutError, asyncio.TimeoutError) as exc:
            # 타임아웃 시 에이전트에 취소를 알리고 TIMEOUT 으로 변환(항목 101).
            await self._safe_send(CancelFrame(request_id=request_id))
            raise RemoteTimeoutError(
                f"원격 에이전트 응답 시간 초과({self._request_timeout:.0f}초)"
            ) from exc
        finally:
            self._pending.pop(request_id, None)

    async def send_infer_stream(
        self, *, prompt: str, model: str | None = None, options: dict[str, Any] | None = None
    ) -> AsyncIterator[str]:
        """스트리밍 추론(항목 98). chunk delta 를 순차 yield 한다."""
        if self._closed:
            raise ConnectionClosedError("연결이 닫혀 있습니다")
        if self._waiters >= self._max_queue:
            raise AgentBusyError("대기 큐가 가득 찼습니다.")
        self._waiters += 1
        request_id = new_request_id()
        queue: asyncio.Queue[ChunkFrame] = asyncio.Queue()
        self._streams[request_id] = queue
        try:
            async with self._sem:
                req = InferRequest(
                    request_id=request_id, model=model, prompt=prompt, options=options or {}
                )
                await self.send(req)
                while True:
                    chunk = await asyncio.wait_for(queue.get(), timeout=self._request_timeout)
                    if chunk.delta:
                        yield chunk.delta
                    if chunk.done:
                        break
        except (TimeoutError, asyncio.TimeoutError) as exc:
            await self._safe_send(CancelFrame(request_id=request_id))
            raise RemoteTimeoutError("원격 에이전트 스트림 시간 초과") from exc
        finally:
            self._waiters -= 1
            self._streams.pop(request_id, None)

    async def _safe_send(self, frame: Frame) -> None:
        try:
            await self.send(frame)
        except Exception:  # pragma: no cover - 취소 통지는 best-effort
            logger.debug("cancel/제어 프레임 송신 실패(무시)")

    # ── 수신 디스패치(항목 96~98, 103) ─────────────────────────────────
    def handle_frame(self, frame: Frame) -> None:
        """read loop 가 파싱한 프레임을 처리한다."""
        self.last_seen_monotonic = time.monotonic()
        if isinstance(frame, InferResult):
            fut = self._pending.get(frame.request_id)
            if fut is not None and not fut.done():
                fut.set_result(frame)
        elif isinstance(frame, InferError):
            fut = self._pending.get(frame.request_id)
            if fut is not None and not fut.done():
                fut.set_exception(RemoteInferError(frame.code, frame.message))
        elif isinstance(frame, ChunkFrame):
            q = self._streams.get(frame.request_id)
            if q is not None:
                q.put_nowait(frame)
        # PongFrame 등은 last_seen 갱신만으로 충분(항목 103).

    @property
    def waiters(self) -> int:
        return self._waiters


class RelayServer:
    """aiohttp WebSocket 릴레이 서버(항목 88). start/stop 으로 생명주기를 관리한다."""

    def __init__(
        self,
        settings: AppSettings,
        registry: ConnectionRegistry,
        verifier: TokenVerifier,
    ) -> None:
        if not _HAVE_AIOHTTP:  # pragma: no cover
            raise RuntimeError("aiohttp 가 없어 릴레이 서버를 만들 수 없습니다.")
        self._settings = settings
        self._registry = registry
        self._verifier = verifier
        self._app = web.Application()
        self._app.router.add_get(settings.relay_path, self._handle_ws)
        self._runner: Any = None
        self._site: Any = None
        self._connections: set[RelayConnection] = set()

    async def start(self) -> None:
        """릴레이를 기동한다(항목 88)."""
        self._runner = web.AppRunner(self._app)
        await self._runner.setup()
        self._site = web.TCPSite(
            self._runner, self._settings.relay_host, self._settings.relay_port
        )
        await self._site.start()
        logger.info(
            "릴레이 기동: ws://%s:%d%s (동시%d/호스트, heartbeat %.0fs)",
            self._settings.relay_host,
            self._settings.relay_port,
            self._settings.relay_path,
            self._settings.relay_max_concurrency_per_host,
            self._settings.relay_heartbeat_seconds,
        )

    async def stop(self) -> None:
        """모든 연결을 닫고 정리한다(항목 111/113)."""
        for conn in list(self._connections):
            await conn.close("서버 종료")
        self._connections.clear()
        if self._runner is not None:
            await self._runner.cleanup()
            self._runner = None
            self._site = None
        logger.info("릴레이 종료")

    async def _handle_ws(self, request: Any) -> Any:
        """WebSocket 핸들러(항목 89~94). 인증 → 등록 → read loop + heartbeat."""
        ws = web.WebSocketResponse(max_msg_size=MAX_FRAME_BYTES, heartbeat=None)
        await ws.prepare(request)

        conn = RelayConnection(
            ws,
            max_concurrency=self._settings.relay_max_concurrency_per_host,
            request_timeout=self._settings.relay_request_timeout_seconds,
        )

        binding = await self._authenticate(ws, conn)
        if binding is None:
            return ws  # 인증 실패 시 _authenticate 가 이미 닫음

        await self._register(binding, conn)
        self._connections.add(conn)
        hb_task = asyncio.create_task(self._heartbeat(conn))
        try:
            await self._read_loop(ws, conn)
        finally:
            hb_task.cancel()
            self._connections.discard(conn)
            self._registry.unregister(conn)
            await conn.close("연결 종료")
        return ws

    async def _authenticate(self, ws: Any, conn: RelayConnection) -> OwnerBinding | None:
        """첫 프레임=auth 강제(타임아웃) → 토큰 검증(항목 91/92/94)."""
        try:
            msg = await asyncio.wait_for(ws.receive(), timeout=10.0)
        except (TimeoutError, asyncio.TimeoutError):
            await self._reject(ws, "인증 타임아웃")
            return None
        if msg.type != WSMsgType.TEXT:
            await self._reject(ws, "첫 메시지는 auth(text)여야 합니다")
            return None
        try:
            frame = loads_frame(msg.data)
        except ProtocolError as exc:
            await self._reject(ws, f"잘못된 auth 프레임: {exc}")
            return None
        if not isinstance(frame, AuthFrame):
            await self._reject(ws, "첫 프레임은 auth 여야 합니다")
            return None
        binding = await self._verifier(frame.token)
        if binding is None:
            await self._reject(ws, "토큰 검증 실패")
            return None
        conn.agent_version = frame.agent_version or binding.agent_version
        conn.platform = frame.platform
        return binding

    async def _reject(self, ws: Any, reason: str) -> None:
        try:
            await ws.send_str(dumps_frame(AuthErrFrame(code=ErrorCode.AUTH_FAILED, message=reason)))
            await ws.close()
        except Exception:  # pragma: no cover
            pass
        logger.info("에이전트 인증 거부: %s", reason)

    async def _register(self, binding: OwnerBinding, conn: RelayConnection) -> None:
        if binding.kind == "user":
            await self._registry.register_user(binding.owner_id, conn)
        else:
            host_user = binding.host_user_id if binding.host_user_id is not None else 0
            await self._registry.register_host(binding.owner_id, host_user, conn)
        await conn.send(AuthOkFrame(session_id=str(conn.owner_key or "")))

    async def _read_loop(self, ws: Any, conn: RelayConnection) -> None:
        """수신 루프(항목 96/109/110). ping→pong, 잘못된 프레임은 무시(연결 유지)."""
        async for msg in ws:
            if msg.type == WSMsgType.TEXT:
                try:
                    frame = loads_frame(msg.data)
                except ProtocolError as exc:
                    logger.debug("잘못된 프레임 수신(무시): %s", exc)
                    continue
                if isinstance(frame, PingFrame):
                    await conn._safe_send(PongFrame())
                    conn.last_seen_monotonic = time.monotonic()
                    continue
                conn.handle_frame(frame)
            elif msg.type in (WSMsgType.CLOSE, WSMsgType.CLOSING, WSMsgType.CLOSED, WSMsgType.ERROR):
                break

    async def _heartbeat(self, conn: RelayConnection) -> None:
        """주기 ping + 만료 감지(항목 102/104)."""
        interval = self._settings.relay_heartbeat_seconds
        timeout = interval * 2.5
        try:
            while True:
                await asyncio.sleep(interval)
                if time.monotonic() - conn.last_seen_monotonic > timeout:
                    logger.info("heartbeat 만료 → 연결 종료: %s", conn.owner_key)
                    await conn.close("heartbeat 만료")
                    return
                await conn._safe_send(PingFrame())
        except asyncio.CancelledError:  # pragma: no cover - 정상 취소
            return

    @property
    def active_connections(self) -> int:
        return len(self._connections)


async def maybe_start_relay(
    settings: AppSettings,
    registry: ConnectionRegistry,
    verifier: TokenVerifier,
) -> RelayServer | None:
    """relay_enabled + aiohttp 가용 시에만 릴레이를 띄운다(항목 112). 봇 부팅에서 호출."""
    if not settings.relay_enabled:
        return None
    if not _HAVE_AIOHTTP:  # pragma: no cover
        logger.warning("RELAY_ENABLED=true 이지만 aiohttp 가 없어 릴레이를 띄울 수 없습니다.")
        return None
    server = RelayServer(settings, registry, verifier)
    await server.start()
    return server
