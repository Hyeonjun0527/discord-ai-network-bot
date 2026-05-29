"""관측성 3종(metrics/health/observability) 단위 테스트 (#47/#48/#55).

prometheus_client / sentry_sdk 가 미설치된 환경에서도 모든 경로가 안전한 no-op
으로 동작함을 검증한다. aiohttp 는 discord.py 의존성으로 항상 존재하므로 헬스
서버는 실제로 기동/요청까지 검증한다. 설치된 환경에서는 메트릭 기록/노출도
함께 검증한다(설치 여부에 따라 조건부).
"""
from __future__ import annotations

import os

import pytest

from discord_assistant import health, metrics, observability
from discord_assistant.settings import AppSettings

# ---------------------------------------------------------------------------
# #47 metrics — prometheus_client 유무에 무관하게 안전해야 한다.
# ---------------------------------------------------------------------------


def test_record_command_is_safe_without_prometheus() -> None:
    """prometheus_client 미설치 시 record_command 는 no-op(예외 없음)이어야 한다."""
    # 설치 여부와 무관하게 호출 자체가 절대 예외를 던지면 안 된다.
    metrics.record_command("summarize", "ok", 123.4)
    metrics.record_command("ask", "error", 50.0)


def test_render_latest_returns_bytes_and_content_type() -> None:
    """render_latest 는 (bytes, content_type) 튜플을 반환한다(미설치 시 빈 본문)."""
    body, content_type = metrics.render_latest()
    assert isinstance(body, bytes)
    assert isinstance(content_type, str)
    assert content_type  # 비어 있지 않은 content-type
    if not metrics.is_enabled():
        assert body == b""


@pytest.mark.skipif(not metrics.is_enabled(), reason="prometheus_client 미설치")
def test_metrics_records_when_enabled() -> None:
    """prometheus_client 설치 시 기록 후 노출 본문에 메트릭 이름이 나타난다."""
    metrics.reset_for_tests()
    metrics.record_command("summarize", "ok", 100.0)
    metrics.record_command("summarize", "error", 200.0)
    body, _ = metrics.render_latest()
    text = body.decode("utf-8")
    assert "discord_assistant_commands_total" in text
    assert "discord_assistant_command_latency_ms" in text
    # status=error 호출이 있었으므로 errors_total 도 노출된다.
    assert "discord_assistant_errors_total" in text


def test_reset_for_tests_is_safe() -> None:
    """reset_for_tests 는 설치 여부와 무관하게 예외 없이 동작한다."""
    metrics.reset_for_tests()


# ---------------------------------------------------------------------------
# #55 observability(Sentry) — sentry_sdk/SENTRY_DSN 유무에 무관하게 안전해야 한다.
# ---------------------------------------------------------------------------


def test_init_sentry_disabled_without_dsn() -> None:
    """DSN 이 비어 있으면 init_sentry 는 False(비활성)를 반환한다."""
    observability.reset_for_tests()
    assert observability.init_sentry("") is False
    assert observability.init_sentry(None) is False
    assert observability.is_enabled() is False


def test_capture_exception_is_safe_when_disabled() -> None:
    """Sentry 비활성 시 capture_exception 은 no-op(예외 없음)이어야 한다."""
    observability.reset_for_tests()
    observability.capture_exception(RuntimeError("boom"))
    observability.capture_exception(None)  # 현재 예외 캡처 경로도 안전해야 한다.


def test_init_sentry_without_sdk_returns_false() -> None:
    """sentry_sdk 미설치 시 DSN 이 있어도 init_sentry 는 False 를 반환한다."""
    observability.reset_for_tests()
    result = observability.init_sentry("https://example@o0.ingest.sentry.io/0")
    # sentry_sdk 가 설치된 환경이면 True, 미설치면 False — 어느 쪽이든 예외는 없어야 한다.
    if not observability._HAVE_SENTRY:
        assert result is False
        assert observability.is_enabled() is False


# ---------------------------------------------------------------------------
# #48 health — aiohttp 기반. 포트 0(비활성)과 실제 기동 모두 검증한다.
# ---------------------------------------------------------------------------


class _FakeBot:
    """is_ready() 만 가진 readiness 더블."""

    def __init__(self, ready: bool) -> None:
        self._ready = ready

    def is_ready(self) -> bool:
        return self._ready


async def test_health_server_disabled_on_port_zero() -> None:
    """포트 0(비활성)이면 start 는 False 를 반환하고 서버를 띄우지 않는다."""
    server = health.HealthServer(_FakeBot(ready=True), port=0)
    started = await server.start()
    assert started is False
    assert server.is_running is False
    # stop 은 멱등하게 안전.
    await server.stop()


