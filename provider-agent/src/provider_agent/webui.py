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
            headers={"Accept": "application/json", "User-Agent": f"nexa-agent/{AGENT_VERSION}"},
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
<meta name="viewport" content="width=device-width,initial-scale=1"><title>로컬 AI 제공자 설정 — NEXA</title><style>
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
.modal-back{position:fixed;inset:0;background:rgba(4,8,16,.62);backdrop-filter:blur(4px);display:none;align-items:center;justify-content:center;z-index:100;padding:18px}
.modal{width:min(520px,100%);max-height:90vh;overflow:auto;background:#0d1726;border:1px solid var(--line2);border-radius:18px;box-shadow:0 30px 80px rgba(0,0,0,.5)}
.modal-head{display:flex;align-items:center;justify-content:space-between;padding:18px 20px;border-bottom:1px solid var(--line)}
.modal-title{font-size:18px;font-weight:850}
.modal-x{background:none;border:0;color:#9fb0cc;font-size:20px;cursor:pointer;line-height:1}
.modal-body{padding:18px 20px}
.modal-foot{display:flex;gap:10px;padding:16px 20px;border-top:1px solid var(--line)}
.modal-foot button{flex:1}
.prereq{margin:12px 0 16px;padding:12px 14px;border-radius:12px;background:rgba(79,125,255,.06);border:1px solid var(--line);color:var(--muted);font-size:13px;line-height:1.7}
.mcard{display:flex;gap:11px;align-items:flex-start;padding:13px 14px;border-radius:13px;border:1px solid var(--line2);margin-top:9px;cursor:pointer;transition:.14s}
.mcard.sel{border-color:var(--blue);background:rgba(79,125,255,.08)}
.mcard .mradio{width:18px;height:18px;border-radius:50%;border:2px solid #5a657a;flex:0 0 auto;margin-top:2px}
.mcard.sel .mradio{border-color:var(--blue);box-shadow:inset 0 0 0 4px var(--blue)}
.mcard .mname{font-weight:800}.mcard .mmeta{color:var(--muted);font-size:12.5px;margin-top:2px}
.modal-msg{margin-top:12px;color:var(--muted);font-size:13px;min-height:18px}
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
#onboard{position:fixed;inset:0;z-index:60;background:radial-gradient(120% 80% at 50% -10%,#0e1a30,#070d18 62%);display:none;overflow:auto;padding:22px 18px 16px}
#onboard.show{display:block}
.onb-wrap{max-width:560px;margin:0 auto;min-height:calc(100% - 0px);display:flex;flex-direction:column}
.onb-top{display:flex;align-items:center;gap:13px;padding:2px 2px 0}
.onb-top .logo{width:52px;height:52px;border-radius:14px;overflow:hidden;border:1px solid rgba(122,156,219,.34);flex:0 0 auto}.onb-top .logo img{width:100%;height:100%;object-fit:cover}
.onb-top h1{font-size:18px;font-weight:850;letter-spacing:-.02em;margin:0}.onb-top .sub{color:var(--muted);font-size:12.5px;margin-top:2px}
.onb-ver{margin-left:auto;align-self:flex-start;font:12px ui-monospace,Menlo,monospace;color:#8fa0b6;border:1px solid var(--line);border-radius:8px;padding:3px 9px}
.onb-steps{display:flex;align-items:center;justify-content:center;gap:16px;margin:18px 0}
.onb-count{font-size:13px;font-weight:800;color:#cfe0ff;border:1px solid var(--line2);border-radius:9px;padding:5px 12px}
.onb-dots{display:flex;align-items:center;gap:9px}.onb-dots i{width:9px;height:9px;border-radius:50%;background:#33405a;display:block;transition:.2s}.onb-dots i.on{background:var(--blue);box-shadow:0 0 0 4px rgba(79,125,255,.14)}.onb-dots .bar{width:24px;height:2px;background:#2a3548}
.onb-card{flex:1;border:1px solid var(--line2);border-radius:20px;background:linear-gradient(180deg,rgba(20,32,56,.55),rgba(11,18,32,.55));padding:26px 22px;box-shadow:inset 0 1px 0 rgba(255,255,255,.04)}
.onb-circ{width:54px;height:54px;border-radius:50%;border:2px solid var(--blue);display:grid;place-items:center;color:var(--blue);margin:2px auto 16px}.onb-circ svg{width:24px;height:24px}
.onb-h{font-size:29px;font-weight:900;text-align:center;letter-spacing:-.03em;margin:0 0 12px}
.onb-d{color:var(--muted);text-align:center;line-height:1.7;font-size:14px;margin-bottom:20px}
.onb-row{display:flex;align-items:center;gap:14px;padding:14px 16px;border-radius:14px;border:1px solid var(--line);background:rgba(255,255,255,.015);margin-top:11px}
.onb-row .ib{width:42px;height:42px;border-radius:12px;display:grid;place-items:center;flex:0 0 auto}.onb-row .ib svg{width:21px;height:21px}
.onb-row .rt{font-weight:800;font-size:15px}.onb-row .rd{color:var(--muted);font-size:12.5px;margin-top:2px}
.onb-opt{cursor:pointer}.onb-opt.sel{border-color:var(--blue);background:rgba(79,125,255,.08)}
.onb-opt .chk{width:26px;height:26px;border-radius:50%;border:2px solid #5a657a;flex:0 0 auto;display:grid;place-items:center;color:#fff;margin-left:auto}.onb-opt .chk svg{width:15px;height:15px;opacity:0}.onb-opt.sel .chk{border-color:var(--blue);background:var(--blue)}.onb-opt.sel .chk svg{opacity:1}
.onb-info{display:flex;gap:12px;align-items:flex-start;margin-top:18px;padding:14px 15px;border-radius:13px;border:1px solid var(--line);color:var(--muted);font-size:12.5px;line-height:1.6}.onb-info b{color:#dbe5f5;font-weight:800}.onb-info svg{width:20px;height:20px;flex:0 0 auto;color:var(--blue)}
.onb-foot{display:flex;gap:12px;margin-top:16px;padding-bottom:4px}
.onb-foot button{flex:1;min-height:62px;border-radius:15px;cursor:pointer;font-weight:850;font-size:16px;border:1px solid var(--line2);background:rgba(79,125,255,.05);color:#eef4ff}
.onb-foot button.pri{flex:1.25;border-color:rgba(128,160,228,.34);background:linear-gradient(180deg,var(--blue),var(--blue2));color:#fff;box-shadow:0 12px 30px rgba(47,99,214,.2)}
.onb-foot .bsub{display:block;font-weight:600;font-size:11.5px;color:rgba(220,230,245,.55);margin-top:3px}.onb-foot button.pri .bsub{color:rgba(255,255,255,.8)}
.onb-sum{border:1px solid var(--line);border-radius:14px;padding:2px 16px;margin-bottom:16px}.onb-sum .sr{display:flex;align-items:center;gap:13px;padding:13px 0;border-bottom:1px solid var(--line)}.onb-sum .sr:last-child{border-bottom:0}.onb-sum .sr .ib{width:38px;height:38px;border-radius:11px;display:grid;place-items:center;flex:0 0 auto}.onb-sum .sv{margin-left:auto;font-weight:800;color:#cfe0ff}
.onb-redo{display:block;text-align:center;margin-top:14px;color:var(--muted);font-size:12.5px;cursor:pointer;background:none;border:0;width:100%}
</style></head><body>
<div class="window">
<div id="onboard"><div class="onb-wrap">
<div class="onb-top"><div class="logo"><img src="/app-icon.png" alt="NEXA 로고"></div><div><h1>NEXA Provider Agent</h1><div class="sub">내 PC를 Discord 서버의 로컬 AI 노드로 연결합니다.</div></div><div class="onb-ver">v__VERSION__</div></div>
<div class="onb-steps"><div class="onb-count" id="onbCount">1 / 4</div><div class="onb-dots" id="onbDots"></div></div>
<div class="onb-card" id="onbCard"></div>
<div class="onb-foot" id="onbFoot"></div>
</div></div>
<div class="appver" title="설치된 버전">v__VERSION__</div>
<main>
<section class="hero"><div class="logo"><img src="/app-icon.png" alt="NEXA 로고"></div><div><h1>NEXA Provider Agent</h1><div class="sub">내 PC를 Discord 서버의 로컬 AI 노드로 연결합니다.</div></div></section>
<section class="card"><div class="ring off" id="ring"><svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2.4" stroke-linecap="round" stroke-linejoin="round"><path d="M20 6 9 17l-5-5"></path></svg></div>
<div><div class="status-title" id="stitle">대기 중</div><div class="status-body" id="ssub">연결 시작을 누르면 풀에 등록됩니다.</div><div class="chips" id="chips"></div></div></section>
<section><h2>1. 제공 모델</h2><div class="grid2" id="models"></div></section>
<section><h2>2. 설정</h2><div class="settings">
<div class="setting"><div class="iconbox"><svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.8"><path d="M12 2v10"></path><path d="M18.4 6.6a9 9 0 1 1-12.8 0"></path></svg></div><div><div class="setting-title">시스템 로그인 시 자동 연결</div><div class="setting-desc">앱을 닫아도 로그인하면 백그라운드에서 자동으로 연결돼 있어요. 이 앱은 설정을 바꿀 때만 열면 됩니다.</div></div><div class="toggle" id="svc" onclick="this.classList.toggle('on')"></div></div>
<div class="setting"><div class="iconbox"><svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.8"><rect x="3" y="4" width="18" height="16" rx="2"></rect><circle cx="8.5" cy="9" r="1.5"></circle><path d="m21 15-5-5L5 21"></path></svg></div><div style="flex:1"><div class="setting-title">이미지 생성 제공 <span class="badge neutral">선택</span></div><div class="setting-desc">Stable Diffusion 으로 <b>/imagine</b> 이미지 생성을 직접 제공합니다.</div><button class="btn" type="button" id="imgInstallBtn" style="display:none;margin-top:9px" onclick="openSD()">＋ 로컬 이미지 모델 설치</button><button class="secondary-btn" type="button" id="imgStartBtn" style="display:none;margin-top:9px;min-height:38px" onclick="startSDApp()">▶ Stable Diffusion 시작</button><div class="setting-desc" id="sdState" style="margin-top:7px"></div></div><div class="toggle" id="img" onclick="this.classList.toggle('on')"></div></div>
</div>
<button class="primary-btn" type="button" id="go" onclick="connect()"><svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round" style="width:20px;height:20px;vertical-align:-4px;margin-right:9px"><path d="M9 17H7A5 5 0 0 1 7 7h2"></path><path d="M15 7h2a5 5 0 0 1 0 10h-2"></path><path d="M8 12h8"></path></svg><span>연동하기</span></button>
<div class="helper" style="text-align:center;margin-top:9px">처음이면 디스코드 로그인 창이 열려요. 한 번 연동하면 다음부턴 바로 연결됩니다.</div>
<div id="bgBar" style="display:none;margin-top:11px"></div>
<div id="applyBar" style="display:none;margin-top:11px"></div>
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
<details><summary><span>고급</span><span>⌄</span></summary><div class="details-body">
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
<div style="margin-top:14px;border-top:1px solid rgba(148,163,184,.10);padding-top:13px">
<button class="secondary-btn" type="button" id="logoutBtn" style="width:100%" onclick="logout()">로그아웃</button>
<div class="helper" style="margin-top:6px">저장된 토큰·서버 연결만 지우고 처음 연동 상태로 돌아갑니다(다른 설정은 유지).</div>
<button class="secondary-btn" type="button" id="resetBtn" style="width:100%;margin-top:11px" onclick="resetAll()">초기화</button>
<div class="helper" style="margin-top:6px">모든 설정을 기본값으로 되돌립니다(토큰·연결·Ollama·이미지 설정 전부).</div></div>
</div></details>
</section>
</main></div>
<div class="modal-back" id="sdModal">
  <div class="modal">
    <div class="modal-head"><div class="modal-title">로컬 이미지 모델 설치</div><button class="modal-x" type="button" onclick="closeSD()" aria-label="닫기">✕</button></div>
    <div class="modal-body">
      <div class="setting-desc">Stable Diffusion 으로 <b>/imagine</b> 이미지 생성을 직접 제공합니다. 이 마법사가 필요한 것을 모두 준비합니다:</div>
      <div class="prereq">① git &nbsp;②&nbsp; 이미지 엔진용 Python &nbsp;③&nbsp; Stable Diffusion(A1111) &nbsp;④&nbsp; 아래에서 고른 모델<br><span style="color:#8b97aa">없는 도구는 자동으로 설치돼요. 첫 준비는 모델 용량·엔진 설치로 시간이 걸립니다.</span></div>
      <div id="sdModelList"></div>
      <div class="pbar" id="sdmpbar" style="margin-top:14px"><div class="pfill" id="sdmpfill"></div></div>
      <div class="modal-msg" id="sdmMsg"></div>
    </div>
    <div class="modal-foot">
      <button class="secondary-btn" type="button" id="sdCancelBtn" onclick="cancelSD()">취소</button>
      <button class="primary-btn" type="button" id="sdStartBtn" style="margin-top:0" onclick="startSD()">설치 시작</button>
    </div>
  </div>
</div>
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
if(!d.models.length){box.innerHTML='<div class="empty">아직 사용할 AI 모델이 없어요. 아래 버튼이면 Ollama 설치부터 모델 다운로드까지 자동으로 해드려요.<br><button class="btn" id="osetupBtn" style="margin-top:12px" onclick="setupOllama()">Ollama 자동 설치 + 모델 받기</button><div class="pbar" id="opbar" style="margin-top:12px"><div class="pfill" id="opfill"></div></div><div id="osetup" style="margin-top:8px;color:var(--muted)"></div></div>';return;}
const CMK='<span class="mcheck"><svg viewBox="0 0 24 24" fill="none"><path d="M20 6 9 17l-5-5" stroke="currentColor" stroke-width="2.6" stroke-linecap="round" stroke-linejoin="round"></path></svg></span>';
box.innerHTML=d.models.map(m=>{const sel=d.selected.includes(m)||!d.selected.length;return `<article class="model${sel?' is-selected':''}" data-model="${esc(m)}" onclick="toggleModel(this)">${CMK}${MICON}<div><div class="model-name">${esc(m)}</div><span class="badge${sel?'':' neutral'}">${sel?'제공 중':'선택 안 함'}</span></div></article>`;}).join('');}
function toggleModel(el){el.classList.toggle('is-selected');const sel=el.classList.contains('is-selected');const b=el.querySelector('.badge');b.textContent=sel?'제공 중':'선택 안 함';b.className='badge'+(sel?'':' neutral');}
function selectedModels(){return [...document.querySelectorAll('.model.is-selected')].map(c=>c.dataset.model);}
async function setupOllama(){const b=document.getElementById('osetupBtn'),bar=document.getElementById('opbar'),el=document.getElementById('osetup');if(b){b.disabled=true;b.style.opacity=.6;b.style.cursor='default';}if(bar)bar.style.display='block';if(el)el.textContent='시작 중…';try{await j('/api/ollama/setup',{method:'POST'});}catch(e){}pollOllamaSetup();}
async function pollOllamaSetup(){const el=document.getElementById('osetup'),fill=document.getElementById('opfill');let p;try{p=await j('/api/ollama/setup-progress');}catch(e){setTimeout(pollOllamaSetup,1500);return;}if(fill&&p.percent!=null)fill.style.width=p.percent+'%';if(p.phase==='error'){if(el)el.innerHTML='⚠ 설치 실패: '+esc(String(p.error||p.message||''));const b=document.getElementById('osetupBtn');if(b){b.disabled=false;b.style.opacity=1;b.style.cursor='pointer';b.textContent='다시 시도';}return;}if(el)el.textContent=(p.message||p.phase||'')+(p.percent?(' ('+p.percent+'%)'):'');if(p.phase==='done'){if(fill)fill.style.width='100%';setTimeout(loadModels,800);return;}setTimeout(pollOllamaSetup,1500);}
// ── 이미지(SD) 설치 마법사 ──
let SD_MODELS=[],SD_SEL='',SD_BUSY=false;
async function loadSDStatus(){let s;try{s=await j('/api/sd/status');}catch(e){return;}const ib=document.getElementById('imgInstallBtn'),sb=document.getElementById('imgStartBtn'),tog=document.getElementById('img');const have=s.installed||s.ready;
if(ib)ib.style.display=(!s.installed&&!s.ready)?'inline-flex':'none';   // 미설치 → 설치 버튼
if(sb)sb.style.display=(s.installed&&!s.ready&&!s.busy)?'inline-flex':'none'; // 설치됨·미실행 → 시작 버튼
if(tog)tog.style.display=have?'block':'none';
const st=document.getElementById('sdState');if(!st)return;
if(s.ready)st.innerHTML='<span style="color:#9fe0a0">'+ICHECK+'Stable Diffusion 실행 중 — 이미지 생성 가능</span>';
else if(s.busy)st.innerHTML='<span style="color:var(--muted)">Stable Diffusion 준비 중…</span>';
else if(s.installed)st.innerHTML='<span style="color:#ffd479">'+IWARN+'설치됨 · <b>미실행</b> — 아래 <b>시작</b> 을 누르세요. (연결돼 있으면 준비되는 대로 자동 반영됩니다.)</span>';
else st.innerHTML='';}
async function startSDApp(){const st=document.getElementById('sdState'),b=document.getElementById('imgStartBtn');if(b){b.disabled=true;b.textContent='시작 중…';}if(st)st.innerHTML='<span style="color:var(--muted)">Stable Diffusion 시작 중… (첫 실행 이후라 보통 1~2분)</span>';try{await j('/api/sd/start',{method:'POST'});}catch(e){}pollSDStart();}
async function pollSDStart(){let p;try{p=await j('/api/sd/setup-progress');}catch(e){setTimeout(pollSDStart,2000);return;}const st=document.getElementById('sdState'),b=document.getElementById('imgStartBtn');
if(p.phase==='error'){if(st)st.innerHTML='<span style="color:#ff8a8a">'+IWARN+'시작 실패: '+esc(String(p.error||p.message||''))+'</span>';if(b){b.disabled=false;b.textContent='▶ Stable Diffusion 시작';}return;}
if(p.phase==='done'){if(b){b.disabled=false;b.textContent='▶ Stable Diffusion 시작';}loadSDStatus();return;}
if(st)st.innerHTML='<span style="color:var(--muted)">'+esc(p.message||p.phase||'시작 중…')+(p.percent?(' ('+p.percent+'%)'):'')+'</span>';
setTimeout(pollSDStart,2000);}
async function openSD(){const m=document.getElementById('sdModal');m.style.display='flex';document.getElementById('sdmMsg').textContent='';document.getElementById('sdmpbar').style.display='none';document.getElementById('sdmpfill').style.width='0';try{const d=await j('/api/sd/models');SD_MODELS=d.models||[];SD_SEL=d.default||(SD_MODELS[0]&&SD_MODELS[0].id);}catch(e){SD_MODELS=[];}renderSDModels();
const sp=await j('/api/sd/setup-progress').catch(()=>null);SD_BUSY=sp&&['installing','downloading','starting'].includes(sp.phase);if(SD_BUSY){lockSDStart();pollSDSetup();}else{unlockSDStart();}}
function closeSD(){if(SD_BUSY){if(!confirm('설치가 진행 중입니다. 닫으면 백그라운드로 계속됩니다. 닫을까요?'))return;}document.getElementById('sdModal').style.display='none';}
function renderSDModels(){const box=document.getElementById('sdModelList');box.innerHTML=SD_MODELS.map(m=>`<div class="mcard${m.id===SD_SEL?' sel':''}" onclick="pickSD('${m.id}')"><div class="mradio"></div><div><div class="mname">${esc(m.name)}</div><div class="mmeta">${esc(m.desc)} · ${esc(m.size)}</div></div></div>`).join('');}
function pickSD(id){if(SD_BUSY)return;SD_SEL=id;renderSDModels();}
function lockSDStart(){const b=document.getElementById('sdStartBtn');b.disabled=true;b.style.opacity=.6;b.textContent='설치 중…';document.getElementById('sdmpbar').style.display='block';}
function unlockSDStart(){const b=document.getElementById('sdStartBtn');b.disabled=false;b.style.opacity=1;b.textContent='설치 시작';b.onclick=startSD;}
async function startSD(){SD_BUSY=true;lockSDStart();document.getElementById('sdmMsg').textContent='시작 중…';try{await j('/api/sd/setup',{method:'POST',headers:{'Content-Type':'application/json'},body:JSON.stringify({model:SD_SEL})});}catch(e){}pollSDSetup();}
async function cancelSD(){if(SD_BUSY){try{await j('/api/sd/cancel',{method:'POST'});}catch(e){}}else{closeSD();}}
async function pollSDSetup(){const msg=document.getElementById('sdmMsg'),fill=document.getElementById('sdmpfill');let p;try{p=await j('/api/sd/setup-progress');}catch(e){setTimeout(pollSDSetup,2000);return;}if(fill&&p.percent!=null)fill.style.width=p.percent+'%';
if(p.phase==='error'){SD_BUSY=false;msg.innerHTML='⚠ 설치 실패: '+esc(String(p.error||p.message||''));const b=document.getElementById('sdStartBtn');b.disabled=false;b.style.opacity=1;b.textContent='다시 시도';b.onclick=startSD;return;}
if(p.phase==='cancelled'){SD_BUSY=false;msg.textContent='설치를 취소했어요.';unlockSDStart();return;}
msg.textContent=(p.message||p.phase||'')+(p.percent?(' ('+p.percent+'%)'):'');
if(p.phase==='done'){SD_BUSY=false;if(fill)fill.style.width='100%';msg.innerHTML='✅ 준비 완료 — 이미지 생성이 켜졌어요. <b>연동하기</b>를 누르면 디스코드에서 바로 쓸 수 있어요.';const t=document.getElementById('img');if(t&&!t.classList.contains('on'))t.classList.add('on');loadSDStatus();const foot=document.getElementById('sdStartBtn');foot.disabled=false;foot.style.opacity=1;foot.textContent='완료';foot.onclick=closeSD;return;}
setTimeout(pollSDSetup,2000);}
function on(id){return document.getElementById(id).classList.contains('on');}
async function refresh(){const s=await j('/api/status');RUN=s.running;
onbVisibility(s.hasToken);
document.getElementById('relay').textContent=s.relayUrl;
if(s.hasToken)document.getElementById('token').placeholder='저장됨 — 바꿀 때만 입력';
// 이미지 토글: 멈춰 있을 때만 저장값으로 동기화(실행 중엔 사용자가 바꾼 의도를 보존 → '변경 적용' 감지).
if(!s.running)document.getElementById('img').classList.toggle('on',s.enableImage);
const bg=!s.running&&s.backgroundRunning;  // 백그라운드 자동시작 서비스가 이미 연결 중
const ring=document.getElementById('ring');ring.className='ring'+(s.running?(s.connected?'':' connecting'):(bg?'':' off'));
document.getElementById('stitle').textContent=s.running?(s.connected?'연결 완료':'연결하는 중…'):(bg?'백그라운드에서 실행 중':'대기 중');
document.getElementById('ssub').textContent=s.running?(s.connected?'이 PC가 로컬 AI 노드로 등록되었습니다.':'중앙 서버에 연결하고 있습니다.'):(bg?'백그라운드 서비스가 이미 연결돼 있어요. 이 창은 설정 변경용입니다.':'연결 시작을 누르면 풀에 등록됩니다.');
const cnt=s.running?s.models.length:selectedModels().length;
let chips='<div class="chip"><span class="dot'+(HAS_MODELS?'':' grey')+'"></span>'+(HAS_MODELS?'Ollama 실행 중':'Ollama 확인 필요')+'</div>';
chips+='<div class="chip">제공 모델 '+cnt+'개</div>';
chips+='<div class="chip"><span class="dot'+((s.connected||bg)?'':' grey')+'"></span>'+(s.running?(s.connected?('처리 '+s.processed+'건'):'연결 시도 중'):(bg?'백그라운드 연결됨':'중지됨'))+(s.imageReady?' · 🖼️':'')+'</div>';
const imgWarn=s.running&&s.enableImage&&!s.imageReady;  // 이미지 토글은 켰지만 SD 미연결 → 광고 안 됨
if(imgWarn)chips+='<div class="chip" style="border-color:rgba(255,212,121,.4);color:#ffd479">이미지: SD 미연결 ⚠️</div>';
document.getElementById('chips').innerHTML=chips;
// 백그라운드 실행 중: 이 창에서 직접 연결하려면 먼저 백그라운드를 중지하도록 안내.
const bgBar=document.getElementById('bgBar');
if(bg){bgBar.style.display='block';bgBar.innerHTML='<div class="helper" style="margin-bottom:7px">이 PC는 <b>백그라운드 서비스</b>로 이미 연결돼 있어요. 이 창에서 직접 연결하려면 먼저 백그라운드를 중지하세요.</div><button class="secondary-btn" type="button" style="width:100%" onclick="stopBackground()">백그라운드 중지</button>';}
else bgBar.style.display='none';
// 변경 적용 배너: 실행 중에 선택 모델·이미지 토글이 광고된 값과 다르면 재연결로 적용하도록 안내.
const advModels=(s.models||[]).slice().sort().join(',');const uiModels=selectedModels().slice().sort().join(',');
const pending=s.running&&((HAS_MODELS&&uiModels!==advModels)||(on('img')!==!!s.enableImage));
const applyBar=document.getElementById('applyBar');
if(pending){applyBar.style.display='block';applyBar.innerHTML='<div class="helper" style="margin-bottom:7px;color:#ffd479">'+IWARN+'바꾼 모델·이미지 설정은 <b>재연결해야</b> 디스코드 풀에 반영됩니다.</div><button class="secondary-btn" type="button" style="width:100%" onclick="reapply()">변경 적용(재연결)</button>';}
else applyBar.style.display='none';
const go=document.getElementById('go');go.innerHTML=s.running?ISTOP+'<span>중지</span>':ILINK+'<span>연동하기</span>';go.className='primary-btn'+(s.running?' stop':'');
const lg=await j('/api/logs');const el=document.getElementById('log');el.textContent=lg.lines.join('\n');el.scrollTop=el.scrollHeight;}
async function stopBackground(){const m=document.getElementById('msg');m.className='';m.textContent='백그라운드 중지 중…';try{const r=await j('/api/service-stop',{method:'POST'});if(r.ok){m.className='ok';m.textContent='백그라운드를 중지했어요. 이제 이 창에서 연동할 수 있어요.';}else{m.className='err';m.textContent='⚠️ '+(r.error||'중지에 실패했어요. 잠시 후 다시 시도해 주세요.');}}catch(e){m.className='err';m.textContent='⚠️ 중지 실패';}setTimeout(refresh,900);}
async function reapply(){const m=document.getElementById('msg');if(HAS_MODELS&&!selectedModels().length){m.className='err';m.textContent='⚠️ 제공할 모델을 1개 이상 선택하세요.';return;}m.className='';m.textContent='변경 적용 중(재연결)…';try{await j('/api/stop',{method:'POST'});const su=await j('/api/setup',{method:'POST',headers:{'Content-Type':'application/json'},body:JSON.stringify({models:selectedModels(),enableImage:on('img')})});if(!su.ok){m.className='err';m.textContent='⚠️ '+(su.error||'저장 실패');return;}const st=await j('/api/start',{method:'POST'});if(st.ok){m.className='ok';m.textContent='✅ 변경을 적용해 다시 연결했어요.';}else{m.className='err';m.textContent='⚠️ '+st.error;}}catch(e){m.className='err';m.textContent='⚠️ 재연결에 실패했어요.';}await refresh();}
// 버튼 하나로 모든 걸: 실행 중이면 중지, 아니면 (설정 저장 → 토큰 있으면 바로 연결 / 없으면 브라우저 로그인 → 콜백이 자동 연결).
async function connect(){const msg=document.getElementById('msg');if(RUN){await j('/api/stop',{method:'POST'});await refresh();return;}
if(HAS_MODELS&&!selectedModels().length){msg.className='err';msg.textContent='⚠️ 제공할 모델을 1개 이상 선택하세요.';return;}
msg.className='';msg.textContent='설정 저장 중…';
const su=await j('/api/setup',{method:'POST',headers:{'Content-Type':'application/json'},body:JSON.stringify({token:document.getElementById('token').value.trim(),models:selectedModels(),enableImage:on('img'),installService:on('svc')})});
if(!su.ok){msg.className='err';msg.textContent='⚠️ '+(su.error||'저장 실패');return;}document.getElementById('token').value='';
const svcNote=su.serviceError?(' · ⚠️ 자동 실행 등록 실패: '+su.serviceError):(su.serviceInstalled?' · 자동 실행 등록':'');
const s=await j('/api/status');
if(s.hasToken){const st=await j('/api/start',{method:'POST'});if(!st.ok){msg.className='err';msg.textContent='⚠️ '+st.error;}else{msg.className=su.serviceError?'err':'ok';msg.textContent='✅ 연결 시작'+svcNote;}await refresh();return;}
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
else{setHelp(help,'muted',d.platform==='win'?'시작 메뉴에 ‘NEXA’ 바로가기를 추가합니다.':'이 앱을 응용 프로그램 폴더로 복사합니다. (관리자 권한 불필요)');}}
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
async function logout(){if(!confirm('저장된 토큰과 모든 서버 연결을 지우고 처음 연동 상태로 돌아갑니다. 계속할까요?'))return;const b=document.getElementById('logoutBtn');if(b){b.disabled=true;b.textContent='로그아웃 중…';}try{await j('/api/logout',{method:'POST'});}catch(e){}await refresh();loadServers();const m=document.getElementById('msg');if(m){m.className='ok';m.textContent='로그아웃됐어요. 이제 처음 연동(온보딩) 화면입니다.';}if(b){b.disabled=false;b.textContent='로그아웃';}}
async function resetAll(){if(!confirm('모든 설정을 기본값으로 되돌립니다(토큰·연결·Ollama·이미지 설정 전부). 계속할까요?'))return;const b=document.getElementById('resetBtn');if(b){b.disabled=true;b.textContent='초기화 중…';}try{await j('/api/reset',{method:'POST'});}catch(e){}location.reload();}
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
// ── 온보딩 마법사(미로그인 초기 화면) ──
const IC={check:'<svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2.4" stroke-linecap="round" stroke-linejoin="round"><path d="M20 6 9 17l-5-5"/></svg>',mon:'<svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.8"><rect x="3" y="4" width="18" height="13" rx="2"/><path d="M8 21h8M12 17v4M12 7v6m0 0 2.5-2.5M12 13l-2.5-2.5"/></svg>',img:'<svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.8"><rect x="3" y="4" width="18" height="16" rx="2"/><circle cx="8.5" cy="9" r="1.5"/><path d="m21 15-5-5L5 21"/></svg>',gear:'<svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.8"><circle cx="12" cy="12" r="3"/><path d="M19.4 15a1.65 1.65 0 0 0 .33 1.82l.06.06a2 2 0 1 1-2.83 2.83l-.06-.06a1.65 1.65 0 0 0-1.82-.33 1.65 1.65 0 0 0-1 1.51V21a2 2 0 0 1-4 0v-.09A1.65 1.65 0 0 0 8 19.4a1.65 1.65 0 0 0-1.82.33l-.06.06a2 2 0 1 1-2.83-2.83l.06-.06a1.65 1.65 0 0 0 .33-1.82 1.65 1.65 0 0 0-1.51-1H2a2 2 0 0 1 0-4h.09A1.65 1.65 0 0 0 4.6 8a1.65 1.65 0 0 0-.33-1.82l-.06-.06a2 2 0 1 1 2.83-2.83l.06.06A1.65 1.65 0 0 0 9 3.6 1.65 1.65 0 0 0 10 2.09V2a2 2 0 0 1 4 0v.09a1.65 1.65 0 0 0 1 1.51 1.65 1.65 0 0 0 1.82-.33l.06-.06a2 2 0 1 1 2.83 2.83l-.06.06a1.65 1.65 0 0 0-.33 1.82V9a1.65 1.65 0 0 0 1.51 1H22a2 2 0 0 1 0 4h-.09a1.65 1.65 0 0 0-1.51 1z"/></svg>',list:'<svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.8"><path d="M8 6h13M8 12h13M8 18h13M3 6h.01M3 12h.01M3 18h.01"/></svg>',pow:'<svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.8"><path d="M12 2v10"/><path d="M18.4 6.6a9 9 0 1 1-12.8 0"/></svg>',link:'<svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.8"><path d="M10 13a5 5 0 0 0 7.5.5l3-3a5 5 0 0 0-7-7l-1.5 1.5"/><path d="M14 11a5 5 0 0 0-7.5-.5l-3 3a5 5 0 0 0 7 7l1.5-1.5"/></svg>',msg:'<svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.8"><path d="M21 15a2 2 0 0 1-2 2H7l-4 4V5a2 2 0 0 1 2-2h14a2 2 0 0 1 2 2z"/></svg>',info:'<svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.8"><circle cx="12" cy="12" r="10"/><path d="M12 16v-4M12 8h.01"/></svg>',spark:'<svg viewBox="0 0 24 24" fill="currentColor"><path d="M12 2l1.8 5.2L19 9l-5.2 1.8L12 16l-1.8-5.2L5 9l5.2-1.8z"/></svg>'};
const ONB={step:1,ollama:true,image:true,none:false,autostart:true,autoconnect:true,imageRecv:false,background:true};
let ONB_DISMISSED=false;
function onbVisibility(hasToken){const el=document.getElementById('onboard');if(!el)return;if(hasToken){el.classList.remove('show');}else if(!ONB_DISMISSED){el.classList.add('show');}}
function rowFeat(k,ic,col,title,badge,desc){const sel=k==='none'?ONB.none:ONB[k];return `<div class="onb-row onb-opt${sel?' sel':''}" onclick="onbPick('${k}')"><div class="ib" style="background:${col}22;color:${col}">${ic}</div><div style="flex:1"><div class="rt">${title}${badge?(' <span class="badge neutral">'+badge+'</span>'):''}</div><div class="rd">${desc}</div></div><div class="chk">${IC.check}</div></div>`;}
function rowTog(k,ic,col,title,desc){return `<div class="onb-row"><div class="ib" style="background:${col}22;color:${col}">${ic}</div><div style="flex:1"><div class="rt">${title}</div><div class="rd">${desc}</div></div><div class="toggle${ONB[k]?' on':''}" onclick="onbTog('${k}')"></div></div>`;}
function onbCardHtml(){
if(ONB.step===1)return `<div class="onb-circ">${IC.check}</div><div class="onb-h">처음 사용 설정</div><div class="onb-d">앱을 사용하기 전에 필요한 기능을 먼저 준비할게요.<br>이 PC를 Discord 서버의 로컬 AI 노드로 연결하기 위한 기본 설정입니다.</div><div class="onb-row"><div class="ib" style="background:rgba(79,125,255,.12);color:#7aa0ff">${IC.mon}</div><div class="rt">Ollama 설치 여부 선택</div></div><div class="onb-row"><div class="ib" style="background:rgba(155,107,255,.12);color:#b48bff">${IC.img}</div><div class="rt">이미지 생성 기능 선택</div></div><div class="onb-row"><div class="ib" style="background:rgba(77,234,152,.12);color:#4dea98">${IC.gear}</div><div class="rt">자동 연결 및 시작 옵션 설정</div></div><div class="onb-info">${IC.info}<div><b>설정은 나중에 다시 바꿀 수 있어요.</b><br>앱 설정에서 언제든 변경할 수 있습니다.</div></div>`;
if(ONB.step===2)return `<div class="onb-circ">${IC.list}</div><div class="onb-h">필수 구성 준비</div><div class="onb-d">이 PC에서 사용할 로컬 AI 기능을 선택하세요.<br>필요한 항목만 설치할 수 있어요.</div>`+rowFeat('ollama',IC.msg,'#7aa0ff','Ollama 설치','권장','텍스트 응답 모델을 이 PC에서 실행합니다.')+rowFeat('image',IC.img,'#b48bff','이미지 생성 기능 준비','선택','Stable Diffusion 환경이 있으면 /imagine 요청도 처리할 수 있어요.')+rowFeat('none',IC.gear,'#4dea98','지금은 설치하지 않기','','나중에 앱 설정에서 다시 준비할 수 있어요.')+`<div class="onb-info">${IC.info}<div><b>예상 준비 시간 5~10분</b><br>설치가 필요한 경우 다음 단계에서 안내합니다.</div></div>`;
if(ONB.step===3)return `<div class="onb-circ">${IC.gear}</div><div class="onb-h">동작 방식 설정</div><div class="onb-d">앱이 언제 실행되고, 언제 자동으로 연결될지 정할 수 있어요.</div>`+rowTog('autostart',IC.pow,'#7aa0ff','시스템 로그인 시 자동 실행','PC를 켜면 앱을 자동으로 실행합니다.')+rowTog('autoconnect',IC.link,'#7aa0ff','로그인 후 자동 연결','앱이 켜지면 바로 Discord 서버와 연결합니다.')+rowTog('imageRecv',IC.img,'#b48bff','이미지 생성 요청 받기','이미지 생성 환경이 준비된 경우 /imagine 요청을 처리합니다.')+rowTog('background',IC.mon,'#4dea98','백그라운드 실행 유지','창을 닫아도 작업 표시줄에서 계속 대기합니다.')+`<div class="onb-info">${IC.info}<div>설정은 나중에 메인 화면의 설정 섹션에서 언제든 바꿀 수 있어요.</div></div>`;
return `<div class="onb-circ">${IC.check}</div><div class="onb-h">준비가 끝났어요</div><div class="onb-d">이제 이 PC를 로컬 AI 노드로 연결할 준비가 되었습니다.<br>아래 내용을 확인하고 메인 화면으로 이동하세요.</div><div class="onb-sum"><div class="sr"><div class="ib" style="background:rgba(79,125,255,.12);color:#7aa0ff">${IC.msg}</div>텍스트 모델<div class="sv">${ONB.ollama?'Ollama 사용':'사용 안 함'}</div></div><div class="sr"><div class="ib" style="background:rgba(155,107,255,.12);color:#b48bff">${IC.img}</div>이미지 생성<div class="sv">${ONB.image?'선택됨':'사용 안 함'}</div></div><div class="sr"><div class="ib" style="background:rgba(77,234,152,.12);color:#4dea98">${IC.gear}</div>자동 연결<div class="sv" style="color:#4dea98">${ONB.autoconnect?'켜짐':'꺼짐'}</div></div></div><div class="onb-info" style="border-color:rgba(79,125,255,.3)">${IC.spark}<div><b>다음 단계</b><br>· 연동하기를 눌러 Discord 로그인 창을 엽니다.<br>· 연결이 끝나면 메인 화면에서 서버 상태를 확인할 수 있어요.</div></div><button class="onb-redo" onclick="onbGo(1)">설정 다시 보기</button>`;}
function onbFootHtml(){
if(ONB.step===1)return `<button onclick="onbSkip()">나중에<span class="bsub">기본 설정으로 시작</span></button><button class="pri" onclick="onbNext()">시작하기 ›<span class="bsub">다음 단계로 진행</span></button>`;
if(ONB.step===2||ONB.step===3)return `<button onclick="onbPrev()">‹ 이전</button><button class="pri" onclick="onbNext()">다음 ›</button>`;
return `<button onclick="onbToMain()">메인 화면으로<span class="bsub">나중에 연동</span></button><button class="pri" onclick="onbConnect()">연동하기<span class="bsub">Discord 로그인 창을 엽니다</span></button>`;}
function onbDotsHtml(){let h='';for(let i=1;i<=4;i++){h+='<i class="'+(i<=ONB.step?'on':'')+'"></i>';if(i<4)h+='<span class="bar"></span>';}return h;}
function renderOnb(){document.getElementById('onbCount').textContent=ONB.step+' / 4';document.getElementById('onbDots').innerHTML=onbDotsHtml();document.getElementById('onbCard').innerHTML=onbCardHtml();document.getElementById('onbFoot').innerHTML=onbFootHtml();}
function onbGo(s){ONB.step=s;renderOnb();}
function onbNext(){if(ONB.step<4)ONB.step++;renderOnb();}
function onbPrev(){if(ONB.step>1)ONB.step--;renderOnb();}
function onbPick(k){if(k==='none'){ONB.none=true;ONB.ollama=false;ONB.image=false;}else{ONB.none=false;ONB[k]=!ONB[k];if(!ONB.ollama&&!ONB.image){ONB.none=true;}}renderOnb();}
function onbTog(k){ONB[k]=!ONB[k];renderOnb();}
async function onbApply(){try{await j('/api/onboard-apply',{method:'POST',headers:{'Content-Type':'application/json'},body:JSON.stringify({enableImage:ONB.imageRecv,autostart:ONB.autostart,autoConnect:ONB.autoconnect,background:ONB.background})});}catch(e){}}
async function onbSkip(){await onbApply();ONB_DISMISSED=true;onbVisibility(false);await refresh();}
async function onbToMain(){await onbApply();ONB_DISMISSED=true;onbVisibility(false);await refresh();}
async function onbConnect(){await onbApply();ONB_DISMISSED=true;onbVisibility(false);connect();}
renderOnb();
loadModels();refresh();loadInstall();loadUpdate();loadServers();loadSDStatus();setInterval(refresh,2000);setInterval(loadServers,2500);setInterval(pollProgress,600);setInterval(loadSDStatus,8000);
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
        # 이 GUI 가 직접 연결돼 있지 않은데 락이 잡혀 있으면 = 백그라운드 자동시작 서비스가 이미 연결 중.
        # (그 경우 GUI 가 다시 연결하려 하면 singleton 으로 막혀 헷갈리므로, 상태로 분명히 보여준다.)
        from . import singleton

        background_running = (not running) and singleton.held_by_other()
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
                # 백그라운드 자동시작 서비스가 이미 연결 중인지(이 창은 설정용임을 알리는 데 쓴다).
                "backgroundRunning": background_running,
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
            {"ok": True, "serviceInstalled": service_installed, "serviceError": service_error, "hasToken": bool(token)}
        )

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

    async def ollama_setup_start(req: web.Request) -> web.Response:
        """앱 내 Ollama 자동 설치(감지→설치→기동→모델 pull) 시작. 진행은 폴링으로 노출."""
        _auth(req)
        from . import ollama_setup

        if ollama_setup.is_busy():
            return web.json_response({"ok": True, "busy": True})
        url = load_config().get("ollama_url") or "http://localhost:11434"
        asyncio.create_task(ollama_setup.run_setup(url))
        return web.json_response({"ok": True})

    async def ollama_setup_progress(req: web.Request) -> web.Response:
        _auth(req)
        from . import ollama_setup

        return web.json_response(ollama_setup.progress())

    async def sd_models(req: web.Request) -> web.Response:
        """설치 마법사에서 고를 수 있는 이미지 모델 목록."""
        _auth(req)
        from . import sd_setup

        return web.json_response({"models": sd_setup.MODELS, "default": sd_setup.DEFAULT_MODEL_ID})

    async def sd_status(req: web.Request) -> web.Response:
        """SD 설치/준비 상태(설정 화면에서 토글 vs 설치 버튼 결정용)."""
        _auth(req)
        from . import sd_setup
        from .sd import SDClient

        url = load_config().get("sd_url") or "http://127.0.0.1:7860"
        ready = await SDClient(url).health()
        return web.json_response(
            {"installed": sd_setup.is_installed(), "ready": bool(ready), "busy": sd_setup.is_busy()}
        )

    async def sd_setup_start(req: web.Request) -> web.Response:
        """설치 마법사: 선택한 모델로 SD(A1111) 설치(전제도구→clone→모델→--api 기동) 시작."""
        _auth(req)
        from . import sd_setup

        if sd_setup.is_busy():
            return web.json_response({"ok": True, "busy": True})
        try:
            data = await req.json()
        except Exception:  # noqa: BLE001 — 본문 없으면 기본 모델
            data = {}
        model_id = (data or {}).get("model") or sd_setup.DEFAULT_MODEL_ID
        url = load_config().get("sd_url") or "http://127.0.0.1:7860"
        asyncio.create_task(sd_setup.run_setup(url, model_id))
        return web.json_response({"ok": True})

    async def sd_start(req: web.Request) -> web.Response:
        """이미 설치된 SD(A1111)를 **기동만** 한다(재부팅·앱 종료 후 다시 켜기). clone/다운로드 없음.

        진행은 설치 마법사와 같은 `/api/sd/setup-progress` 로 폴링한다.
        """
        _auth(req)
        from . import sd_setup

        if sd_setup.is_busy():
            return web.json_response({"ok": True, "busy": True})
        url = load_config().get("sd_url") or "http://127.0.0.1:7860"
        asyncio.create_task(sd_setup.launch_only(url))
        return web.json_response({"ok": True})

    async def sd_setup_cancel(req: web.Request) -> web.Response:
        """진행 중인 SD 설치를 취소."""
        _auth(req)
        from . import sd_setup

        sd_setup.request_cancel()
        return web.json_response({"ok": True})

    async def sd_setup_progress(req: web.Request) -> web.Response:
        _auth(req)
        from . import sd_setup

        return web.json_response(sd_setup.progress())

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
            except (TimeoutError, asyncio.CancelledError, Exception):  # noqa: BLE001
                pass
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
            except (TimeoutError, asyncio.CancelledError, Exception):  # noqa: BLE001
                pass
        _state["agent"] = None
        _state["task"] = None
        from . import singleton

        singleton.release()
        from .config_file import config_path

        try:
            config_path().unlink(missing_ok=True)  # 설정 파일 전체 삭제 → 기본값
        except OSError:
            pass
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
    app.router.add_post("/api/ollama/setup", ollama_setup_start)
    app.router.add_get("/api/ollama/setup-progress", ollama_setup_progress)
    app.router.add_get("/api/sd/models", sd_models)
    app.router.add_get("/api/sd/status", sd_status)
    app.router.add_post("/api/sd/setup", sd_setup_start)
    app.router.add_post("/api/sd/start", sd_start)
    app.router.add_post("/api/sd/cancel", sd_setup_cancel)
    app.router.add_get("/api/sd/setup-progress", sd_setup_progress)
    app.router.add_post("/api/setup", setup)
    app.router.add_post("/api/start", start)
    app.router.add_post("/api/stop", stop)
    app.router.add_post("/api/service-stop", service_stop)
    app.router.add_post("/api/logout", logout)
    app.router.add_post("/api/reset", reset_all)
    app.router.add_post("/api/onboard-apply", onboard_apply)

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


APP_DISPLAY_NAME = "NEXA"


def _brand_icon_png(size: int = 512) -> bytes | None:
    """dock 아이콘용 NEXA 로고 PNG 바이트. 번들 에셋을 우선 로드한다."""
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
            _handoff_to_service_on_close()  # 닫을 때 백그라운드 서비스로 연결 인계(설치돼 있으면)
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
