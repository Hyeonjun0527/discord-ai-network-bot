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

from aiohttp import web

from .config import AgentConfig, config_from_args
from .config_file import load_config, save_config
from .constants import AGENT_VERSION
from .i18n import t
from .netguard import RemoteOllamaBlocked, ensure_ollama_allowed

# 공개 기본 중앙 서버(유저는 입력하지 않음). 자체호스팅만 고급에서 바꿀 수 있다.
DEFAULT_RELAY = "wss://discord-ai.yeon.world/agent"


def _default_relay() -> str:
    """기본 중앙 서버 주소. 로컬 개발은 RELAY_URL 환경변수로 우회(예: ws://localhost:8085/agent)."""
    import os

    return (os.getenv("RELAY_URL") or DEFAULT_RELAY).rstrip("/")


_mascot_cache: bytes | None = None


def _mascot_bytes() -> bytes:
    """패키지 에셋의 마스코트 PNG(1회 로드·캐시). 없으면 빈 바이트(이미지만 깨지고 동작은 정상)."""
    global _mascot_cache
    if _mascot_cache is None:
        try:
            from importlib import resources

            _mascot_cache = (resources.files("provider_agent") / "assets" / "mascot.png").read_bytes()
        except Exception:  # noqa: BLE001
            _mascot_cache = b""
    return _mascot_cache


# 최근 로그 라인(대시보드 표시용).
_log_lines: deque[str] = deque(maxlen=200)
_log_attached = False


class _RingHandler(logging.Handler):
    def emit(self, record: logging.LogRecord) -> None:
        try:
            _log_lines.append(self.format(record))
        except Exception:  # noqa: BLE001
            pass


def _attach_log_capture() -> None:
    global _log_attached
    if _log_attached:
        return
    h = _RingHandler()
    h.setFormatter(
        logging.Formatter("%(asctime)s %(levelname)-5s | %(message)s", datefmt="%H:%M:%S")
    )
    logging.getLogger("provider_agent").addHandler(h)
    _log_attached = True


# 실행 중 에이전트 상태(웹 루프와 같은 태스크).
_state: dict = {"agent": None, "task": None}


async def _detect_models() -> list[str]:
    """로컬 Ollama 의 설치 모델 목록(자동 감지). 실패하면 빈 목록."""
    from .ollama import OllamaClient, OllamaError

    saved = load_config()
    url = saved.get("ollama_url") or "http://localhost:11434"
    try:
        return await OllamaClient(url).list_models()
    except OllamaError:
        return []


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
            headers={"Accept": "application/json", "User-Agent": f"nyassistant-agent/{AGENT_VERSION}"},
        )
        with urllib.request.urlopen(req, timeout=4, context=ctx) as resp:  # noqa: S310 - https 고정
            return bool(json.loads(resp.read().decode("utf-8")).get("enabled"))
    except Exception:  # noqa: BLE001 - 서버 미설정/네트워크 실패는 비활성으로
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
            except Exception:  # noqa: BLE001
                pass
            time.sleep(interval_s)

    threading.Thread(target=_loop, daemon=True).start()


def _page(session_key: str) -> str:
    """세션 키를 주입한 제어판 HTML. 디자인은 데스크톱 앱 카드(레퍼런스), 동작은 실제 API 연결."""
    return _PAGE_TEMPLATE.replace("__SESSION_KEY__", session_key).replace("__VERSION__", AGENT_VERSION)


