"""Bugsink/Sentry SDK 초기화.

Bugsink는 Sentry SDK 호환 서버이므로 SDK 코드는 Sentry를 사용하고, DSN만 Bugsink 프로젝트
값으로 주입한다. DSN이 없으면 아무 작업도 하지 않는다.
"""
from __future__ import annotations

import logging
import os
import platform

from .constants import AGENT_VERSION

logger = logging.getLogger("provider_agent.bugsink")


def _environment() -> str:
    return (os.getenv("SENTRY_ENV") or os.getenv("APP_ENV") or "development").strip()


def _load_sentry_sdk(*, warn: bool):
    try:
        import sentry_sdk
    except ImportError:
        if warn:
            logger.warning("Bugsink DSN이 설정됐지만 sentry-sdk를 불러오지 못했습니다.")
        return None
    return sentry_sdk


def init_bugsink() -> None:
    dsn = (os.getenv("BUGSINK_DSN") or os.getenv("SENTRY_DSN") or "").strip()
    if not dsn:
        return

    sentry_sdk = _load_sentry_sdk(warn=True)
    if sentry_sdk is None:
        return

    sentry_sdk.init(
        dsn=dsn,
        environment=_environment(),
        release=f"nexa-agent@{AGENT_VERSION}",
        traces_sample_rate=0.0,
        send_default_pii=False,
    )
    sentry_sdk.set_tag("app", "desktop")
    sentry_sdk.set_tag("appVersion", AGENT_VERSION)
    sentry_sdk.set_tag("environment", _environment())
    sentry_sdk.set_tag("platform", platform.system().lower() or "unknown")


def capture_api_error(
    error: BaseException,
    *,
    request_id: str,
    method: str,
    api_endpoint: str,
    server_base_url: str,
    http_status: int | None = None,
) -> None:
    sentry_sdk = _load_sentry_sdk(warn=False)
    if sentry_sdk is None:
        return

    with sentry_sdk.push_scope() as scope:
        scope.set_tag("app", "desktop")
        scope.set_tag("appVersion", AGENT_VERSION)
        scope.set_tag("environment", _environment())
        scope.set_tag("platform", platform.system().lower() or "unknown")
        scope.set_tag("apiEndpoint", api_endpoint)
        scope.set_tag("requestId", request_id)
        if http_status is not None:
            scope.set_tag("httpStatus", str(http_status))
        scope.set_context(
            "api",
            {
                "requestId": request_id,
                "method": method,
                "endpoint": api_endpoint,
                "httpStatus": http_status,
                "serverBaseUrl": server_base_url,
            },
        )
        sentry_sdk.capture_exception(error)
