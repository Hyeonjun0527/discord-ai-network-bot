"""추론 처리 오케스트레이션 (차수 3).

서버가 보낸 infer 를 받아 localhost Ollama 로 처리하고 result/error 를 회신한다. 동시 처리 제한
(세마포어), 일일 한도, cancel 취소, 주기적 provider_status 보고, SIGINT graceful 종료.
"""
from __future__ import annotations

import asyncio
import logging
import signal
from collections import deque

from . import sysinfo
from .config import AgentConfig
from .connection import AgentConnection
from .constants import AGENT_VERSION, IMAGE_CHUNK_CHARS, MAX_RESPONSE_CHARS, ErrorCode
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


def _agent_sync_base(relay: str) -> str:
    """relay(wss://…/agent) → 중앙 서버 https 베이스(에이전트 동기화 엔드포인트용)."""
    base = relay.replace("wss://", "https://").replace("ws://", "http://")
    if base.endswith("/agent"):
        base = base[: -len("/agent")]
    return base.rstrip("/")


def _post_agent_sync(base: str, durable_token: str) -> list[dict]:
    """중앙 서버에 durable 토큰으로 자동 동기화 요청 → 승인된 미연결 서버의 일회용 토큰 목록."""
    import json
    import ssl
    import urllib.request

    import certifi

    ctx = ssl.create_default_context(cafile=certifi.where())
    body = json.dumps({"durableToken": durable_token}).encode("utf-8")
    # User-Agent 필수(WAF 가 기본 Python-urllib UA 를 403 으로 막는다).
    req = urllib.request.Request(
        base + "/provider/agent/sync",
        data=body,
        method="POST",
        headers={
            "Content-Type": "application/json",
            "Accept": "application/json",
            "User-Agent": f"nyassistant-agent/{AGENT_VERSION}",
        },
    )
    with urllib.request.urlopen(req, timeout=6, context=ctx) as resp:  # noqa: S310 - http(로컬)/https 고정
        return list(json.loads(resp.read().decode("utf-8")).get("joins") or [])


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
        self._sd_boot_task: asyncio.Task[None] | None = None  # 설치된 SD 자동기동 + 준비되면 재광고
        self._sem = asyncio.Semaphore(cfg.max_concurrency)
        self._remaining = cfg.daily_limit  # 0 = 무제한
        self._models: list[str] = list(cfg.models)
        self._inflight = 0
        self._processed = 0  # 누적 처리 건수(로컬 요약)
        # 취소 표시. 수신된 적 없는 cancel 이 무한히 쌓이지 않게 FIFO 로 상한(가장 오래된 것부터 폐기).
        self._cancelled: set[str] = set()
        self._cancel_order: deque[str] = deque()
        self._cancel_cap = 4096
        self._tasks: dict[str, asyncio.Task[None]] = {}
        self._stop = asyncio.Event()
        # 멀티-서버: 여러 디스코드 길드에 동시 접속. 각 항목 {conn, task, status_task, guild_id, guild_name, token}.
        # 공유 자원(ollama·세마포어·일일한도·모델)은 위에서 인스턴스 단위로 묶여 모든 연결이 함께 쓴다.
        self._entries: list[dict] = []
        self._entries_lock = asyncio.Lock()

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
            self._mark_cancelled(frame.request_id)
            existing = self._tasks.get(frame.request_id)
            if existing is not None:
                existing.cancel()

    def _mark_cancelled(self, request_id: str) -> None:
        """취소 표시(상한 FIFO). 수신 안 된 cancel 이 무한 누적되지 않게 가장 오래된 것부터 폐기."""
        if request_id in self._cancelled:
            return
        self._cancelled.add(request_id)
        self._cancel_order.append(request_id)
        while len(self._cancel_order) > self._cancel_cap:
            self._cancelled.discard(self._cancel_order.popleft())

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
        """하나라도 인증된 연결이 있으면 연결됨으로 본다(멀티-서버)."""
        return any(e["conn"].authed for e in self._entries)

    # ── 멀티-서버 연결 관리 ─────────────────────────────────────────────
    def _make_entry(self, token: str, guild_id: int | None, guild_name: str | None) -> dict:
        """토큰 1개에 대한 연결 엔트리 생성(공유 핸들러·hello 재사용, durable 토큰은 자기 엔트리에 저장)."""
        import dataclasses

        ccfg = dataclasses.replace(self._cfg, token=token)

        def _persist(new_token: str, gid: int | None = guild_id, old: str = token) -> None:
            try:
                from .config_file import set_connection_token

                set_connection_token(new_token, guild_id=gid, old_token=old)
            except Exception:  # noqa: BLE001
                pass

        conn = AgentConnection(ccfg, self._on_server_frame, self._build_hello, on_durable_token=_persist)
        return {"conn": conn, "task": None, "status_task": None,
                "guild_id": guild_id, "guild_name": guild_name, "token": token}

    def _spawn_entry(self, entry: dict) -> None:
        entry["task"] = asyncio.create_task(entry["conn"].run())
        entry["status_task"] = asyncio.create_task(self._status_loop(entry["conn"]))
        self._entries.append(entry)

    async def _stop_entry(self, entry: dict) -> None:
        try:
            await entry["conn"].stop()
        except Exception:  # noqa: BLE001
            pass
        for t in (entry["task"], entry["status_task"]):
            if t is not None:
                t.cancel()
        tasks = [t for t in (entry["task"], entry["status_task"]) if t is not None]
        if tasks:
            await asyncio.gather(*tasks, return_exceptions=True)

    async def add_connection(self, token: str, guild_id: int | None = None, guild_name: str | None = None) -> None:
        """실행 중에 서버 연결을 추가(같은 길드/토큰이면 교체). 저장 + 즉시 접속."""
        from .config_file import add_connection as _save

        _save(token, guild_id, guild_name)
        async with self._entries_lock:
            for e in list(self._entries):
                if (guild_id is not None and e["guild_id"] == guild_id) or e["token"] == token:
                    await self._stop_entry(e)
                    self._entries.remove(e)
            self._spawn_entry(self._make_entry(token, guild_id, guild_name))

    async def remove_connection(self, guild_id: int | None = None, token: str | None = None) -> bool:
        """실행 중에 서버 연결을 해제(길드ID 우선). 저장 + 즉시 끊기."""
        from .config_file import remove_connection as _save

        _save(guild_id=guild_id, token=token)
        removed = False
        async with self._entries_lock:
            for e in list(self._entries):
                if (guild_id is not None and e["guild_id"] == guild_id) or (token is not None and e["token"] == token):
                    await self._stop_entry(e)
                    self._entries.remove(e)
                    removed = True
        return removed

    async def rename_connection(self, index: int, name: str | None) -> bool:
        """index 번째 연결의 표시 이름을 바꾼다(토큰-추가 '이름 미상' 라벨링)."""
        from .config_file import rename_connection as _save

        label = (name or "").strip() or None
        _save(index, label)
        async with self._entries_lock:
            if 0 <= index < len(self._entries):
                self._entries[index]["guild_name"] = label
                return True
        return False

    async def remove_connection_at(self, index: int) -> bool:
        """index 번째 연결을 해제(길드ID 없는 토큰-추가 연결도 정확히 지목)."""
        from .config_file import remove_connection_at as _save

        async with self._entries_lock:
            if 0 <= index < len(self._entries):
                e = self._entries.pop(index)
                await self._stop_entry(e)
                _save(index)  # 엔트리·config 가 같은 순서라 같은 index
                return True
        return False

    def connections_status(self) -> list[dict]:
        """GUI '내 서버 목록'용: 연결별 index/길드/연결상태(토큰은 노출하지 않음)."""
        return [
            {"index": i, "guildId": e["guild_id"], "guildName": e["guild_name"], "connected": e["conn"].authed}
            for i, e in enumerate(self._entries)
        ]

    # ── 자동 동기화: 연동된 사용자가 디스코드 /프로바이더참여 한 새 서버에 앱이 스스로 연결 ──────
    async def _sync_joins_once(self) -> None:
        """durable 토큰(=연동 신원)으로 중앙 서버에 묻고, 승인됐지만 아직 연결 안 된 서버에 자동 연결한다."""
        from .config_file import load_connections

        durable = next(
            (c.get("token") or "" for c in load_connections() if (c.get("token") or "").startswith("dv1.")),
            "",
        )
        if not durable:
            return  # durable 토큰이 아직 없으면(미연동) 동기화 불가 — 디스코드는 가이드를 준다.
        base = _agent_sync_base(self._cfg.relay_url)
        try:
            joins = await asyncio.to_thread(_post_agent_sync, base, durable)
        except Exception:  # noqa: BLE001 - 네트워크/서버 실패는 다음 주기에 재시도
            return
        existing = {e["guildId"] for e in self.connections_status() if e.get("guildId") is not None}
        for jn in joins:
            gid = jn.get("guildId")
            token = jn.get("token")
            if not token or gid is None or gid in existing:
                continue
            logger.info("새 서버 자동 참여(동기화): guild=%s", gid)
            await self.add_connection(token, gid, jn.get("guildName"))

    async def _sync_loop(self, interval_s: float = 45.0) -> None:
        """첫 연결 직후 한 번, 그 뒤 주기적으로 자동 참여 동기화. stop 요청 시 종료."""
        await asyncio.sleep(5.0)
        while not self._stop.is_set():
            await self._sync_joins_once()
            try:
                await asyncio.wait_for(self._stop.wait(), timeout=interval_s)
            except asyncio.TimeoutError:
                pass

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
        # 선택한 모델만 제공한다. 아무것도 선택하지 않았으면 자동으로 전체를 제공하지 않는다
        # (예전의 "빈 목록 → 전체 자동감지" 폴백 제거 — 사용자가 고른 모델만 광고·라우팅).
        if not self._models:
            logger.warning(
                "제공할 모델이 선택되지 않았습니다 — 텍스트 요청을 제공하지 않습니다"
                "(앱 ‘제공 모델’에서 1개 이상 선택하세요)."
            )

        # SD 이미지 capability: opt-in + 런타임 health 로 확정.
        if self._sd is not None:
            self._image_ready = await self._sd.health()
            if self._image_ready:
                logger.info("이미지 생성(SD) 활성: %s", self._cfg.sd_url)
            else:
                logger.warning("SD(%s) 미연결 — 설치돼 있으면 자동 기동을 시도하고, 준비되면 이미지 capability 를 재광고합니다", self._cfg.sd_url)

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

        # 저장된 모든 서버 연결을 동시에 띄운다(없으면 cfg.token 하나). 연결 하나가 죽어도(인증실패 등)
        # 나머지는 유지 — run 은 stop 요청까지만 대기한다.
        from .config_file import load_connections

        saved = load_connections()
        if not saved and self._cfg.token:
            saved = [{"token": self._cfg.token, "guild_id": None, "guild_name": None}]
        async with self._entries_lock:
            for s in saved:
                self._spawn_entry(self._make_entry(s["token"], s.get("guild_id"), s.get("guild_name")))

        # 이미지 opt-in 인데 SD 가 안 떠 있으면(예: 재부팅 후) 설치돼 있을 때 자동으로 SD 를 띄운다.
        # 텍스트 연결은 막지 않고 백그라운드로 기동 → 준비되면 image capability 를 재광고(재연결).
        if self._sd is not None and not self._image_ready:
            self._sd_boot_task = asyncio.create_task(self._boot_sd())

        # 연동된 사용자가 디스코드 /프로바이더참여 한 새 서버에 자동 연결되도록 동기화 루프를 띄운다.
        sync_task = asyncio.create_task(self._sync_loop())
        stop_task = asyncio.create_task(self._stop.wait())
        try:
            await stop_task
        finally:
            sync_task.cancel()
            if self._sd_boot_task is not None:
                self._sd_boot_task.cancel()
            async with self._entries_lock:
                entries = list(self._entries)
                self._entries.clear()
            for e in entries:
                await self._stop_entry(e)
            stop_task.cancel()
        logger.info("에이전트 종료")
        return 0

    async def _boot_sd(self) -> None:
        """설치된 SD 가 꺼져 있으면 자동으로 띄우고, 준비되면 image capability 를 재광고한다.

        capability 는 연결 시점 hello 로만 광고되므로, SD 가 늦게 떠도 풀에 반영되려면 재연결이
        필요하다. 텍스트 연결을 막지 않도록 백그라운드 태스크로 돌린다(재부팅 후 무인 복구 핵심).
        """
        from . import sd_setup

        if not sd_setup.is_installed() or sd_setup.is_busy():
            return
        logger.info("SD 가 꺼져 있어 자동 기동을 시도합니다(설치돼 있음)…")
        try:
            ok = await sd_setup.launch_only(self._cfg.sd_url)
        except asyncio.CancelledError:
            raise
        except Exception as exc:  # noqa: BLE001 - 자동 기동 실패는 에이전트를 멈추지 않는다
            logger.warning("SD 자동 기동 실패: %s", exc)
            return
        if not ok or self._sd is None:
            return
        self._image_ready = await self._sd.health()
        if self._image_ready and not self._stop.is_set():
            logger.info("SD 준비 완료 — 이미지 capability 재광고(재연결)")
            await self._readvertise()

    async def _readvertise(self) -> None:
        """capability 변경(예: SD 준비됨)을 라이브 세션에 반영: 모든 연결을 재접속(새 hello)."""
        async with self._entries_lock:
            entries = list(self._entries)
            self._entries.clear()
        for e in entries:
            await self._stop_entry(e)
        if self._stop.is_set():
            return
        async with self._entries_lock:
            for e in entries:
                self._spawn_entry(self._make_entry(e["token"], e["guild_id"], e["guild_name"]))

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
    # 헤드리스(자동실행 서비스 등)도 주기적으로 새 버전을 받아 헤드리스로 교체·재실행한다
    # (GUI 워처와 별개 — 창 없이 도는 서비스가 껐다 켜야만 업데이트되던 문제 해소).
    if cfg.auto_update:
        from . import updater

        updater.start_service_update_watcher()
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
