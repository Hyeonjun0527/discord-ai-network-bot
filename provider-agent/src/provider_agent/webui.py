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
    h.setFormatter(logging.Formatter("%(asctime)s %(levelname)-5s | %(message)s", datefmt="%H:%M:%S"))
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
    return (
        "<!doctype html><html lang=ko><head><meta charset=utf-8>"
        "<meta name=viewport content='width=device-width,initial-scale=1'><title>AI 일꾼</title><style>"
        "*{box-sizing:border-box}body{font-family:system-ui,'Pretendard',sans-serif;margin:0;background:#0d0f12;color:#e8eaed}"
        ".wrap{max-width:440px;margin:0 auto;padding:20px 20px 28px}"
        "h1{font-size:17px;margin:4px 0 2px}.sub{color:#788;font-size:12px;margin-bottom:16px}"
        ".pill{display:flex;align-items:center;gap:9px;background:#15181d;border:1px solid #23262d;border-radius:12px;padding:13px 15px;margin-bottom:18px}"
        ".dot{width:11px;height:11px;border-radius:50%;background:#5a606b;flex:0 0 auto}.dot.on{background:#b8ff39;box-shadow:0 0 8px #b8ff3988}.dot.try{background:#ffd23f}"
        ".pill .t{font-size:14px;font-weight:600}.pill .s{font-size:12px;color:#889;margin-top:1px}"
        ".step{font-size:12px;color:#8b93a0;font-weight:700;margin:18px 0 7px;letter-spacing:.02em}"
        ".row{display:flex;gap:8px}.row input{flex:1}"
        "input[type=text],input[type=password]{width:100%;padding:11px;border-radius:9px;border:1px solid #2a2e36;background:#15181d;color:#e8eaed;font-size:14px}"
        "#models{display:flex;flex-direction:column;gap:2px;background:#15181d;border:1px solid #2a2e36;border-radius:9px;padding:6px 10px}"
        ".chk{display:flex;gap:9px;align-items:center;padding:7px 0;font-size:14px}.chk input{width:17px;height:17px;flex:0 0 auto}"
        ".opts{margin-top:12px}.opts .chk{font-size:13px;color:#cdd}"
        "button{font-family:inherit;border:0;border-radius:10px;font-weight:700;font-size:14px;cursor:pointer;padding:12px 16px}"
        ".pri{width:100%;margin-top:18px;background:#b8ff39;color:#0a0b0d;font-size:15px;padding:14px}"
        ".pri.stop{background:#2a2e36;color:#ff8a8a}"
        ".ghost{background:#23262d;color:#cdd;padding:11px 13px;font-size:13px;white-space:nowrap}"
        "#msg{margin-top:10px;font-size:13px;min-height:18px}.err{color:#ff8a8a}.ok{color:#b8ff39}"
        "details{margin-top:18px;border-top:1px solid #1e2128;padding-top:12px}summary{font-size:12px;color:#788;cursor:pointer;list-style:none}"
        "summary::-webkit-details-marker{display:none}summary::before{content:'▸ ';color:#566}details[open] summary::before{content:'▾ '}"
        "#log{margin-top:9px;background:#0a0b0d;border:1px solid #1e2128;border-radius:8px;padding:9px;height:150px;overflow:auto;font:11px/1.5 ui-monospace,Menlo,monospace;color:#8a9;white-space:pre-wrap}"
        ".ro{color:#9aa;font-size:12px;background:#15181d;border:1px dashed #2a2e36;border-radius:7px;padding:8px;margin-top:7px;word-break:break-all}"
        "small{color:#788;font-size:12px}code{background:#1c1f26;padding:1px 5px;border-radius:4px}</style></head><body><div class=wrap>"
        "<h1>🖥️ 내 PC 를 AI 일꾼으로</h1><div class=sub>로컬 Ollama 를 커뮤니티 풀에 연결합니다</div>"
        # 상태 배지(최상단)
        "<div class=pill><span class='dot' id=dot></span><div><div class=t id=stxt>중지됨</div><div class=s id=ssub>시작을 누르면 풀에 연결됩니다</div></div></div>"
        # 1) 토큰
        "<div class=step>1 · 토큰</div>"
        "<div class=row><input type=password id=token placeholder='토큰 붙여넣기'><button class=ghost onclick=getToken()>토큰 받기</button></div>"
        # 2) 모델
        "<div class=step>2 · 제공할 모델</div><div id=models></div>"
        # 옵션
        "<div class=opts>"
        "<div class=chk><input type=checkbox id=service checked><label for=service>로그인할 때 자동으로 시작</label></div>"
        "<div class=chk><input type=checkbox id=image><label for=image>이미지 생성도 제공 <small>(로컬 Stable Diffusion 필요)</small></label></div>"
        "</div>"
        # 3) 시작/중지(하나의 주 버튼)
        "<button class=pri id=go onclick=toggle()>▶ 시작</button>"
        "<div id=msg></div>"
        # 고급/로그(접힘)
        "<details><summary>로그 보기</summary><div id=log></div></details>"
        "<details><summary>고급</summary><small>중앙 서버(고정)</small><div class=ro id=relay></div></details>"
        "</div><script>"
        f"const K='{session_key}';const H={{'X-Session':K}};let RUN=false;"
        "async function j(u,o){o=o||{};o.headers=Object.assign({},H,o.headers||{});const r=await fetch(u,o);return r.json();}"
        "async function loadModels(){const d=await j('/api/models');const box=document.getElementById('models');"
        "if(!d.models.length){box.innerHTML=\"<small>Ollama 에서 모델을 못 찾았어요. <code>ollama pull llama3.1:8b</code> 후 새로고침.</small>\";return;}"
        "box.innerHTML=d.models.map(m=>`<label class=chk><input type=checkbox class=mc value='${m}' ${d.selected.includes(m)||!d.selected.length?'checked':''}>${m}</label>`).join('');}"
        "function selectedModels(){return [...document.querySelectorAll('.mc:checked')].map(c=>c.value);}"
        "async function refresh(){const s=await j('/api/status');RUN=s.running;"
        "document.getElementById('relay').textContent=s.relayUrl;"
        "if(s.hasToken)document.getElementById('token').placeholder='저장됨 — 바꿀 때만 입력';"
        "document.getElementById('image').checked=s.enableImage;"
        "const dot=document.getElementById('dot');dot.className='dot'+(s.running?(s.connected?' on':' try'):'');"
        "const go=document.getElementById('go');go.textContent=s.running?'■ 중지':'▶ 시작';go.className='pri'+(s.running?' stop':'');"
        "document.getElementById('stxt').textContent=s.running?(s.connected?'실행 중 · 연결됨':'연결하는 중…'):'중지됨';"
        "document.getElementById('ssub').textContent=s.running?(s.connected?`처리 ${s.processed}건`+(s.imageReady?' · 🖼️ 이미지 제공':''):'중앙 서버에 연결 시도 중'):'시작을 누르면 풀에 연결됩니다';"
        "const lg=await j('/api/logs');const el=document.getElementById('log');el.textContent=lg.lines.join('\\n');el.scrollTop=el.scrollHeight;}"
        "async function getToken(){const s=await j('/api/status');"
        "if(!s.connectEnabled){msg.className='';msg.textContent='‘토큰 받기’는 곧 지원돼요. 지금은 디스코드 /provider-join 토큰을 붙여넣어 주세요.';return;}"
        "const cb=location.origin+'/connect/callback';"
        "location.href=s.relayUrl.replace('wss://','https://').replace('ws://','http://').replace(/\\/agent$/,'')+'/provider/connect?cb='+encodeURIComponent(cb)+'&state='+encodeURIComponent(K);}"
        "async function toggle(){if(RUN){await j('/api/stop',{method:'POST'});await refresh();return;}"
        "msg.className='';msg.textContent='저장하고 시작하는 중…';"
        "const su=await j('/api/setup',{method:'POST',headers:{'Content-Type':'application/json'},body:JSON.stringify({token:token.value.trim(),models:selectedModels(),enableImage:image.checked,installService:service.checked})});"
        "if(!su.ok){msg.className='err';msg.textContent='⚠️ '+(su.error||'저장 실패');return;}token.value='';"
        "const st=await j('/api/start',{method:'POST'});"
        "if(!st.ok){msg.className='err';msg.textContent='⚠️ '+st.error;}else{msg.className='ok';msg.textContent='✅ 시작됨'+(su.serviceInstalled?' · 자동 시작 등록':'');}await refresh();}"
        "loadModels();refresh();setInterval(refresh,2000);"
        "</script></body></html>"
    )


