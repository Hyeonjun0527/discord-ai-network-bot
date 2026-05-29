"""Prometheus 메트릭 — 선택적 관측성 계층 (#47).

prometheus_client 가 설치된 경우에만 활성화되는 경량 메트릭 모듈이다. 미설치
환경에서는 모든 함수가 안전한 no-op 으로 동작해, 운영/테스트 어디서도 크래시를
일으키지 않는다(import 가드).

노출 메트릭
----------
* ``discord_assistant_commands_total{command,status}`` — 명령 실행 건수(Counter).
* ``discord_assistant_command_latency_ms{command}`` — 명령 지연(Histogram, ms).
* ``discord_assistant_errors_total{command}`` — 오류 건수(Counter).

bot.py 의 ``_record_usage`` 가 SQLite 기록과 병행해 ``record_command`` 를 호출한다.
prometheus_client 가 없으면 ``record_command`` 는 즉시 반환한다.
"""
from __future__ import annotations

import logging
from typing import TYPE_CHECKING, Any

logger = logging.getLogger(__name__)

# --- 선택 의존성 import 가드 ---
# prometheus_client 미설치 환경에서도 import 가 깨지지 않도록 방어한다. 설치되어
# 있으면 실제 메트릭 객체를 만들고, 없으면 _ENABLED=False 로 두어 전부 no-op 한다.
try:  # pragma: no cover - 설치 여부에 따라 분기(미설치 환경이 기본)
    from prometheus_client import (
        CONTENT_TYPE_LATEST,
        CollectorRegistry,
        Counter,
        Histogram,
        generate_latest,
    )

    _ENABLED = True
except ImportError:  # pragma: no cover - prometheus_client 미설치
    CONTENT_TYPE_LATEST = "text/plain; version=0.0.4; charset=utf-8"
    CollectorRegistry = None  # type: ignore[assignment,misc]
    Counter = None  # type: ignore[assignment,misc]
    Histogram = None  # type: ignore[assignment,misc]
    generate_latest = None  # type: ignore[assignment]
    _ENABLED = False

if TYPE_CHECKING:
    from prometheus_client import CollectorRegistry as _CollectorRegistry


# 메트릭은 모듈 전역 registry 에 등록한다(prometheus_client 의 관례). 미설치 시
# None 으로 두고, 노출/기록 헬퍼가 _ENABLED 로 분기한다.
_registry: Any = None
_commands_total: Any = None
_command_latency_ms: Any = None
_errors_total: Any = None

# 지연 히스토그램 버킷(ms). 슬래시 명령 응답은 수백 ms~수만 ms 범위가 많으므로
# 그 분포를 잘 드러내는 버킷을 둔다.
_LATENCY_BUCKETS_MS = (
    50.0,
    100.0,
    250.0,
    500.0,
    1_000.0,
    2_500.0,
    5_000.0,
    10_000.0,
    30_000.0,
    60_000.0,
)


def _build_metrics(registry: "_CollectorRegistry") -> None:
    """주어진 registry 에 메트릭 객체를 생성/등록한다(내부용)."""
    global _commands_total, _command_latency_ms, _errors_total
    _commands_total = Counter(
        "discord_assistant_commands_total",
        "명령 실행 건수(상태별).",
        ["command", "status"],
        registry=registry,
    )
    _command_latency_ms = Histogram(
        "discord_assistant_command_latency_ms",
        "명령 지연 시간(ms).",
        ["command"],
        buckets=_LATENCY_BUCKETS_MS,
        registry=registry,
    )
    _errors_total = Counter(
        "discord_assistant_errors_total",
        "명령 처리 중 발생한 오류 건수.",
        ["command"],
        registry=registry,
    )


if _ENABLED:  # pragma: no branch - 설치 시 1회 초기화
    _registry = CollectorRegistry()
    _build_metrics(_registry)


def is_enabled() -> bool:
    """prometheus_client 가 설치되어 메트릭이 활성화돼 있으면 True."""
    return _ENABLED


def record_command(command: str, status: str, latency_ms: float) -> None:
    """명령 실행 결과를 메트릭에 기록한다 (#47).

    - commands_total{command,status} 를 1 증가.
    - command_latency_ms{command} 에 지연을 관측.
    - status 가 'ok' 가 아니면 errors_total{command} 도 1 증가.

    prometheus_client 미설치 시 즉시 반환(no-op). 어떤 예외도 호출부로 새지 않게
    방어해, 메트릭 기록 실패가 명령 처리를 깨뜨리지 않도록 한다.
    """
    if not _ENABLED:
        return
    try:
        _commands_total.labels(command=command, status=status).inc()
        _command_latency_ms.labels(command=command).observe(float(latency_ms))
        if status != "ok":
            _errors_total.labels(command=command).inc()
    except Exception as exc:  # pragma: no cover - 방어적; 정상 경로에서 발생 안 함
        logger.debug("메트릭 기록 실패(무시): %s", exc)


def render_latest() -> tuple[bytes, str]:
    """현재 메트릭을 Prometheus 노출 포맷의 (body, content_type) 로 반환한다 (#48).

    prometheus_client 미설치 시 빈 본문과 기본 content-type 을 돌려준다(서버가
    빈 /metrics 를 그대로 노출하도록).
    """
    if not _ENABLED:
        return b"", CONTENT_TYPE_LATEST
    return generate_latest(_registry), CONTENT_TYPE_LATEST


def reset_for_tests() -> None:
    """메트릭 registry 를 초기화한다. 테스트 격리 전용.

    prometheus_client 미설치 시 no-op. 설치 시 새 registry 로 메트릭을 다시 만든다.
    """
    global _registry
    if not _ENABLED:
        return
    _registry = CollectorRegistry()
    _build_metrics(_registry)
