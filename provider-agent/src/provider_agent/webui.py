"""로컬 제어판 (`--gui`) — 네이티브 앱 창.

OS 내장 웹뷰(pywebview: macOS WKWebView / Windows WebView2 / Linux WebKit)로 **주소창 없는
진짜 프로그램 창**을 띄운다. 화면은 HTML 한 장(다크), 내부 aiohttp 서버가 API 를 제공한다.
웹뷰가 없는 환경(헤드리스 등)은 자동으로 기본 브라우저로 폴백한다.

흐름은 위에서 아래로 한눈에: ① 토큰 → ② 모델 선택 → ③ 시작. 상태는 맨 위 큰 배지로.
중앙 서버 주소는 고정(읽기전용, 고급에 숨김). 모델은 로컬 Ollama 에서 자동 감지.

보안: 127.0.0.1 만 바인딩 + 세션 키. 토큰 콜백(`/connect/callback`)은 state==세션키로 검증.
"""

from __future__ import annotations

import asyncio
import logging
import secrets
import threading
from collections import deque
from collections.abc import Iterable
from importlib.resources.abc import Traversable
from typing import TypedDict

from aiohttp import web

from .config import AgentConfig, config_from_args
from .config_file import load_config, persist_partial, save_config
from .constants import AGENT_VERSION, APP_DISPLAY_NAME, DEFAULT_TEXT_MODEL
from .i18n import t

logger = logging.getLogger("provider_agent.webui")

# 공개 기본 중앙 서버(유저는 입력하지 않음). 자체호스팅만 고급에서 바꿀 수 있다.
DEFAULT_RELAY = "wss://discord-ai.yeon.world/agent"


def _default_relay() -> str:
    """기본 중앙 서버 주소. 로컬 개발은 RELAY_URL 환경변수로 우회(예: ws://localhost:8085/agent)."""
    import os

    return (os.getenv("RELAY_URL") or DEFAULT_RELAY).rstrip("/")


_mascot_cache: bytes | None = None
_app_icon_cache: bytes | None = None


def _mascot_bytes() -> bytes:
    """패키지 에셋의 마스코트 PNG(1회 로드·캐시). 없으면 빈 바이트(이미지만 깨지고 동작은 정상)."""
    global _mascot_cache
    if _mascot_cache is None:
        try:
            from importlib import resources

            _mascot_cache = (resources.files("provider_agent") / "assets" / "mascot.png").read_bytes()
        except Exception as exc:  # noqa: BLE001
            logger.debug("mascot 에셋 로드 실패(이미지만 비고 동작은 정상): %s", exc)
            _mascot_cache = b""
    return _mascot_cache


def _app_icon_bytes() -> bytes:
    """패키지 에셋의 Nexa 앱 아이콘 PNG(1회 로드·캐시). 없으면 빈 바이트."""
    global _app_icon_cache
    if _app_icon_cache is None:
        try:
            from importlib import resources

            _app_icon_cache = (resources.files("provider_agent") / "assets" / "app-icon.png").read_bytes()
        except Exception:  # noqa: BLE001
            _app_icon_cache = b""
    return _app_icon_cache


def _parse_guild_id(req: web.Request) -> int | None:
    """경로 변수 guildId 를 정수로 파싱한다. 없거나 숫자가 아니면 None.

    예외를 흐름제어로 쓰지 않고(예외 원칙 5) 사전 검사한다. 잘못된 서버 응답([_bad_server])은 한 곳에서
    만들어 다수 admin 핸들러의 try/except 복붙을 제거한다(DRY·예외 원칙 9 의 정신).
    """
    raw = req.match_info.get("guildId", "")
    return int(raw) if raw.isdigit() else None


def _bad_server() -> web.Response:
    """누락/형식오류 guildId 의 표준 응답(200 + {ok:false}) — 프런트 계약 보존."""
    return web.json_response({"ok": False, "error": "잘못된 서버"})


# 최근 로그 라인(대시보드 표시용).
_log_lines: deque[str] = deque(maxlen=200)
_log_attached = False


def _log_source(name: str, msg: str) -> str:
    """로그 한 줄의 출처를 분류한다 — 화면에서 SD/Ollama/연결을 구분·필터하기 위함.

    1순위는 **로거 이름**(provider_agent.sd_setup/ollama_setup = 설치·기동 로그, 권위 있음),
    2순위는 에이전트 코어(provider_agent)가 남기는 메시지의 키워드로 보강한다.
    값: SD | Ollama | Relay | Agent.
    """
    n = name or ""
    if n.endswith("sd_setup"):
        return "SD"
    if n.endswith("ollama_setup"):
        return "Ollama"
    m = msg or ""
    low = m.lower()
    if "stable diffusion" in low or "sd(" in low or "/imagine" in low or "이미지 생성" in m:
        return "SD"
    if "ollama" in low or "/ask" in low:
        return "Ollama"
    if "중앙 서버" in m or "릴레이" in m or "relay" in low or "wss" in low:
        return "Relay"
    return "Agent"


class _SrcFormatter(logging.Formatter):
    """포맷에 출처 토큰(%(src)s)을 주입한다. 와이어 형식: 'HH:MM:SS LEVEL SOURCE | message'."""

    def format(self, record: logging.LogRecord) -> str:
        record.src = _log_source(record.name, record.getMessage())
        return super().format(record)


class _RingHandler(logging.Handler):
    def emit(self, record: logging.LogRecord) -> None:
        try:
            _log_lines.append(self.format(record))
        except Exception:  # noqa: BLE001 - 로그 핸들러 내부: 여기서 logger 를 호출하면 재귀하므로 의도적으로 무시
            pass


def _attach_log_capture() -> None:
    global _log_attached
    if _log_attached:
        return
    h = _RingHandler()
    h.setLevel(logging.INFO)
    # 출처(SOURCE)를 한 토큰으로 포함 → 화면이 에이전트/Ollama/SD/연결로 필터·배지 표시.
    h.setFormatter(
        _SrcFormatter("%(asctime)s %(levelname)-5s %(src)s | %(message)s", datefmt="%H:%M:%S")
    )
    agent_logger = logging.getLogger("provider_agent")
    agent_logger.addHandler(h)
    # 로거 기본 레벨은 WARNING 이라 INFO(연결·SD/Ollama 활성·/ask 처리 등 정작 유용한 활동)가 버려진다.
    # 화면 로그가 거의 비어 보이던 원인 → INFO 로 올려 실제 활동이 잡히게 한다(콘솔도 INFO, 에이전트 정상 수준).
    if agent_logger.level == logging.NOTSET or agent_logger.level > logging.INFO:
        agent_logger.setLevel(logging.INFO)
    _log_attached = True


# 실행 중 에이전트 상태(웹 루프와 같은 태스크).
_state: dict = {"agent": None, "task": None}


class OllamaState(TypedDict):
    installed: bool  # 실행파일(ollama) 설치 여부
    ready: bool  # daemon 이 응답하는지(/api/tags)
    models: list[str]  # 설치된 모델명 목록(하위호환)
    modelsDetail: list[dict]  # 모델 상세(name·size·modifiedAt·family) — GUI 용량/마지막 사용


async def _detect_ollama() -> OllamaState:
    """Ollama 런타임 상태를 한 번에 판정한다: installed(실행파일)·ready(daemon 응답)·models(설치 목록).

    P1: '미설치(실행파일 없음)'와 '설치됐지만 daemon 꺼짐'을 구분한다. 둘 다 list_models 는
    ECONNREFUSED → 같은 OllamaError → 모델 0개로 보여, UI 가 'daemon 만 켜면 되는' 사용자에게도
    '설치하세요'만 안내하던 문제를 해소한다(SD 의 installed/ready 분리와 동일한 패턴).
    """
    from . import ollama_setup
    from .ollama import OllamaClient, OllamaError

    saved = load_config()
    url = saved.get("ollama_url") or "http://localhost:11434"
    try:
        installed = bool(ollama_setup.is_installed())
    except Exception:  # noqa: BLE001 - 설치 여부 판정 실패는 미설치로 보수 처리
        installed = False
    try:
        detail = await OllamaClient(url).list_models_detailed()
    except OllamaError:
        return {"installed": installed, "ready": False, "models": [], "modelsDetail": []}
    # list_models 성공 = daemon 응답 = 실행 중(PATH 밖 바이너리로 떠 있어도 ready 면 installed 로 본다).
    return {"installed": True, "ready": True, "models": [d["name"] for d in detail], "modelsDetail": detail}


async def _detect_models() -> list[str]:
    """로컬 Ollama 의 설치 모델 목록(자동 감지). 실패하면 빈 목록(하위호환 헬퍼)."""
    return (await _detect_ollama())["models"]


def _is_default_text_model(model: str) -> bool:
    base = DEFAULT_TEXT_MODEL.split(":", 1)[0]
    return model == DEFAULT_TEXT_MODEL or model.startswith(f"{base}:")


def _selected_text_models(available: list[str], saved_models: object | None) -> list[str]:
    raw_saved: Iterable[object] = (
        [] if isinstance(saved_models, str) or not isinstance(saved_models, Iterable) else saved_models
    )
    saved = [str(m).strip() for m in raw_saved if str(m).strip()]
    if saved:
        return saved
    for model in available:
        if _is_default_text_model(model):
            return [model]
    return []


async def _run_ollama_setup_and_select_default(url: str) -> None:
    from . import ollama_setup

    if await ollama_setup.run_setup(url):
        persist_partial({"models": [DEFAULT_TEXT_MODEL]})


async def _run_ollama_install(url: str, model: str, *, select: bool) -> None:
    """카탈로그에서 고른 임의 모델을 설치하고(진행률은 ollama_setup.progress), 성공 시 제공 대상에 추가(P3)."""
    from . import ollama_setup

    if await ollama_setup.run_setup(url, model) and select:
        saved = [str(m).strip() for m in (load_config().get("models") or []) if str(m).strip()]
        if model not in saved:
            saved.append(model)
        persist_partial({"models": saved})


def _model_matches(catalog_id: str, detected: str) -> bool:
    """카탈로그 모델 id 가 설치된 모델명과 같은 계열인지(정확 일치 또는 같은 base 태그)."""
    if detected == catalog_id:
        return True
    base = catalog_id.split(":", 1)[0]
    return detected == base or detected.startswith(f"{base}:")


def _build_cfg_from_saved() -> AgentConfig | None:
    """저장된 설정으로 AgentConfig 구성(없으면 None). config_from_args 로 해석 일관성 유지."""
    try:
        cfg, _ = config_from_args([])  # CLI 빈 인자 → 저장 설정/기본값 사용
        return cfg
    except SystemExit:
        return None


def _start_agent() -> dict:
    """저장된 설정으로 에이전트를 시작한다(이미 실행 중이면 무시). 같은 이벤트 루프의 핸들러에서 호출.

    /api/start 와 OAuth 콜백(토큰 받은 직후 자동 연결)에서 공용으로 쓴다.
    """
    task = _state["task"]
    if task is not None and not task.done():
        return {"ok": False, "error": t("alreadyRunning")}
    cfg = _build_cfg_from_saved()
    if cfg is None or not cfg.token:
        return {"ok": False, "error": t("saveTokenFirst")}
    # 단일 인스턴스: 백그라운드 자동실행 서비스 등 다른 에이전트가 이미 연결돼 있으면 막는다(핑퐁 방지).
    from . import singleton

    if not singleton.acquire():
        return {"ok": False, "error": t("otherInstanceConnected")}
    from .agent import ProviderAgent

    agent = ProviderAgent(cfg)
    _state["agent"] = agent
    _state["task"] = asyncio.create_task(agent.run(install_signals=False))
    return {"ok": True}


