"""익명 텔레메트리 opt-in (차수 10 #130).

기본 비활성. ``cfg.telemetry`` 가 True 일 때만 익명 이벤트를 남긴다(개인정보·프롬프트 미포함).
원격 수집 sink 는 외부 구성(현재는 로컬 로그만). opt-in 메커니즘과 안전 기본값을 제공한다.
"""
from __future__ import annotations

import logging

logger = logging.getLogger("provider_agent.telemetry")

# 허용된 익명 필드만 보낸다(프롬프트·토큰·식별정보 금지).
ALLOWED_KEYS = {"event", "agent_version", "platform", "models_count"}


def emit(cfg, event: str, **props) -> bool:
    """텔레메트리 이벤트 기록. opt-in(꺼짐)이면 아무것도 하지 않고 False 반환."""
    if not getattr(cfg, "telemetry", False):
        return False
    payload = {"event": event}
    for k, v in props.items():
        if k in ALLOWED_KEYS:
            payload[k] = v
    logger.info("telemetry %s", payload)  # 로컬 로그 sink(원격은 외부)
    return True
