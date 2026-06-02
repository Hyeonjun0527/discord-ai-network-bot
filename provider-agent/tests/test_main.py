"""__main__ 경고/엔트리 동작 테스트."""
from __future__ import annotations

import logging

from provider_agent.__main__ import _warn_risky_config
from provider_agent.config import AgentConfig


def test_warns_on_risky_flags(caplog):
    cfg = AgentConfig(token="T", allow_remote_ollama=True, daily_limit=0, pause_on_battery=False)
    log = logging.getLogger("provider_agent")
    with caplog.at_level(logging.WARNING, logger="provider_agent"):
        _warn_risky_config(cfg, log)
    text = "\n".join(r.getMessage() for r in caplog.records)
    assert "원격 Ollama" in text
    assert "무제한" in text
    assert "배터리" in text


def test_no_warns_on_safe_defaults(caplog):
    cfg = AgentConfig(token="T")  # 안전 기본값
    log = logging.getLogger("provider_agent")
    with caplog.at_level(logging.WARNING, logger="provider_agent"):
        _warn_risky_config(cfg, log)
    warnings = [r for r in caplog.records if r.levelno >= logging.WARNING]
    assert warnings == []