def _connect_base(relay: str) -> str:
    """relay(wss://…/agent) → 중앙 서버 https 베이스. 토큰 받기 OAuth 시작점 도출."""
    base = relay.replace("wss://", "https://").replace("ws://", "http://")
    if base.endswith("/agent"):
        base = base[: -len("/agent")]
    return base.rstrip("/")


# ‘디스코드 로그인’ OAuth 가능 여부는 서버 설정에 달려 있다 → 백그라운드로 서버에 물어 캐시.
# env(AGENT_CONNECT_ENABLED)는 강제 오버라이드. _connect_enabled()는 동기·즉시(캐시 읽기)라
# 이벤트루프를 막지 않는다(네트워크는 _start_connect_status_refresher 데몬이 담당).
_connect_cache: dict = {"enabled": False}


def _probe_connect_status() -> bool:
    """중앙 서버에 OAuth(디스코드 로그인) 활성 여부를 물어본다(블로킹). 실패하면 False."""
    import json
    import ssl
    import urllib.request

    import certifi

    base = _connect_base(load_config().get("relay_url") or _default_relay())
    try:
        ctx = ssl.create_default_context(cafile=certifi.where())
        # User-Agent 필수: 서버 앞단(WAF/CDN)이 기본 'Python-urllib' UA 를 403 으로 막는다.
        req = urllib.request.Request(
            base + "/provider/connect/status",
            headers={"Accept": "application/json", "User-Agent": f"nexa-agent/{AGENT_VERSION}"},
        )
        with urllib.request.urlopen(req, timeout=4, context=ctx) as resp:  # noqa: S310 - https 고정
            return bool(json.loads(resp.read().decode("utf-8")).get("enabled"))
    except Exception as exc:  # noqa: BLE001 - 서버 미설정/네트워크 실패는 비활성으로
        logger.debug("connect 상태 확인 실패(비활성으로 처리): %s", exc)
        return False


def _connect_enabled() -> bool:
    """디스코드 로그인 추가 버튼을 켤지. env 강제 우선, 아니면 백그라운드로 갱신된 서버 상태(캐시)."""
    import os

    if os.getenv("AGENT_CONNECT_ENABLED"):
        return True
    return bool(_connect_cache["enabled"])


def _start_connect_status_refresher(interval_s: float = 60.0) -> None:
    """서버 OAuth 활성 여부를 주기적으로 갱신하는 데몬(첫 갱신 즉시). GUI 시작 시 호출."""
    import threading
    import time

    def _loop() -> None:
        while True:
            try:
                _connect_cache["enabled"] = _probe_connect_status()
            except Exception as exc:  # noqa: BLE001
                logger.debug("connect 상태 갱신 실패(다음 주기 재시도): %s", exc)
            time.sleep(interval_s)

    threading.Thread(target=_loop, daemon=True).start()


def _assets_dir() -> Traversable:
    """이식된 데스크톱 앱 자산 디렉토리(webui_assets) — 패키지 안 traversable.

    scripts/sync_desktop_app.py 가 prototypes/desktop 을 변환해 생성한다(생성물, 커밋 안 함).
    PyInstaller 번들에선 collect_data_files 로 같은 위치에 포함된다.
    """
    from importlib import resources

    return resources.files("provider_agent") / "webui_assets"


def _assets_index() -> str | None:
    """webui_assets/index.html 이 있으면 본문 반환, 없으면 None(폴백)."""
    try:
        idx = _assets_dir() / "index.html"
        if idx.is_file():
            return idx.read_text(encoding="utf-8")
    except Exception as exc:  # noqa: BLE001 - 자산 부재/읽기 실패는 폴백으로
        logger.debug("webui_assets/index.html 로드 실패 — 인라인 폴백: %s", exc)
    return None


# webui_assets/index.html 이 없을 때(개발자가 sync-desktop 미실행)의 안내 페이지.
# 조용히 깨지는 대신 무엇을 해야 하는지 분명히 알려준다(배포물엔 항상 자산이 번들되므로 사용자는 보지 않음).
_MISSING_ASSETS_PAGE = (
    "<!doctype html><html lang=ko><head><meta charset=utf-8>"
    "<title>Nexa — 자산 미생성</title></head>"
    "<body style='font-family:system-ui;background:#0d0f12;color:#e8eaed;text-align:center;padding-top:80px'>"
    "<div style='max-width:480px;margin:0 auto;line-height:1.7'>"
    "<h2>데스크톱 앱 자산이 아직 생성되지 않았어요</h2>"
    "<p>리포지토리 루트에서 아래를 실행한 뒤 다시 여세요:</p>"
    "<pre style='background:#152133;padding:12px 16px;border-radius:8px;display:inline-block;color:#cdd'>"
    "make sync-desktop</pre>"
    "<p style='color:#9fb0c6;font-size:13px'>(또는 <code>python scripts/sync_desktop_app.py</code>)</p>"
    "</div></body></html>"
)


def _page(session_key: str) -> str:
    """세션 키를 주입한 제어판 HTML. 이식된 앱 자산(webui_assets/index.html)을 서빙한다.

    자산이 없으면(개발자가 sync-desktop 미실행) 조용히 깨지지 않게 안내 페이지를 반환한다.
    배포물(PyInstaller)은 CI 에서 sync-desktop 으로 자산을 번들하므로 사용자는 안내 페이지를 보지 않는다.
    """
    template = _assets_index()
    if template is None:
        return _MISSING_ASSETS_PAGE
    return template.replace("__SESSION_KEY__", session_key).replace("__VERSION__", AGENT_VERSION)


def _running_agent() -> object | None:
    """실행 중인 에이전트(태스크가 살아 있을 때만). webui 라우트들이 공유한다."""
    agent = _state["agent"]
    task = _state["task"]
    return agent if (agent is not None and task is not None and not task.done()) else None


def _index_for_guild(guild_id_str: str) -> int | None:
    """guildId(문자열) → 저장 연결 목록의 index. 데스크톱 앱은 64bit guildId 만 다루므로(정밀도)
    webui 가 index 로 변환한다. 못 찾으면 None."""
    from .config_file import load_connections

    for i, c in enumerate(load_connections()):
        if c.get("guild_id") is not None and str(c.get("guild_id")) == str(guild_id_str):
            return i
    return None


def _resolve_index(data: dict) -> int | None:
    """body 에서 index 또는 guildId 로 대상 연결 index 해석(둘 중 하나)."""
    if "guildId" in data and data.get("guildId") is not None:
        return _index_for_guild(str(data["guildId"]))
    raw = data.get("index")
    if raw is None:
        return None
    try:
        return int(raw)
    except (TypeError, ValueError):
        return None


def _register_asset_routes(app: web.Application, session_key: str) -> None:
    """인증 불필요한 정적 자산 라우트(메인 페이지·마스코트·앱아이콘·webui_assets js/img).

    핸들러는 session_key(메인 페이지) 와 모듈 헬퍼만 의존 → build_app 의 다른 상태와 무관해 분리(SRP).
    경로 정규식이 파일명만 허용해 경로 탈출(../)을 구조적으로 막는다(원본 동작 불변).
    """

    async def index(_req: web.Request) -> web.Response:
        return web.Response(text=_page(session_key), content_type="text/html")

    async def mascot(_req: web.Request) -> web.Response:
        return web.Response(body=_mascot_bytes(), content_type="image/png")

    async def app_icon(_req: web.Request) -> web.Response:
        return web.Response(body=_app_icon_bytes(), content_type="image/png")

    async def asset_js(req: web.Request) -> web.Response:
        name = req.match_info["asset"]
        try:
            f = _assets_dir() / name
            if not f.is_file():
                raise web.HTTPNotFound()
            return web.Response(body=f.read_bytes(), content_type="text/javascript")
        except web.HTTPException:
            raise
        except Exception as exc:  # noqa: BLE001
            raise web.HTTPNotFound() from exc

    async def asset_img(req: web.Request) -> web.Response:
        name = req.match_info["name"]
        ctype = "image/svg+xml" if name.lower().endswith(".svg") else "image/png"
        try:
            f = _assets_dir() / "img" / name
            if not f.is_file():
                raise web.HTTPNotFound()
            return web.Response(body=f.read_bytes(), content_type=ctype)
        except web.HTTPException:
            raise
        except Exception as exc:  # noqa: BLE001
            raise web.HTTPNotFound() from exc

    app.router.add_get("/", index)
    app.router.add_get("/mascot.png", mascot)
    app.router.add_get("/app-icon.png", app_icon)
    app.router.add_get(r"/{asset:[\w\-]+\.js}", asset_js)
    app.router.add_get(r"/img/{name:[\w\-.]+}", asset_img)


