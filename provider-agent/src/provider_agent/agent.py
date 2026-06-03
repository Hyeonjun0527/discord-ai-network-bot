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
from .constants import IMAGE_CHUNK_CHARS, MAX_RESPONSE_CHARS, ErrorCode
from .ollama import OllamaClient, OllamaError
from .protocol import (
    CancelFrame,
    ChunkFrame,
    Frame,
    InferError,
    InferRequest,
    InferResult,
    ProviderHelloFrame,
    ProviderStatusFrame,
)

logger = logging.getLogger("provider_agent.agent")


class ProviderAgent:
    def __init__(self, cfg: AgentConfig, ollama: OllamaClient | None = None, sd=None) -> None:
        self._cfg = cfg
        self._ollama = ollama or OllamaClient(cfg.ollama_url, cfg.request_timeout)
        # 로컬 SD(이미지 생성)는 opt-in(--enable-image). 런타임 health 로 capability 확정(SD Phase 1).
        if sd is not None:
            self._sd = sd
        elif cfg.enable_image:
            from .sd import SDClient

            self._sd = SDClient(cfg.sd_url, cfg.request_timeout)
        else:
            self._sd = None
        self._image_ready = False
        self._sem = asyncio.Semaphore(cfg.max_concurrency)
        self._remaining = cfg.daily_limit  # 0 = 무제한
        self._models: list[str] = list(cfg.models)
        self._inflight = 0
        self._processed = 0  # 누적 처리 건수(로컬 요약)
        self._cancelled: set[str] = set()
        self._tasks: dict[str, asyncio.Task[None]] = {}
        self._stop = asyncio.Event()
        self._conn = AgentConnection(cfg, self._on_server_frame, self._build_hello)

    # ── 핸드셰이크 ──────────────────────────────────────────────────────
    def _build_hello(self) -> ProviderHelloFrame:
        capabilities = ["text"]
        if self._image_ready:
            capabilities.append("image")
        return ProviderHelloFrame(
            models=self._models,
            max_concurrency=self._cfg.max_concurrency,
            remaining_daily_requests=(self._remaining if self._cfg.daily_limit > 0 else 0),
            capabilities=capabilities,
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
        # 일일 한도·동시성은 **에이전트 내부에서 강제**한다. 서버가 더 많은 요청을 보내도
        # 이 게이트(self._cfg.daily_limit / self._sem)를 우회할 수 없다(프로바이더 주권).
        if self._cfg.daily_limit > 0 and self._remaining <= 0:
            await self._safe_send(conn, InferError(req.request_id, code=ErrorCode.BUSY, message="일일 한도 초과"))
            return
        # 자원 보호: CPU 고부하·배터리 방전 중에는 자동 pause(BUSY 로 반려, 한도 소모 안 함).
        paused, reason = sysinfo.should_pause(self._cfg.pause_on_battery, self._cfg.pause_on_high_load)
        if paused:
            logger.info("자원 보호로 일시 중지(%s) — 요청 반려", reason)
            await self._safe_send(conn, InferError(req.request_id, code=ErrorCode.BUSY, message=f"자원 보호 일시중지({reason})"))
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
                if req.task == "image":
                    # 로컬 SD 이미지 생성(SD Phase 2). 미지원이면 에러.
                    if self._sd is None or not self._image_ready:
                        await self._safe_send(
                            conn, InferError(req.request_id, code=ErrorCode.OLLAMA_ERROR, message="이미지 생성을 지원하지 않는 프로바이더입니다")
                        )
                    else:
                        await self._handle_image(conn, req)
                        self._processed += 1
                elif req.stream:
                    # 스트리밍(#142): 부분 텍스트를 ChunkFrame 으로 점진 전송 후 done 표시.
                    # 무거운/폭주 응답 보호: 누적 길이가 MAX_RESPONSE_CHARS 를 넘으면 조기 종료.
                    emitted = 0
                    async for kind, val in self._ollama.generate_stream(req.prompt, model):
                        if kind == "chunk":
                            if emitted >= MAX_RESPONSE_CHARS:
                                continue
                            piece = val[: MAX_RESPONSE_CHARS - emitted]
                            emitted += len(piece)
                            await self._safe_send(conn, ChunkFrame(req.request_id, delta=piece, done=False))
                    await self._safe_send(conn, ChunkFrame(req.request_id, delta="", done=True))
                    self._processed += 1
                else:
                    text, usage = await self._ollama.generate(req.prompt, model)
                    if len(text) > MAX_RESPONSE_CHARS:
                        text = text[:MAX_RESPONSE_CHARS]
                    self._processed += 1
                    await self._safe_send(conn, InferResult(req.request_id, text=text, usage=usage))
            except OllamaError as exc:
                await self._safe_send(conn, InferError(req.request_id, code=ErrorCode.OLLAMA_ERROR, message=str(exc)))
            except asyncio.CancelledError:
                logger.info("요청 %s… 취소됨", req.request_id[:8])
                raise
            finally:
                self._inflight -= 1

    async def _handle_image(self, conn: AgentConnection, req: InferRequest) -> None:
        """로컬 SD 로 이미지를 생성해 base64 PNG 를 ChunkFrame 으로 분할 전송(SD Phase 2)."""
        from .sd import SDError

        assert self._sd is not None  # 호출 전 _image_ready 로 가드됨
        try:
            b64 = await self._sd.txt2img(req.prompt)
        except SDError as exc:
            await self._safe_send(conn, InferError(req.request_id, code=ErrorCode.OLLAMA_ERROR, message=str(exc)))
            return
        # 1MB 프레임 한계 때문에 base64 를 조각내어 보낸다. 마지막에 done=True(빈 delta).
        for i in range(0, len(b64), IMAGE_CHUNK_CHARS):
            await self._safe_send(conn, ChunkFrame(req.request_id, delta=b64[i : i + IMAGE_CHUNK_CHARS], done=False))
        await self._safe_send(conn, ChunkFrame(req.request_id, delta="", done=True))

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
                # 로컬 상태 콘솔(차수 10): 처리/대기/잔여 요약
                remaining = "무제한" if self._cfg.daily_limit == 0 else str(self._remaining)
                logger.info("상태: 처리 %d · 진행중 %d · 일일잔여 %s", self._processed, self._inflight, remaining)
        except asyncio.CancelledError:
            return

    # ── 제어/상태(웹 UI·트레이용) ──────────────────────────────────────
    def request_stop(self) -> None:
        """실행 중인 에이전트에 정상 종료를 요청한다(웹 UI/트레이 중지 버튼)."""
        self._stop.set()

    def is_connected(self) -> bool:
        return self._conn.authed

    @property
    def image_ready(self) -> bool:
        return self._image_ready

    @property
    def models(self) -> list[str]:
        return list(self._models)

    def status_line(self) -> str:
        """트레이/콘솔용 한 줄 상태 요약."""
        conn = "연결됨" if self.is_connected() else "연결 끊김"
        img = " · 이미지" if self._image_ready else ""
        remaining = "무제한" if self._cfg.daily_limit == 0 else str(self._remaining)
        return f"{conn} · 처리 {self._processed} · 잔여 {remaining}{img}"

    def _start_tray(self) -> None:
        """데스크톱이면 트레이 아이콘을 띄운다(라이브 상태 + 중지). 헤드리스/미설치는 no-op."""
        from . import tray as _tray

        if not _tray.tray_available():
            logger.info("트레이 미지원 환경 — 콘솔 상태로 진행")
            return
        loop = asyncio.get_running_loop()

        def _on_quit() -> None:  # 트레이 스레드 → asyncio 루프로 안전 전달
            loop.call_soon_threadsafe(self._stop.set)

        _tray.run_tray(self.status_line, _on_quit)

    # ── 실행 ────────────────────────────────────────────────────────────
    async def run(self, install_signals: bool = True) -> int:
        if not self._models:
            try:
                self._models = await self._ollama.list_models()
                logger.info("감지된 모델: %s", self._models or "(없음)")
            except OllamaError as exc:
                logger.warning("Ollama 모델 목록 실패(%s) — 빈 목록으로 진행", exc)

        # SD 이미지 capability: opt-in + 런타임 health 로 확정.
        if self._sd is not None:
            self._image_ready = await self._sd.health()
            if self._image_ready:
                logger.info("이미지 생성(SD) 활성: %s", self._cfg.sd_url)
            else:
                logger.warning("SD(%s) 에 닿지 못해 이미지 capability 비활성", self._cfg.sd_url)

        # 웹 UI/트레이에서 같은 루프의 태스크로 돌릴 때는 시그널 핸들러를 설치하지 않는다.
        if install_signals:
            loop = asyncio.get_running_loop()
            for sig in (signal.SIGINT, signal.SIGTERM):
                try:
                    loop.add_signal_handler(sig, self._stop.set)
                except (NotImplementedError, AttributeError, ValueError):  # pragma: no cover - Windows 등
                    pass
            # 설정 hot-reload(#129): SIGHUP → 저장 설정에서 models 재적용.
            sighup = getattr(signal, "SIGHUP", None)
            if sighup is not None:
                try:
                    loop.add_signal_handler(sighup, self.reload_models)
                except (NotImplementedError, AttributeError, ValueError):  # pragma: no cover
                    pass

        if self._cfg.tray:
            self._start_tray()

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

    def reload_models(self) -> list[str]:
        """SIGHUP: 저장된 설정 파일에서 models 를 다시 읽어 적용(#129 hot-reload)."""
        from .config_file import load_config

        saved = load_config()
        models = saved.get("models")
        if models:
            self._models = list(models)
            logger.info("설정 hot-reload: models=%s", self._models)
        return self._models

    @property
    def processed(self) -> int:
        return self._processed


def run_agent(cfg: AgentConfig) -> int:
    # 단일 인스턴스: 이미 다른 에이전트(예: GUI 안에서 실행 중)가 연결돼 있으면 조용히 종료한다.
    # (둘이 같은 프로바이더로 동시에 붙으면 서버가 번갈아 끊어 핑퐁이 생긴다.)
    from . import singleton

    if not singleton.acquire():
        logger.warning("다른 에이전트 인스턴스가 이미 실행 중입니다 — 이 인스턴스는 종료합니다(중복 연결 방지).")
        return 0
    return asyncio.run(ProviderAgent(cfg).run())


async def _self_test(cfg: AgentConfig) -> int:
    """연결 전 자가 점검(차수 10): Ollama 도달·모델·추론 1회."""
    ollama = OllamaClient(cfg.ollama_url, cfg.request_timeout)
    if not await ollama.health():
        logger.error("❌ Ollama 연결 실패: %s", cfg.ollama_url)
        return 1
    models = await ollama.list_models()
    logger.info("✅ Ollama OK (%s) · 모델 %d개: %s", cfg.ollama_url, len(models), models)
    target = cfg.models[0] if cfg.models else (models[0] if models else None)
    if target:
        try:
            text, _ = await ollama.generate("ping", target)
            logger.info("✅ 추론 테스트 OK (model=%s): %r", target, text[:40])
        except OllamaError as exc:
            logger.error("⚠️ 추론 테스트 실패: %s", exc)
            return 1
    # 이미지(SD) opt-in 점검: 도달·1장 생성.
    if cfg.enable_image:
        from .sd import SDClient, SDError

        sd = SDClient(cfg.sd_url, cfg.request_timeout)
        if not await sd.health():
            logger.error("❌ SD 연결 실패: %s", cfg.sd_url)
            return 1
        try:
            img = await sd.txt2img("a small red circle", {"steps": 4, "width": 64, "height": 64})
            logger.info("✅ SD 이미지 생성 OK (%s): %d bytes(base64)", cfg.sd_url, len(img))
        except SDError as exc:
            logger.error("⚠️ SD 생성 테스트 실패: %s", exc)
            return 1
    return 0


def self_test(cfg: AgentConfig) -> int:
    return asyncio.run(_self_test(cfg))