def build_app(session_key: str) -> web.Application:
    _attach_log_capture()
    app = web.Application()

    def _auth(req: web.Request) -> None:
        if req.headers.get("X-Session") != session_key:
            raise web.HTTPForbidden(text="세션 키 불일치")

    async def index(_req: web.Request) -> web.Response:
        return web.Response(text=_page(session_key), content_type="text/html")

    async def models(req: web.Request) -> web.Response:
        _auth(req)
        saved = load_config()
        return web.json_response({"models": await _detect_models(), "selected": list(saved.get("models") or [])})

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
                "relayUrl": saved.get("relay_url") or DEFAULT_RELAY,
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
            return web.json_response({"ok": False, "error": "토큰을 먼저 입력하세요(또는 ‘토큰 받기’)."})
        models_list = [str(m).strip() for m in (data.get("models") or []) if str(m).strip()]
        enable_image = bool(data.get("enableImage"))
        relay = (saved.get("relay_url") or DEFAULT_RELAY).rstrip("/")
        cfg = AgentConfig(token=token, relay_url=relay, models=tuple(models_list), enable_image=enable_image)
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
        relay = (saved.get("relay_url") or DEFAULT_RELAY).rstrip("/")
        save_config(AgentConfig(token=token, relay_url=relay, models=tuple(saved.get("models") or ()), enable_image=bool(saved.get("enable_image"))))
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
    print(f"\n제어판: {url}\n(창이 안 보이면 위 주소를 브라우저에서 여세요. AGENT_GUI_BROWSER=1 로 브라우저 강제.)\n", flush=True)

    if _webview_available():
        try:
            import webview  # type: ignore[import-untyped]

            webview.create_window("내 PC 를 AI 일꾼으로", url, width=460, height=820, min_size=(380, 560))
            webview.start()  # 메인 스레드 점유, 창 닫으면 반환
            return
        except Exception as exc:  # noqa: BLE001 - 웹뷰 실패 시 브라우저로 폴백
            logging.getLogger("provider_agent").warning("네이티브 창 실패(%s) — 브라우저로 폴백", exc)

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