def _register_comfy_routes(app: web.Application, session_key: str) -> None:
    """ComfyUI 라이프사이클 라우트(1급 엔진: 앱이 직접 설치/실행/정지/웹UI 오픈/체크포인트). SD.Next 는 제거됨."""

    def _auth(req: web.Request) -> None:
        if req.headers.get("X-Session") != session_key:
            raise web.HTTPForbidden(text="세션 키 불일치")

    async def comfy_status(req: web.Request) -> web.Response:
        """ComfyUI 설치/실행/바쁨 상태(데스크톱 엔진 카드 버튼 결정용)."""
        _auth(req)
        from . import comfy_setup

        running = await comfy_setup.health()
        return web.json_response(
            {"installed": comfy_setup.is_installed(), "running": bool(running), "busy": comfy_setup.is_busy()}
        )

    async def comfy_setup_start(req: web.Request) -> web.Response:
        """ComfyUI 설치(핀 clone→3.13 venv→torch/deps→기동) 시작. 진행은 /api/comfy/setup-progress 폴링."""
        _auth(req)
        from . import comfy_setup

        if comfy_setup.is_busy():
            return web.json_response({"ok": True, "busy": True})
        # 기본 모델은 SD 카탈로그 첫 항목 재사용(.safetensors 는 ComfyUI 도 동일하게 로드).
        from . import sd_setup

        asyncio.create_task(comfy_setup.run_setup(sd_setup.DEFAULT_MODEL_URL))
        return web.json_response({"ok": True})

    async def comfy_setup_progress(req: web.Request) -> web.Response:
        _auth(req)
        from . import comfy_setup

        return web.json_response(comfy_setup.progress())

    async def comfy_start(req: web.Request) -> web.Response:
        """설치된 ComfyUI 를 기동(꺼져 있을 때)."""
        _auth(req)
        from . import comfy_setup

        if not comfy_setup.is_installed():
            return web.json_response({"ok": False, "error": "ComfyUI 가 아직 설치되지 않았어요."})
        ok = await comfy_setup.start()
        return web.json_response({"ok": bool(ok)})

    async def comfy_stop(req: web.Request) -> web.Response:
        """앱이 띄운 ComfyUI 프로세스를 정지."""
        _auth(req)
        from . import comfy_setup

        await comfy_setup.stop()
        return web.json_response({"ok": True})

    async def comfy_models(req: web.Request) -> web.Response:
        """ComfyUI 에 설치된 체크포인트 목록 + 활성(폴더 스캔 = '아무 .safetensors 나' 자동 인식)."""
        _auth(req)
        from . import comfy_setup
        from .comfy import ComfyClient

        models = await ComfyClient(comfy_setup.webui_url()).list_checkpoints()
        active = load_config().get("comfy_model") or (models[0] if models else None)
        return web.json_response({"models": models, "active": active})

    async def comfy_catalog(req: web.Request) -> web.Response:
        """큐레이션 이미지 모델 카탈로그 — 인기 SDXL 체크포인트를 목록에서 골라 설치/교체. installed 포함."""
        _auth(req)
        from . import comfy_setup

        return web.json_response({"models": comfy_setup.catalog()})

    async def comfy_install_model(req: web.Request) -> web.Response:
        """모델 URL(.safetensors/.ckpt)을 ComfyUI 체크포인트 폴더로 다운로드(카탈로그·임의 URL 공용, gated 는 HF 토큰).
        body {url}. 대용량이라 **백그라운드로 받고 즉시 반환** — 진행률은 /api/comfy/setup-progress 폴링."""
        _auth(req)
        from . import comfy_setup

        try:
            data = await req.json()
        except Exception:  # noqa: BLE001
            data = {}
        url = str((data or {}).get("url") or "").strip()
        fn = url.rsplit("/", 1)[-1].split("?")[0] if url else ""
        if not url or not fn.endswith((".safetensors", ".ckpt")):
            return web.json_response({"ok": False, "error": ".safetensors/.ckpt 직접 링크인지 확인하세요. gated 모델은 설정에서 HF 토큰을 넣으세요."})
        if comfy_setup.is_busy():
            return web.json_response({"ok": True, "busy": True})
        asyncio.create_task(comfy_setup.download_model(url))
        return web.json_response({"ok": True, "started": True})

    async def comfy_select(req: web.Request) -> web.Response:
        """활성 ComfyUI 체크포인트 전환(저장 + 떠 있으면 에이전트에 즉시 반영). body {model}."""
        _auth(req)
        from .config_file import persist_partial

        try:
            data = await req.json()
        except Exception:  # noqa: BLE001
            data = {}
        name = (data or {}).get("model")
        if not name:
            return web.json_response({"ok": False, "error": "모델을 지정하세요."})
        persist_partial({"comfy_model": name})  # 다음 기동에도 유지
        swapped = False
        agent = _running_agent()
        if agent is not None and getattr(agent, "_sd", None) is not None:
            try:
                swapped = bool(await agent._sd.set_checkpoint(name))  # type: ignore[attr-defined]
                agent._invalidate_resolution()  # type: ignore[attr-defined]
            except Exception as exc:  # noqa: BLE001
                logging.getLogger("provider_agent").warning("ComfyUI 모델 전환 실패: %s", exc)
        return web.json_response({"ok": True, "active": name, "applied": "live" if swapped else "saved"})

    async def comfy_open(req: web.Request) -> web.Response:
        """ComfyUI 웹 UI 를 시스템 브라우저로 연다(같은 포트에서 ComfyUI 가 서빙)."""
        _auth(req)
        import webbrowser

        from . import comfy_setup

        if not await comfy_setup.health():
            return web.json_response({"ok": False, "error": "ComfyUI 가 실행 중이 아니에요. 먼저 시작하세요."})
        try:
            webbrowser.open(comfy_setup.webui_url())
        except Exception:  # noqa: BLE001
            return web.json_response({"ok": False, "error": "브라우저를 열 수 없습니다."})
        return web.json_response({"ok": True})

    # ComfyUI 라이프사이클(이미지 엔진 — 설치/시작/정지/웹UI/체크포인트. SD.Next 는 제거됨)
    app.router.add_get("/api/comfy/status", comfy_status)
    app.router.add_post("/api/comfy/setup", comfy_setup_start)
    app.router.add_get("/api/comfy/setup-progress", comfy_setup_progress)
    app.router.add_post("/api/comfy/start", comfy_start)
    app.router.add_post("/api/comfy/stop", comfy_stop)
    app.router.add_post("/api/comfy/open", comfy_open)
    app.router.add_get("/api/comfy/models", comfy_models)
    app.router.add_get("/api/comfy/catalog", comfy_catalog)
    app.router.add_post("/api/comfy/select", comfy_select)
    app.router.add_post("/api/comfy/install-model", comfy_install_model)


def _register_update_routes(app: web.Application, session_key: str) -> None:
    """앱 설치(installer) + 자동/수동 업데이트(updater) 라우트."""

    def _auth(req: web.Request) -> None:
        if req.headers.get("X-Session") != session_key:
            raise web.HTTPForbidden(text="세션 키 불일치")

    async def install_info(req: web.Request) -> web.Response:
        _auth(req)
        from .installer import install_info as _info

        return web.json_response(_info())

    async def install(req: web.Request) -> web.Response:
        _auth(req)
        from .installer import install_app

        return web.json_response(install_app())

    async def update_info(req: web.Request) -> web.Response:
        _auth(req)
        from . import updater

        loop = asyncio.get_event_loop()
        info = await loop.run_in_executor(None, updater.check)  # 네트워크 → 스레드풀
        info["autoUpdate"] = bool(load_config().get("auto_update", True))
        return web.json_response(info)

    async def update_apply(req: web.Request) -> web.Response:
        _auth(req)
        from . import updater

        # 백그라운드 스레드에서 다운로드·교체(프런트는 /api/update-progress 로 진행률 폴링).
        if updater.is_updating():
            return web.json_response({"ok": True, "started": True})

        def _worker() -> None:
            result = updater.apply_update()
            if result.get("ok") and result.get("restarting"):
                _schedule_exit()  # 교체 위해 종료 → 헬퍼가 swap·재실행

        threading.Thread(target=_worker, daemon=True).start()
        return web.json_response({"ok": True, "started": True})

    async def update_progress(req: web.Request) -> web.Response:
        _auth(req)
        from . import updater

        return web.json_response(updater.update_progress())

    async def auto_update_set(req: web.Request) -> web.Response:
        _auth(req)
        from .config_file import persist_partial

        data = await req.json()
        on = bool(data.get("autoUpdate"))
        persist_partial({"auto_update": on})  # 다른 설정 영향 없이 즉시 저장
        return web.json_response({"ok": True, "autoUpdate": on})

    app.router.add_get("/api/install-info", install_info)
    app.router.add_post("/api/install", install)
    app.router.add_get("/api/update-info", update_info)
    app.router.add_get("/api/update-progress", update_progress)
    app.router.add_post("/api/update", update_apply)
    app.router.add_post("/api/auto-update", auto_update_set)


def _register_ollama_routes(app: web.Application, session_key: str) -> None:
    """Ollama(텍스트 엔진) 카탈로그/설치/진행 라우트."""

    def _auth(req: web.Request) -> None:
        if req.headers.get("X-Session") != session_key:
            raise web.HTTPForbidden(text="세션 키 불일치")

    async def ollama_catalog(req: web.Request) -> web.Response:
        """추천 텍스트 모델 카탈로그 + 각 모델의 installed/selected 상태(P3).

        프런트는 미설치 모델을 '설치 가능'으로 보여주고, status.models 와 대조해 'Discord 에 광고 중'을 표시한다.
        """
        _auth(req)
        from . import ollama_setup

        saved = load_config()
        oll = await _detect_ollama()
        detected = oll["models"]
        selected = set(_selected_text_models(detected, saved.get("models")))
        items = []
        for m in ollama_setup.catalog():
            installed = any(_model_matches(m["id"], d) for d in detected)
            items.append({**m, "installed": installed, "selected": m["id"] in selected})
        return web.json_response(
            {
                "models": items,
                "default": DEFAULT_TEXT_MODEL,
                "ollamaInstalled": oll["installed"],
                "ollamaReady": oll["ready"],
            }
        )

    async def ollama_setup_start(req: web.Request) -> web.Response:
        """앱 내 Ollama 설치/모델 pull 시작. 본문에 ``model`` 이 있으면 그 모델을, 없으면 기본 모델을 받는다(P3).

        진행은 ``/api/ollama/setup-progress`` 폴링으로 노출(한 번에 하나 — is_busy 가드).
        """
        _auth(req)
        from . import ollama_setup

        if ollama_setup.is_busy():
            return web.json_response({"ok": True, "busy": True})
        url = load_config().get("ollama_url") or "http://localhost:11434"
        try:
            data = await req.json()
        except Exception:  # noqa: BLE001 - 본문 없는 POST(기본 셋업)는 기본 모델 경로
            data = {}
        model = str(data.get("model", "")).strip()
        if model:
            asyncio.create_task(_run_ollama_install(url, model, select=bool(data.get("select", True))))
        else:
            asyncio.create_task(_run_ollama_setup_and_select_default(url))
        return web.json_response({"ok": True})

    async def ollama_setup_progress(req: web.Request) -> web.Response:
        _auth(req)
        from . import ollama_setup

        return web.json_response(ollama_setup.progress())

    app.router.add_get("/api/ollama/catalog", ollama_catalog)
    app.router.add_post("/api/ollama/setup", ollama_setup_start)
    app.router.add_get("/api/ollama/setup-progress", ollama_setup_progress)


