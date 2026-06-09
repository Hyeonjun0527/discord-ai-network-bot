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

# 이미지 생성 중 진행률 청크 전송 간격(초). 디스코드 메시지 편집 레이트리밋을 고려해 너무 잦지 않게.
SD_PROGRESS_POLL_S = 1.5
# 진행률 추정 점근 곡선의 반감기(초): pct = 95·t/(t+HALF). 이 값에서 약 47%.
# 생성 중 SD 를 폴링하면 MPS 가 크래시하므로(아래 _handle_image 주석) 진행률은 경과시간으로만 추정한다.
SD_PROGRESS_HALFLIFE_S = 6.0


def _merge_models(base: list[str], extra: list[str]) -> list[str]:
    """광고 모델 목록 = 로컬(Ollama) 선택 + 클라우드(Gemini), 순서 보존·중복 제거."""
    return list(dict.fromkeys([*base, *extra]))


def _agent_sync_base(relay: str) -> str:
    """relay(wss://…/agent) → 중앙 서버 https 베이스(에이전트 동기화 엔드포인트용)."""
    base = relay.replace("wss://", "https://").replace("ws://", "http://")
    if base.endswith("/agent"):
        base = base[: -len("/agent")]
    return base.rstrip("/")


def _central_post(url: str, payload: dict, timeout: float = 8.0) -> dict:
    """중앙 서버에 JSON POST 후 응답 dict 반환 — SSL(certifi)·헤더·직렬화·파싱 공통(7개 admin/sync 호출 공유).

    User-Agent 필수(WAF 가 기본 Python-urllib UA 를 403 으로 막는다). 아래 7개 래퍼는 URL·payload·timeout 만 달라진다.
    """
    import json
    import ssl
    import urllib.request

    import certifi

    ctx = ssl.create_default_context(cafile=certifi.where())
    body = json.dumps(payload).encode("utf-8")
    req = urllib.request.Request(
        url,
        data=body,
        method="POST",
        headers={"Content-Type": "application/json", "Accept": "application/json", "User-Agent": f"nexa-agent/{AGENT_VERSION}"},
    )
    with urllib.request.urlopen(req, timeout=timeout, context=ctx) as resp:  # noqa: S310 - http(로컬)/https 고정
        return dict(json.loads(resp.read().decode("utf-8")))


def _post_agent_sync(base: str, durable_token: str) -> list[dict]:
    """중앙 서버에 durable 토큰으로 자동 동기화 요청 → 승인된 미연결 서버의 일회용 토큰 목록."""
    resp = _central_post(base + "/provider/agent/sync", {"durableToken": durable_token}, timeout=6)
    return list(resp.get("joins") or [])


def _post_provider_admin(base: str, action: str, durable_token: str, guild_id: int, target_id: int = 0) -> dict:
    """중앙 서버 관리 API 호출(관리자 전용). durable 토큰으로 신원 인증 → central 이 관리자 판정 후 동작.

    action: 'manage'(승인대기·로스터 조회) | 'approve' | 'reject' | 'remove'. 권한은 central 이 JDA 로 판정한다.
    """
    return _central_post(
        base + "/provider/admin/" + action,
        {"durableToken": durable_token, "guildId": guild_id, "targetProviderId": target_id},
    )


def _post_provider_admin_policy(base: str, durable_token: str, guild_id: int, auto_approve: bool) -> dict:
    """서버 제공 정책(신규 자동 승인) 토글 — central 이 관리자 판정 후 저장."""
    return _central_post(
        base + "/provider/admin/manage/policy",
        {"durableToken": durable_token, "guildId": guild_id, "autoApprove": auto_approve},
    )


def _post_provider_admin_promptset(
    base: str,
    path: str,
    durable_token: str,
    guild_id: int,
    *,
    name: str = "",
    content: str = "",
    set_id: str = "",
) -> dict:
    """전역 프롬프트셋(서버 전체 기본 AI 성격) 관리 API 호출(관리자 전용).

    path: ''(목록) | '/add'(추가) | '/default'(기본 지정) | '/delete'(삭제). 권한은 central 이 JDA 로 판정한다.
    builtin(니아) 셋의 전문은 central 이 응답에 담지 않는다(preview 만).
    """
    return _central_post(
        base + "/provider/admin/prompt-sets" + path,
        {"durableToken": durable_token, "guildId": guild_id, "name": name, "content": content, "id": set_id},
    )


def _post_provider_admin_guild(base: str, path: str, durable_token: str, guild_id: int) -> dict:
    """길드 단위 읽기 관리 API 호출(관리자 전용). path: channel-ai|knowledge|presets. body 는 신원+길드만."""
    return _central_post(base + "/provider/admin/" + path, {"durableToken": durable_token, "guildId": guild_id})


def _post_provider_admin_delete(base: str, path: str, durable_token: str, guild_id: int, id_key: str, id_val: str) -> dict:
    """관리 삭제 공통(프리셋/지식 소스). central 이 durable 토큰 신원 + 길드 소유권을 가드한다."""
    return _central_post(base + path, {"durableToken": durable_token, "guildId": guild_id, id_key: str(id_val)})


def _post_provider_admin_channels(
    base: str,
    path: str,
    durable_token: str,
    guild_id: int,
    *,
    channel_id: int = 0,
    allow: bool = True,
) -> dict:
    """채널 AI 허용 관리 API 호출(관리자 전용). path: ''(목록) | '/toggle'(허용/금지).

    channel_id 는 64bit Discord ID — Python int 는 정밀도 손실이 없으므로 그대로 전송한다.
    권한은 central 이 JDA 로 판정하고, 허용 목록 "빈 목록 = 전체 허용" 의미도 central 이 보존한다.
    """
    return _central_post(
        base + "/provider/admin/channels" + path,
        {"durableToken": durable_token, "guildId": guild_id, "channelId": channel_id, "allow": allow},
    )