# macOS 데스크톱 앱 카드 디자인(레퍼런스) — 정적 목업을 실제 백엔드(/api/*)에 연결.
# 세션 키는 __SESSION_KEY__ 자리에 주입(secrets.token_urlsafe → URL-safe 문자만이라 안전).
_PAGE_TEMPLATE = r"""<!doctype html><html lang="ko"><head><meta charset="utf-8">
<meta name="viewport" content="width=device-width,initial-scale=1"><title>로컬 AI 제공자 설정 — 냥시스턴트</title><style>
:root{color-scheme:dark;--bg:#08111d;--bg2:#040811;--line:rgba(148,163,184,.17);--line2:rgba(79,125,255,.36);--text:#edf4ff;--muted:#a7b3c5;--faint:#76849a;--blue:#4f7dff;--blue2:#2f63d6;font-family:-apple-system,BlinkMacSystemFont,"Segoe UI","Apple SD Gothic Neo","Noto Sans KR",sans-serif}
*{box-sizing:border-box}html,body{min-height:100%}
body{margin:0;color:var(--text);background:radial-gradient(circle at 18% 0%,rgba(64,96,180,.18),transparent 32%),radial-gradient(circle at 85% 16%,rgba(54,86,156,.10),transparent 28%),linear-gradient(180deg,var(--bg) 0%,var(--bg2) 100%);display:block;padding:0}
body::before{content:"";position:fixed;inset:0;pointer-events:none;opacity:.10;background-image:linear-gradient(rgba(148,163,184,.18) 1px,transparent 1px),linear-gradient(90deg,rgba(148,163,184,.18) 1px,transparent 1px);background-size:34px 34px;mask-image:linear-gradient(to bottom,rgba(0,0,0,.92),transparent 84%)}
button,input{font:inherit}
/* 클릭 가능한 모든 요소는 손가락 포인터(데스크톱 webview 에서 기본 화살표로 보이는 문제 방지). */
button,summary,.toggle,.model,[onclick]{cursor:pointer}
button:disabled{cursor:default}
.window{width:100%;min-height:100vh}
.appver{position:fixed;top:11px;right:13px;z-index:6;font:700 11.5px/1 ui-monospace,Menlo,monospace;color:var(--faint);background:rgba(8,17,29,.7);border:1px solid var(--line);border-radius:8px;padding:4px 8px;letter-spacing:.02em;backdrop-filter:blur(6px)}
main{padding:22px 20px 22px}
.hero{display:grid;grid-template-columns:48px minmax(0,1fr);gap:13px;align-items:center;margin-bottom:14px}
.logo{width:48px;height:48px;border-radius:14px;overflow:hidden;border:1px solid rgba(122,156,219,.34);box-shadow:0 0 0 5px rgba(79,125,255,.06);background:#0e1623}.logo img{width:100%;height:100%;object-fit:cover}
h1{margin:0;font-size:clamp(19px,3.2vw,24px);line-height:1.15;letter-spacing:-.04em;font-weight:850}
.sub{margin:4px 0 0;color:var(--muted);font-size:13px;line-height:1.5;letter-spacing:-.02em}
.card{border:1px solid var(--line);border-radius:16px;background:radial-gradient(circle at 14% 50%,rgba(79,125,255,.08),transparent 30%),linear-gradient(180deg,rgba(17,29,45,.82),rgba(10,17,29,.88));padding:15px;display:grid;grid-template-columns:72px 1fr;gap:15px;align-items:center;margin-bottom:6px}
.ring{width:66px;height:66px;border-radius:50%;border:3px solid var(--blue);box-shadow:inset 0 0 0 9px rgba(79,125,255,.03);display:grid;place-items:center;color:var(--blue)}
.ring.off{border-color:#39435a;color:#5a657a;box-shadow:none}.ring.connecting{border-color:#ffbd2e;color:#ffbd2e}
.ring svg{width:32px;height:32px}
.status-title{font-size:19px;font-weight:850;letter-spacing:-.03em;margin-bottom:4px}.status-body{color:var(--muted);font-size:13px;margin-bottom:11px}
.chips{display:flex;flex-wrap:wrap;gap:7px}.chip{display:inline-flex;align-items:center;gap:7px;min-height:30px;padding:0 12px;border-radius:9px;border:1px solid rgba(148,163,184,.13);background:rgba(255,255,255,.02);color:#dce6f4;font-size:12.5px}
.dot{width:9px;height:9px;border-radius:50%;background:#5ab8ff;box-shadow:0 0 0 4px rgba(90,184,255,.10)}.dot.grey{background:#5a657a;box-shadow:none}
section{margin-top:15px}h2{margin:0 0 9px;font-size:17px;font-weight:850;letter-spacing:-.03em}
.token-row{display:grid;grid-template-columns:1fr 150px;gap:10px}
.input{min-height:50px;display:flex;align-items:center;gap:11px;padding:0 16px;border-radius:13px;border:1px solid rgba(148,163,184,.15);background:rgba(255,255,255,.02);color:var(--muted)}
.input svg{width:20px;height:20px;flex:0 0 auto}.input input{width:100%;background:transparent;border:0;outline:0;color:var(--text);font-size:15px}.input input::placeholder{color:#8b97aa}
.secondary-btn{min-height:50px;border-radius:13px;border:1px solid var(--line2);background:rgba(79,125,255,.05);color:#eef4ff;font-weight:800;font-size:14px;cursor:pointer}
.helper{margin:8px 0 0;color:var(--muted);font-size:12.5px;line-height:1.55}
.grid2{display:grid;grid-template-columns:repeat(2,minmax(0,1fr));gap:12px}
.model{position:relative;min-height:76px;display:grid;grid-template-columns:40px 1fr;gap:12px;align-items:center;padding:13px 15px;border-radius:14px;border:1px solid rgba(148,163,184,.14);background:linear-gradient(180deg,rgba(17,28,43,.68),rgba(10,17,28,.76));cursor:pointer;text-align:left;color:var(--text);opacity:.5;transition:opacity .12s,border-color .12s}
.model.is-selected{opacity:1;border-color:var(--line2);background:linear-gradient(180deg,rgba(25,39,65,.82),rgba(11,20,36,.82))}
.model .mcheck{position:absolute;top:9px;right:9px;width:22px;height:22px;border-radius:7px;display:grid;place-items:center;background:linear-gradient(180deg,#4b82ff,#285bcb);opacity:0;transition:opacity .12s}
.model.is-selected .mcheck{opacity:1}.model .mcheck svg{width:14px;height:14px;color:#fff}
.model-icon{width:38px;height:38px;color:#dbe6f4}
.model-name{font-size:16px;font-weight:800;letter-spacing:-.02em;margin-bottom:6px;word-break:break-all}
.badge{display:inline-flex;align-items:center;height:24px;padding:0 9px;border-radius:7px;border:1px solid rgba(79,125,255,.34);background:rgba(79,125,255,.08);color:#89acff;font-size:12px;font-weight:800}.badge.neutral{color:#c4cedd;border-color:rgba(148,163,184,.20);background:rgba(148,163,184,.08)}
.empty{color:#8492a6;font-size:13px;grid-column:1/-1}.empty code{background:#152133;padding:2px 7px;border-radius:6px;color:#cdd}
.settings{border-radius:14px;border:1px solid rgba(148,163,184,.14);background:linear-gradient(180deg,rgba(17,28,43,.68),rgba(10,17,28,.76));padding:0 16px}
.setting{min-height:58px;display:grid;grid-template-columns:38px 1fr 54px;gap:12px;align-items:center;border-bottom:1px solid rgba(148,163,184,.10)}.setting:last-child{border-bottom:0}
.iconbox{width:38px;height:38px;border-radius:11px;display:grid;place-items:center;background:rgba(255,255,255,.04);border:1px solid rgba(255,255,255,.05);color:#b9c6d9}.iconbox svg{width:20px;height:20px}
.setting-title{font-size:15px;font-weight:850;letter-spacing:-.02em;margin-bottom:3px}.setting-desc{color:var(--muted);font-size:12.5px;line-height:1.5}
.toggle{justify-self:end;width:50px;height:30px;border-radius:999px;border:1px solid rgba(255,255,255,.08);background:#2b3445;padding:3px;position:relative;cursor:pointer}
.toggle::after{content:"";position:absolute;top:3px;left:3px;width:22px;height:22px;border-radius:50%;background:#ebf0f7;box-shadow:0 2px 8px rgba(0,0,0,.25);transition:left .15s}
.toggle.on{background:linear-gradient(180deg,var(--blue),var(--blue2))}.toggle.on::after{left:23px}
.primary-btn{width:100%;min-height:54px;margin-top:14px;border-radius:14px;border:1px solid rgba(128,160,228,.34);background:linear-gradient(180deg,var(--blue),var(--blue2));color:#fff;font-size:18px;font-weight:850;letter-spacing:-.02em;box-shadow:0 14px 34px rgba(47,99,214,.18),inset 0 1px 0 rgba(255,255,255,.16);cursor:pointer}
.primary-btn.stop{background:linear-gradient(180deg,#b3402f,#8c2f24)}
#msg{margin-top:10px;font-size:13px;min-height:17px;text-align:center}.err{color:#ff8a8a}.ok{color:#9fe0a0}
details{margin-top:10px;border-radius:13px;border:1px solid rgba(148,163,184,.14);background:rgba(255,255,255,.018);overflow:hidden}
summary{list-style:none;min-height:46px;display:flex;align-items:center;justify-content:space-between;padding:0 18px;font-size:15px;font-weight:800;cursor:pointer}summary::-webkit-details-marker{display:none}
.details-body{border-top:1px solid rgba(148,163,184,.10);padding:12px 16px;color:var(--muted);line-height:1.6}
#log{background:#070d16;border:1px solid rgba(148,163,184,.14);border-radius:9px;padding:10px;height:120px;overflow:auto;font:12px/1.5 ui-monospace,Menlo,monospace;color:#8fa0b6;white-space:pre-wrap}
.ro{word-break:break-all;color:#9fb0c6}
.pbar{height:8px;border-radius:6px;background:rgba(148,163,184,.15);overflow:hidden;margin-top:10px;display:none}
.pfill{height:100%;width:0;background:linear-gradient(90deg,var(--blue),var(--blue2));border-radius:6px;transition:width .25s ease}
@media (max-width:560px){.hero{grid-template-columns:1fr}.card{grid-template-columns:1fr}.token-row{grid-template-columns:1fr}.grid2{grid-template-columns:1fr}}
</style></head><body>
<div class="window">
<div class="appver" title="설치된 버전">v__VERSION__</div>
<main>
<section class="hero"><div class="logo"><img src="/mascot.png" alt="냥시스턴트 마스코트"></div><div><h1>AI 네트워크 구축 도우미 · 냥시스턴트</h1><div class="sub">내 PC를 Discord 서버의 로컬 AI 노드로 연결합니다.</div></div></section>
<section class="card"><div class="ring off" id="ring"><svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2.4" stroke-linecap="round" stroke-linejoin="round"><path d="M20 6 9 17l-5-5"></path></svg></div>
<div><div class="status-title" id="stitle">대기 중</div><div class="status-body" id="ssub">연결 시작을 누르면 풀에 등록됩니다.</div><div class="chips" id="chips"></div></div></section>
<section><h2>1. 제공 모델</h2><div class="grid2" id="models"></div></section>
<section><h2>2. 설정</h2><div class="settings">
<div class="setting"><div class="iconbox"><svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.8"><path d="M12 2v10"></path><path d="M18.4 6.6a9 9 0 1 1-12.8 0"></path></svg></div><div><div class="setting-title">시스템 로그인 시 자동 연결</div><div class="setting-desc">앱을 닫아도 로그인하면 백그라운드에서 자동으로 연결돼 있어요. 이 앱은 설정을 바꿀 때만 열면 됩니다.</div></div><div class="toggle" id="svc" onclick="this.classList.toggle('on')"></div></div>
<div class="setting"><div class="iconbox"><svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.8"><rect x="3" y="4" width="18" height="16" rx="2"></rect><circle cx="8.5" cy="9" r="1.5"></circle><path d="m21 15-5-5L5 21"></path></svg></div><div><div class="setting-title">이미지 생성 제공 <span class="badge neutral">선택</span></div><div class="setting-desc">Stable Diffusion 환경이 있으면 /imagine 요청을 처리합니다.</div></div><div class="toggle" id="img" onclick="this.classList.toggle('on')"></div></div>
</div>
<button class="primary-btn" type="button" id="go" onclick="connect()"><svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round" style="width:20px;height:20px;vertical-align:-4px;margin-right:9px"><path d="M9 17H7A5 5 0 0 1 7 7h2"></path><path d="M15 7h2a5 5 0 0 1 0 10h-2"></path><path d="M8 12h8"></path></svg><span>연동하기</span></button>
<div class="helper" style="text-align:center;margin-top:9px">처음이면 디스코드 로그인 창이 열려요. 한 번 연동하면 다음부턴 바로 연결됩니다.</div>
<div id="msg"></div>
<section id="serversSec" style="display:none;margin-top:16px"><h2>내 서버</h2>
<div class="settings" id="serverList"></div>
<button class="secondary-btn" type="button" id="addServerBtn" style="width:100%;margin-top:10px" onclick="addServer()">＋ 다른 서버에 연결(디스코드 로그인)</button>
<div class="helper" id="addServerHelp" style="margin-top:7px">여러 디스코드 서버의 프로바이더로 동시에 연결할 수 있어요.</div>
<details style="margin-top:10px"><summary><span>토큰으로 추가</span><span>⌄</span></summary><div class="details-body">
<label class="input" style="margin-bottom:9px"><input type="text" id="addTokName" placeholder="서버 별명(예: 우리 동아리)"></label>
<label class="input" style="margin-bottom:9px"><input type="password" id="addTokVal" placeholder="다른 서버의 /provider-join 토큰"></label>
<button class="secondary-btn" type="button" style="width:100%" onclick="addByToken()">추가</button>
<div class="helper" id="addTokHelp" style="margin-top:7px">디스코드 로그인 추가가 안 될 때, 그 서버에서 받은 토큰을 붙여넣어 추가합니다.</div></div></details></section>
<details><summary><span>로그 보기</span><span>⌄</span></summary><div class="details-body"><div id="log"></div></div></details>
<details><summary><span>고급 · 토큰 직접 입력</span><span>⌄</span></summary><div class="details-body">
<label class="input" style="margin-bottom:11px"><svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.8" style="width:20px;height:20px;flex:0 0 auto"><path d="M21 2l-2 2m-7.61 7.61a5.5 5.5 0 1 1-7.78 7.78 5.5 5.5 0 0 1 7.78-7.78Zm0 0L15.5 7.5m0 0 3 3L22 7l-3-3m-3.5 3.5L19 4"></path></svg><input type="password" id="token" placeholder="/provider-join 토큰 붙여넣기(선택)"></label>
중앙 서버(고정): <span class="ro" id="relay"></span>
<div id="installRow" style="display:none;margin-top:13px">
<button class="secondary-btn" type="button" id="installBtn" style="width:100%" onclick="install()">응용 프로그램에 추가하기</button>
<div class="helper" id="installHelp" style="margin-top:7px"></div></div>
<div id="updateRow" style="display:none;margin-top:14px;border-top:1px solid rgba(148,163,184,.10);padding-top:13px">
<div style="display:flex;align-items:center;justify-content:space-between;gap:10px">
<div><div style="font-weight:850;font-size:14px;letter-spacing:-.02em">자동 업데이트</div><div class="helper" style="margin-top:2px">새 버전이 나오면 앱 시작 때 자동으로 받아 적용합니다.</div></div>
<div class="toggle" id="autoupd" onclick="toggleAutoUpdate()"></div></div>
<div id="verStatus" style="margin-top:10px">버전 확인 중…</div>
<button class="secondary-btn" type="button" id="updateBtn" style="width:100%;margin-top:9px;display:none" onclick="doUpdate()"></button>
<div class="pbar" id="pbar"><div class="pfill" id="pfill"></div></div></div>
</div></details>
</section>
</main></div>
<script>
const K="__SESSION_KEY__";const H={"X-Session":K};let RUN=false;let HAS_MODELS=false;
const MICON='<svg class="model-icon" viewBox="0 0 80 80" fill="none" stroke="currentColor" stroke-width="3" stroke-linecap="round" stroke-linejoin="round"><path d="M27 20c0-6 7-8 11-4 4-4 11-2 11 4v18c0 10-6 18-17 18S15 48 15 38V26c0-5 4-9 9-9h3Z"></path><path d="M30 31h.1M46 31h.1M31 43c4 3 10 3 14 0"></path><path d="M20 55v10M42 55v10M52 48v14"></path></svg>';
const IINSTALL='<svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.9" stroke-linecap="round" stroke-linejoin="round" style="width:17px;height:17px;vertical-align:-4px;margin-right:8px"><path d="M12 3v10"></path><path d="m8 11 4 4 4-4"></path><rect x="4" y="16.5" width="16" height="4.5" rx="1.5"></rect></svg>';
const ICHECK='<svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2.4" stroke-linecap="round" stroke-linejoin="round" style="width:14px;height:14px;vertical-align:-2px;margin-right:6px"><path d="M20 6 9 17l-5-5"></path></svg>';
const IWARN='<svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round" style="width:14px;height:14px;vertical-align:-2px;margin-right:6px"><path d="M10.3 3.9 1.8 18a2 2 0 0 0 1.7 3h17a2 2 0 0 0 1.7-3L13.7 3.9a2 2 0 0 0-3.4 0Z"></path><path d="M12 9v4M12 17h.01"></path></svg>';
const ILINK='<svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round" style="width:20px;height:20px;vertical-align:-4px;margin-right:9px"><path d="M9 17H7A5 5 0 0 1 7 7h2"></path><path d="M15 7h2a5 5 0 0 1 0 10h-2"></path><path d="M8 12h8"></path></svg>';
const ISTOP='<svg viewBox="0 0 24 24" fill="currentColor" stroke="none" style="width:17px;height:17px;vertical-align:-3px;margin-right:9px"><rect x="6" y="6" width="12" height="12" rx="2.5"></rect></svg>';
async function j(u,o){o=o||{};o.headers=Object.assign({},H,o.headers||{});const r=await fetch(u,o);return r.json();}
function esc(s){return s.replace(/[&<>"']/g,c=>({'&':'&amp;','<':'&lt;','>':'&gt;','"':'&quot;',"'":'&#39;'}[c]));}
async function loadModels(){const d=await j('/api/models');const box=document.getElementById('models');HAS_MODELS=d.models.length>0;
if(!d.models.length){box.innerHTML='<div class="empty">Ollama에서 모델을 못 찾았어요. <code>ollama pull llama3.1:8b</code> 후 새로고침하세요.</div>';return;}
const CMK='<span class="mcheck"><svg viewBox="0 0 24 24" fill="none"><path d="M20 6 9 17l-5-5" stroke="currentColor" stroke-width="2.6" stroke-linecap="round" stroke-linejoin="round"></path></svg></span>';
box.innerHTML=d.models.map(m=>{const sel=d.selected.includes(m)||!d.selected.length;return `<article class="model${sel?' is-selected':''}" data-model="${esc(m)}" onclick="toggleModel(this)">${CMK}${MICON}<div><div class="model-name">${esc(m)}</div><span class="badge${sel?'':' neutral'}">${sel?'제공 중':'선택 안 함'}</span></div></article>`;}).join('');}
function toggleModel(el){el.classList.toggle('is-selected');const sel=el.classList.contains('is-selected');const b=el.querySelector('.badge');b.textContent=sel?'제공 중':'선택 안 함';b.className='badge'+(sel?'':' neutral');}
function selectedModels(){return [...document.querySelectorAll('.model.is-selected')].map(c=>c.dataset.model);}
function on(id){return document.getElementById(id).classList.contains('on');}
async function refresh(){const s=await j('/api/status');RUN=s.running;
document.getElementById('relay').textContent=s.relayUrl;
if(s.hasToken)document.getElementById('token').placeholder='저장됨 — 바꿀 때만 입력';
document.getElementById('img').classList.toggle('on',s.enableImage);
const ring=document.getElementById('ring');ring.className='ring'+(s.running?(s.connected?'':' connecting'):' off');
document.getElementById('stitle').textContent=s.running?(s.connected?'연결 완료':'연결하는 중…'):'대기 중';
document.getElementById('ssub').textContent=s.running?(s.connected?'이 PC가 로컬 AI 노드로 등록되었습니다.':'중앙 서버에 연결하고 있습니다.'):'연결 시작을 누르면 풀에 등록됩니다.';
const cnt=s.running?s.models.length:selectedModels().length;
let chips='<div class="chip"><span class="dot'+(HAS_MODELS?'':' grey')+'"></span>'+(HAS_MODELS?'Ollama 실행 중':'Ollama 확인 필요')+'</div>';
chips+='<div class="chip">제공 모델 '+cnt+'개</div>';
chips+='<div class="chip"><span class="dot'+(s.connected?'':' grey')+'"></span>'+(s.running?(s.connected?('처리 '+s.processed+'건'):'연결 시도 중'):'중지됨')+(s.imageReady?' · 🖼️':'')+'</div>';
document.getElementById('chips').innerHTML=chips;
const go=document.getElementById('go');go.innerHTML=s.running?ISTOP+'<span>중지</span>':ILINK+'<span>연동하기</span>';go.className='primary-btn'+(s.running?' stop':'');
const lg=await j('/api/logs');const el=document.getElementById('log');el.textContent=lg.lines.join('\n');el.scrollTop=el.scrollHeight;}
// 버튼 하나로 모든 걸: 실행 중이면 중지, 아니면 (설정 저장 → 토큰 있으면 바로 연결 / 없으면 브라우저 로그인 → 콜백이 자동 연결).
async function connect(){const msg=document.getElementById('msg');if(RUN){await j('/api/stop',{method:'POST'});await refresh();return;}
if(HAS_MODELS&&!selectedModels().length){msg.className='err';msg.textContent='⚠️ 제공할 모델을 1개 이상 선택하세요.';return;}
msg.className='';msg.textContent='설정 저장 중…';
const su=await j('/api/setup',{method:'POST',headers:{'Content-Type':'application/json'},body:JSON.stringify({token:document.getElementById('token').value.trim(),models:selectedModels(),enableImage:on('img'),installService:on('svc')})});
if(!su.ok){msg.className='err';msg.textContent='⚠️ '+(su.error||'저장 실패');return;}document.getElementById('token').value='';
const s=await j('/api/status');
if(s.hasToken){const st=await j('/api/start',{method:'POST'});if(!st.ok){msg.className='err';msg.textContent='⚠️ '+st.error;}else{msg.className='ok';msg.textContent='✅ 연결 시작'+(su.serviceInstalled?' · 자동 실행 등록':'');}await refresh();return;}
if(s.connectEnabled){const r=await j('/api/connect-open',{method:'POST',headers:{'Content-Type':'application/json'},body:JSON.stringify({origin:location.origin})});
if(r.ok){msg.className='ok';msg.textContent='🌐 브라우저에서 디스코드 로그인·승인하면 자동으로 연결됩니다.';}else{msg.className='err';msg.textContent='⚠️ '+(r.error||'브라우저 열기 실패');}return;}
msg.className='err';msg.textContent='⚠️ 토큰이 필요합니다. ‘고급’에서 /provider-join 토큰을 붙여넣어 주세요.';}
// 설치 버튼: 맥/윈도우만, 빌드된 앱에서만 활성. 상태는 거의 안 변하므로 1회 + 설치 후에만 갱신.
function setHelp(el,kind,msg){const c=kind==='ok'?'#9fe0a0':kind==='err'?'#ff8a8a':'var(--muted)';const ic=kind==='ok'?ICHECK:kind==='err'?IWARN:'';el.innerHTML='<span style="color:'+c+'">'+ic+esc(msg)+'</span>';}
async function loadInstall(){let d;try{d=await j('/api/install-info');}catch(e){return;}
const row=document.getElementById('installRow');if(!d||(d.platform!=='mac'&&d.platform!=='win')){row.style.display='none';return;}
row.style.display='block';const btn=document.getElementById('installBtn'),help=document.getElementById('installHelp');
btn.innerHTML=IINSTALL+'<span>'+esc(d.label)+'</span>';
if(!d.supported){btn.disabled=true;btn.style.opacity='.55';setHelp(help,'muted',d.reason||'지원되지 않아요.');return;}
btn.disabled=false;btn.style.opacity='';
if(d.installed){btn.disabled=true;btn.style.opacity='.55';setHelp(help,'ok','이미 설치되어 있어요.');}
else{setHelp(help,'muted',d.platform==='win'?'시작 메뉴에 ‘냥시스턴트’ 바로가기를 추가합니다.':'이 앱을 응용 프로그램 폴더로 복사합니다. (관리자 권한 불필요)');}}
async function install(){const btn=document.getElementById('installBtn'),help=document.getElementById('installHelp');
const old=btn.innerHTML;btn.disabled=true;btn.innerHTML='<span>설치 중…</span>';help.innerHTML='';
let r;try{r=await j('/api/install',{method:'POST'});}catch(e){r={ok:false,error:'요청에 실패했어요.'};}
if(r.ok){btn.style.opacity='.55';btn.innerHTML=ICHECK+'<span>완료</span>';setHelp(help,'ok',r.message||'완료했어요.');}
else{btn.disabled=false;btn.innerHTML=old;setHelp(help,'err',r.error||'실패했어요.');}}
// 업데이트: 현재/최신 버전 비교 + 자동 업데이트 토글 + (구버전이면) 수동 업데이트 버튼.
let AUTOUPD=true;
async function loadUpdate(){let d;try{d=await j('/api/update-info');}catch(e){return;}
const row=document.getElementById('updateRow');row.style.display='block';
AUTOUPD=!!d.autoUpdate;document.getElementById('autoupd').classList.toggle('on',AUTOUPD);
const vs=document.getElementById('verStatus'),btn=document.getElementById('updateBtn');btn.style.display='none';
if(d.error){setHelp(vs,'muted','현재 v'+d.current+' · '+d.error);return;}
if(d.outdated){setHelp(vs,'muted','현재 v'+d.current+' → 최신 v'+d.latest+' 업데이트 있음');
if(d.supported){btn.disabled=false;btn.style.display='block';btn.innerHTML=IINSTALL+'<span>지금 업데이트</span>';}
}else{setHelp(vs,'ok','현재 v'+d.current+' · 최신입니다');}}
async function toggleAutoUpdate(){AUTOUPD=!AUTOUPD;document.getElementById('autoupd').classList.toggle('on',AUTOUPD);
try{await j('/api/auto-update',{method:'POST',headers:{'Content-Type':'application/json'},body:JSON.stringify({autoUpdate:AUTOUPD})});}catch(e){}}
let UPDATING=false;
function fmtMB(b){return (b/1048576).toFixed(1);}
function renderProgress(p){const bar=document.getElementById('pbar'),fill=document.getElementById('pfill'),vs=document.getElementById('verStatus'),btn=document.getElementById('updateBtn');
const active=['downloading','verifying','installing','restarting'].includes(p.phase);
bar.style.display=active?'block':'none';if(active)btn.style.display='none';
if(p.phase==='downloading'){fill.style.width=(p.total>0?p.percent:25)+'%';
const sz=p.total>0?(' '+fmtMB(p.downloaded)+'/'+fmtMB(p.total)+'MB'):'';
setHelp(vs,'muted','내려받는 중 '+(p.total>0?p.percent+'%':'…')+sz);}
else if(p.phase==='verifying'){fill.style.width='100%';setHelp(vs,'muted','무결성 검증 중…');}
else if(p.phase==='installing'){fill.style.width='100%';setHelp(vs,'muted','설치 중…');}
else if(p.phase==='restarting'){fill.style.width='100%';setHelp(vs,'ok',p.message||'업데이트 완료 — 곧 다시 열립니다.');}
else if(p.phase==='error'){bar.style.display='none';setHelp(vs,'err',p.error||'업데이트 실패');}}
async function pollProgress(){let p;try{p=await j('/api/update-progress');}catch(e){return;}
const active=['downloading','verifying','installing','restarting'].includes(p.phase);
if(active){UPDATING=true;renderProgress(p);}
else if(p.phase==='error'){renderProgress(p);if(UPDATING){UPDATING=false;const b=document.getElementById('updateBtn');b.disabled=false;b.innerHTML=IINSTALL+'<span>다시 시도</span>';b.style.display='block';}}
else if(UPDATING){UPDATING=false;document.getElementById('pbar').style.display='none';loadUpdate();}}
async function doUpdate(){const btn=document.getElementById('updateBtn');btn.disabled=true;btn.innerHTML='<span>준비 중…</span>';UPDATING=true;
try{await j('/api/update',{method:'POST'});}catch(e){}}
// 내 서버 목록(멀티-서버): 연결된 디스코드 서버들 표시·서버별 해제·다른 서버 추가(OAuth).
async function loadServers(){let d;try{d=await j('/api/servers');}catch(e){return;}
const sec=document.getElementById('serversSec'),list=document.getElementById('serverList');
if(!d.servers||!d.servers.length){sec.style.display='none';return;}
sec.style.display='block';
list.innerHTML=d.servers.map((s,i)=>{const idx=s.index!=null?s.index:i;const named=!!(s.guildName);const nm=esc(s.guildName||(s.guildId?('서버 '+s.guildId):'이름 미상'));
return '<div style="display:flex;align-items:center;gap:10px;min-height:56px;border-bottom:1px solid rgba(148,163,184,.10)">'
+'<span class="dot'+(s.connected?'':' grey')+'"></span>'
+'<div style="flex:1;min-width:0"><div style="font-weight:800;font-size:14.5px;letter-spacing:-.02em;white-space:nowrap;overflow:hidden;text-overflow:ellipsis">'+nm+(named?'':' <span class="helper" style="font-weight:600">(이름 미상)</span>')+'</div>'
+'<div class="helper" style="margin:1px 0 0">'+(s.connected?'연결됨':'대기 중…')+'</div></div>'
+'<button class="secondary-btn" style="min-height:34px;padding:0 12px;font-size:13px" onclick="renameServer('+idx+')">수정</button>'
+'<button class="secondary-btn" style="min-height:34px;padding:0 12px;font-size:13px" onclick="removeServer('+idx+')">해제</button></div>';}).join('');}
async function removeServer(idx){await j('/api/server-remove',{method:'POST',headers:{'Content-Type':'application/json'},body:JSON.stringify({index:idx})});loadServers();refresh();}
async function renameServer(idx){const name=prompt('이 서버의 표시 이름을 입력하세요');if(name==null)return;
await j('/api/server-rename',{method:'POST',headers:{'Content-Type':'application/json'},body:JSON.stringify({index:idx,name:name})});loadServers();}
async function addByToken(){const help=document.getElementById('addTokHelp');const tok=document.getElementById('addTokVal').value.trim();const nm=document.getElementById('addTokName').value.trim();
if(!tok){help.innerHTML='<span style="color:#ff8a8a">토큰을 입력하세요.</span>';return;}
const r=await j('/api/server-add-token',{method:'POST',headers:{'Content-Type':'application/json'},body:JSON.stringify({token:tok,name:nm})});
if(r.ok){document.getElementById('addTokVal').value='';document.getElementById('addTokName').value='';help.innerHTML='<span style="color:#9fe0a0">추가했어요.</span>';loadServers();refresh();}
else{help.innerHTML='<span style="color:#ff8a8a">⚠️ '+esc(r.error||'추가 실패')+'</span>';}}
async function addServer(){const help=document.getElementById('addServerHelp');const s=await j('/api/status');
if(!s.connectEnabled){help.innerHTML='<span style="color:#ffd479">디스코드 로그인 추가가 아직 활성화되지 않았어요. ‘고급’에서 다른 서버의 /provider-join 토큰을 붙여넣어 추가하세요.</span>';return;}
const r=await j('/api/connect-open',{method:'POST',headers:{'Content-Type':'application/json'},body:JSON.stringify({origin:location.origin})});
help.innerHTML=r.ok?'<span style="color:#9fe0a0">🌐 브라우저에서 추가할 서버를 고르세요. 완료되면 목록에 나타납니다.</span>':('⚠️ '+esc(r.error||'브라우저 열기 실패'));}
loadModels();refresh();loadInstall();loadUpdate();loadServers();setInterval(refresh,2000);setInterval(loadServers,2500);setInterval(pollProgress,600);
</script></body></html>"""