def _register_server_routes(app: web.Application, session_key: str) -> None:
    """서버 관리 라우트(목록/일시중지/제거/이름변경 · 내 제공 정책/모델 · 관리자 승인·정책/프롬프트셋/채널AI/지식/프리셋). build_app(god-function)에서 추출 — 동작·경로 불변."""

    def _auth(req: web.Request) -> None:
        if req.headers.get("X-Session") != session_key:
            raise web.HTTPForbidden(text="세션 키 불일치")

    async def servers(req: web.Request) -> web.Response:
        """'내 서버 목록': 실행 중이면 실시간 연결상태, 아니면 저장된 목록(연결 안 됨)."""
        _auth(req)
        agent = _state["agent"]
        task = _state["task"]
        if agent is not None and task is not None and not task.done():
            return web.json_response({"servers": agent.connections_status()})
        from .config_file import load_connections

        saved = load_connections()
        from .config_file import load_guild_policies

        policies = load_guild_policies()

        def _paused(gid: object) -> bool:
            return bool((policies.get(gid) or {}).get("paused")) if isinstance(gid, int) else False

        # guildId 는 64bit Discord ID — JS number 정밀도 손실 방지로 문자열 emit(connections_status 와 동일).
        return web.json_response(
            {"servers": [
                {
                    "index": i,
                    "guildId": (str(c.get("guild_id")) if c.get("guild_id") is not None else None),
                    "guildName": c.get("guild_name"),
                    "connected": False,
                    "paused": _paused(c.get("guild_id")),
                }
                for i, c in enumerate(saved)
            ]}
        )

    async def server_pause(req: web.Request) -> web.Response:
        """이 서버에 대한 내 제공 일시중지/재개(provider self-service). body {paused}. 연결은 유지, 이 길드 요청만 반려."""
        _auth(req)
        guild_id = _parse_guild_id(req)
        if guild_id is None:
            return _bad_server()
        data = await req.json()
        paused = bool(data.get("paused"))
        agent = _running_agent()
        if agent is not None:
            await agent.set_guild_policy(guild_id, {"paused": paused})  # type: ignore[attr-defined]
        else:
            from .config_file import set_guild_policy

            set_guild_policy(guild_id, {"paused": paused})
        return web.json_response({"ok": True, "paused": paused})

    async def server_remove(req: web.Request) -> web.Response:
        """서버 연결 해제(내 로컬 연결만 정리 — 이 서버에 더는 붙지 않음). body {guildId} 또는 {index}.

        주의: 이건 **내 연결/설정 정리**다(중앙 풀의 관리자 등록 제거와 별개). 중앙은 세션이 오프라인이 될 뿐.
        """
        _auth(req)
        data = await req.json()
        index = _resolve_index(data if isinstance(data, dict) else {})
        if index is None:
            return web.json_response({"ok": False, "error": "잘못된 항목"})
        agent = _running_agent()
        if agent is not None:
            await agent.remove_connection_at(index)  # type: ignore[attr-defined]
        else:
            from .config_file import remove_connection_at

            remove_connection_at(index)
        return web.json_response({"ok": True})

    # 내 제공 정책 키 경계: camelCase(앱·와이어) ↔ snake_case(파이썬·저장).
    _POLICY_KEYS = {"dailyLimit": "daily_limit", "maxConcurrency": "max_concurrency", "maxSeconds": "max_seconds"}

    def _policy_camel(guild_id: int) -> dict:
        """저장된 이 서버 정책을 camelCase 로 readback(미저장 키는 기본값). 앱이 화면에 그대로 표시."""
        from .config_file import load_guild_policies

        saved = load_guild_policies().get(guild_id, {})
        defaults = {"daily_limit": 0, "max_concurrency": 1, "max_seconds": 0}
        return {camel: saved.get(snake, defaults[snake]) for camel, snake in _POLICY_KEYS.items()}

    async def server_policy_get(req: web.Request) -> web.Response:
        """저장된 내 제공 정책 readback(앱 상세 화면). 하드코딩 대신 실제 강제값을 보여주기 위함."""
        _auth(req)
        guild_id = _parse_guild_id(req)
        if guild_id is None:
            return _bad_server()
        return web.json_response({"ok": True, "policy": _policy_camel(guild_id)})

    async def server_policy(req: web.Request) -> web.Response:
        """이 서버에 대한 내 정책 override 저장·적용(데스크톱 앱 G3 · /provider-limit 의 로컬 GUI).

        body(camelCase 경계): {dailyLimit, maxConcurrency, maxSeconds}.
        프로바이더 로컬에서 **실제 강제**: dailyLimit(서버별 일일 한도)·maxConcurrency(서버별
        동시 처리 세마포어)·maxSeconds(1건 최대 처리 시간 wait_for). 셋 다 서버가 더 보내도 우회 불가.
        공개 대상(scope)은 앱에서 제거됨 — '서버 멤버 누구나'(ALL)는 중앙의 길드별 라우팅 격리로
        이미 보장되고(다른 서버 멤버는 호출 불가), 세분화는 강제되지 않아 가짜 컨트롤을 두지 않는다.
        """
        _auth(req)
        guild_id = _parse_guild_id(req)
        if guild_id is None:
            return _bad_server()
        data = await req.json()
        policy = {snake: data[camel] for camel, snake in _POLICY_KEYS.items() if camel in data}
        agent = _running_agent()
        if agent is not None:
            await agent.set_guild_policy(guild_id, policy)  # type: ignore[attr-defined]
        else:
            from .config_file import set_guild_policy

            set_guild_policy(guild_id, policy)
        return web.json_response({"ok": True, "policy": _policy_camel(guild_id)})

    async def server_models_get(req: web.Request) -> web.Response:
        """이 서버에 **제공할 모델** 설정 readback. available=내가 줄 수 있는 전체 모델, chatModels=이 서버 선택
        (빈=전체), imageEnabled=이 서버 이미지 제공 여부, imageReady=SD 준비됨."""
        _auth(req)
        guild_id = _parse_guild_id(req)
        if guild_id is None:
            return _bad_server()
        from .config_file import load_guild_policies

        agent = _running_agent()
        saved = load_config()
        available = list(agent.models) if agent is not None else list(saved.get("models") or [])  # type: ignore[attr-defined]
        pol = load_guild_policies().get(guild_id, {})
        sel = pol.get("chatModels")
        return web.json_response(
            {
                "ok": True,
                "available": available,
                "chatModels": list(sel) if isinstance(sel, list) else [],
                "imageEnabled": pol.get("imageEnabled") is not False,
                "imageReady": bool(agent is not None and agent.image_ready),  # type: ignore[attr-defined]
            }
        )

    async def server_models(req: web.Request) -> web.Response:
        """이 서버에 제공할 채팅 모델·이미지 여부를 설정·적용(서버별 자율). body {chatModels:[..], imageEnabled:bool}.
        chatModels 빈 배열 = 전체 제공. 적용 시 그 서버 연결을 재광고해 중앙 풀이 새 모델 집합을 즉시 안다."""
        _auth(req)
        guild_id = _parse_guild_id(req)
        if guild_id is None:
            return _bad_server()
        data = await req.json()
        policy: dict = {}
        if "chatModels" in data:
            policy["chatModels"] = [str(m) for m in (data.get("chatModels") or [])]
        if "imageEnabled" in data:
            policy["imageEnabled"] = bool(data.get("imageEnabled"))
        agent = _running_agent()
        if agent is not None:
            await agent.set_guild_policy(guild_id, policy)  # type: ignore[attr-defined]
        else:
            from .config_file import set_guild_policy

            set_guild_policy(guild_id, policy)
        return web.json_response({"ok": True})

    async def server_manage(req: web.Request) -> web.Response:
        """서버 관리(관리자) — 승인 대기·로스터 조회. central 관리 채널로 프록시(권한은 central 이 JDA 로 판정)."""
        _auth(req)
        guild_id = _parse_guild_id(req)
        if guild_id is None:
            return _bad_server()
        agent = _running_agent()
        if agent is None:
            return web.json_response({"ok": False, "error": "에이전트가 실행 중이 아니에요"})
        return web.json_response(await agent.admin_manage(guild_id))  # type: ignore[attr-defined]

    async def provider_admin(req: web.Request) -> web.Response:
        """Provider 승인/거절/제거(관리자). {action}=approve|reject|remove, body {providerUserId}."""
        _auth(req)
        action = req.match_info.get("action", "")
        if action not in ("approve", "reject", "remove"):
            return web.json_response({"ok": False, "error": "알 수 없는 작업"})
        guild_id = _parse_guild_id(req)
        if guild_id is None:
            return _bad_server()
        data = await req.json()
        try:
            target = int(data.get("providerUserId"))
        except (TypeError, ValueError):
            return web.json_response({"ok": False, "error": "대상 Provider 가 필요해요"})
        agent = _running_agent()
        if agent is None:
            return web.json_response({"ok": False, "error": "에이전트가 실행 중이 아니에요"})
        return web.json_response(await agent.admin_action(action, guild_id, target))  # type: ignore[attr-defined]

    async def server_manage_policy(req: web.Request) -> web.Response:
        """서버 제공 정책 — 신규 자동 승인 토글(관리자). body {autoApprove}. central 이 권한 판정."""
        _auth(req)
        guild_id = _parse_guild_id(req)
        if guild_id is None:
            return _bad_server()
        data = await req.json()
        auto = bool(data.get("autoApprove"))
        agent = _running_agent()
        if agent is None:
            return web.json_response({"ok": False, "error": "에이전트가 실행 중이 아니에요"})
        return web.json_response(await agent.admin_set_policy(guild_id, auto))  # type: ignore[attr-defined]

    async def server_prompt_sets(req: web.Request) -> web.Response:
        """전역 프롬프트셋(서버 전체 기본 AI 성격) 목록(관리자). central 로 프록시. builtin(니아)은 preview 만."""
        _auth(req)
        guild_id = _parse_guild_id(req)
        if guild_id is None:
            return _bad_server()
        agent = _running_agent()
        if agent is None:
            return web.json_response({"ok": False, "error": "에이전트가 실행 중이 아니에요"})
        return web.json_response(await agent.admin_prompt_sets(guild_id))  # type: ignore[attr-defined]

    async def server_prompt_set_add(req: web.Request) -> web.Response:
        """전역 프롬프트셋 추가(관리자). body {name, content}. 추가만으로 기본이 되지는 않는다."""
        _auth(req)
        guild_id = _parse_guild_id(req)
        if guild_id is None:
            return _bad_server()
        data = await req.json()
        name = str(data.get("name") or "").strip()
        content = str(data.get("content") or "").strip()
        agent = _running_agent()
        if agent is None:
            return web.json_response({"ok": False, "error": "에이전트가 실행 중이 아니에요"})
        return web.json_response(await agent.admin_prompt_set_add(guild_id, name, content))  # type: ignore[attr-defined]

    async def server_prompt_set_default(req: web.Request) -> web.Response:
        """전역 프롬프트셋 기본 지정(관리자). body {id}. id='nia' 면 NEXA 기본 정체성(니아)으로 되돌린다."""
        _auth(req)
        guild_id = _parse_guild_id(req)
        if guild_id is None:
            return _bad_server()
        data = await req.json()
        set_id = str(data.get("id") or "").strip()
        agent = _running_agent()
        if agent is None:
            return web.json_response({"ok": False, "error": "에이전트가 실행 중이 아니에요"})
        return web.json_response(await agent.admin_prompt_set_default(guild_id, set_id))  # type: ignore[attr-defined]

    async def server_prompt_set_delete(req: web.Request) -> web.Response:
        """전역 프롬프트셋 삭제(관리자). body {id}. builtin(니아)은 삭제 불가."""
        _auth(req)
        guild_id = _parse_guild_id(req)
        if guild_id is None:
            return _bad_server()
        data = await req.json()
        set_id = str(data.get("id") or "").strip()
        agent = _running_agent()
        if agent is None:
            return web.json_response({"ok": False, "error": "에이전트가 실행 중이 아니에요"})
        return web.json_response(await agent.admin_prompt_set_delete(guild_id, set_id))  # type: ignore[attr-defined]

    async def server_channels(req: web.Request) -> web.Response:
        """채널 AI 허용 목록(관리자). central 로 프록시. 빈 허용 목록 = 전체 채널 허용."""
        _auth(req)
        guild_id = _parse_guild_id(req)
        if guild_id is None:
            return _bad_server()
        agent = _running_agent()
        if agent is None:
            return web.json_response({"ok": False, "error": "에이전트가 실행 중이 아니에요"})
        return web.json_response(await agent.admin_channels(guild_id))  # type: ignore[attr-defined]

    async def server_channel_toggle(req: web.Request) -> web.Response:
        """채널 AI 허용/금지 토글(관리자). body {channelId, allow}. channelId 는 문자열(64bit)."""
        _auth(req)
        guild_id = _parse_guild_id(req)
        if guild_id is None:
            return _bad_server()
        data = await req.json()
        try:
            channel_id = int(str(data.get("channelId") or "0"))
        except ValueError:
            return web.json_response({"ok": False, "error": "잘못된 채널"})
        allow = bool(data.get("allow"))
        agent = _running_agent()
        if agent is None:
            return web.json_response({"ok": False, "error": "에이전트가 실행 중이 아니에요"})
        return web.json_response(await agent.admin_channel_toggle(guild_id, channel_id, allow))  # type: ignore[attr-defined]

    async def _server_guild_read(req: web.Request, attr: str) -> web.Response:
        """길드 단위 읽기 관리 탭(채널AI/RAG/프리셋) 공통 프록시 — central 이 권한 판정·기능게이트."""
        _auth(req)
        guild_id = _parse_guild_id(req)
        if guild_id is None:
            return _bad_server()
        agent = _running_agent()
        if agent is None:
            return web.json_response({"ok": False, "error": "에이전트가 실행 중이 아니에요"})
        return web.json_response(await getattr(agent, attr)(guild_id))

    async def server_channel_ai(req: web.Request) -> web.Response:
        """채널 AI 프로필 목록(관리자, 읽기)."""
        return await _server_guild_read(req, "admin_channel_ai")

    async def server_knowledge(req: web.Request) -> web.Response:
        """지식 소스(RAG) 목록(관리자, 읽기)."""
        return await _server_guild_read(req, "admin_knowledge")

    async def server_presets(req: web.Request) -> web.Response:
        """프리셋 목록(관리자, 읽기)."""
        return await _server_guild_read(req, "admin_presets")

    async def _server_guild_delete(req: web.Request, attr: str, id_field: str) -> web.Response:
        """길드 관리 삭제 공통 프록시(프리셋/지식 소스). central 이 권한·소유권 가드. body {<id_field>}."""
        _auth(req)
        guild_id = _parse_guild_id(req)
        if guild_id is None:
            return _bad_server()
        data = await req.json()
        item_id = str(data.get(id_field) or "").strip() if isinstance(data, dict) else ""
        if not item_id:
            return web.json_response({"ok": False, "error": "대상이 필요해요"})
        agent = _running_agent()
        if agent is None:
            return web.json_response({"ok": False, "error": "에이전트가 실행 중이 아니에요"})
        return web.json_response(await getattr(agent, attr)(guild_id, item_id))

    async def server_preset_delete(req: web.Request) -> web.Response:
        """프리셋 삭제(관리자, 쓰기). central 이 길드 소유권 가드. body {presetId}."""
        return await _server_guild_delete(req, "admin_preset_delete", "presetId")

    async def server_knowledge_delete(req: web.Request) -> web.Response:
        """지식 소스(RAG) 삭제(관리자, 쓰기). central 이 길드 소유권 가드. body {sourceId}."""
        return await _server_guild_delete(req, "admin_knowledge_delete", "sourceId")

    async def server_rename(req: web.Request) -> web.Response:
        """서버 표시 이름 바꾸기(토큰-추가 '이름 미상' 라벨링). body {guildId|index, name}."""
        _auth(req)
        data = await req.json()
        index = _resolve_index(data if isinstance(data, dict) else {})
        if index is None:
            return web.json_response({"ok": False, "error": "잘못된 항목"})
        name = str(data.get("name") or "").strip()[:60]
        agent = _running_agent()
        if agent is not None:
            await agent.rename_connection(index, name)  # type: ignore[attr-defined]
        else:
            from .config_file import rename_connection

            rename_connection(index, name)
        return web.json_response({"ok": True})

    async def server_add_token(req: web.Request) -> web.Response:
        """토큰으로 서버 추가(+ 직접 입력한 별명). 다른 서버의 /provider-join 토큰을 붙여넣을 때."""
        _auth(req)
        data = await req.json()
        token = str(data.get("token") or "").strip()
        name = str(data.get("name") or "").strip()[:60] or None
        if not token:
            return web.json_response({"ok": False, "error": "토큰을 입력하세요."})
        agent = _running_agent()
        if agent is not None:
            await agent.add_connection(token, None, name)  # type: ignore[attr-defined]
        else:
            from .config_file import add_connection

            add_connection(token, None, name)
            _start_agent()
        return web.json_response({"ok": True})

    app.router.add_get("/api/servers", servers)
    app.router.add_post("/api/server-remove", server_remove)
    app.router.add_post("/api/server-rename", server_rename)
    app.router.add_post("/api/servers/{guildId}/pause", server_pause)
    app.router.add_get("/api/servers/{guildId}/policy", server_policy_get)
    app.router.add_get("/api/servers/{guildId}/models", server_models_get)
    app.router.add_post("/api/servers/{guildId}/models", server_models)
    app.router.add_post("/api/servers/{guildId}/policy", server_policy)
    app.router.add_get("/api/servers/{guildId}/manage", server_manage)
    app.router.add_post("/api/servers/{guildId}/manage/policy", server_manage_policy)
    app.router.add_get("/api/servers/{guildId}/prompts", server_prompt_sets)
    app.router.add_post("/api/servers/{guildId}/prompts/add", server_prompt_set_add)
    app.router.add_post("/api/servers/{guildId}/prompts/default", server_prompt_set_default)
    app.router.add_post("/api/servers/{guildId}/prompts/delete", server_prompt_set_delete)
    app.router.add_get("/api/servers/{guildId}/channels", server_channels)
    app.router.add_post("/api/servers/{guildId}/channels/toggle", server_channel_toggle)
    app.router.add_get("/api/servers/{guildId}/channel-ai", server_channel_ai)
    app.router.add_get("/api/servers/{guildId}/knowledge", server_knowledge)
    app.router.add_get("/api/servers/{guildId}/presets", server_presets)
    app.router.add_post("/api/servers/{guildId}/presets/delete", server_preset_delete)
    app.router.add_post("/api/servers/{guildId}/knowledge/delete", server_knowledge_delete)
    app.router.add_post("/api/servers/{guildId}/providers/{action}", provider_admin)
    app.router.add_post("/api/server-add-token", server_add_token)


