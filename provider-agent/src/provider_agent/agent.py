"""추론 처리 오케스트레이션 (차수 3).

서버가 보낸 infer 를 받아 localhost Ollama 로 처리하고 result/error 를 회신한다. 동시 처리 제한
(세마포어), 일일 한도, cancel 취소, 주기적 provider_status 보고, SIGINT graceful 종료.
"""
from __future__ import annotations

import asyncio
import logging
import signal

from . import sysinfo
from .config import AgentConfig
from .connection import AgentConnection
from .constants import ErrorCode
from .ollama import OllamaClient, OllamaError
from .protocol import (
    CancelFrame,
    Frame,
    InferError,
    InferRequest,
    InferResult,
    ProviderHelloFrame,
    ProviderStatusFrame,
)

logger = logging.getLogger("provider_agent.agent")


class ProviderAgent:
    def __init__(self, cfg: AgentConfig, ollama: OllamaClient | None = None) -> None:
        self._cfg = cfg
        self._ollama = ollama or OllamaClient(cfg.ollama_url, cfg.request_timeout)
        self._sem = asyncio.Semaphore(cfg.max_concurrency)
        self._remaining = cfg.daily_limit  # 0 = 무제한
        self._models: list[str] = list(cfg.models)
        self._inflight = 0
        self._cancelled: set[str] = set()
        self._tasks: dict[str, asyncio.Task[None]] = {}
        self._stop = asyncio.Event()
        self._conn = AgentConnection(cfg, self._on_server_frame, self._build_hello)

    # ── 핸드셰이크 ──────────────────────────────────────────────────────
    def _build_hello(self) -> ProviderHelloFrame:
        return ProviderHelloFrame(
            models=self._models,
            max_concurrency=self._cfg.max_concurrency,
            remaining_daily_requests=(self._remaining if self._cfg.daily_limit > 0 else 0),
        )

    # ── 서버 프레임 처리 ────────────────────────────────────────────────
    async def _on_server_frame(self, conn: AgentConnection, frame: Frame) -> None:
        if isinstance(frame, InferRequest):
            req_id = frame.request_id
            task = asyncio.create_task(self.handle_infer(conn, frame))
            self._tasks[req_id] = task
            task.add_done_callback(lambda _t: self._tasks.pop(req_id, None))
        elif isinstance(frame, CancelFrame):
            self._cancelled.add(frame.request_id)
            existing = self._tasks.get(frame.request_id)
            if existing is not None:
                existing.cancel()

    async def handle_infer(self, conn: AgentConnection, req: InferRequest) -> None:
        if req.request_id in self._cancelled:
            self._cancelled.discard(req.request_id)
            return
        if self._cfg.daily_limit > 0 and self._remaining <= 0:
            await self._safe_send(conn, InferError(req.request_id, code=ErrorCode.BUSY, message="일일 한도 초과"))
            return
        async with self._sem:
            if req.request_id in self._cancelled:
                self._cancelled.discard(req.request_id)
                return
            self._inflight += 1
            if self._cfg.daily_limit > 0:
                self._remaining -= 1
            # 서버가 모델을 지정하지 않으면 내가 제공하는 첫 모델로 처리한다.
            model = req.model or (self._models[0] if self._models else None)
            try:
                text, usage = await self._ollama.generate(req.prompt, model)
                await self._safe_send(conn, InferResult(req.request_id, text=text, usage=usage))
            except OllamaError as exc:
                await self._safe_send(conn, InferError(req.request_id, code=ErrorCode.OLLAMA_ERROR, message=str(exc)))
            except asyncio.CancelledError:
                logger.info("요청 %s… 취소됨", req.request_id[:8])
                raise
            finally:
                self._inflight -= 1

    async def _safe_send(self, conn: AgentConnection, frame: Frame) -> None:
        try:
            await conn.send(frame)
        except Exception as exc:  # noqa: BLE001
            logger.debug("응답 송신 실패(연결 끊김?): %s", exc)

    # ── 상태 보고 ───────────────────────────────────────────────────────
    async def _status_loop(self, conn: AgentConnection) -> None:
        try:
            while not self._stop.is_set():
                await asyncio.sleep(self._cfg.heartbeat_seconds)
                if not conn.authed:
                    continue
                await self._safe_send(
                    conn,
                    ProviderStatusFrame(
                        load=sysinfo.load_level(),
                        battery=sysinfo.battery_state(),
                        online=True,
                        busy=self._inflight > 0,
                    ),
                )
        except asyncio.CancelledError:
            return

    # ── 실행 ────────────────────────────────────────────────────────────
    async def run(self) -> int:
        if not self._models:
            try:
                self._models = await self._ollama.list_models()
                logger.info("감지된 모델: %s", self._models or "(없음)")
            except OllamaError as exc:
                logger.warning("Ollama 모델 목록 실패(%s) — 빈 목록으로 진행", exc)

        loop = asyncio.get_running_loop()
        for sig in (signal.SIGINT, signal.SIGTERM):
            try:
                loop.add_signal_handler(sig, self._stop.set)
            except (NotImplementedError, AttributeError, ValueError):  # pragma: no cover - Windows 등
                pass

        conn_task = asyncio.create_task(self._conn.run())
        status_task = asyncio.create_task(self._status_loop(self._conn))
        stop_task = asyncio.create_task(self._stop.wait())
        try:
            await asyncio.wait({conn_task, stop_task}, return_when=asyncio.FIRST_COMPLETED)
        finally:
            await self._conn.stop()
            for task in (status_task, conn_task, stop_task):
                task.cancel()
            await asyncio.gather(status_task, conn_task, stop_task, return_exceptions=True)
        logger.info("에이전트 종료")
        return 0


def run_agent(cfg: AgentConfig) -> int:
    return asyncio.run(ProviderAgent(cfg).run())
