"""Bugsink/Sentry SDK 초기화.

Bugsink는 Sentry SDK 호환 서버이므로 SDK 코드는 Sentry를 사용하고, DSN만 Bugsink 프로젝트
값으로 주입한다. DSN이 없으면 아무 작업도 하지 않는다.
"""
from __future__ import annotations

import logging
import os

from .constants import AGENT_VERSION

logger = logging.getLogger("provider_agent.bugsink")


def init_bugsink() -> None:
    dsn = (os.getenv("BUGSINK_DSN") or os.getenv("SENTRY_DSN") or "").strip()
    if not dsn:
        return

    try:
        import sentry_sdk
    except ImportError:
        logger.warning("Bugsink DSN이 설정됐지만 sentry-sdk를 불러오지 못했습니다.")
        return

    sentry_sdk.init(
        dsn=dsn,
        environment=(os.getenv("SENTRY_ENV") or os.getenv("APP_ENV") or "development").strip(),
        release=f"nexa-agent@{AGENT_VERSION}",
        traces_sample_rate=0.0,
        send_default_pii=False,
    )
