"""로컬 웹 설정 UI (`--gui`).

브라우저에서 토큰·풀 설정·자동 시작을 **클릭**으로. 터미널 명령 없이 프로바이더를 세팅한다.
서버는 **127.0.0.1 만** 바인딩하고, 페이지가 가진 **세션 키**를 가진 요청만 처리한다(로컬 다른
프로세스/사이트의 무단 접근 차단). 저장하면 설정+자동시작이 등록되어 이후 알아서 연결된다.
"""
from __future__ import annotations

import secrets

from aiohttp import web

from .config import AgentConfig
from .config_file import load_config, save_config
from .netguard import RemoteOllamaBlocked, ensure_ollama_allowed

DEFAULT_RELAY = "ws://localhost:8080/agent"


def _page(session_key: str) -> str:
    return (
        "<!doctype html><html lang=ko><head><meta charset=utf-8>"
        "<meta name=viewport content='width=device-width,initial-scale=1'>"
        "<title>프로바이더 설정</title><style>"
        "body{font-family:system-ui,'Pretendard',sans-serif;max-width:560px;margin:32px auto;padding:0 18px;background:#0d0f12;color:#e8eaed}"
        "h1{font-size:20px}label{display:block;margin:14px 0 4px;font-size:13px;color:#aab}"
        "input,select{width:100%;padding:10px;border-radius:8px;border:1px solid #2a2e36;background:#15181d;color:#e8eaed;font-size:14px}"
        ".row{display:flex;gap:8px;align-items:center;margin-top:12px}.row input[type=checkbox]{width:auto}"
        "button{margin-top:18px;width:100%;padding:12px;border:0;border-radius:9px;background:#b8ff39;color:#0a0b0d;font-weight:700;font-size:15px;cursor:pointer}"
        "#msg{margin-top:14px;font-size:14px;min-height:20px}.ok{color:#b8ff39}.err{color:#ff6b6b}"
        "small{color:#788}</style></head><body>"
        "<h1>🖥️ 프로바이더 설정</h1>"
        "<small>디스코드 <b>/provider-join</b> 으로 받은 토큰을 붙여넣고 저장하세요. 한 번만 하면 됩니다.</small>"
        "<label>토큰(1회용)</label><input id=token placeholder='ABCDE-FGHIJ-KLMNP'>"
        "<label>중앙 서버 주소</label><input id=relay>"
        "<label>제공 모델(쉼표, 비우면 자동감지)</label><input id=models placeholder='llama3.1:8b'>"
        "<div class=row><input type=checkbox id=image><label style='margin:0'>이미지 생성도 제공(로컬 Stable Diffusion 필요)</label></div>"
        "<div class=row><input type=checkbox id=service checked><label style='margin:0'>로그인 시 자동 시작(권장)</label></div>"
        "<button onclick=save()>저장하고 자동 연결</button>"
        "<div id=msg></div>"
        "<script>"
        f"const K='{session_key}';"
        "async function load(){const r=await fetch('/api/status',{headers:{'X-Session':K}});const d=await r.json();"
        "relay.value=d.relayUrl||'" + DEFAULT_RELAY + "';token.value=d.hasToken?'(저장됨)':'';models.value=(d.models||[]).join(',');image.checked=!!d.enableImage;}"
        "async function save(){msg.className='';msg.textContent='저장 중...';"
        "const body={token:token.value.trim(),relayUrl:relay.value.trim(),models:models.value,enableImage:image.checked,installService:service.checked};"
        "const r=await fetch('/api/setup',{method:'POST',headers:{'X-Session':K,'Content-Type':'application/json'},body:JSON.stringify(body)});"
        "const d=await r.json();if(d.ok){msg.className='ok';msg.textContent='✅ 저장 완료! '+(d.serviceInstalled?'자동 시작 등록됨 — 이 창을 닫아도 됩니다.':'에이전트를 실행하세요.');}"
        "else{msg.className='err';msg.textContent='⚠️ '+(d.error||'실패');}}"
        "load();</script></body></html>"
    )


def build_app(session_key: str) -> web.Application:
    app = web.Application()

    def _auth(req: web.Request) -> None:
        if req.headers.get("X-Session") != session_key:
            raise web.HTTPForbidden(text="세션 키 불일치")

    async def index(_req: web.Request) -> web.Response:
        return web.Response(text=_page(session_key), content_type="text/html")

    async def status(req: web.Request) -> web.Response:
        _auth(req)
        saved = load_config()
        return web.json_response(
            {
                "hasToken": bool(saved.get("token")),
                "relayUrl": saved.get("relay_url") or "",
                "models": list(saved.get("models") or []),
                "enableImage": bool(saved.get("enable_image")),
            }
        )

    async def setup(req: web.Request) -> web.Response:
        _auth(req)
        data = await req.json()
        token = str(data.get("token", "")).strip()
        saved = load_config()
        # '(저장됨)' 표시를 그대로 보내면 기존 저장 토큰 유지.
        if not token or token == "(저장됨)":
            token = str(saved.get("token", ""))
        if not token:
            return web.json_response({"ok": False, "error": "토큰을 입력하세요."})
        relay = (str(data.get("relayUrl", "")).strip() or DEFAULT_RELAY).rstrip("/")
        models = tuple(m.strip() for m in str(data.get("models", "")).split(",") if m.strip())
        enable_image = bool(data.get("enableImage"))
        cfg = AgentConfig(
            token=token,
            relay_url=relay,
            models=models,
            enable_image=enable_image,
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
            except Exception as exc:  # noqa: BLE001 - 등록 실패는 치명적이지 않음
                return web.json_response({"ok": True, "serviceInstalled": False, "warn": str(exc)})
        return web.json_response({"ok": True, "serviceInstalled": service_installed})

    app.router.add_get("/", index)
    app.router.add_get("/api/status", status)
    app.router.add_post("/api/setup", setup)
    return app


def run_gui(host: str = "127.0.0.1", port: int = 0) -> None:
    """로컬 설정 UI 를 띄우고 브라우저를 연다(127.0.0.1 전용 + 세션 키)."""
    import asyncio
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
        print(f"\n설정 UI: {url}\n(브라우저가 자동으로 열립니다. 안 열리면 위 주소를 여세요.)\n")
        try:
            webbrowser.open(url)
        except Exception:  # noqa: BLE001 - 헤드리스 등에서 실패해도 주소 출력으로 충분
            pass
        await asyncio.Event().wait()  # Ctrl+C 까지 유지

    asyncio.run(_serve())