def build_app(session_key: str) -> web.Application:
    _attach_log_capture()
    app = web.Application()
    _register_asset_routes(app, session_key)

    def _auth(req: web.Request) -> None:
        if req.headers.get("X-Session") != session_key:
            raise web.HTTPForbidden(text="세션 키 불일치")

    async def models(req: web.Request) -> web.Response:
        _auth(req)
        saved = load_config()
        oll = await _detect_ollama()
        detected = oll["models"]
        selected = _selected_text_models(detected, saved.get("models"))
        # 기본 응답 모델: 저장값이 제공 중이면 그것, 아니면 권장 기본(제공 중일 때)·없으면 첫 제공 모델.
        saved_default = str(saved.get("default_model") or "")
        default_model = (
            saved_default if saved_default in selected
            else DEFAULT_TEXT_MODEL if DEFAULT_TEXT_MODEL in selected
            else selected[0] if selected else DEFAULT_TEXT_MODEL
        )
        return web.json_response(
            {
                "models": detected,
                "modelsDetail": oll.get("modelsDetail", []),  # name·size·modifiedAt·family — GUI 용량/마지막 사용
                "selected": selected,
                "default": default_model,
                "defaultInstalled": any(_is_default_text_model(m) for m in detected),
                # P1: 미설치 vs daemon-down vs 정상을 UI 가 구분하도록.
                "ollamaInstalled": oll["installed"],
                "ollamaReady": oll["ready"],
            }
        )

    async def status(req: web.Request) -> web.Response:
        _auth(req)
        saved = load_config()
        agent = _state["agent"]
        task = _state["task"]
        running = task is not None and not task.done()
        # 이 GUI 가 직접 연결돼 있지 않은데 락이 잡혀 있으면 = 백그라운드 자동시작 서비스가 이미 연결 중.
        # (그 경우 GUI 가 다시 연결하려 하면 singleton 으로 막혀 헷갈리므로, 상태로 분명히 보여준다.)
        from . import singleton, updater

        background_running = (not running) and singleton.held_by_other()
        return web.json_response(
            {
                "running": running,
                # 앱 버전(설치 버전, cheap) — 사이드바 표시용. 최신/업데이트 판정은 /api/update-info(네트워크) 별도.
                "version": updater.current_version(),
                "connected": bool(agent and agent.is_connected()),
                "processed": int(agent.processed) if agent else 0,
                "imageReady": bool(agent and agent.image_ready),
                "models": list(agent.models) if agent else list(saved.get("models") or []),
                "hasToken": bool(saved.get("token")),
                "relayUrl": saved.get("relay_url") or _default_relay(),
                "enableImage": bool(saved.get("enable_image")),
                # 백그라운드 자동시작 서비스가 이미 연결 중인지(이 창은 설정용임을 알리는 데 쓴다 — 런타임 상태).
                "backgroundRunning": background_running,
                # 백그라운드 상주 **설정값**(창을 닫아도 제공 유지). 홈 핀은 런타임이 아니라 이 설정을 표시해야 한다
                # (설정 화면의 'background' 토글과 동일 출처). tray=background 로 저장되므로 둘 중 하나라도 참이면 켜짐.
                "background": bool(saved.get("background") or saved.get("tray")),
                # ‘디스코드 로그인’ OAuth 가능 여부는 **서버 설정**으로 결정된다(에이전트 env 불필요).
                # 서버에 OAuth 앱(client-id/secret)이 설정돼 있으면 자동으로 켜진다.
                "connectEnabled": _connect_enabled(),
                # 클라우드 AI(Gemini) 설정 여부(키 자체는 노출 안 함) · ComfyUI 이미지 백엔드 주소.
                "geminiConfigured": bool(saved.get("gemini_api_key")),
                "comfyUrl": str(saved.get("comfy_url") or ""),
                "hfConfigured": bool(saved.get("hf_token")),
            }
        )

    async def logs(req: web.Request) -> web.Response:
        _auth(req)
        return web.json_response({"lines": list(_log_lines)})

    async def setup(req: web.Request) -> web.Response:
        _auth(req)
        data = await req.json()
        saved = load_config()
        # 토큰은 선택: 입력이 있으면 그것, 없으면 저장값 유지(없어도 설정만 저장 가능 — 연동하기 전 단계).
        token = str(data.get("token", "")).strip() or str(saved.get("token", ""))
        requested_models = [str(m).strip() for m in (data.get("models") or []) if str(m).strip()]
        models_list = _selected_text_models(await _detect_models(), requested_models)
        enable_image = bool(data.get("enableImage"))
        # 기본 응답 모델(모델 미지정 요청에 쓰는 폴백) — 제공 목록 안의 모델만 유효. 없으면 저장값 유지, 그것도 없으면 빈 값(첫 모델).
        requested_default = str(data.get("default") or data.get("defaultModel") or "").strip()
        default_model = requested_default if requested_default in models_list else str(saved.get("default_model") or "")
        if default_model and default_model not in models_list:
            default_model = ""
        relay = (saved.get("relay_url") or _default_relay()).rstrip("/")
        cfg = AgentConfig(
            token=token,
            relay_url=relay,
            models=tuple(models_list),
            default_model=default_model,
            enable_image=enable_image,
            auto_update=bool(saved.get("auto_update", True)),  # 저장된 자동업데이트 설정 보존(초기화 방지)
        )
        save_config(cfg)
        # 라이브 반영(P4): 백그라운드 서비스가 디스코드 연결을 담당 중이면, 바뀐 설정(enable_image/models)을
        # 그 프로세스가 즉시 반영하도록 재시작한다. 서비스는 시작 시점 config 만 읽고 파일 변경을 감시하지 않으므로,
        # 저장만으로는 이미지 토글이 디스코드 풀에 절대 반영되지 않는다(='토글 켰는데 image provider 없음'의 원인).
        service_restarted = False
        if data.get("applyToBackground"):
            from . import service as service_mod
            from . import singleton

            task = _state["task"]
            agent = _state["agent"]
            gui_running = task is not None and not task.done()
            if gui_running and agent is not None:
                # GUI 인-프로세스 에이전트: 새 모델 선택을 즉시 적용·재광고(연결 유지, hello 재전송) →
                #   status.models·홈/서버 '제공 모델'·풀 광고가 모델 화면 선택과 일치한다.
                await agent.set_models(models_list, default_model)  # type: ignore[attr-defined]
            elif (not gui_running) and singleton.held_by_other() and service_mod.is_installed():
                service_restarted = bool(service_mod.kickstart())
        service_installed = False
        service_error: str | None = None
        if data.get("installService"):
            # 자동 실행 서비스를 로드하면 RunAtLoad 로 헤드리스 인스턴스가 곧바로 뜬다.
            # 그 인스턴스가 이번 세션의 프로바이더 연결을 가로채지 않도록, GUI 가 먼저 락을 쥔다
            # (서비스 쪽은 singleton 으로 깔끔히 종료 → 중복 연결·창 2개 없음).
            from . import singleton

            singleton.acquire()
            from .service import install_service

            try:
                install_service()
                service_installed = True
            except Exception as exc:  # noqa: BLE001 — 실패 사유를 사용자에게 보여준다(옛날엔 조용히 삼킴)
                service_error = str(exc)
                logging.getLogger("provider_agent").warning("자동 시작 서비스 등록 실패: %s", exc)
        return web.json_response(
            {
                "ok": True,
                "serviceInstalled": service_installed,
                "serviceError": service_error,
                "serviceRestarted": service_restarted,
                "hasToken": bool(token),
            }
        )

    async def image_toggle(req: web.Request) -> web.Response:
        """이미지 요청 받기(enableImage) **전용** 토글 — body {on}. /api/setup 의 모델 재계산을 거치지 않아
        제공 모델 선택을 건드리지 않는다(과거: setup 경유 토글이 모델을 기본값으로 리셋시키던 위험 제거).

        - 설정은 항상 persist_partial 로 저장(재시작 후에도 유지).
        - GUI 인-프로세스 에이전트 실행 중이면 ``agent.set_image_enabled`` 로 **라이브 적용**(ComfyUI health·재광고).
        - 백그라운드 서비스가 제공 중이면 kickstart(재기동)로 새 설정 반영.
        - ComfyUI 미설치/미실행이면 imageReady=False 로 알려 앱이 로컬 실행 탭의 설치를 안내하게 한다.
        """
        _auth(req)
        try:
            data = await req.json()
        except Exception:  # noqa: BLE001
            data = {}
        on = bool(data.get("on")) if isinstance(data, dict) else False
        persist_partial({"enable_image": on})
        image_ready = False
        applied = "saved"
        agent = _running_agent()
        if agent is not None:
            image_ready = bool(await agent.set_image_enabled(on))  # type: ignore[attr-defined]
            applied = "live"
        else:
            from . import service as service_mod
            from . import singleton

            if singleton.held_by_other() and service_mod.is_installed():
                applied = "service" if service_mod.kickstart() else "saved"
        return web.json_response({"ok": True, "on": on, "imageReady": image_ready, "applied": applied})

    async def cloud_settings(req: web.Request) -> web.Response:
        """클라우드 AI 설정 — Gemini 키(관리자 1개로 서버 무료 제공)·ComfyUI 주소. body {geminiApiKey?, comfyUrl?}.

        키는 이 PC config 에만 저장(central 엔 안 올림). Gemini 는 **라이브 적용**(재시작 없이 풀에 광고),
        comfyUrl 변경(이미지 백엔드 전환)은 다음 이미지 토글/재연결에 반영(needsRestart).
        """
        _auth(req)
        try:
            data = await req.json()
        except Exception:  # noqa: BLE001
            data = {}
        out: dict = {"ok": True}
        if "geminiApiKey" in data:
            key = str(data.get("geminiApiKey") or "").strip()
            persist_partial({"gemini_api_key": key})
            agent = _running_agent()
            if agent is not None:
                out["geminiValid"] = bool(await agent.set_gemini_key(key))  # type: ignore[attr-defined]
            out["geminiConfigured"] = bool(key)
        if "comfyUrl" in data:
            comfy = str(data.get("comfyUrl") or "").strip().rstrip("/")
            persist_partial({"comfy_url": comfy})
            out["comfyUrl"] = comfy
            out["needsRestart"] = True
        if "hfToken" in data:
            tok = str(data.get("hfToken") or "").strip()
            persist_partial({"hf_token": tok})  # gated 모델 다운로드용. 이 PC 에만.
            out["hfConfigured"] = bool(tok)
        return web.json_response(out)

    async def connect_open(req: web.Request) -> web.Response:
        """‘토큰 받기’: 앱 창은 그대로 두고 **시스템 기본 브라우저**에서 디스코드 OAuth 를 연다.

        웹뷰 내부에서 디스코드 로그인을 열면 차단될 수 있어 호스트 브라우저로 연다. cb 는 이 로컬
        서버의 콜백(localhost)만 허용, state 는 서버 세션키를 사용(콜백에서 검증).
        """
        _auth(req)
        import re
        import webbrowser
        from urllib.parse import quote

        if not _connect_enabled():
            return web.json_response(
                {"ok": False, "error": "서버에 디스코드 로그인(OAuth)이 아직 설정되지 않았어요. 토큰으로 추가하세요."}
            )
        data = await req.json()
        origin = str(data.get("origin", "")).strip()
        if not re.fullmatch(r"http://(127\.0\.0\.1|localhost)(:\d+)?", origin):
            return web.json_response({"ok": False, "error": "로컬 주소가 아닙니다."})
        saved = load_config()
        base = _connect_base(saved.get("relay_url") or _default_relay())
        cb = origin + "/connect/callback"
        url = f"{base}/provider/connect?cb={quote(cb, safe='')}&state={quote(session_key, safe='')}"
        try:
            webbrowser.open(url)
        except Exception:  # noqa: BLE001
            return web.json_response({"ok": False, "error": "브라우저를 열 수 없습니다."})
        return web.json_response({"ok": True})

    async def connect_callback(req: web.Request) -> web.Response:
        """‘토큰 받기’ OAuth 콜백: 중앙 서버가 디스코드 인증 후 token·state 를 붙여 리디렉트.

        state==세션키로 위조 방지(이 콜백은 상위 네비게이션이라 X-Session 헤더가 없음).
        성공하면 토큰을 저장하고 메인 화면으로 되돌린다.
        """
        if req.query.get("state") != session_key:
            return web.Response(status=403, text="잘못된 요청(state 불일치)")
        # 서버가 토큰 대신 error 를 보낼 수 있다(취소·승인대기·인증실패) — 친절히 안내.
        err = (req.query.get("error") or "").strip()
        token = (req.query.get("token") or "").strip()
        if not token:
            messages = {
                "pending": "🕒 관리자 승인을 기다리는 중입니다. 승인되면 다시 ‘연동하기’를 눌러 주세요.",
                "cancelled": "취소되었습니다. 다시 시도하려면 앱에서 ‘연동하기’를 눌러 주세요.",
                "token": "디스코드 인증에 실패했어요. 다시 시도해 주세요.",
                "identify": "디스코드 사용자 확인에 실패했어요. 다시 시도해 주세요.",
            }
            msg = messages.get(err, "토큰을 받지 못했습니다. 다시 시도해 주세요.")
            return web.Response(
                text="<!doctype html><meta charset=utf-8><body style='font-family:system-ui;background:#0d0f12;color:#e8eaed;text-align:center;padding-top:80px'>"
                f"<div style='max-width:360px;margin:0 auto;line-height:1.6'>{msg}<br><b>이 탭을 닫고 앱으로 돌아가세요.</b></div>"
                "<script>setTimeout(()=>window.close(),2200)</script>",
                content_type="text/html",
            )
        # 멀티-서버: 서버가 함께 보내는 guild(id)·guildName 으로 '내 서버 목록'에 추가(교체 아님).
        gid_raw = (req.query.get("guild") or "").strip()
        guild_id = int(gid_raw) if gid_raw.isdigit() else None
        guild_name = (req.query.get("guildName") or "").strip() or None
        agent = _state["agent"]
        task = _state["task"]
        if agent is not None and task is not None and not task.done():
            await agent.add_connection(token, guild_id, guild_name)  # 실행 중이면 즉시 새 서버 접속
        else:
            from .config_file import add_connection

            add_connection(token, guild_id, guild_name)  # 저장 후
            _start_agent()  # 저장된 모든 서버로 접속 시작
        label = guild_name or "서버"
        return web.Response(
            text="<!doctype html><meta charset=utf-8><body style='font-family:system-ui;background:#0d0f12;color:#b8ff39;text-align:center;padding-top:80px'>"
            f"✅ ‘{label}’ 연동 완료! 자동으로 연결했어요. <b>이 탭을 닫고 앱 창으로 돌아가세요.</b>"
            "<script>setTimeout(()=>window.close(),1800)</script>",
            content_type="text/html",
        )

    async def start(req: web.Request) -> web.Response:
        _auth(req)
        return web.json_response(_start_agent())

    async def stop(req: web.Request) -> web.Response:
        _auth(req)
        agent = _state["agent"]
        task = _state["task"]
        if agent is not None:
            agent.request_stop()
        if task is not None:
            try:
                await asyncio.wait_for(asyncio.shield(task), timeout=10)
            except (TimeoutError, asyncio.CancelledError, Exception) as exc:  # noqa: BLE001
                # CancelledError(정상 취소)는 기대된 것 — 나머지 예외도 종료 경로라 전파하지 않되 흔적은 남긴다(예외 원칙 3).
                logger.debug("백그라운드 작업 정리 중 예외(무시): %s", exc)
        _state["agent"] = None
        _state["task"] = None
        from . import singleton

        singleton.release()  # 다음 시작/다른 인스턴스를 위해 락 해제
        return web.json_response({"ok": True})

    async def service_stop(req: web.Request) -> web.Response:
        """백그라운드 자동시작 서비스를 중지한다(앱 안에서 직접 연결하고 싶을 때)."""
        _auth(req)
        from .service import stop_service

        try:
            ok = await asyncio.to_thread(stop_service)
        except Exception as exc:  # noqa: BLE001
            return web.json_response({"ok": False, "error": str(exc)})
        return web.json_response({"ok": bool(ok)})

    async def logout(req: web.Request) -> web.Response:
        """로그아웃(연동 해제·초기화): 실행 중 에이전트 중지 + 저장된 토큰·서버 연결 제거.

        온보딩(첫 연동) 상태로 되돌린다. Ollama/이미지 등 다른 설정은 보존한다.
        """
        _auth(req)
        # 1) 실행 중이면 중지(stop 과 동일 절차)
        agent = _state["agent"]
        task = _state["task"]
        if agent is not None:
            agent.request_stop()
        if task is not None:
            try:
                await asyncio.wait_for(asyncio.shield(task), timeout=10)
            except (TimeoutError, asyncio.CancelledError, Exception) as exc:  # noqa: BLE001
                # CancelledError(정상 취소)는 기대된 것 — 나머지 예외도 종료 경로라 전파하지 않되 흔적은 남긴다(예외 원칙 3).
                logger.debug("백그라운드 작업 정리 중 예외(무시): %s", exc)
        _state["agent"] = None
        _state["task"] = None
        from . import singleton

        singleton.release()
        # 2) 토큰·서버 연결 비우기 → 온보딩 상태(다른 설정 보존)
        from .config_file import save_connections

        save_connections([])  # connections·token 모두 비움(다른 설정은 그대로)
        return web.json_response({"ok": True})

    async def reset_all(req: web.Request) -> web.Response:
        """초기화: 실행 중 에이전트 중지 + 설정 파일 전체 삭제 → 모든 설정 기본값.

        로그아웃과 분리된 동작 — 토큰·연결뿐 아니라 Ollama/이미지 등 모든 설정을 비운다.
        """
        _auth(req)
        agent = _state["agent"]
        task = _state["task"]
        if agent is not None:
            agent.request_stop()
        if task is not None:
            try:
                await asyncio.wait_for(asyncio.shield(task), timeout=10)
            except (TimeoutError, asyncio.CancelledError, Exception) as exc:  # noqa: BLE001
                # CancelledError(정상 취소)는 기대된 것 — 나머지 예외도 종료 경로라 전파하지 않되 흔적은 남긴다(예외 원칙 3).
                logger.debug("백그라운드 작업 정리 중 예외(무시): %s", exc)
        _state["agent"] = None
        _state["task"] = None
        from . import singleton

        singleton.release()
        from .config_file import config_path

        try:
            config_path().unlink(missing_ok=True)  # 설정 파일 전체 삭제 → 기본값
        except OSError as exc:
            # 삭제 실패는 다음 실행에 이전 설정(토큰 등)이 남는다는 뜻 — 조용히 넘기지 않는다(예외 원칙 3).
            logger.warning("설정 파일 삭제 실패 — 다음 실행에 이전 설정이 남을 수 있음: %s", exc)
        return web.json_response({"ok": True})

    async def onboard_apply(req: web.Request) -> web.Response:
        """온보딩 선택(이미지 제공·자동 실행/연결 등)을 **실제로** 설정·시스템에 반영(토큰·연결은 건드리지 않음).

        - ``autostart`` → 로그인 시 자동 실행 서비스 등록(``install_service``). GUI 가 먼저 singleton 락을
          쥐어, RunAtLoad 로 곧장 뜨는 헤드리스 서비스가 이번 세션 연결을 가로채지 않게 한다(/api/setup 과 동일).
        - ``background`` → 트레이 상주(``tray``)로 저장 → 다음 실행부터 트레이에서 계속 대기.
        - ``autoConnect`` → 저장 후 GUI 재시작 시 on_startup 훅이 저장된 서버로 자동 연결.
        - ``enableImage`` → SD 이미지 생성 capability 광고(즉시 반영).
        """
        _auth(req)
        try:
            data = await req.json()
        except Exception:  # noqa: BLE001
            data = {}
        from .config_file import persist_partial

        autostart = bool(data.get("autostart", True))
        persist_partial(
            {
                "enable_image": bool(data.get("enableImage")),
                "auto_connect": bool(data.get("autoConnect", True)),
                "background": bool(data.get("background", True)),
                # background 상주는 기존 트레이 기능으로 실현 — 서비스/에이전트 실행 모드가 읽는다.
                "tray": bool(data.get("background", True)),
                "autostart_pref": autostart,
            }
        )
        service_installed = False
        service_error: str | None = None
        if autostart:
            # /api/setup 의 installService 와 동일 패턴: GUI 가 먼저 락을 쥐고 서비스를 등록한다.
            try:
                from . import singleton

                singleton.acquire()
                from .service import install_service

                install_service()
                service_installed = True
            except Exception as exc:  # noqa: BLE001 - 등록 실패는 온보딩을 막지 않되 사유는 남긴다
                service_error = str(exc)
                logging.getLogger("provider_agent").warning("온보딩 자동시작 서비스 등록 실패: %s", exc)
        return web.json_response({"ok": True, "serviceInstalled": service_installed, "serviceError": service_error})

    # 통합 설정 키 매핑(camelCase API ↔ snake config). GET/POST 양쪽이 같은 표를 본다(드리프트 방지).
    # 모두 SAVEABLE 또는 온보딩 토글(auto_connect/background/autostart_pref)이라 persist_partial 로 안전히 저장된다.
    _SETTINGS_MAP = {
        "autostart": "autostart_pref",
        "background": "background",
        "autoConnect": "auto_connect",
        "autoUpdate": "auto_update",
        "enableImage": "enable_image",
        "ollamaUrl": "ollama_url",
        "relayUrl": "relay_url",
        "allowRemoteOllama": "allow_remote_ollama",
    }
    # 저장만으로는 실행 중 에이전트에 반영되지 않아 재연결이 필요한 항목(시작 시점 config 만 읽는다).
    # enable_image 도 라이브 in-process 전파 경로가 없으므로(과거 함정) 재시작 대상에 포함한다 — '즉시반영' 흉내 금지.
    _SETTINGS_NEEDS_RESTART = {"relayUrl", "ollamaUrl", "enableImage", "allowRemoteOllama"}

    async def settings_get(req: web.Request) -> web.Response:
        """분산돼 있던 설정(setup/onboard-apply/auto-update)을 한 번에 조회(camelCase).

        저장 설정(load_config)을 snake→camel 로 변환해 통합 반환한다. 토큰 자체는 노출하지 않고
        보유 여부만(hasToken) 내려 데스크톱 앱 설정 화면이 단일 GET 으로 현재 값을 그릴 수 있게 한다.
        """
        _auth(req)
        saved = load_config()
        return web.json_response(
            {
                "autostart": bool(saved.get("autostart_pref")),
                "background": bool(saved.get("background")),
                "autoConnect": bool(saved.get("auto_connect")),
                "autoUpdate": bool(saved.get("auto_update", True)),
                "enableImage": bool(saved.get("enable_image")),
                "ollamaUrl": saved.get("ollama_url") or "http://localhost:11434",
                "relayUrl": saved.get("relay_url") or _default_relay(),
                "allowRemoteOllama": bool(saved.get("allow_remote_ollama")),
                "geminiConfigured": bool(saved.get("gemini_api_key")),
                "comfyUrl": str(saved.get("comfy_url") or ""),
                "hfConfigured": bool(saved.get("hf_token")),
                "hasToken": bool(saved.get("token")),
            }
        )

    async def settings_post(req: web.Request) -> web.Response:
        """통합 설정 부분 변경: body ``{key: value}``(한 번에 1개 이상). 허용 키만 persist_partial 로 저장.

        런타임 반영: 실행 중 에이전트에 안전히 즉시 반영할 수 있는 항목은 없다(시작 시점 config 만 읽음).
        재연결이 필요한 항목(relayUrl/ollamaUrl/enableImage/allowRemoteOllama)이 하나라도 바뀌면
        ``needsRestart: true`` 로 알려 거짓 '즉시반영'을 피한다(과거 enable_image 라이브 전파 미작동 함정).
        """
        _auth(req)
        try:
            data = await req.json()
        except Exception:  # noqa: BLE001 - 잘못된 본문은 빈 변경으로 취급
            data = {}
        updates: dict = {}
        needs_restart = False
        for key, value in (data.items() if isinstance(data, dict) else []):
            snake = _SETTINGS_MAP.get(key)
            if snake is None:
                continue  # 허용되지 않은 키는 무시
            updates[snake] = value
            if key in _SETTINGS_NEEDS_RESTART:
                needs_restart = True
        if updates:
            persist_partial(updates)
        # autostart 는 저장만으론 효과가 없다 — **실제로** 로그인 자동시작 서비스를 등록/해제해야 한다
        # (과거: 설정 화면 토글이 autostart_pref 만 저장하고 서비스는 onboarding 에서만 등록되던 버그).
        service_error: str | None = None
        if isinstance(data, dict) and "autostart" in data:
            from . import service as service_mod
            from . import singleton

            try:
                if bool(data.get("autostart")):
                    singleton.acquire()  # GUI 가 락을 쥐고 등록(헤드리스 인스턴스 충돌 방지)
                    service_mod.install_service()
                else:
                    service_mod.uninstall_service()
            except Exception as exc:  # noqa: BLE001 - 실패 사유만 표면화(저장은 유지)
                service_error = str(exc)
                logging.getLogger("provider_agent").warning("autostart 서비스 적용 실패: %s", exc)
        # autoConnect 를 켜면 ≤45s 주기 동기화를 기다리지 않고 **즉시** 승인된 서버에 연결 시도(라이브).
        if isinstance(data, dict) and data.get("autoConnect"):
            agent = _running_agent()
            if agent is not None:
                try:
                    await agent.sync_now()  # type: ignore[attr-defined]
                except Exception as exc:  # noqa: BLE001 - 동기화 실패는 다음 주기 재시도
                    logging.getLogger("provider_agent").warning("autoConnect 즉시 동기화 실패: %s", exc)
        return web.json_response({"ok": True, "needsRestart": needs_restart, "serviceError": service_error})

    async def open_folder(req: web.Request) -> web.Response:
        """로컬 폴더를 OS 파일 탐색기로 연다(데스크톱 앱 ⋯ '출력/모델 폴더 열기'). 로컬 전용 — 같은 PC.

        body {which}=comfyModels: ComfyUI 체크포인트 폴더(.safetensors 를 넣으면 자동 인식).
        body {which}=comfyOutputs(기본): ComfyUI 출력 폴더(생성된 이미지). 둘 다 없으면 만들어서 연다.
        """
        _auth(req)
        import subprocess
        import sys

        from . import comfy_setup

        try:
            body = await req.json()
        except Exception:  # noqa: BLE001 - 본문 없으면 기본값
            body = {}
        which = (body.get("which") if isinstance(body, dict) else None) or "comfyOutputs"
        if which == "comfyModels":
            # ComfyUI 체크포인트 폴더(여기에 .safetensors 를 넣으면 자동 인식). 없으면 만들어서 연다.
            target = comfy_setup.model_dir()
        else:
            # ComfyUI 출력 폴더(생성 이미지). ComfyUI 는 install_dir/output 에 저장한다.
            target = comfy_setup.install_dir() / "output"
        target.mkdir(parents=True, exist_ok=True)
        try:
            if sys.platform == "darwin":
                subprocess.Popen(["open", str(target)])
            elif sys.platform.startswith("win"):
                subprocess.Popen(["explorer", str(target)])
            else:
                subprocess.Popen(["xdg-open", str(target)])
        except OSError as exc:
            return web.json_response({"ok": False, "error": str(exc)})
        return web.json_response({"ok": True, "path": str(target)})

    # 정적 자산 라우트(/, /mascot.png, /app-icon.png, /*.js, /img/*)는 _register_asset_routes 가 등록함.
    app.router.add_get("/api/models", models)
    app.router.add_get("/api/status", status)
    app.router.add_get("/api/logs", logs)
    app.router.add_get("/connect/callback", connect_callback)
    app.router.add_post("/api/connect-open", connect_open)
    _register_server_routes(app, session_key)
    _register_update_routes(app, session_key)
    _register_ollama_routes(app, session_key)
    _register_comfy_routes(app, session_key)
    app.router.add_post("/api/setup", setup)
    app.router.add_post("/api/start", start)
    app.router.add_post("/api/stop", stop)
    app.router.add_post("/api/service-stop", service_stop)
    app.router.add_post("/api/logout", logout)
    app.router.add_post("/api/reset", reset_all)
    app.router.add_post("/api/onboard-apply", onboard_apply)
    # 통합 설정 — 분산된 setup/onboard-apply/auto-update 를 단일 GET/POST 로 통합(데스크톱 앱 설정 화면).
    app.router.add_get("/api/settings", settings_get)
    app.router.add_post("/api/settings", settings_post)
    app.router.add_post("/api/open-folder", open_folder)
    app.router.add_post("/api/image", image_toggle)
    app.router.add_post("/api/cloud", cloud_settings)

    async def _autoconnect_on_startup(_app: web.Application) -> None:
        """온보딩에서 '로그인 후 자동 연결'을 켰고 저장된 서버가 있으면, GUI 가 뜨자마자 자동 연결한다.

        서버 이벤트 루프에서 도는 on_startup 훅이라 ``_start_agent``(asyncio.create_task)를 바로 쓸 수 있다.
        auto_connect 키가 없으면(온보딩 미완료 기존 사용자) 아무것도 하지 않아 깜짝 자동연결을 막는다.
        """
        from .config_file import load_connections

        saved = load_config()
        if bool(saved.get("auto_connect")) and load_connections():
            res = _start_agent()
            if not res.get("ok"):
                logging.getLogger("provider_agent").info("자동 연결 보류: %s", res.get("error"))

    app.on_startup.append(_autoconnect_on_startup)
    return app