class ProviderAgent:
    def __init__(self, cfg: AgentConfig, ollama: OllamaClient | None = None, sd=None) -> None:
        self._cfg = cfg
        self._ollama = ollama or OllamaClient(cfg.ollama_url, cfg.request_timeout)
        # 이미지 엔진 = **ComfyUI 전용**(SD.Next 는 제거됨 — 유지보수 중단된 레거시). 우선순위:
        #   ① 유저가 직접 띄운 외부 ComfyUI(per-user 설정의 comfy_url) > ② 앱이 관리하는 로컬 ComfyUI(localhost:8188).
        # txt2img/health 인터페이스(덕타이핑)라 _handle_image 는 백엔드와 무관하게 동일하게 동작한다.
        from . import comfy_setup

        # 외부 comfy_url 이 있으면 그것(유저 로컬에서 직접 실행), 없으면 앱이 관리하는 ComfyUI(localhost:8188).
        # 설치/실행 전이면 health=False 라 이미지 미광고(자동 기동은 _boot_sd).
        self._comfy_url = cfg.comfy_url or comfy_setup.webui_url()
        self._image_backend = "comfyui"
        if sd is not None:
            self._sd = sd
        elif cfg.enable_image:
            from .comfy import ComfyClient

            self._sd = ComfyClient(self._comfy_url, cfg.request_timeout)
        else:
            self._sd = None
        self._image_ready = False
        self._sd_wh: tuple[int, int] | None = None  # 활성 체크포인트 기준 생성 해상도(lazy 캐시, 모델 변경 시 무효화)
        self._sd_boot_task: asyncio.Task[None] | None = None  # 설치된 SD 자동기동 + 준비되면 재광고
        self._sem = asyncio.Semaphore(cfg.max_concurrency)
        # 동시 처리 상한도 **서버(guild)별**로 독립 적용. 길드 전용 세마포어(lazy).
        # 전역 self._sem 은 머신 전체 보호, 여기 세마포어는 그 서버 1곳의 동시 처리 상한.
        # 정책 변경(set_guild_policy) 시 해당 항목을 폐기 → 새 상한으로 재생성.
        self._guild_sems: dict[int | None, asyncio.Semaphore] = {}
        # 일일 한도는 **서버(guild)별로 독립** 적용한다. 한 에이전트가 여러 길드에 제공해도
        # 길드마다 한도만큼 따로 카운트(전역 합산 공유 X). 0 = 무제한(dict 미사용).
        # 키 = guild_id(None = 길드 미상 토큰 연결 폴백). lazy init: 첫 접근 시 그 길드 한도로 채움.
        self._remaining_by_guild: dict[int | None, int] = {}
        # 서버별 정책 override {guild_id: {daily_limit, …}} — 데스크톱 앱 G3 가 설정. run() 에서 로드.
        # 없는 길드는 전역 기본(cfg.daily_limit)을 쓴다.
        self._guild_policy: dict[int, dict] = {}
        # 클라우드 Gemini 백엔드(관리자 키 1개로 서버 전체 제공). 키 있으면 gemini 모델을 풀에 광고하고
        # gemini-* 모델 요청을 Gemini API 로 라우팅한다(Ollama 와 동일 한도·공정성). 키는 이 PC 에만.
        self._gemini = None
        self._gemini_models: list[str] = []
        if cfg.gemini_api_key:
            from .gemini import GeminiClient

            self._gemini = GeminiClient(cfg.gemini_api_key, cfg.request_timeout)
            self._gemini_models = list(cfg.gemini_models)
        self._models: list[str] = _merge_models(list(cfg.models), self._gemini_models)
        self._default_model: str = (cfg.default_model or "").strip()
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

    # ── 일일 한도(서버별) ───────────────────────────────────────────────
    def _limit_for(self, guild_id: int | None) -> int:
        """이 길드에 적용할 일일 한도. 서버별 override(G3) 가 있으면 그 값, 없으면 전역 기본."""
        if guild_id is not None:
            pol = self._guild_policy.get(guild_id)
            if pol is not None and pol.get("daily_limit") is not None:
                return max(0, int(pol["daily_limit"]))
        return self._cfg.daily_limit

    def _remaining_for(self, guild_id: int | None) -> int:
        """이 길드의 남은 일일 한도. 무제한이면 0(센티넬). 첫 접근 시 그 길드 한도로 init."""
        limit = self._limit_for(guild_id)
        if limit <= 0:
            return 0  # 무제한(hello 의 remaining=0 은 '한도 없음'을 뜻함)
        if guild_id not in self._remaining_by_guild:
            self._remaining_by_guild[guild_id] = limit
        return self._remaining_by_guild[guild_id]

    # ── 동시 처리·최대 시간(서버별) ──────────────────────────────────────
    def _concurrency_for(self, guild_id: int | None) -> int:
        """이 길드에 적용할 동시 처리 상한. 서버별 override(G3) 우선, 없으면 전역."""
        if guild_id is not None:
            pol = self._guild_policy.get(guild_id)
            if pol is not None and pol.get("max_concurrency") is not None:
                return max(1, int(pol["max_concurrency"]))
        return self._cfg.max_concurrency

    def _guild_sem(self, guild_id: int | None) -> asyncio.Semaphore:
        """이 길드 전용 동시성 세마포어(lazy). 정책 변경 시 set_guild_policy 에서 폐기→재생성."""
        sem = self._guild_sems.get(guild_id)
        if sem is None:
            sem = asyncio.Semaphore(self._concurrency_for(guild_id))
            self._guild_sems[guild_id] = sem
        return sem

    def _max_seconds_for(self, guild_id: int | None) -> float:
        """이 길드 1건 최대 처리 시간(초). 서버별 override 만 적용(없으면 0 = 추가 상한 없음)."""
        if guild_id is not None:
            pol = self._guild_policy.get(guild_id)
            if pol is not None and pol.get("max_seconds") is not None:
                return max(0.0, float(pol["max_seconds"]))
        return 0.0

    # ── 핸드셰이크 ──────────────────────────────────────────────────────
    def _models_for(self, guild_id: int | None) -> list[str]:
        """이 길드에 광고할 채팅 모델. 길드 정책 chatModels override 가 있으면 그것(현재 제공 가능한 것만),
        없거나 비면 전체(self._models). 서버별로 어떤 모델을 줄지 관리자가 고를 수 있게 한다."""
        if guild_id is not None:
            sel = self._guild_policy.get(guild_id, {}).get("chatModels")
            if isinstance(sel, list) and sel:
                picked = [m for m in sel if m in self._models]
                if picked:
                    return picked
        return self._models

    def _image_for(self, guild_id: int | None) -> bool:
        """이 길드에 이미지(SD) capability 를 광고할지. SD 준비됨 + 길드가 명시 비활성(imageEnabled=False)이 아님."""
        if not self._image_ready:
            return False
        return guild_id is None or self._guild_policy.get(guild_id, {}).get("imageEnabled") is not False

    def _build_hello(self, guild_id: int | None = None) -> ProviderHelloFrame:
        capabilities = ["text"]
        if self._image_for(guild_id):
            capabilities.append("image")
        return ProviderHelloFrame(
            models=self._models_for(guild_id),
            max_concurrency=self._concurrency_for(guild_id),
            remaining_daily_requests=self._remaining_for(guild_id),
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
        # 한도는 **이 연결의 guild 별로 독립** — 다른 서버의 소진이 이 서버에 영향 주지 않는다.
        # 한도값도 서버별(override 우선). 0 = 그 서버 무제한.
        guild_id = conn.guild_id
        # 이 서버에 대한 내 제공 일시중지(provider 주권) — 연결은 유지하되 이 길드 요청만 반려.
        if guild_id is not None and self._guild_policy.get(guild_id, {}).get("paused"):
            await self._safe_send(conn, InferError(req.request_id, code=ErrorCode.BUSY, message="이 서버 제공 일시중지됨"))
            return
        limit = self._limit_for(guild_id)
        if limit > 0 and self._remaining_for(guild_id) <= 0:
            await self._safe_send(conn, InferError(req.request_id, code=ErrorCode.BUSY, message="일일 한도 초과"))
            return
        # 자원 보호: CPU 고부하·배터리 방전 중에는 자동 pause(BUSY 로 반려, 한도 소모 안 함).
        paused, reason = sysinfo.should_pause(self._cfg.pause_on_battery, self._cfg.pause_on_high_load)
        if paused:
            logger.info("자원 보호로 일시 중지(%s) — 요청 반려", reason)
            await self._safe_send(conn, InferError(req.request_id, code=ErrorCode.BUSY, message=f"자원 보호 일시중지({reason})"))
            return
        # 동시 처리 상한도 **서버별** 강제: 이 길드 전용 세마포어로 게이트(전역 self._sem 은 머신 보호).
        # 최대 처리 시간(서버별)은 wait_for 로 1건씩 강제 — 넘으면 중단·반려(한도/자원 보호).
        guild_sem = self._guild_sem(guild_id)
        max_seconds = self._max_seconds_for(guild_id)
        async with guild_sem, self._sem:
            if req.request_id in self._cancelled:
                self._cancelled.discard(req.request_id)
                return
            self._inflight += 1
            if limit > 0:
                self._remaining_by_guild[guild_id] = self._remaining_for(guild_id) - 1
            # 서버가 모델을 지정하지 않으면 '기본 응답 모델'로 처리한다(설정값이 제공 중이면 그것, 아니면 첫 모델).
            model = req.model or self._preferred_model()
            try:
                if max_seconds > 0:
                    await asyncio.wait_for(self._run_infer(conn, req, model), timeout=max_seconds)
                else:
                    await self._run_infer(conn, req, model)
            except asyncio.TimeoutError:
                logger.info("요청 %s… 최대 처리 시간(%ss) 초과 — 중단", req.request_id[:8], int(max_seconds))
                await self._safe_send(
                    conn, InferError(req.request_id, code=ErrorCode.OLLAMA_ERROR, message=f"최대 처리 시간({int(max_seconds)}s) 초과")
                )
            except OllamaError as exc:
                # 서버측 진단을 위해 모델·요청 맥락과 함께 남긴다(사용자에겐 InferError 로 전달, 예외 원칙 4).
                logger.warning("Ollama 오류(model=%s, request=%s): %s", model, req.request_id[:8], exc)
                await self._safe_send(conn, InferError(req.request_id, code=ErrorCode.OLLAMA_ERROR, message=str(exc)))
            except asyncio.CancelledError:
                logger.info("요청 %s… 취소됨", req.request_id[:8])
                raise
            finally:
                self._inflight -= 1

    async def _run_infer(self, conn: AgentConnection, req: InferRequest, model: str | None) -> None:
        """실제 추론 처리(image/stream/text). 최대 처리 시간(max_seconds)은 호출부가 wait_for 로 강제."""
        if req.task == "image":
            # 로컬 SD 이미지 생성(SD Phase 2). 미지원이면 에러.
            if self._sd is None or not self._image_ready:
                await self._safe_send(
                    conn, InferError(req.request_id, code=ErrorCode.OLLAMA_ERROR, message="이미지 생성을 지원하지 않는 프로바이더입니다")
                )
            else:
                await self._handle_image(conn, req)
                self._processed += 1
        elif self._gemini is not None and (model or "").startswith("gemini-"):
            # 클라우드 Gemini 라우팅(관리자 키). gemini-* 모델은 Gemini API 로 처리(한도·공정성은 동일).
            await self._run_gemini(conn, req, model)
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

    async def _run_gemini(self, conn: AgentConnection, req: InferRequest, model: str | None) -> None:
        """클라우드 Gemini 로 텍스트 추론(관리자 키). 스트림 요청도 한 번에 받아 청크로 흘려보낸다."""
        from .gemini import GeminiError

        assert self._gemini is not None
        try:
            text, usage = await self._gemini.generate(req.prompt, model)
        except GeminiError as exc:
            await self._safe_send(conn, InferError(req.request_id, code=ErrorCode.OLLAMA_ERROR, message=str(exc)))
            return
        if len(text) > MAX_RESPONSE_CHARS:
            text = text[:MAX_RESPONSE_CHARS]
        self._processed += 1
        if req.stream:
            for i in range(0, len(text), IMAGE_CHUNK_CHARS):
                await self._safe_send(conn, ChunkFrame(req.request_id, delta=text[i : i + IMAGE_CHUNK_CHARS], done=False))
            await self._safe_send(conn, ChunkFrame(req.request_id, delta="", done=True))
        else:
            await self._safe_send(conn, InferResult(req.request_id, text=text, usage=usage))

    async def _handle_image(self, conn: AgentConnection, req: InferRequest) -> None:
        """로컬 SD 로 이미지를 생성해 base64 PNG 를 ChunkFrame 으로 분할 전송(SD Phase 2).

        ⚠️ 생성 중에는 SD 에 **어떤 동시 요청도 보내지 않는다**. PyTorch MPS(Apple Silicon)는
        생성 중 다른 스레드의 접근에 thread-safe 하지 않아, /sdapi/v1/progress 같은 동시 폴링이
        Metal command encoder 경합을 일으켜 드라이버 세그폴트(AGXMetal SIGSEGV)로 SD 프로세스를
        죽인다(실증: 폴링 시 gen#0 즉시 크래시 vs 폴링 제거 시 순차 3/3 생존). 그래서 진행률은
        SD 를 건드리지 않는 **경과시간 기반 추정**(_emit_estimated_progress)으로만 보낸다.
        SD 가 생성 도중 죽으면 1회 재기동·재시도한다(_generate_image_with_retry).
        """
        b64 = await self._generate_image_with_retry(conn, req)
        if b64 is None:
            return  # 에러는 헬퍼가 이미 전송
        # 1MB 프레임 한계 때문에 base64 를 조각내어 보낸다. 마지막에 done=True(빈 delta).
        for i in range(0, len(b64), IMAGE_CHUNK_CHARS):
            await self._safe_send(conn, ChunkFrame(req.request_id, delta=b64[i : i + IMAGE_CHUNK_CHARS], done=False))
        await self._safe_send(conn, ChunkFrame(req.request_id, delta="", done=True))

    async def _generate_image_with_retry(self, conn: AgentConnection, req: InferRequest) -> str | None:
        """txt2img 를 진행률 추정과 함께 실행. ComfyUI 가 생성 중 죽으면 1회 재기동·재시도.
        성공 시 base64 PNG, 실패 시 InferError 송신 후 None."""
        from .comfy import ComfyError

        w, h = await self._resolution()  # 생성 직전(비동시) 1회 — SDXL 1024 vs SD1.5 512
        for attempt in range(2):  # 원샷 + 1회 재시도(MPS 가 드물게 크래시할 때 사용자에게 이미지를 돌려준다)
            assert self._sd is not None  # 호출 전 _image_ready 로 가드됨
            gen_task: asyncio.Task[str] = asyncio.create_task(self._sd.txt2img(req.prompt, {"width": w, "height": h}))
            prog_task = asyncio.create_task(self._emit_estimated_progress(conn, req.request_id, gen_task))
            try:
                return await gen_task
            except ComfyError as exc:
                if attempt == 0 and await self._recover_sd():
                    logger.warning("SD 가 생성 중 종료된 듯 — 재기동 후 1회 재시도: %s", exc)
                    continue
                await self._safe_send(conn, InferError(req.request_id, code=ErrorCode.OLLAMA_ERROR, message=str(exc)))
                return None
            finally:
                prog_task.cancel()
        return None

    async def _resolution(self) -> tuple[int, int]:
        """활성 체크포인트에 맞는 생성 해상도(SDXL 1024 vs SD1.5 512). 첫 조회 후 캐시.

        **생성 시작 전(비동시)에만 호출**한다 — 생성 중 SD 조회는 MPS 크래시를 부른다(_handle_image 주석).
        모델 변경 시 _invalidate_resolution() 으로 캐시를 비운다.
        """
        if self._sd_wh is not None:
            return self._sd_wh
        from . import sd_setup

        wh = (512, 512)
        try:
            ckpt = await self._sd.current_checkpoint() if self._sd is not None else None
            wh = sd_setup.resolution_for_checkpoint(ckpt)
        except Exception as exc:  # noqa: BLE001 - 조회 실패는 기본 해상도로 폴백(비치명적)
            logger.debug("활성 체크포인트 조회 실패 — 512 폴백: %s", exc)
        self._sd_wh = wh
        return wh

    def _invalidate_resolution(self) -> None:
        """모델이 바뀔 수 있는 시점(SD 재기동/재광고)에 해상도 캐시를 비운다."""
        self._sd_wh = None

    async def _recover_sd(self) -> bool:
        """이미지 백엔드(ComfyUI)가 죽었으면 재기동·재확인(생성 재시도 직전). 성공 True.
        앱이 관리하는 ComfyUI 면 재기동 시도, 외부 URL(직접 띄운 인스턴스)이면 health 만 재확인."""
        if self._sd is None:
            return False
        try:
            if await self._sd.health():
                return True  # 이미 살아있음(일시적 네트워크 오류였음)
            from . import comfy_setup

            if comfy_setup.is_installed() and await comfy_setup.start() and await self._sd.health():
                self._invalidate_resolution()
                return True
            return False
        except Exception as exc:  # noqa: BLE001 - 재기동 실패는 비치명적(원 에러를 사용자에게 전달)
            logger.warning("ComfyUI 재기동 실패: %s", exc)
            return False

    async def _emit_estimated_progress(self, conn: AgentConnection, request_id: str, gen_task: "asyncio.Task[str]") -> None:
        """생성이 끝날 때까지 **경과시간 기반 추정** 진행률을 progress 청크로 보낸다(SD 미조회).

        SD 를 폴링하면 MPS 크래시가 나므로 절대 호출하지 않는다(_handle_image 주석 참고).
        점근 곡선 pct=95·t/(t+HALF) 로 0→95% 까지만(완료는 done 청크가 알린다). 하드웨어마다
        속도가 달라 정확치는 아니지만 '생성이 진행 중'임을 정직하게 보여준다.
        """
        loop = asyncio.get_event_loop()
        start = loop.time()
        last = -1
        try:
            while not gen_task.done():
                await asyncio.sleep(SD_PROGRESS_POLL_S)
                if gen_task.done():
                    break
                elapsed = loop.time() - start
                pct = int(95 * elapsed / (elapsed + SD_PROGRESS_HALFLIFE_S))
                if pct > last and 0 < pct < 100:
                    last = pct
                    await self._safe_send(conn, ChunkFrame(request_id, delta="", done=False, progress=pct))
        except asyncio.CancelledError:
            return

    async def _safe_send(self, conn: AgentConnection, frame: Frame) -> None:
        try:
            await conn.send(frame)
        except Exception as exc:  # noqa: BLE001
            # 프레임 타입·길드 맥락을 남겨 어떤 응답이 유실됐는지 추적 가능하게 한다(예외 원칙 4).
            logger.debug("응답 송신 실패(연결 끊김?) frame=%s guild=%s: %s", type(frame).__name__, conn.guild_id, exc)

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
                # 로컬 상태 콘솔(차수 10): 처리/대기/잔여 요약(서버별 한도라 잔여는 길드별로 표시)
                logger.info("상태: 처리 %d · 진행중 %d · 일일잔여 %s", self._processed, self._inflight, self._remaining_summary())
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
            except Exception as exc:  # noqa: BLE001
                # 토큰 저장 실패를 삼키면 다음 재연결 인증이 조용히 깨진다 — 최소한 남긴다(예외 원칙 3·4).
                logger.warning("durable 토큰 저장 실패(guild=%s) — 다음 재연결에 영향 가능: %s", gid, exc)

        entry: dict = {"conn": None, "task": None, "status_task": None,
                       "guild_id": guild_id, "guild_name": guild_name, "token": token}

        def _on_guild_info(gid: int | None, gname: str | None) -> None:
            # auth_ok 가 내려준 길드 정보로 엔트리 갱신(토큰-연결도 서버명 자동 표시).
            if gid is not None:
                entry["guild_id"] = gid
            if gname:
                entry["guild_name"] = gname

        conn = AgentConnection(
            ccfg, self._on_server_frame, self._build_hello,
            on_durable_token=_persist, on_guild_info=_on_guild_info,
        )
        entry["conn"] = conn
        return entry

    def _spawn_entry(self, entry: dict) -> None:
        entry["task"] = asyncio.create_task(entry["conn"].run())
        entry["status_task"] = asyncio.create_task(self._status_loop(entry["conn"]))
        self._entries.append(entry)

    async def _stop_entry(self, entry: dict) -> None:
        try:
            await entry["conn"].stop()
        except Exception as exc:  # noqa: BLE001
            logger.debug("연결 종료 실패(guild=%s): %s", entry.get("guild_id"), exc)
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
        """GUI '내 서버 목록'용: 연결별 index/길드/연결상태(토큰은 노출하지 않음).

        guildId 는 64bit Discord ID — JS number 는 2^53 초과에서 정밀도가 깨지므로 **문자열**로 내보낸다
        (관리 API URL 이 잘못된 길드를 가리키는 버그 방지). 길드 미상(토큰만 연결)이면 None.
        """
        return [
            {
                "index": i,
                "guildId": (str(e["guild_id"]) if e["guild_id"] is not None else None),
                "guildName": e["guild_name"],
                "connected": e["conn"].authed,
                "paused": bool(self._guild_policy.get(e["guild_id"], {}).get("paused")),
            }
            for i, e in enumerate(self._entries)
        ]

    # ── 서버 관리(관리자) — central 관리 채널 호출. durable 토큰으로 신원, 권한은 central 이 판정 ──
    def _durable_token(self) -> str:
        """저장된 durable(dv1.) 토큰. 관리 API 신원 인증용. 없으면 빈 문자열(미연동)."""
        from .config_file import load_connections

        return next((c.get("token") or "" for c in load_connections() if (c.get("token") or "").startswith("dv1.")), "")

    async def admin_manage(self, guild_id: int) -> dict:
        """이 서버의 승인 대기·로스터 조회(관리자). 권한 없으면 central 이 ok=False 반환."""
        dt = self._durable_token()
        if not dt:
            return {"ok": False, "error": "연동된 신원이 없어요(durable 토큰 없음)"}
        base = _agent_sync_base(self._cfg.relay_url)
        return await asyncio.to_thread(_post_provider_admin, base, "manage", dt, guild_id, 0)

    async def admin_action(self, action: str, guild_id: int, target_provider_id: int) -> dict:
        """Provider 승인/거절/제거(관리자). action: approve|reject|remove."""
        if action not in ("approve", "reject", "remove"):
            return {"ok": False, "error": "알 수 없는 작업"}
        dt = self._durable_token()
        if not dt:
            return {"ok": False, "error": "연동된 신원이 없어요(durable 토큰 없음)"}
        base = _agent_sync_base(self._cfg.relay_url)
        return await asyncio.to_thread(_post_provider_admin, base, action, dt, guild_id, target_provider_id)

    async def admin_set_policy(self, guild_id: int, auto_approve: bool) -> dict:
        """서버 제공 정책 — 신규 자동 승인 토글(관리자)."""
        dt = self._durable_token()
        if not dt:
            return {"ok": False, "error": "연동된 신원이 없어요(durable 토큰 없음)"}
        base = _agent_sync_base(self._cfg.relay_url)
        return await asyncio.to_thread(_post_provider_admin_policy, base, dt, guild_id, auto_approve)

    # ── 전역 프롬프트셋(서버 전체 기본 AI 성격) — 관리자. 기본 지정 없으면 NEXA 기본 정체성(니아) ──
    async def admin_prompt_sets(self, guild_id: int) -> dict:
        """이 서버의 전역 프롬프트셋 목록(관리자). builtin(니아)은 preview 만(전문 비공개)."""
        dt = self._durable_token()
        if not dt:
            return {"ok": False, "error": "연동된 신원이 없어요(durable 토큰 없음)"}
        base = _agent_sync_base(self._cfg.relay_url)
        return await asyncio.to_thread(_post_provider_admin_promptset, base, "", dt, guild_id)

    async def admin_prompt_set_add(self, guild_id: int, name: str, content: str) -> dict:
        """전역 프롬프트셋 추가(관리자). 추가만으로 기본이 되지는 않는다."""
        dt = self._durable_token()
        if not dt:
            return {"ok": False, "error": "연동된 신원이 없어요(durable 토큰 없음)"}
        base = _agent_sync_base(self._cfg.relay_url)
        return await asyncio.to_thread(_post_provider_admin_promptset, base, "/add", dt, guild_id, name=name, content=content)

    async def admin_prompt_set_default(self, guild_id: int, set_id: str) -> dict:
        """전역 프롬프트셋 기본 지정(관리자). set_id='nia' 면 NEXA 기본 정체성(니아)으로 되돌린다."""
        dt = self._durable_token()
        if not dt:
            return {"ok": False, "error": "연동된 신원이 없어요(durable 토큰 없음)"}
        base = _agent_sync_base(self._cfg.relay_url)
        return await asyncio.to_thread(_post_provider_admin_promptset, base, "/default", dt, guild_id, set_id=set_id)

    async def admin_prompt_set_delete(self, guild_id: int, set_id: str) -> dict:
        """전역 프롬프트셋 삭제(관리자). 기본이던 셋을 지우면 니아로 되돌아간다. builtin(니아)은 삭제 불가."""
        dt = self._durable_token()
        if not dt:
            return {"ok": False, "error": "연동된 신원이 없어요(durable 토큰 없음)"}
        base = _agent_sync_base(self._cfg.relay_url)
        return await asyncio.to_thread(_post_provider_admin_promptset, base, "/delete", dt, guild_id, set_id=set_id)

    # ── 채널 AI 허용(관리 화면 08) — 관리자. 빈 허용 목록 = 전체 채널 허용 ──
    async def admin_channels(self, guild_id: int) -> dict:
        """이 서버의 채널 AI 허용 목록(관리자). central 이 JDA 텍스트 채널 + 허용 정책을 합쳐 반환."""
        dt = self._durable_token()
        if not dt:
            return {"ok": False, "error": "연동된 신원이 없어요(durable 토큰 없음)"}
        base = _agent_sync_base(self._cfg.relay_url)
        return await asyncio.to_thread(_post_provider_admin_channels, base, "", dt, guild_id)

    async def admin_channel_toggle(self, guild_id: int, channel_id: int, allow: bool) -> dict:
        """채널 AI 허용/금지 토글(관리자). channel_id 는 64bit Discord ID."""
        dt = self._durable_token()
        if not dt:
            return {"ok": False, "error": "연동된 신원이 없어요(durable 토큰 없음)"}
        base = _agent_sync_base(self._cfg.relay_url)
        return await asyncio.to_thread(_post_provider_admin_channels, base, "/toggle", dt, guild_id, channel_id=channel_id, allow=allow)

    # ── 읽기 전용 관리 탭(채널AI/RAG/프리셋) — 관리자. 추가·편집은 Discord 명령·웹 대시보드 ──
    async def admin_channel_ai(self, guild_id: int) -> dict:
        """채널 AI 프로필 목록(관리 화면 09 읽기)."""
        dt = self._durable_token()
        if not dt:
            return {"ok": False, "error": "연동된 신원이 없어요(durable 토큰 없음)"}
        base = _agent_sync_base(self._cfg.relay_url)
        return await asyncio.to_thread(_post_provider_admin_guild, base, "channel-ai", dt, guild_id)

    async def admin_knowledge(self, guild_id: int) -> dict:
        """지식 소스(RAG) 목록(관리 화면 10 읽기)."""
        dt = self._durable_token()
        if not dt:
            return {"ok": False, "error": "연동된 신원이 없어요(durable 토큰 없음)"}
        base = _agent_sync_base(self._cfg.relay_url)
        return await asyncio.to_thread(_post_provider_admin_guild, base, "knowledge", dt, guild_id)

    async def admin_presets(self, guild_id: int) -> dict:
        """프리셋 목록(관리 화면 11 읽기)."""
        dt = self._durable_token()
        if not dt:
            return {"ok": False, "error": "연동된 신원이 없어요(durable 토큰 없음)"}
        base = _agent_sync_base(self._cfg.relay_url)
        return await asyncio.to_thread(_post_provider_admin_guild, base, "presets", dt, guild_id)

    async def admin_preset_delete(self, guild_id: int, preset_id: str) -> dict:
        """프리셋 삭제(관리 화면 11 쓰기). central 이 길드 소유권 가드."""
        dt = self._durable_token()
        if not dt:
            return {"ok": False, "error": "연동된 신원이 없어요(durable 토큰 없음)"}
        base = _agent_sync_base(self._cfg.relay_url)
        return await asyncio.to_thread(_post_provider_admin_delete, base, "/provider/admin/presets/delete", dt, guild_id, "presetId", preset_id)

    async def admin_knowledge_delete(self, guild_id: int, source_id: str) -> dict:
        """지식 소스(RAG) 삭제(관리 화면 10 쓰기). central 이 길드 소유권 가드."""
        dt = self._durable_token()
        if not dt:
            return {"ok": False, "error": "연동된 신원이 없어요(durable 토큰 없음)"}
        base = _agent_sync_base(self._cfg.relay_url)
        return await asyncio.to_thread(_post_provider_admin_delete, base, "/provider/admin/knowledge/delete", dt, guild_id, "sourceId", source_id)

    # ── 서버별 정책(데스크톱 앱 G3) ─────────────────────────────────────
    def guild_policy(self, guild_id: int) -> dict:
        """현재 적용 중인 이 서버의 내 정책(전역 기본값과 병합)."""
        pol = dict(self._guild_policy.get(guild_id, {}))
        pol.setdefault("daily_limit", self._cfg.daily_limit)
        return pol

    async def set_guild_policy(self, guild_id: int, policy: dict) -> None:
        """이 서버에 대한 내 정책 override 를 저장·적용(앱 G3). 한도 변경은 즉시 재광고(hello)."""
        from .config_file import set_guild_policy as _save

        _save(guild_id, policy)
        self._guild_policy[guild_id] = {**self._guild_policy.get(guild_id, {}), **policy}
        # 새 한도로 잔여 리셋 → 모든 연결 재광고(새 remaining 을 hello 로 중앙에 보고)
        self._remaining_by_guild.pop(guild_id, None)
        # 동시 처리 상한이 바뀌었을 수 있으니 길드 세마포어 폐기 → 다음 요청 때 새 상한으로 재생성.
        self._guild_sems.pop(guild_id, None)
        await self._readvertise()

    # ── 자동 동기화: 연동된 사용자가 디스코드 /프로바이더참여 한 새 서버에 앱이 스스로 연결 ──────
    async def sync_now(self) -> None:
        """자동 참여 동기화를 **즉시 1회** 실행(앱에서 '자동 연결' 켤 때 ≤45s 대기 없이 바로 반영)."""
        await self._sync_joins_once()

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
        except Exception as exc:  # noqa: BLE001 - 네트워크/서버 실패는 다음 주기에 재시도
            logger.warning("자동 서버 동기화 실패(다음 주기 재시도): %s", exc)
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

    def _remaining_summary(self) -> str:
        """서버별 일일 잔여 요약. 무제한이면 '무제한', 아니면 길드별 잔여(아직 없으면 한도값)."""
        if self._cfg.daily_limit <= 0:
            return "무제한"
        if not self._remaining_by_guild:
            return f"{self._cfg.daily_limit}/서버"
        return " ".join(f"{g}:{n}" for g, n in self._remaining_by_guild.items())

    def status_line(self) -> str:
        """트레이/콘솔용 한 줄 상태 요약."""
        conn = "연결됨" if self.is_connected() else "연결 끊김"
        img = " · 이미지" if self._image_ready else ""
        return f"{conn} · 처리 {self._processed} · 잔여 {self._remaining_summary()}{img}"

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

        # ComfyUI 이미지 capability: opt-in + 런타임 health 로 확정.
        if self._sd is not None:
            self._image_ready = await self._sd.health()
            if self._image_ready:
                logger.info("이미지 생성(ComfyUI) 활성: %s", self._comfy_url)
            else:
                logger.warning(
                    "ComfyUI(%s) 미연결 — 설치돼 있으면 자동 기동을 시도하고, 준비되면 이미지 capability 를 재광고합니다",
                    self._comfy_url,
                )

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
            # 트레이는 선택적 UX — 생성 실패(pystray 백엔드/환경 문제 등)가 Provider 본체를 죽이면 안 된다.
            # tray_available() 통과 후에도 run_tray 가 실패할 수 있어(예: 일부 환경 pystray ValueError) 방어한다.
            try:
                self._start_tray()
            except Exception as exc:  # noqa: BLE001 - 트레이 실패는 비치명적, 헤드리스로 계속
                logger.warning("트레이 시작 실패 — 헤드리스(콘솔 상태)로 계속합니다: %s", exc)

        # 저장된 모든 서버 연결을 동시에 띄운다(없으면 cfg.token 하나). 연결 하나가 죽어도(인증실패 등)
        # 나머지는 유지 — run 은 stop 요청까지만 대기한다.
        from .config_file import load_connections, load_guild_policies

        self._guild_policy = load_guild_policies()  # 서버별 한도 override 적용
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
        """설치된 ComfyUI 가 꺼져 있으면 자동으로 띄우고, 준비되면 image capability 를 재광고한다.

        capability 는 연결 시점 hello 로만 광고되므로, ComfyUI 가 늦게 떠도 풀에 반영되려면 재연결이
        필요하다. 텍스트 연결을 막지 않도록 백그라운드 태스크로 돌린다(재부팅 후 무인 복구 핵심).
        앱이 관리하는 ComfyUI 면 자동 기동(설치돼 있을 때) — 1급 엔진이므로 살려둔다.
        외부 URL(직접 띄운 인스턴스)이면 is_installed=False → start no-op, health 만 본다.
        """
        from . import comfy_setup

        if comfy_setup.is_installed():
            await comfy_setup.start()
        if self._sd is not None and await self._sd.health():
            # 저장된 선택 체크포인트 적용(없으면 첫 모델). 재시작에도 유저 선택 유지.
            from .config_file import load_config

            cm = load_config().get("comfy_model")
            if cm:
                try:
                    await self._sd.set_checkpoint(cm)
                except Exception as exc:  # noqa: BLE001 - 실패해도 첫 모델로 동작
                    # 저장된 체크포인트가 조용히 무시되면 유저는 선택한 모델 대신 기본을 보게 된다 — 남긴다(예외 원칙 3).
                    logger.warning("저장된 체크포인트 적용 실패(%s) — 기본 모델로 진행: %s", cm, exc)
            self._image_ready = True
            self._invalidate_resolution()
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
        """SIGHUP: 저장된 설정 파일에서 models·기본 응답 모델을 다시 읽어 적용(#129 hot-reload)."""
        from .config_file import load_config

        saved = load_config()
        models = saved.get("models")
        if models:
            self._models = list(models)
            logger.info("설정 hot-reload: models=%s", self._models)
        self._default_model = str(saved.get("default_model") or "").strip()
        return self._models

    async def set_models(self, models: list[str], default_model: str | None = None) -> None:
        """제공 모델 선택을 라이브로 적용·재광고(앱 모델 화면 '적용'). self._models 갱신 + 모든 연결 재접속(새 hello)
        → 중앙 풀이 새 모델 집합을 즉시 안다. status.models 도 이 값을 반영(홈/서버 '제공 모델' 일치)."""
        self._models = _merge_models(list(models), self._gemini_models)  # Gemini 모델은 항상 유지
        if default_model is not None:
            self._default_model = (default_model or "").strip()
        await self._readvertise()

    async def set_gemini_key(self, api_key: str) -> bool:
        """클라우드 Gemini 키를 **라이브로** 적용(앱 설정에서 키 입력). 키가 있으면 gemini-2.5-flash-lite 를
        풀에 광고하고 gemini-* 요청을 라우팅, 비우면 제거. 재시작 없이 즉시 반영(재광고). 키 유효 여부 반환."""
        key = (api_key or "").strip()
        if key:
            from .gemini import DEFAULT_GEMINI_MODEL, GeminiClient

            self._gemini = GeminiClient(key, self._cfg.request_timeout)
            self._gemini_models = [DEFAULT_GEMINI_MODEL]
            ok = await self._gemini.health()
        else:
            self._gemini = None
            self._gemini_models = []
            ok = False
        # 광고 모델 = 현재 로컬 선택 + gemini. (set_models 가 _gemini_models 를 항상 유지하므로 재계산)
        base = [m for m in self._models if not m.startswith("gemini-")]
        self._models = _merge_models(base, self._gemini_models)
        await self._readvertise()
        return ok

    async def set_image_enabled(self, on: bool) -> bool:
        """이미지(ComfyUI) 제공을 **라이브로** 켜고 끈다(앱 '이미지 요청 받기' 토글). enable_image=False 로 시작해
        self._sd 가 None 이어도 여기서 ComfyClient 를 만들어 health 확인 후 재광고한다(재시작 불필요).

        반환값 = 즉시 image_ready 여부. ComfyUI 가 설치만 되고 꺼져 있으면 백그라운드 자동 기동(_boot_sd)을 걸고
        준비되면 다시 재광고한다(이때 반환은 False — 아직 준비 전). 미설치면 image 는 광고되지 않는다(앱이 설치 안내).
        """
        if self._sd_boot_task is not None:
            self._sd_boot_task.cancel()
            self._sd_boot_task = None
        if on:
            if self._sd is None:
                from .comfy import ComfyClient

                self._sd = ComfyClient(self._comfy_url or self._cfg.comfy_url, self._cfg.request_timeout)
            self._image_ready = await self._sd.health()
            if not self._image_ready:
                # 설치돼 있으면 자동 기동(준비되면 _boot_sd 가 재광고). 텍스트 제공은 막지 않는다.
                self._sd_boot_task = asyncio.create_task(self._boot_sd())
        else:
            self._sd = None
            self._image_ready = False
        await self._readvertise()
        return self._image_ready

    def _preferred_model(self) -> str | None:
        """모델 미지정 요청에 쓸 기본 응답 모델 — 설정된 기본이 제공 중이면 그것, 아니면 첫 모델."""
        if self._default_model and self._default_model in self._models:
            return self._default_model
        return self._models[0] if self._models else None

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
    # 이미지(ComfyUI) opt-in 점검: 도달 확인(health). 설치/모델은 로컬 실행 탭에서 관리.
    if cfg.enable_image:
        from . import comfy_setup
        from .comfy import ComfyClient

        url = cfg.comfy_url or comfy_setup.webui_url()
        if await ComfyClient(url, cfg.request_timeout).health():
            logger.info("✅ ComfyUI OK (%s)", url)
        else:
            logger.warning("⚠️ ComfyUI 미연결 (%s) — 로컬 실행 탭에서 설치/시작하세요", url)
    return 0


def self_test(cfg: AgentConfig) -> int:
    return asyncio.run(_self_test(cfg))
