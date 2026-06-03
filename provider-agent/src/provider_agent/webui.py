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


def _connect_base(relay: str) -> str:
    """relay(wss://…/agent) → 중앙 서버 https 베이스. 토큰 받기 OAuth 시작점 도출."""
    base = relay.replace("wss://", "https://").replace("ws://", "http://")
    if base.endswith("/agent"):
        base = base[: -len("/agent")]
    return base.rstrip("/")


def _page(session_key: str) -> str:
    """세션 키를 주입한 제어판 HTML. 디자인은 데스크톱 앱 카드(레퍼런스), 동작은 실제 API 연결."""
    return _PAGE_TEMPLATE.replace("__SESSION_KEY__", session_key)


# macOS 데스크톱 앱 카드 디자인(레퍼런스) — 정적 목업을 실제 백엔드(/api/*)에 연결.
# 세션 키는 __SESSION_KEY__ 자리에 주입(secrets.token_urlsafe → URL-safe 문자만이라 안전).
_PAGE_TEMPLATE = r"""<!doctype html><html lang="ko"><head><meta charset="utf-8">
<meta name="viewport" content="width=device-width,initial-scale=1"><title>내 PC를 AI 일꾼으로</title><style>
:root{--bg:#070d16;--panel:#0d1624;--line:rgba(144,164,199,.18);--text:#eef4ff;--muted:#9aa7ba;--faint:#68758a;--blue:#4f7dff;--radius-xl:26px;--radius-lg:18px;font-family:-apple-system,BlinkMacSystemFont,"Segoe UI","Apple SD Gothic Neo","Noto Sans KR",sans-serif}
*{box-sizing:border-box}html,body{min-height:100%}
body{margin:0;color:var(--text);background:radial-gradient(circle at 18% 0%,rgba(56,104,202,.22),transparent 34%),radial-gradient(circle at 80% 20%,rgba(73,64,150,.18),transparent 36%),linear-gradient(180deg,#0a111c 0%,#050912 100%);display:grid;place-items:center;padding:24px 14px}
button,input{font:inherit}
.app{position:relative;width:min(900px,100%);overflow:hidden;border-radius:var(--radius-xl);background:linear-gradient(180deg,rgba(16,28,45,.96),rgba(7,14,25,.98));border:1px solid rgba(142,164,198,.22);box-shadow:0 26px 80px rgba(0,0,0,.52),inset 0 1px 0 rgba(255,255,255,.06)}
.titlebar{height:60px;display:grid;grid-template-columns:1fr auto 1fr;align-items:center;padding:0 22px;border-bottom:1px solid rgba(142,164,198,.12);background:rgba(10,17,29,.76)}
.traffic{display:flex;gap:9px}.tdot{width:13px;height:13px;border-radius:999px;box-shadow:inset 0 0 0 1px rgba(255,255,255,.18)}.red{background:#ff625d}.yellow{background:#ffbd2e}.green{background:#28c840}
.titlebar-title{color:#e6edf7;font-size:16px;font-weight:700;letter-spacing:-.2px}.more{justify-self:end;color:#9aa6b7;letter-spacing:3px;font-weight:900}
main{padding:38px 38px 34px}
.hero{position:relative;display:grid;grid-template-columns:96px 1fr;gap:24px;align-items:center;margin-bottom:26px}
.mascot-wrap{position:relative;width:88px;height:88px;border-radius:26px;display:grid;place-items:center;background:linear-gradient(180deg,rgba(30,48,78,.78),rgba(15,25,41,.76));border:1px solid rgba(112,143,196,.28);box-shadow:0 16px 34px rgba(0,0,0,.32),inset 0 1px 0 rgba(255,255,255,.08)}
.mascot-wrap img{width:74px;height:74px;object-fit:cover;border-radius:999px}
.ai-badge{position:absolute;right:-9px;bottom:-8px;width:30px;height:30px;border-radius:999px;display:grid;place-items:center;color:#bcd0ff;font-size:11px;font-weight:800;background:#14243e;border:1px solid rgba(91,130,223,.58);box-shadow:0 8px 20px rgba(0,0,0,.35)}
h1{margin:0;font-size:clamp(28px,4.6vw,42px);letter-spacing:-1.3px;line-height:1.08;font-weight:850}
.hero p{margin:12px 0 0;color:#a7b3c5;font-size:clamp(15px,2vw,18px);letter-spacing:-.2px}
.status-card{display:grid;grid-template-columns:124px 1fr;gap:26px;align-items:center;padding:26px;border-radius:var(--radius-lg);background:linear-gradient(180deg,rgba(18,31,50,.74),rgba(11,20,35,.82));border:1px solid var(--line)}
.status-ring{width:100px;height:100px;border-radius:999px;padding:4px;background:conic-gradient(from 18deg,var(--blue) 0 72%,rgba(79,125,255,.12) 72% 100%);display:grid;place-items:center}
.status-ring.off{background:conic-gradient(from 18deg,#39435a 0 100%)}
.status-ring.connecting{background:conic-gradient(from 18deg,#ffbd2e 0 35%,rgba(255,189,46,.12) 35% 100%)}
.status-ring-inner{width:100%;height:100%;border-radius:inherit;background:#0b1423;display:grid;place-items:center;border:1px solid rgba(137,160,194,.14)}
.status-ring svg{width:44px;height:44px;color:var(--blue)}.status-ring.off svg{color:#5a657a}.status-ring.connecting svg{color:#ffbd2e}
.status-copy h2{margin:0 0 7px;font-size:25px;letter-spacing:-.7px}.status-copy p{margin:0;color:var(--muted);font-size:16px}
.chips{display:flex;flex-wrap:wrap;gap:10px;margin-top:20px}
.chip{min-height:40px;display:inline-flex;align-items:center;gap:8px;padding:0 13px;color:#d7e1f0;background:rgba(9,16,28,.52);border:1px solid rgba(149,169,203,.16);border-radius:10px;font-size:14px}
.chip-dot{width:9px;height:9px;border-radius:50%;background:#58a7ff;box-shadow:0 0 0 4px rgba(88,167,255,.12)}.chip-dot.grey{background:#5a657a;box-shadow:none}
.section{margin-top:34px}
.section-title{margin:0 0 16px;display:flex;align-items:center;gap:9px;color:#f3f6fb;font-size:23px;font-weight:820;letter-spacing:-.7px}
.token-row{display:grid;grid-template-columns:1fr 176px;gap:16px;align-items:center}
.input-shell{height:66px;display:grid;grid-template-columns:50px 1fr;align-items:center;padding:0 18px;border-radius:14px;background:rgba(9,16,28,.58);border:1px solid rgba(145,165,199,.19);transition:border-color .18s,box-shadow .18s}
.input-shell:focus-within{border-color:rgba(79,125,255,.58);box-shadow:0 0 0 4px rgba(79,125,255,.08)}
.input-shell svg{width:23px;height:23px;color:#7d8ba0}.input-shell input{width:100%;border:0;outline:0;background:transparent;color:#e8eef8;font-size:18px}.input-shell input::placeholder{color:#738198}
.secondary-button,.primary-button{cursor:pointer;user-select:none;border:0;border-radius:14px;font-weight:780;letter-spacing:-.2px;transition:transform .16s,background .16s,opacity .16s}
.secondary-button:hover,.primary-button:hover{transform:translateY(-1px)}.secondary-button:active,.primary-button:active{transform:translateY(0);opacity:.88}
.secondary-button{height:66px;display:inline-flex;align-items:center;justify-content:center;gap:9px;color:#e8eefb;font-size:17px;background:linear-gradient(180deg,rgba(21,33,54,.9),rgba(11,18,31,.9));border:1px solid rgba(92,132,220,.64)}.secondary-button svg{width:18px;height:18px;color:#9eb6e8}
.help{margin:12px 0 0;color:#8492a6;font-size:15px}
.models{display:grid;grid-template-columns:1fr 1fr;gap:16px}
.model-card{min-height:120px;display:grid;grid-template-columns:40px 52px 1fr;align-items:center;gap:16px;padding:20px 22px;border-radius:16px;background:linear-gradient(180deg,rgba(18,30,48,.74),rgba(10,18,31,.76));border:1px solid rgba(145,165,199,.18);cursor:pointer;text-align:left;color:var(--text)}
.model-card.is-selected{border-color:rgba(79,125,255,.48);background:linear-gradient(180deg,rgba(25,39,65,.82),rgba(11,20,36,.82))}
.check{width:32px;height:32px;border-radius:9px;display:grid;place-items:center;background:linear-gradient(180deg,#4b82ff,#285bcb);box-shadow:inset 0 1px 0 rgba(255,255,255,.18);opacity:0;transition:opacity .15s}.model-card.is-selected .check{opacity:1}
.check svg{width:21px;height:21px;color:#fff}.model-icon{width:46px;height:46px;color:#cfd8e6;opacity:.9}
.model-title{font-size:19px;font-weight:780;letter-spacing:-.4px;word-break:break-all}
.tag{margin-top:7px;display:inline-flex;padding:3px 9px;border:1px solid rgba(79,125,255,.38);border-radius:8px;color:#95b6ff;font-size:13px;font-weight:700;background:rgba(79,125,255,.08)}
.empty{color:#8492a6;font-size:15px}.empty code{background:#152133;padding:2px 7px;border-radius:6px;color:#cdd}
.settings-panel{overflow:hidden;border-radius:18px;background:rgba(12,21,35,.72);border:1px solid rgba(145,165,199,.17)}
.setting-row{display:grid;grid-template-columns:56px 1fr auto;align-items:center;gap:18px;min-height:96px;padding:20px 22px}.setting-row+.setting-row{border-top:1px solid rgba(145,165,199,.12)}
.icon-box{width:50px;height:50px;border-radius:14px;display:grid;place-items:center;background:linear-gradient(180deg,rgba(41,54,80,.84),rgba(24,34,52,.84));border:1px solid rgba(145,165,199,.16)}.icon-box svg{width:26px;height:26px;color:#a9b8d0}
.setting-title{display:flex;align-items:center;gap:8px;font-size:19px;font-weight:780;letter-spacing:-.4px}.setting-sub{margin-top:5px;color:#8d9baf;font-size:14px}
.beta{padding:3px 7px;border-radius:7px;background:rgba(79,125,255,.14);color:#9db7ff;font-size:12px;font-weight:800}
.switch{position:relative;width:58px;height:34px;display:inline-block}.switch input{display:none}
.slider{position:absolute;inset:0;border-radius:999px;background:#263044;border:1px solid rgba(145,165,199,.12);transition:.18s}
.slider::after{content:"";position:absolute;width:26px;height:26px;left:3px;top:3px;border-radius:50%;background:#e7edf6;box-shadow:0 4px 12px rgba(0,0,0,.28);transition:.18s}
.switch input:checked+.slider{background:linear-gradient(180deg,#4d7cf4,#315bbf)}.switch input:checked+.slider::after{transform:translateX(24px)}
.primary-button{width:100%;height:70px;margin-top:30px;display:flex;align-items:center;justify-content:center;gap:12px;color:#fff;font-size:22px;background:linear-gradient(180deg,#3e72dc,#2e56b4);border:1px solid rgba(161,185,255,.24);box-shadow:0 16px 36px rgba(16,47,112,.25),inset 0 1px 0 rgba(255,255,255,.15)}
.primary-button.is-stop{background:linear-gradient(180deg,#b3402f,#8c2f24)}.primary-button svg{width:23px;height:23px}
#msg{margin-top:12px;font-size:14px;min-height:18px;text-align:center}.err{color:#ff8a8a}.ok{color:#9fe0a0}
.accordions{display:grid;gap:12px;margin-top:26px}
details{border-radius:15px;background:rgba(12,21,35,.66);border:1px solid rgba(145,165,199,.15);overflow:hidden}
summary{min-height:62px;display:flex;align-items:center;gap:14px;padding:0 22px;color:#cbd5e4;cursor:pointer;list-style:none;font-size:17px;font-weight:650}summary::-webkit-details-marker{display:none}
summary svg{width:22px;height:22px;color:#92a1b7}summary::after{content:"⌄";margin-left:auto;color:#a5b0c0;font-size:24px;transform:translateY(-2px)}details[open] summary::after{transform:rotate(180deg) translateY(2px)}
.details-body{padding:0 22px 18px 58px;color:#8f9caf;line-height:1.6}
#log{background:#070d16;border:1px solid rgba(145,165,199,.14);border-radius:10px;padding:11px;height:170px;overflow:auto;font:12px/1.55 ui-monospace,Menlo,monospace;color:#8fa0b6;white-space:pre-wrap}
.ro{word-break:break-all;color:#9fb0c6}
@media (max-width:760px){main{padding:28px 18px 24px}.hero{grid-template-columns:1fr;gap:16px}.status-card{grid-template-columns:1fr;gap:16px;padding:20px}.token-row{grid-template-columns:1fr}.secondary-button{width:100%}.models{grid-template-columns:1fr}.setting-row{grid-template-columns:50px 1fr}.setting-row .switch{grid-column:2;justify-self:start;margin-top:8px}h1{font-size:32px}}
</style></head><body>
<section class="app">
<header class="titlebar"><div class="traffic"><span class="tdot red"></span><span class="tdot yellow"></span><span class="tdot green"></span></div><div class="titlebar-title">내 PC를 AI 일꾼으로</div><div class="more">•••</div></header>
<main>
<section class="hero"><div class="mascot-wrap"><img src="/mascot.png" alt=""><span class="ai-badge">AI</span></div><div><h1>내 PC를 AI 일꾼으로</h1><p>로컬 Ollama를 커뮤니티 풀에 연결합니다</p></div></section>
<section class="status-card"><div class="status-ring off" id="ring"><div class="status-ring-inner"><svg viewBox="0 0 24 24" fill="none"><path d="M20 6 9 17l-5-5" stroke="currentColor" stroke-width="2.4" stroke-linecap="round" stroke-linejoin="round"/></svg></div></div>
<div class="status-copy"><h2 id="stitle">중지됨</h2><p id="ssub">시작을 누르면 풀에 연결됩니다.</p><div class="chips" id="chips"></div></div></section>
<section class="section"><h2 class="section-title">1. 토큰</h2>
<div class="token-row"><label class="input-shell"><svg viewBox="0 0 24 24" fill="none"><path d="M15.5 7.5a4.5 4.5 0 1 1-2.2 3.87L4 20.7 2.3 19l2.4-2.4L3.2 15 5 13.2l1.5 1.5 3.13-3.13A4.5 4.5 0 0 1 15.5 7.5Z" stroke="currentColor" stroke-width="1.9" stroke-linecap="round" stroke-linejoin="round"/></svg><input type="text" id="token" placeholder="토큰을 붙여넣기" autocomplete="off"></label>
<button class="secondary-button" type="button" onclick="getToken()">토큰 받기 <svg viewBox="0 0 24 24" fill="none"><path d="M7 17 17 7M9 7h8v8" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"/></svg></button></div>
<p class="help">커뮤니티 풀에서 발급받은 토큰을 입력하세요.</p></section>
<section class="section"><h2 class="section-title">2. 제공할 모델</h2><div class="models" id="models"></div></section>
<section class="section"><h2 class="section-title">3. 설정</h2><div class="settings-panel">
<div class="setting-row"><span class="icon-box"><svg viewBox="0 0 24 24" fill="none"><path d="M12.5 14.5 9.5 11.5m3 3c3.4-1.2 6.2-4 7.5-8.5.2-.7-.4-1.3-1.1-1.1-4.5 1.3-7.3 4.1-8.5 7.5m3 3-3 3-4-1 1-4 3-3" stroke="currentColor" stroke-width="1.8" stroke-linecap="round" stroke-linejoin="round"/></svg></span><div><div class="setting-title">로그인할 때 자동으로 시작</div><div class="setting-sub">시스템 로그인 시 프로그램을 자동 실행합니다.</div></div><label class="switch"><input type="checkbox" id="service" checked><span class="slider"></span></label></div>
<div class="setting-row"><span class="icon-box"><svg viewBox="0 0 24 24" fill="none"><path d="M4 5h16v14H4V5Z" stroke="currentColor" stroke-width="1.8"/><path d="m7 16 4-4 3 3 2-2 3 3" stroke="currentColor" stroke-width="1.8" stroke-linecap="round" stroke-linejoin="round"/></svg></span><div><div class="setting-title">이미지 생성도 제공 <span class="beta">Beta</span></div><div class="setting-sub">로컬 Stable Diffusion을 함께 제공합니다.</div></div><label class="switch"><input type="checkbox" id="image"><span class="slider"></span></label></div>
</div></section>
<button class="primary-button" type="button" id="go" onclick="toggle()"><svg id="goicon" viewBox="0 0 24 24" fill="currentColor"><path d="M8 5.3v13.4c0 .8.9 1.3 1.6.9l10.2-6.7c.6-.4.6-1.4 0-1.8L9.6 4.4C8.9 4 8 4.5 8 5.3Z"/></svg><span id="gotext">시작</span></button>
<div id="msg"></div>
<div class="accordions">
<details><summary><svg viewBox="0 0 24 24" fill="none"><path d="M7 3h8l4 4v14H7V3Z" stroke="currentColor" stroke-width="1.8"/><path d="M15 3v5h4M10 12h6M10 16h6" stroke="currentColor" stroke-width="1.8" stroke-linecap="round"/></svg>로그 보기</summary><div class="details-body"><div id="log"></div></div></details>
<details><summary><svg viewBox="0 0 24 24" fill="none"><path d="M12 15.5a3.5 3.5 0 1 0 0-7 3.5 3.5 0 0 0 0 7Z" stroke="currentColor" stroke-width="1.8"/></svg>고급 설정</summary><div class="details-body">중앙 서버(고정): <span class="ro" id="relay"></span></div></details>
</div>
</main></section>
<script>
const K="__SESSION_KEY__";const H={"X-Session":K};let RUN=false;
const MICON='<svg class="model-icon" viewBox="0 0 48 48" fill="none"><path d="M16 37V18c0-4 3-7 7-7h5c4 0 7 3 7 7v19" stroke="currentColor" stroke-width="2.2" stroke-linecap="round"/><path d="M16 24h-3c-3 0-5 2-5 5v8m27-13h3c3 0 5 2 5 5v8M21 23h.01M30 23h.01M22 31c2 1.4 5 1.4 7 0" stroke="currentColor" stroke-width="2.2" stroke-linecap="round"/><path d="M20 11 16 4m14 7 4-7" stroke="currentColor" stroke-width="2.2" stroke-linecap="round"/></svg>';
const CHK='<span class="check"><svg viewBox="0 0 24 24" fill="none"><path d="M20 6 9 17l-5-5" stroke="currentColor" stroke-width="2.4" stroke-linecap="round" stroke-linejoin="round"/></svg></span>';
async function j(u,o){o=o||{};o.headers=Object.assign({},H,o.headers||{});const r=await fetch(u,o);return r.json();}
function esc(s){return s.replace(/[&<>"']/g,c=>({'&':'&amp;','<':'&lt;','>':'&gt;','"':'&quot;',"'":'&#39;'}[c]));}
let HAS_MODELS=false;
async function loadModels(){const d=await j('/api/models');const box=document.getElementById('models');HAS_MODELS=d.models.length>0;
if(!d.models.length){box.innerHTML='<div class="empty">Ollama에서 모델을 못 찾았어요. <code>ollama pull llama3.1:8b</code> 후 새로고침하세요.</div>';return;}
box.innerHTML=d.models.map(m=>{const sel=d.selected.includes(m)||!d.selected.length;return `<button class="model-card${sel?' is-selected':''}" type="button" data-model="${esc(m)}" onclick="this.classList.toggle('is-selected')">${CHK}${MICON}<span><span class="model-title">${esc(m)}</span><br><span class="tag">사용 가능</span></span></button>`;}).join('');}
function selectedModels(){return [...document.querySelectorAll('.model-card.is-selected')].map(c=>c.dataset.model);}
async function refresh(){const s=await j('/api/status');RUN=s.running;
document.getElementById('relay').textContent=s.relayUrl;
if(s.hasToken)document.getElementById('token').placeholder='저장됨 — 바꿀 때만 입력';
document.getElementById('image').checked=s.enableImage;
const ring=document.getElementById('ring');ring.className='status-ring'+(s.running?(s.connected?'':' connecting'):' off');
document.getElementById('stitle').textContent=s.running?(s.connected?'연결 완료':'연결하는 중…'):'중지됨';
document.getElementById('ssub').textContent=s.running?(s.connected?'내 PC가 커뮤니티 풀에 연결되어 있습니다.':'중앙 서버에 연결하고 있습니다.'):'시작을 누르면 풀에 연결됩니다.';
const cnt=s.running?s.models.length:selectedModels().length;
let chips='<span class="chip"><span class="chip-dot'+(HAS_MODELS?'':' grey')+'"></span>'+(HAS_MODELS?'Ollama 실행 중':'Ollama 확인 필요')+'</span>';
chips+='<span class="chip">모델 '+cnt+'개'+(s.running?' 제공 중':' 선택됨')+'</span>';
chips+='<span class="chip"><span class="chip-dot'+(s.connected?'':' grey')+'"></span>'+(s.running?(s.connected?('처리 '+s.processed+'건'):'연결 시도 중'):'중지됨')+(s.imageReady?' · 🖼️':'')+'</span>';
document.getElementById('chips').innerHTML=chips;
document.getElementById('go').className='primary-button'+(s.running?' is-stop':'');
document.getElementById('gotext').textContent=s.running?'중지':'시작';
document.getElementById('goicon').innerHTML=s.running?'<rect x="6" y="6" width="12" height="12" rx="2"/>':'<path d="M8 5.3v13.4c0 .8.9 1.3 1.6.9l10.2-6.7c.6-.4.6-1.4 0-1.8L9.6 4.4C8.9 4 8 4.5 8 5.3Z"/>';
const lg=await j('/api/logs');const el=document.getElementById('log');el.textContent=lg.lines.join('\n');el.scrollTop=el.scrollHeight;}
async function getToken(){const s=await j('/api/status');
if(!s.connectEnabled){msg.className='';msg.textContent='‘토큰 받기’는 곧 지원돼요. 지금은 디스코드 /provider-join 토큰을 붙여넣어 주세요.';return;}
const cb=location.origin+'/connect/callback';
location.href=s.relayUrl.replace('wss://','https://').replace('ws://','http://').replace(/\/agent$/,'')+'/provider/connect?cb='+encodeURIComponent(cb)+'&state='+encodeURIComponent(K);}
async function toggle(){const msg=document.getElementById('msg');if(RUN){await j('/api/stop',{method:'POST'});await refresh();return;}
msg.className='';msg.textContent='저장하고 시작하는 중…';
const su=await j('/api/setup',{method:'POST',headers:{'Content-Type':'application/json'},body:JSON.stringify({token:document.getElementById('token').value.trim(),models:selectedModels(),enableImage:document.getElementById('image').checked,installService:document.getElementById('service').checked})});
if(!su.ok){msg.className='err';msg.textContent='⚠️ '+(su.error||'저장 실패');return;}document.getElementById('token').value='';
const st=await j('/api/start',{method:'POST'});
if(!st.ok){msg.className='err';msg.textContent='⚠️ '+st.error;}else{msg.className='ok';msg.textContent='✅ 시작됨'+(su.serviceInstalled?' · 자동 시작 등록':'');}await refresh();}
loadModels();refresh();setInterval(refresh,2000);
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
        import os

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
                # ‘토큰 받기’ OAuth 는 중앙 서버 /provider/connect 가 배포된 뒤 켠다(그전엔 복붙 안내).
                "connectEnabled": bool(os.getenv("AGENT_CONNECT_ENABLED")),
            }
        )

    async def logs(req: web.Request) -> web.Response:
        _auth(req)
        return web.json_response({"lines": list(_log_lines)})

    async def setup(req: web.Request) -> web.Response:
        _auth(req)
        data = await req.json()
        saved = load_config()
        token = str(data.get("token", "")).strip() or str(saved.get("token", ""))
        if not token:
            return web.json_response(
                {"ok": False, "error": "토큰을 먼저 입력하세요(또는 ‘토큰 받기’)."}
            )
        models_list = [str(m).strip() for m in (data.get("models") or []) if str(m).strip()]
        enable_image = bool(data.get("enableImage"))
        relay = (saved.get("relay_url") or _default_relay()).rstrip("/")
        cfg = AgentConfig(
            token=token, relay_url=relay, models=tuple(models_list), enable_image=enable_image
        )
        if enable_image:
            try:
                ensure_ollama_allowed(cfg.sd_url, cfg.allow_remote_sd)
            except RemoteOllamaBlocked:
                return web.json_response({"ok": False, "error": "SD 주소가 localhost 가 아닙니다."})
        save_config(cfg)
        service_installed = False
        if data.get("installService"):
            from .service import install_service

            try:
                install_service()
                service_installed = True
            except Exception:  # noqa: BLE001
                pass
        return web.json_response({"ok": True, "serviceInstalled": service_installed})

    async def connect_callback(req: web.Request) -> web.Response:
        """‘토큰 받기’ OAuth 콜백: 중앙 서버가 디스코드 인증 후 token·state 를 붙여 리디렉트.

        state==세션키로 위조 방지(이 콜백은 상위 네비게이션이라 X-Session 헤더가 없음).
        성공하면 토큰을 저장하고 메인 화면으로 되돌린다.
        """
        if req.query.get("state") != session_key:
            return web.Response(status=403, text="잘못된 요청(state 불일치)")
        token = (req.query.get("token") or "").strip()
        if not token:
            return web.Response(status=400, text="토큰이 비어 있습니다")
        saved = load_config()
        relay = (saved.get("relay_url") or _default_relay()).rstrip("/")
        save_config(
            AgentConfig(
                token=token,
                relay_url=relay,
                models=tuple(saved.get("models") or ()),
                enable_image=bool(saved.get("enable_image")),
            )
        )
        return web.Response(
            text="<!doctype html><meta charset=utf-8><body style='font-family:system-ui;background:#0d0f12;color:#b8ff39;text-align:center;padding-top:80px'>"
            "✅ 토큰을 받았습니다. 돌아갑니다…<script>setTimeout(()=>location.href='/',900)</script>",
            content_type="text/html",
        )

    async def start(req: web.Request) -> web.Response:
        _auth(req)
        task = _state["task"]
        if task is not None and not task.done():
            return web.json_response({"ok": False, "error": "이미 실행 중입니다."})
        cfg = _build_cfg_from_saved()
        if cfg is None or not cfg.token:
            return web.json_response({"ok": False, "error": "먼저 토큰을 저장하세요."})
        from .agent import ProviderAgent

        agent = ProviderAgent(cfg)
        _state["agent"] = agent
        _state["task"] = asyncio.create_task(agent.run(install_signals=False))
        return web.json_response({"ok": True})

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
        return web.json_response({"ok": True})

    app.router.add_get("/", index)
    app.router.add_get("/mascot.png", mascot)
    app.router.add_get("/api/models", models)
    app.router.add_get("/api/status", status)
    app.router.add_get("/api/logs", logs)
    app.router.add_get("/connect/callback", connect_callback)
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


def run_gui(host: str = "127.0.0.1", port: int = 0) -> None:
    """로컬 제어판을 네이티브 앱 창으로 띄운다(웹뷰 없으면 브라우저 폴백)."""
    session_key = secrets.token_urlsafe(24)
    app = build_app(session_key)
    url = _start_server_thread(app, host, port)
    print(
        f"\n제어판: {url}\n(창이 안 보이면 위 주소를 브라우저에서 여세요. AGENT_GUI_BROWSER=1 로 브라우저 강제.)\n",
        flush=True,
    )

    if _webview_available():
        try:
            import webview  # type: ignore[import-untyped]

            webview.create_window(
                "내 PC 를 AI 일꾼으로", url, width=900, height=1000, min_size=(420, 640)
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
