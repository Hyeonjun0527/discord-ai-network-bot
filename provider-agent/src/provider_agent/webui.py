"""로컬 웹 제어판 (`--gui`).

브라우저에서 **클릭**으로 프로바이더를 설정·시작·중지하고 라이브 상태/로그를 본다.
중앙 서버 주소는 **고정(프리필)** — 유저가 외워 칠 필요 없음. 제공 모델은 **로컬 Ollama 에서
자동 감지**해 체크박스로 고른다. 에이전트의 aiohttp 를 재사용한다(별도 GUI 툴킷 불필요).

보안: 127.0.0.1 만 바인딩 + 페이지가 가진 **세션 키** 요청만 처리(로컬 무단 접근 차단).
"""
from __future__ import annotations

import asyncio
import logging
import secrets
from collections import deque

from aiohttp import web

from .config import AgentConfig, config_from_args
from .config_file import load_config, save_config
from .netguard import RemoteOllamaBlocked, ensure_ollama_allowed

# 공개 기본 중앙 서버(유저는 입력하지 않음). 자체호스팅만 고급에서 바꿀 수 있다.
DEFAULT_RELAY = "wss://discord-ai.yeon.world/agent"

# 최근 로그 라인(웹 대시보드 표시용).
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


def _page(session_key: str) -> str:
    return (
        "<!doctype html><html lang=ko><head><meta charset=utf-8>"
        "<meta name=viewport content='width=device-width,initial-scale=1'><title>프로바이더</title><style>"
        "body{font-family:system-ui,'Pretendard',sans-serif;max-width:600px;margin:28px auto;padding:0 18px;background:#0d0f12;color:#e8eaed}"
        "h1{font-size:20px}h2{font-size:15px;color:#aab;margin:22px 0 8px;border-top:1px solid #23262d;padding-top:18px}"
        "label{display:block;margin:12px 0 4px;font-size:13px;color:#aab}"
        "input[type=text],input[type=password]{width:100%;padding:10px;border-radius:8px;border:1px solid #2a2e36;background:#15181d;color:#e8eaed;font-size:14px}"
        ".chk{display:flex;gap:8px;align-items:center;margin:8px 0}.chk input{width:auto}"
        "#models{display:flex;flex-direction:column;gap:6px;background:#15181d;border:1px solid #2a2e36;border-radius:8px;padding:10px}"
        "button{margin-top:14px;padding:11px 16px;border:0;border-radius:9px;background:#b8ff39;color:#0a0b0d;font-weight:700;font-size:14px;cursor:pointer}"
        "button.sec{background:#23262d;color:#e8eaed}.bar{display:flex;gap:8px}"
        "#status{font-size:14px;margin-top:6px}.dot{display:inline-block;width:9px;height:9px;border-radius:50%;background:#5a606b;margin-right:6px}"
        ".on{background:#b8ff39}#log{margin-top:10px;background:#0a0b0d;border:1px solid #23262d;border-radius:8px;padding:10px;height:160px;overflow:auto;font:12px/1.5 ui-monospace,Menlo,monospace;color:#9aa;white-space:pre-wrap}"
        "small{color:#788}.ro{color:#9aa;font-size:13px;background:#15181d;border:1px dashed #2a2e36;border-radius:8px;padding:8px}#msg{margin-top:8px;font-size:13px}.err{color:#ff6b6b}.ok{color:#b8ff39}</style></head><body>"
        "<h1>🖥️ 내 PC 를 AI 일꾼으로</h1>"
        "<h2>설정</h2>"
        "<small>디스코드 <b>/provider-join</b> 으로 받은 토큰을 붙여넣으세요.</small>"
        "<label>토큰(1회용)</label><input type=password id=token placeholder='ABCDE-FGHIJ-KLMNP'>"
        "<label>제공할 모델 <span id=mhint style=color:#788></span></label><div id=models></div>"
        "<div class=chk><input type=checkbox id=image><label style='margin:0'>이미지 생성도 제공(로컬 Stable Diffusion 필요)</label></div>"
        "<div class=chk><input type=checkbox id=service checked><label style='margin:0'>로그인 시 자동 시작(권장)</label></div>"
        "<label>중앙 서버</label><div class=ro id=relay></div>"
        "<button onclick=save()>저장</button>"
        "<div id=msg></div>"
        "<h2>실행 / 상태</h2>"
        "<div class=bar><button onclick=start()>▶ 시작</button><button class=sec onclick=stop()>■ 중지</button></div>"
        "<div id=status><span class=dot id=dot></span><span id=stxt>중지됨</span></div>"
        "<div id=log></div>"
        "<script>"
        f"const K='{session_key}';const H={{'X-Session':K}};"
        "async function j(u,o){o=o||{};o.headers=Object.assign({},H,o.headers||{});const r=await fetch(u,o);return r.json();}"
        "async function loadModels(){const d=await j('/api/models');const box=document.getElementById('models');"
        "if(!d.models.length){box.innerHTML='<small>Ollama 에서 모델을 못 찾았어요. <code>ollama pull llama3.1:8b</code> 후 새로고침.</small>';return;}"
        "box.innerHTML=d.models.map(m=>`<div class=chk><input type=checkbox class=mc value='${m}' ${d.selected.includes(m)||!d.selected.length?'checked':''}><label style='margin:0'>${m}</label></div>`).join('');}"
        "async function refresh(){const s=await j('/api/status');document.getElementById('relay').textContent=s.relayUrl;"
        "document.getElementById('token').placeholder=s.hasToken?'(저장됨 — 바꿀 때만 입력)':'ABCDE-FGHIJ-KLMNP';"
        "document.getElementById('image').checked=s.enableImage;"
        "document.getElementById('dot').className='dot'+(s.connected?' on':'');"
        "document.getElementById('stxt').textContent=s.running?(s.connected?`연결됨 · 처리 ${s.processed}건`+(s.imageReady?' · 🖼️이미지':''):'연결 시도 중...'):'중지됨';"
        "const lg=await j('/api/logs');const el=document.getElementById('log');el.textContent=lg.lines.join('\\n');el.scrollTop=el.scrollHeight;}"
        "function selectedModels(){return [...document.querySelectorAll('.mc:checked')].map(c=>c.value);}"
        "async function save(){msg.className='';msg.textContent='저장 중...';"
        "const d=await j('/api/setup',{method:'POST',headers:{'Content-Type':'application/json'},body:JSON.stringify({token:token.value.trim(),models:selectedModels(),enableImage:image.checked,installService:service.checked})});"
        "msg.className=d.ok?'ok':'err';msg.textContent=d.ok?('✅ 저장됨'+(d.serviceInstalled?' · 자동 시작 등록':'')):('⚠️ '+(d.error||'실패'));if(d.ok)token.value='';refresh();}"
        "async function start(){const d=await j('/api/start',{method:'POST'});if(!d.ok){msg.className='err';msg.textContent='⚠️ '+d.error;}refresh();}"
        "async function stop(){await j('/api/stop',{method:'POST'});refresh();}"
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
            return web.json_response({"ok": False, "error": "토큰을 입력하세요."})
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
    app.router.add_post("/api/setup", setup)
    app.router.add_post("/api/start", start)
    app.router.add_post("/api/stop", stop)
    return app


def run_gui(host: str = "127.0.0.1", port: int = 0) -> None:
    """로컬 제어판을 띄우고 브라우저를 연다(127.0.0.1 전용 + 세션 키)."""
    import webbrowser

    session_key = secrets.token_urlsafe(24)
    app = build_app(session_key)

    async def _serve() -> None:
        runner = web.AppRunner(app)
        await runner.setup()
        site = web.TCPSite(runner, host, port)
        await site.start()
        actual_port = site._server.sockets[0].getsockname()[1]  # type: ignore[union-attr]
        url = f"http://{host}:{actual_port}/"
        print(f"\n제어판: {url}\n(브라우저가 자동으로 열립니다. 안 열리면 위 주소를 여세요.)\n", flush=True)
        try:
            webbrowser.open(url)
        except Exception:  # noqa: BLE001
            pass
        await asyncio.Event().wait()

    asyncio.run(_serve())
