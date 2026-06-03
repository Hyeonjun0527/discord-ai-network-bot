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
<meta name="viewport" content="width=device-width,initial-scale=1"><title>로컬 AI 제공자 설정 — 냥시스턴트</title><style>
:root{color-scheme:dark;--bg:#08111d;--bg2:#040811;--line:rgba(148,163,184,.17);--line2:rgba(79,125,255,.36);--text:#edf4ff;--muted:#a7b3c5;--faint:#76849a;--blue:#4f7dff;--blue2:#2f63d6;font-family:-apple-system,BlinkMacSystemFont,"Segoe UI","Apple SD Gothic Neo","Noto Sans KR",sans-serif}
*{box-sizing:border-box}html,body{min-height:100%}
body{margin:0;color:var(--text);background:radial-gradient(circle at 18% 0%,rgba(64,96,180,.18),transparent 32%),radial-gradient(circle at 85% 16%,rgba(54,86,156,.10),transparent 28%),linear-gradient(180deg,var(--bg) 0%,var(--bg2) 100%);display:grid;place-items:start center;padding:10px 12px}
body::before{content:"";position:fixed;inset:0;pointer-events:none;opacity:.10;background-image:linear-gradient(rgba(148,163,184,.18) 1px,transparent 1px),linear-gradient(90deg,rgba(148,163,184,.18) 1px,transparent 1px);background-size:34px 34px;mask-image:linear-gradient(to bottom,rgba(0,0,0,.92),transparent 84%)}
button,input{font:inherit}
.window{width:min(620px,100%);border-radius:22px;overflow:hidden;border:1px solid rgba(148,163,184,.18);background:linear-gradient(180deg,rgba(13,22,35,.98),rgba(7,13,23,.99));box-shadow:0 26px 80px rgba(0,0,0,.48),inset 0 1px 0 rgba(255,255,255,.04)}
.titlebar{height:44px;display:grid;grid-template-columns:1fr auto 1fr;align-items:center;padding:0 18px;border-bottom:1px solid rgba(148,163,184,.12);background:rgba(9,15,26,.82)}
.traffic{display:flex;gap:8px}.traffic span{width:12px;height:12px;border-radius:50%;box-shadow:inset 0 0 0 1px rgba(255,255,255,.16)}.red{background:#ff625d}.yellow{background:#ffbd2e}.green{background:#28c840}
.title{font-size:13px;font-weight:800;letter-spacing:-.01em;color:#e8eff8}.dots{justify-self:end;color:#96a3b7;letter-spacing:4px;font-weight:900;font-size:12px}
main{padding:16px 20px 20px}
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
.model{min-height:76px;display:grid;grid-template-columns:40px 1fr;gap:12px;align-items:center;padding:13px 15px;border-radius:14px;border:1px solid rgba(148,163,184,.14);background:linear-gradient(180deg,rgba(17,28,43,.68),rgba(10,17,28,.76));cursor:pointer;text-align:left;color:var(--text)}
.model.is-selected{border-color:var(--line2);background:linear-gradient(180deg,rgba(25,39,65,.82),rgba(11,20,36,.82))}
.model-icon{width:38px;height:38px;color:#dbe6f4;opacity:.55}.model.is-selected .model-icon{opacity:1}
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
@media (max-width:560px){.hero{grid-template-columns:1fr}.card{grid-template-columns:1fr}.token-row{grid-template-columns:1fr}.grid2{grid-template-columns:1fr}}
</style></head><body>
<div class="window">
<div class="titlebar"><div class="traffic"><span class="red"></span><span class="yellow"></span><span class="green"></span></div><div class="title">로컬 AI 제공자 설정</div><div class="dots">•••</div></div>
<main>
<section class="hero"><div class="logo"><img src="/mascot.png" alt="냥시스턴트 마스코트"></div><div><h1>AI 네트워크 구축 도우미 · 냥시스턴트</h1><div class="sub">내 PC를 Discord 서버의 로컬 AI 노드로 연결합니다.</div></div></section>
<section class="card"><div class="ring off" id="ring"><svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2.4" stroke-linecap="round" stroke-linejoin="round"><path d="M20 6 9 17l-5-5"></path></svg></div>
<div><div class="status-title" id="stitle">대기 중</div><div class="status-body" id="ssub">연결 시작을 누르면 풀에 등록됩니다.</div><div class="chips" id="chips"></div></div></section>
<section><h2>1. 연결 토큰</h2>
<div class="token-row"><label class="input"><svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.8"><path d="M21 2l-2 2m-7.61 7.61a5.5 5.5 0 1 1-7.78 7.78 5.5 5.5 0 0 1 7.78-7.78Zm0 0L15.5 7.5m0 0 3 3L22 7l-3-3m-3.5 3.5L19 4"></path></svg><input type="password" id="token" placeholder="발급받은 토큰을 입력하세요"></label>
<button class="secondary-btn" type="button" onclick="getToken()">토큰 발급 안내 ↗</button></div>
<div class="helper">Discord에서 발급한 토큰을 붙여넣은 뒤 연결을 시작하세요.</div></section>
<section><h2>2. 제공 모델</h2><div class="grid2" id="models"></div></section>
<section><h2>3. 설정</h2><div class="settings">
<div class="setting"><div class="iconbox"><svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.8"><path d="M12 2v10"></path><path d="M18.4 6.6a9 9 0 1 1-12.8 0"></path></svg></div><div><div class="setting-title">시스템 로그인 시 자동 실행</div><div class="setting-desc">로그인 후 에이전트를 자동으로 실행합니다.</div></div><div class="toggle on" id="svc" onclick="this.classList.toggle('on')"></div></div>
<div class="setting"><div class="iconbox"><svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.8"><rect x="3" y="4" width="18" height="16" rx="2"></rect><circle cx="8.5" cy="9" r="1.5"></circle><path d="m21 15-5-5L5 21"></path></svg></div><div><div class="setting-title">이미지 생성 제공 <span class="badge neutral">선택</span></div><div class="setting-desc">Stable Diffusion 환경이 있으면 /imagine 요청을 처리합니다.</div></div><div class="toggle" id="img" onclick="this.classList.toggle('on')"></div></div>
</div>
<button class="primary-btn" type="button" id="go" onclick="toggle()">▶ 연결 시작</button>
<div id="msg"></div>
<details><summary><span>로그 보기</span><span>⌄</span></summary><div class="details-body"><div id="log"></div></div></details>
<details><summary><span>고급 설정</span><span>⌄</span></summary><div class="details-body">중앙 서버(고정): <span class="ro" id="relay"></span></div></details>
</section>
</main></div>
<script>
const K="__SESSION_KEY__";const H={"X-Session":K};let RUN=false;let HAS_MODELS=false;
const MICON='<svg class="model-icon" viewBox="0 0 80 80" fill="none" stroke="currentColor" stroke-width="3" stroke-linecap="round" stroke-linejoin="round"><path d="M27 20c0-6 7-8 11-4 4-4 11-2 11 4v18c0 10-6 18-17 18S15 48 15 38V26c0-5 4-9 9-9h3Z"></path><path d="M30 31h.1M46 31h.1M31 43c4 3 10 3 14 0"></path><path d="M20 55v10M42 55v10M52 48v14"></path></svg>';
async function j(u,o){o=o||{};o.headers=Object.assign({},H,o.headers||{});const r=await fetch(u,o);return r.json();}
function esc(s){return s.replace(/[&<>"']/g,c=>({'&':'&amp;','<':'&lt;','>':'&gt;','"':'&quot;',"'":'&#39;'}[c]));}
async function loadModels(){const d=await j('/api/models');const box=document.getElementById('models');HAS_MODELS=d.models.length>0;
if(!d.models.length){box.innerHTML='<div class="empty">Ollama에서 모델을 못 찾았어요. <code>ollama pull llama3.1:8b</code> 후 새로고침하세요.</div>';return;}
box.innerHTML=d.models.map(m=>{const sel=d.selected.includes(m)||!d.selected.length;return `<article class="model${sel?' is-selected':''}" data-model="${esc(m)}" onclick="this.classList.toggle('is-selected')">${MICON}<div><div class="model-name">${esc(m)}</div><span class="badge">사용 가능</span></div></article>`;}).join('');}
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
const go=document.getElementById('go');go.textContent=s.running?'■ 중지':'▶ 연결 시작';go.className='primary-btn'+(s.running?' stop':'');
const lg=await j('/api/logs');const el=document.getElementById('log');el.textContent=lg.lines.join('\n');el.scrollTop=el.scrollHeight;}
async function getToken(){const s=await j('/api/status');const msg=document.getElementById('msg');
if(!s.connectEnabled){msg.className='';msg.textContent='토큰 발급은 곧 지원돼요. 지금은 디스코드 /provider-join 토큰을 붙여넣어 주세요.';return;}
const cb=location.origin+'/connect/callback';
location.href=s.relayUrl.replace('wss://','https://').replace('ws://','http://').replace(/\/agent$/,'')+'/provider/connect?cb='+encodeURIComponent(cb)+'&state='+encodeURIComponent(K);}
async function toggle(){const msg=document.getElementById('msg');if(RUN){await j('/api/stop',{method:'POST'});await refresh();return;}
msg.className='';msg.textContent='저장하고 연결하는 중…';
const su=await j('/api/setup',{method:'POST',headers:{'Content-Type':'application/json'},body:JSON.stringify({token:document.getElementById('token').value.trim(),models:selectedModels(),enableImage:on('img'),installService:on('svc')})});
if(!su.ok){msg.className='err';msg.textContent='⚠️ '+(su.error||'저장 실패');return;}document.getElementById('token').value='';
const st=await j('/api/start',{method:'POST'});
if(!st.ok){msg.className='err';msg.textContent='⚠️ '+st.error;}else{msg.className='ok';msg.textContent='✅ 연결 시작'+(su.serviceInstalled?' · 자동 실행 등록':'');}await refresh();}
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
                "내 PC 를 AI 일꾼으로", url, width=600, height=800, min_size=(400, 600)
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