def _start_server_thread(app: web.Application, host: str, port: int) -> str:
    """aiohttp 서버를 데몬 스레드(자체 이벤트 루프)에서 띄우고 실제 URL 을 돌려준다.

    네이티브 웹뷰는 메인 스레드를 점유하므로 서버는 별도 스레드에서 돌려야 한다.
    """
    info: dict = {}
    ready = threading.Event()

    def _serve() -> None:
        loop = asyncio.new_event_loop()
        asyncio.set_event_loop(loop)
        runner = web.AppRunner(app)
        loop.run_until_complete(runner.setup())
        site = web.TCPSite(runner, host, port)
        loop.run_until_complete(site.start())
        actual = site._server.sockets[0].getsockname()[1]  # type: ignore[union-attr]
        info["url"] = f"http://{host}:{actual}/"
        ready.set()
        loop.run_forever()

    threading.Thread(target=_serve, daemon=True).start()
    ready.wait(10)
    return str(info["url"])


def _webview_available() -> bool:
    import os

    if os.getenv("AGENT_GUI_BROWSER"):  # 강제 브라우저 모드(옵트아웃)
        return False
    try:
        import webview  # noqa: F401
    except ImportError:
        return False
    return True



def _brand_icon_png(size: int = 512) -> bytes | None:
    """dock 아이콘용 Nexa 로고 PNG 바이트. 번들 에셋을 우선 로드한다."""
    try:
        from importlib import resources

        return (resources.files("provider_agent") / "assets" / "app-icon.png").read_bytes()
    except Exception:  # noqa: BLE001 - 에셋 없으면 dock 아이콘 미설정(치명적 아님)
        return None


