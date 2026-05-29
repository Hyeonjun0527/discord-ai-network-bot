"""헬스/메트릭 HTTP 서버 — 선택적 관측성 계층 (#48).

aiohttp(이미 discord.py 의존성으로 존재) 기반 경량 HTTP 서버를 띄워 다음
엔드포인트를 노출한다.

* ``GET /healthz`` — 프로세스 생존(liveness). 항상 200.
* ``GET /readyz``  — 봇 준비 상태(readiness). ``bot.is_ready()`` 가 True 면 200,
  아니면 503.
* ``GET /metrics`` — Prometheus 노출 포맷(metrics.render_latest). prometheus_client
  미설치 시 빈 본문을 200 으로 노출한다.

포트는 settings 의 ``metrics_port`` (0=비활성)로 제어한다. 0 이면 ``start`` 가
서버를 띄우지 않고 None 을 반환해, 미설정 환경에서 아무 부작용이 없다(백워드 호환).

aiohttp 가 어떤 이유로 import 되지 않더라도(import 가드) 서버 기동만 건너뛰고
크래시하지 않는다.
"""
from __future__ import annotations

import logging
from typing import TYPE_CHECKING, Any, Protocol

from . import metrics

logger = logging.getLogger(__name__)

# --- import 가드 ---
# aiohttp 는 discord.py 의 런타임 의존성이라 사실상 항상 존재하지만, 방어적으로
# 가드해 import 실패 시에도 모듈 import 자체는 깨지지 않게 한다.
try:  # pragma: no cover - aiohttp 는 보통 설치돼 있음
    from aiohttp import web

    _HAVE_AIOHTTP = True
except ImportError:  # pragma: no cover - aiohttp 미설치(이론상)
    web = None  # type: ignore[assignment]
    _HAVE_AIOHTTP = False

if TYPE_CHECKING:
    from aiohttp.web import Application, Request, Response


class _ReadinessProvider(Protocol):
    """``is_ready() -> bool`` 만 요구하는 최소 프로토콜(테스트 더블 호환)."""

    def is_ready(self) -> bool: ...


def build_app(bot: _ReadinessProvider) -> "Application":
    """헬스/메트릭 라우트를 가진 aiohttp Application 을 구성한다 (#48).

    aiohttp 미설치 시 RuntimeError 를 던진다(호출부는 start 를 통해서만 접근하며,
    start 가 가드하므로 정상 경로에서는 발생하지 않는다).
    """
    if not _HAVE_AIOHTTP:  # pragma: no cover - 방어적
        raise RuntimeError("aiohttp 가 설치되어 있지 않아 헬스 서버를 만들 수 없습니다.")

    async def _healthz(_request: "Request") -> "Response":
        # liveness: 프로세스가 응답할 수 있으면 OK.
        return web.json_response({"status": "ok"})

    async def _readyz(_request: "Request") -> "Response":
        # readiness: 봇 게이트웨이 준비 완료 여부.
        ready = False
        try:
            ready = bool(bot.is_ready())
        except Exception:  # pragma: no cover - 방어적
            ready = False
        status_code = 200 if ready else 503
        return web.json_response({"ready": ready}, status=status_code)

    async def _metrics(_request: "Request") -> "Response":
        body, content_type = metrics.render_latest()
        # Prometheus 의 Content-Type 은 'text/plain; version=0.0.4; charset=utf-8'
        # 처럼 version/charset 메타를 포함한다. aiohttp 의 content_type= 인자는
        # 파라미터(charset 등)를 허용하지 않아 ValueError 를 내므로, 원본 헤더를
        # 그대로 보존하기 위해 headers 로 전체 Content-Type 을 명시한다(#135).
        return web.Response(body=body, headers={"Content-Type": content_type})

    app = web.Application()
    app.router.add_get("/healthz", _healthz)
    app.router.add_get("/readyz", _readyz)
    app.router.add_get("/metrics", _metrics)
    return app


class HealthServer:
    """aiohttp 헬스/메트릭 서버의 기동/정리를 캡슐화한다 (#48).

    ``start`` 로 백그라운드 기동, ``stop`` 으로 graceful 정리한다. 포트 0(비활성)
    이거나 aiohttp 미설치면 기동을 건너뛴다.
    """

    def __init__(self, bot: _ReadinessProvider, *, port: int, host: str = "0.0.0.0") -> None:
        self._bot = bot
        self._port = int(port)
        self._host = host
        self._runner: Any = None
        self._site: Any = None

    @property
    def is_running(self) -> bool:
        return self._runner is not None

    async def start(self) -> bool:
        """서버를 기동한다. 실제로 떴으면 True, (비활성/미설치/실패) 건너뛰면 False.

        포트가 0 이하이거나 aiohttp 가 없으면 조용히 건너뛴다(백워드 호환). 기동
        실패도 봇 기동을 막지 않도록 예외를 흡수하고 False 를 반환한다.
        """
        if self._port <= 0:
            logger.debug("헬스 서버 비활성(METRICS_PORT=0).")
            return False
        if not _HAVE_AIOHTTP:  # pragma: no cover - aiohttp 미설치(이론상)
            logger.warning("aiohttp 미설치로 헬스 서버를 띄울 수 없습니다.")
            return False
        if self._runner is not None:
            return True
        try:
            app = build_app(self._bot)
            runner = web.AppRunner(app)
            await runner.setup()
            site = web.TCPSite(runner, host=self._host, port=self._port)
            await site.start()
            self._runner = runner
            self._site = site
            logger.info("헬스/메트릭 서버 기동: http://%s:%d", self._host, self._port)
            return True
        except Exception as exc:  # pragma: no cover - 포트 충돌 등 런타임 오류
            logger.warning("헬스 서버 기동 실패(무시): %s", exc)
            # 부분 초기화된 runner 가 있으면 정리한다.
            await self.stop()
            return False

    async def stop(self) -> None:
        """서버를 graceful 하게 정리한다(멱등)."""
        site = self._site
        runner = self._runner
        self._site = None
        self._runner = None
        if site is not None:
            try:
                await site.stop()
            except Exception as exc:  # pragma: no cover - 방어적
                logger.debug("헬스 서버 site 정리 실패(무시): %s", exc)
        if runner is not None:
            try:
                await runner.cleanup()
            except Exception as exc:  # pragma: no cover - 방어적
                logger.debug("헬스 서버 runner 정리 실패(무시): %s", exc)