async def test_health_server_endpoints() -> None:
    """실제로 서버를 띄워 /healthz·/readyz·/metrics 응답을 검증한다."""
    aiohttp = pytest.importorskip("aiohttp")
    fake_bot = _FakeBot(ready=True)
    # 포트 0 을 OS 가 자동 할당하도록 했다가 실제 포트를 읽는 방식이 아니라,
    # 고정 포트로 띄운 뒤 실패 시 건너뛴다(샌드박스 네트워크 제약 방어).
    server = health.HealthServer(fake_bot, port=18099, host="127.0.0.1")
    started = await server.start()
    if not started:
        pytest.skip("로컬에서 헬스 서버 포트 바인딩 불가(샌드박스 제약)")
    try:
        assert server.is_running is True
        async with aiohttp.ClientSession() as session:
            async with session.get("http://127.0.0.1:18099/healthz") as resp:
                assert resp.status == 200
                data = await resp.json()
                assert data["status"] == "ok"
            async with session.get("http://127.0.0.1:18099/readyz") as resp:
                assert resp.status == 200
                data = await resp.json()
                assert data["ready"] is True
            async with session.get("http://127.0.0.1:18099/metrics") as resp:
                assert resp.status == 200
                # 본문은 bytes 든 text 든 정상 응답이면 충분하다.
                await resp.read()
    finally:
        await server.stop()
        assert server.is_running is False


async def test_health_server_readyz_503_when_not_ready() -> None:
    """봇이 준비되지 않았으면 /readyz 는 503 을 반환한다."""
    aiohttp = pytest.importorskip("aiohttp")
    server = health.HealthServer(_FakeBot(ready=False), port=18100, host="127.0.0.1")
    started = await server.start()
    if not started:
        pytest.skip("로컬에서 헬스 서버 포트 바인딩 불가(샌드박스 제약)")
    try:
        async with aiohttp.ClientSession() as session:
            async with session.get("http://127.0.0.1:18100/readyz") as resp:
                assert resp.status == 503
                data = await resp.json()
                assert data["ready"] is False
    finally:
        await server.stop()


def test_build_app_routes_registered() -> None:
    """build_app 이 3개 라우트를 등록한다."""
    pytest.importorskip("aiohttp")
    app = health.build_app(_FakeBot(ready=True))
    paths = {route.resource.canonical for route in app.router.routes()}
    assert {"/healthz", "/readyz", "/metrics"}.issubset(paths)


# ---------------------------------------------------------------------------
# settings — METRICS_PORT / SENTRY_DSN 파싱(from_env).
# ---------------------------------------------------------------------------


def test_settings_observability_defaults(monkeypatch: pytest.MonkeyPatch) -> None:
    """기본값: metrics_port=0(비활성), sentry_dsn=''(비활성)."""
    monkeypatch.setenv("DISCORD_BOT_TOKEN", "test-token-123")
    monkeypatch.delenv("METRICS_PORT", raising=False)
    monkeypatch.delenv("SENTRY_DSN", raising=False)
    settings = AppSettings.from_env(load_env_file=False)
    assert settings.metrics_port == 0
    assert settings.sentry_dsn == ""


def test_settings_observability_from_env(monkeypatch: pytest.MonkeyPatch) -> None:
    """METRICS_PORT / SENTRY_DSN 환경 변수가 반영된다."""
    monkeypatch.setenv("DISCORD_BOT_TOKEN", "test-token-123")
    monkeypatch.setenv("METRICS_PORT", "9090")
    monkeypatch.setenv("SENTRY_DSN", "https://example@o0.ingest.sentry.io/0")
    settings = AppSettings.from_env(load_env_file=False)
    assert settings.metrics_port == 9090
    assert settings.sentry_dsn == "https://example@o0.ingest.sentry.io/0"


def test_settings_negative_metrics_port_rejected(monkeypatch: pytest.MonkeyPatch) -> None:
    """음수 METRICS_PORT 는 minimum=0 검증으로 거부된다."""
    monkeypatch.setenv("DISCORD_BOT_TOKEN", "test-token-123")
    monkeypatch.setenv("METRICS_PORT", "-1")
    with pytest.raises(ValueError):
        AppSettings.from_env(load_env_file=False)


@pytest.fixture(autouse=True)
def _cleanup_env() -> None:
    """테스트 후 관측성 상태를 초기화한다(다른 테스트 격리)."""
    yield
    metrics.reset_for_tests()
    observability.reset_for_tests()
    os.environ.pop("METRICS_PORT", None)
    os.environ.pop("SENTRY_DSN", None)