def _set_macos_app_identity(name: str) -> None:
    """macOS dock/메뉴바 앱 이름·아이콘 설정. 번들 아닌 Python 프로세스가 'Python'/로켓으로 뜨는 것 교정.

    pyobjc(웹뷰 cocoa 백엔드 의존)·PIL 이 없거나 macOS 가 아니면 조용히 무시(GUI 동작 불변).
    """
    import sys

    if sys.platform != "darwin":
        return
    try:
        from Foundation import NSBundle  # type: ignore[import-not-found]

        bundle = NSBundle.mainBundle()
        info = bundle.localizedInfoDictionary() or bundle.infoDictionary()
        if info is not None:
            info["CFBundleName"] = name
            info["CFBundleDisplayName"] = name
    except Exception as exc:  # noqa: BLE001 - 이름 설정 실패는 치명적이지 않음
        logger.debug("macOS 앱 이름 설정 실패(무시): %s", exc)
    try:
        png = _brand_icon_png()
        if png is None:
            return
        from AppKit import NSApplication, NSImage  # type: ignore[import-not-found]
        from Foundation import NSData  # type: ignore[import-not-found]

        data = NSData.dataWithBytes_length_(png, len(png))
        image = NSImage.alloc().initWithData_(data)
        if image is not None:
            NSApplication.sharedApplication().setApplicationIconImage_(image)
    except Exception as exc:  # noqa: BLE001 - 아이콘 설정 실패는 치명적이지 않음
        logger.debug("macOS 앱 아이콘 설정 실패(무시): %s", exc)


