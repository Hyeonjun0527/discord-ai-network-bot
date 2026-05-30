"""설정/CLI 테스트 — 우선순위(CLI>env>기본), 토큰 필수."""
from __future__ import annotations

import pytest

from provider_agent.config import config_from_args


def test_cli_args():
    cfg, verbose = config_from_args(
        ["--token", "T", "--relay-url", "ws://h:9/agent", "--ollama-url", "http://o:1234",
         "--model", "a", "--model", "b", "--max-concurrency", "3", "--daily-limit", "50", "-v"]
    )
    assert cfg.token == "T"
    assert cfg.relay_url == "ws://h:9/agent"
    assert cfg.ollama_url == "http://o:1234"
    assert cfg.models == ("a", "b")
    assert cfg.max_concurrency == 3
    assert cfg.daily_limit == 50
    assert verbose is True


def test_env_fallback(monkeypatch):
    monkeypatch.setenv("AGENT_TOKEN", "ENVTOK")
    monkeypatch.setenv("RELAY_URL", "ws://envhost/agent")
    monkeypatch.setenv("OLLAMA_BASE_URL", "http://envollama:11434")
    cfg, _ = config_from_args([])
    assert cfg.token == "ENVTOK"
    assert cfg.relay_url == "ws://envhost/agent"
    assert cfg.ollama_url == "http://envollama:11434"


def test_cli_overrides_env(monkeypatch):
    monkeypatch.setenv("AGENT_TOKEN", "ENVTOK")
    cfg, _ = config_from_args(["--token", "CLITOK"])
    assert cfg.token == "CLITOK"


def test_token_required(monkeypatch):
    monkeypatch.delenv("AGENT_TOKEN", raising=False)
    with pytest.raises(SystemExit):
        config_from_args([])


def test_masked_hides_token():
    cfg, _ = config_from_args(["--token", "supersecret"])
    assert "supersecret" not in cfg.masked()
    assert "***" in cfg.masked()