def build_app(session_key: str) -> web.Application:
    _attach_log_capture()
    app = web.Application()

    def _auth(req: web.Request) -> None:
        if req.headers.get("X-Session") != session_key:
            raise web.HTTPForbidden(text="세션 키 불일치")

    async def index(_req: web.Request) -> web.Response:
        return web.Response(text=_page(session_key), content_type="text/html")

    async def mascot(_req: web.Request) -> web.Response:
        return web.Response(body=_mascot_bytes(), content_type="image/png")

    async def models(req: web.Request) -> web.Response:
        _auth(req)
        saved = load_config()
        return web.json_response(
            {"models": await _detect_models(), "selected": list(saved.get("models") or [])}
        )

    async def status(req: web.Request) -> web.Response:
        _auth(req)
        saved = load_config()
        agent = _state["agent"]
        task = _state["task"]
        running = task is not None and not task.done()
        return web.json_response(
            {
                "running": running,
                "connected": bool(agent and agent.is_connected()),
                "processed": int(agent.processed) if agent else 0,
                "imageReady": bool(agent and agent.image_ready),
                "models": list(agent.models) if agent else list(saved.get("models") or []),
                "hasToken": bool(saved.get("token")),
                "relayUrl": saved.get("relay_url") or _default_relay(),
                "enableImage": bool(saved.get("enable_image")),
                # ‘디스코드 로그인’ OAuth 가능 여부는 **서버 설정**으로 결정된다(에이전트 env 불필요).
                # 서버에 OAuth 앱(client-id/secret)이 설정돼 있으면 자동으로 켜진다.
                "connectEnabled": _connect_enabled(),
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
        models_list = [str(m).strip() for m in (data.get("models") or []) if str(m).strip()]
        enable_image = bool(data.get("enableImage"))
        relay = (saved.get("relay_url") or _default_relay()).rstrip("/")
        cfg = AgentConfig(
            token=token,
            relay_url=relay,
            models=tuple(models_list),
            enable_image=enable_image,
            auto_update=bool(saved.get("auto_update", True)),  # 저장된 자동업데이트 설정 보존(초기화 방지)
        )
        if enable_image:
            try:
                ensure_ollama_allowed(cfg.sd_url, cfg.allow_remote_sd)
            except RemoteOllamaBlocked:
                return web.json_response({"ok": False, "error": "SD 주소가 localhost 가 아닙니다."})
        save_config(cfg)
        service_installed = False
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
            except Exception:  # noqa: BLE001
                pass
        return web.json_response({"ok": True, "serviceInstalled": service_installed, "hasToken": bool(token)})

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

    async def servers(req: web.Request) -> web.Response:
        """'내 서버 목록': 실행 중이면 실시간 연결상태, 아니면 저장된 목록(연결 안 됨)."""
        _auth(req)
        agent = _state["agent"]
        task = _state["task"]
        if agent is not None and task is not None and not task.done():
            return web.json_response({"servers": agent.connections_status()})
        from .config_file import load_connections

        saved = load_connections()
        return web.json_response(
            {"servers": [
                {"index": i, "guildId": c.get("guild_id"), "guildName": c.get("guild_name"), "connected": False}
                for i, c in enumerate(saved)
            ]}
        )

    def _running_agent() -> object | None:
        agent = _state["agent"]
        task = _state["task"]
        return agent if (agent is not None and task is not None and not task.done()) else None

    async def server_remove(req: web.Request) -> web.Response:
        """서버 연결 해제(목록 index 기준 — 길드ID 없는 토큰-추가 연결도 정확히 지목)."""
        _auth(req)
        data = await req.json()
        try:
            index = int(data.get("index"))
        except (TypeError, ValueError):
            return web.json_response({"ok": False, "error": "잘못된 항목"})
        agent = _running_agent()
        if agent is not None:
            await agent.remove_connection_at(index)  # type: ignore[attr-defined]
        else:
            from .config_file import remove_connection_at

            remove_connection_at(index)
        return web.json_response({"ok": True})

    async def server_rename(req: web.Request) -> web.Response:
        """서버 표시 이름 바꾸기(토큰-추가 '이름 미상' 라벨링). index + name."""
        _auth(req)
        data = await req.json()
        try:
            index = int(data.get("index"))
        except (TypeError, ValueError):
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
            except (TimeoutError, asyncio.CancelledError, Exception):  # noqa: BLE001
                pass
        _state["agent"] = None
        _state["task"] = None
        from . import singleton

        singleton.release()  # 다음 시작/다른 인스턴스를 위해 락 해제
        return web.json_response({"ok": True})

    app.router.add_get("/", index)
    app.router.add_get("/mascot.png", mascot)
    app.router.add_get("/api/models", models)
    app.router.add_get("/api/status", status)
    app.router.add_get("/api/logs", logs)
    app.router.add_get("/connect/callback", connect_callback)
    app.router.add_post("/api/connect-open", connect_open)
    app.router.add_get("/api/servers", servers)
    app.router.add_post("/api/server-remove", server_remove)
    app.router.add_post("/api/server-rename", server_rename)
    app.router.add_post("/api/server-add-token", server_add_token)
    app.router.add_get("/api/install-info", install_info)
    app.router.add_post("/api/install", install)
    app.router.add_get("/api/update-info", update_info)
    app.router.add_get("/api/update-progress", update_progress)
    app.router.add_post("/api/update", update_apply)
    app.router.add_post("/api/auto-update", auto_update_set)
    app.router.add_post("/api/setup", setup)
    app.router.add_post("/api/start", start)
    app.router.add_post("/api/stop", stop)
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


APP_DISPLAY_NAME = "냥시스턴트"


def _brand_icon_png(size: int = 512) -> bytes | None:
    """dock 아이콘용 브랜드 마스코트(냥시스턴트 고양이) PNG 바이트. 번들 에셋을 우선 로드한다."""
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
    except Exception:  # noqa: BLE001 - 이름 설정 실패는 치명적이지 않음
        pass
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
    except Exception:  # noqa: BLE001 - 아이콘 설정 실패는 치명적이지 않음
        pass


def _schedule_exit(delay: float = 1.2) -> None:
    """응답을 보낸 뒤 곧 프로세스를 종료(업데이트 헬퍼가 교체·재실행하도록). 데몬 타이머."""
    import os
    import threading

    threading.Timer(delay, lambda: os._exit(0)).start()


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

    def _loop() -> None:
        while True:
            if _auto_update_once():
                return  # 적용 시작 → 곧 종료·재실행되므로 루프 종료
            time.sleep(interval)

    threading.Thread(target=_loop, daemon=True).start()


def _auto_update_once() -> bool:
    """자동 업데이트 1회 검사. 토글 ON + 구버전 + 지원이면 받아 적용하고 종료를 예약한다.

    적용을 시작했으면 True(다운로드는 _progress 로 프로그래스바에 반영). 실패·해당없음은 False.
    """
    from . import updater

    try:
        if not bool(load_config().get("auto_update", True)) or updater.is_updating():
            return False
        info = updater.check()
        if info.get("outdated") and info.get("supported"):
            logging.getLogger("provider_agent").info(
                "자동 업데이트: v%s → v%s 적용", info.get("current"), info.get("latest")
            )
            result = updater.apply_update()  # _progress 갱신(프로그래스바) + swap 헬퍼
            if result.get("ok") and result.get("restarting"):
                _schedule_exit()  # 헬퍼가 교체·재실행
                return True
    except Exception:  # noqa: BLE001 - 자동 업데이트 실패는 앱 동작을 막지 않는다
        pass
    return False


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
            webview.create_window(
                f"로컬 AI 제공자 설정 · {APP_DISPLAY_NAME}", url, width=600, height=800, min_size=(400, 600)
            )
            webview.start()  # 메인 스레드 점유, 창 닫으면 반환
            return
        except Exception as exc:  # noqa: BLE001 - 웹뷰 실패 시 브라우저로 폴백
            logging.getLogger("provider_agent").warning(
                "네이티브 창 실패(%s) — 브라우저로 폴백", exc
            )

    import webbrowser

    try:
        webbrowser.open(url)
    except Exception:  # noqa: BLE001
        pass

    async def _block() -> None:
        await asyncio.Event().wait()

    try:
        asyncio.run(_block())
    except KeyboardInterrupt:
        pass