def _schedule_exit(delay: float = 1.2) -> None:
    """응답을 보낸 뒤 곧 프로세스를 종료(업데이트 헬퍼가 교체·재실행하도록). 데몬 타이머."""
    import os
    import threading

    threading.Timer(delay, lambda: os._exit(0)).start()


def _handoff_to_service_on_close() -> None:
    """창을 닫을 때, 자동시작 서비스가 등록돼 있으면 백그라운드로 연결을 **인계**한다.

    GUI 가 열려 있는 동안엔 GUI 가 singleton 락을 쥐어 서비스가 비어 있다(서비스는 RunAtLoad 로
    떴다가 락 충돌로 즉시 정상종료 → KeepAlive 미적용이라 그대로 죽어 있음). 그래서 예전엔 앱을
    닫으면 다음 로그인 전까지 백그라운드 연결이 없었다. 이제 창을 닫는 순간 락을 풀고 서비스를
    kickstart 해, 앱을 닫아도 끊김 없이 백그라운드에서 계속 연결되게 한다.
    """
    try:
        from . import service, singleton
        from .config_file import load_connections

        if not service.is_installed():
            return
        saved = load_config()
        if not saved.get("token") and not load_connections():
            return  # 연결할 토큰이 없으면 인계할 것도 없음
        singleton.release()  # GUI 락 해제 → 서비스가 락을 잡을 수 있게
        if service.kickstart():
            logging.getLogger("provider_agent").info("창 닫힘 — 백그라운드 서비스로 연결을 인계했습니다.")
    except Exception as exc:  # noqa: BLE001 - 인계 실패는 종료를 막지 않는다
        logging.getLogger("provider_agent").warning("백그라운드 인계 실패: %s", exc)


def _start_auto_update_watcher() -> None:
    """auto_update(기본 ON)면 **시작 시 + 주기적으로** 새 버전을 검사·적용한다(실행 중에도).

    앱을 켜 둔 채 새 버전이 나와도 다음 주기에 자동으로 받아 교체·재실행한다(껐다 켜야만
    적용되는 문제 해소). 다운로드 진행률은 _progress 에 반영되어 열려 있는 GUI 에 프로그래스바로
    보인다. 빌드된 앱·구버전·지원 OS 일 때만 실제 교체. 실패·미설정은 조용히 무시한다.
    간격은 기본 2시간(AGENT_UPDATE_INTERVAL_S 로 조정, 테스트용).
    """
    import os
    import threading
    import time

    interval = max(30.0, float(os.getenv("AGENT_UPDATE_INTERVAL_S") or 7200))
    retry = min(300.0, interval)  # 전이 실패/미적용 시 짧게 재시도(시작 시 네트워크 전이 실패로 2시간 스트랜드 방지)

    def _loop() -> None:
        while True:
            outcome = _auto_update_once()
            if outcome == "applied":
                return  # 적용 시작 → 곧 종료·재실행되므로 루프 종료
            # '최신 확인됨'일 때만 긴 간격, 그 외(네트워크 전이 실패·적용 실패)는 짧게 재시도한다.
            # (예전엔 한 번 실패하면 무조건 2시간 잤다 → 시작 시 일시적 네트워크 실패로 업데이트가 안 됐다.)
            time.sleep(interval if outcome == "uptodate" else retry)

    threading.Thread(target=_loop, daemon=True).start()


def _auto_update_once() -> str:
    """자동 업데이트 1회 검사. 결과: "applied"(적용 시작) | "uptodate"(확인됨·최신/미지원) | "pending"(재시도).

    "pending" 은 네트워크 전이 실패·확인 불가·적용 실패 등 — 호출부가 **짧게 재시도**해야 하는 경우다.
    """
    from . import updater

    try:
        if not bool(load_config().get("auto_update", True)):
            return "uptodate"  # 자동 업데이트 끔 → 자주 재시도할 이유 없음
        if updater.is_updating():
            return "pending"
        info = updater.check()
        if info.get("error"):
            return "pending"  # 네트워크 전이 실패(시작 시 흔함) → 짧게 재시도
        if info.get("outdated") and info.get("supported"):
            logging.getLogger("provider_agent").info(
                "자동 업데이트: v%s → v%s 적용", info.get("current"), info.get("latest")
            )
            result = updater.apply_update()  # _progress 갱신(프로그래스바) + swap 헬퍼
            if result.get("ok") and result.get("restarting"):
                _schedule_exit()  # 헬퍼가 교체·재실행
                return "applied"
            return "pending"  # 적용 실패 → 재시도
        return "uptodate"  # 확인됨 + 최신(또는 이 OS 미지원) → 긴 간격
    except Exception:  # noqa: BLE001 - 자동 업데이트 실패는 앱 동작을 막지 않는다
        return "pending"


def run_gui(host: str = "127.0.0.1", port: int = 0) -> None:
    """로컬 제어판을 네이티브 앱 창으로 띄운다(웹뷰 없으면 브라우저 폴백)."""
    session_key = secrets.token_urlsafe(24)
    app = build_app(session_key)
    url = _start_server_thread(app, host, port)
    print(
        f"\n제어판: {url}\n(창이 안 보이면 위 주소를 브라우저에서 여세요. AGENT_GUI_BROWSER=1 로 브라우저 강제.)\n",
        flush=True,
    )
    _start_auto_update_watcher()  # 자동 업데이트 ON 이면 시작 시+주기적으로 검사·적용(실행 중에도)
    _start_connect_status_refresher()  # 서버의 디스코드 로그인(OAuth) 활성 여부 주기 갱신

    if _webview_available():
        try:
            import webview  # type: ignore[import-untyped]

            _set_macos_app_identity(APP_DISPLAY_NAME)  # dock 이름/아이콘을 'Python'/로켓 대신 브랜드로
            # 가로로 긴(landscape) 기본 크기 — UI 가 사이드바(232px)+메인 그리드라 세로형(600x800)이면
            # 메인이 비좁고 잘려 보였다. 16:10 비율의 가로형 기본값 + 가로형 최소 크기로 통일.
            webview.create_window(
                f"로컬 AI 제공자 설정 · {APP_DISPLAY_NAME}", url, width=1180, height=760, min_size=(1000, 660)
            )
            webview.start()  # 메인 스레드 점유, 창 닫으면 반환
            _handoff_to_service_on_close()  # 닫을 때 백그라운드 서비스로 연결 인계(설치돼 있으면, 비블로킹)
            # 창을 닫았는데도 즉시 안 꺼지고 '응답없음'으로 멈추던 문제(실증) 방지: 인터프리터 finalize·
            # 배경 데몬 스레드(asyncio 루프·SD 서브프로세스 transport)가 종료를 지연시키지 않게 즉시 강제 종료.
            # 인계는 detached 로 이미 떠났고, webui/agent 는 데몬·별프로세스라 정리할 것이 없다.
            import os

            os._exit(0)
            return
        except Exception as exc:  # noqa: BLE001 - 웹뷰 실패 시 브라우저로 폴백
            logging.getLogger("provider_agent").warning(
                "네이티브 창 실패(%s) — 브라우저로 폴백", exc
            )

    import webbrowser

    try:
        webbrowser.open(url)
    except Exception as exc:  # noqa: BLE001
        # 웹뷰도 브라우저도 실패하면 사용자는 창을 못 본다 — 원인을 남겨 진단 가능하게 한다(예외 원칙 3·4).
        logger.warning("브라우저 열기 실패 — 수동으로 %s 를 열어주세요: %s", url, exc)

    async def _block() -> None:
        await asyncio.Event().wait()

    try:
        asyncio.run(_block())
    except KeyboardInterrupt:
        pass
