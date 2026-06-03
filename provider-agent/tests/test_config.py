"""설정/CLI 테스트 — 우선순위(CLI>env>기본), 토큰 필수."""
from __future__ import annotations

import pytest

from provider_agent.config import config_from_args


@pytest.fixture(autouse=True)
def _isolated_config(monkeypatch, tmp_path):
    # 저장된 설정 파일(~/.config) 을 읽지 않도록 격리한다(실사용 토큰이 테스트에 새지 않게).
    monkeypatch.setenv("XDG_CONFIG_HOME", str(tmp_path))
    monkeypatch.delenv("AGENT_TOKEN", raising=False)
    monkeypatch.delenv("RELAY_URL", raising=False)
    monkeypatch.delenv("OLLAMA_BASE_URL", raising=False)
    yield


def test_cli_args():
    # 원격 Ollama 주소(o)는 안전 기본값에 막히므로 --allow-remote-ollama 를 명시한다.
    cfg, verbose = config_from_args(
        ["--token", "T", "--relay-url", "ws://h:9/agent", "--ollama-url", "http://o:1234",
         "--allow-remote-ollama",
         "--model", "a", "--model", "b", "--max-concurrency", "3", "--daily-limit", "50", "-v"]
    )
    assert cfg.token == "T"
    assert cfg.relay_url == "ws://h:9/agent"
    assert cfg.ollama_url == "http://o:1234"
    assert cfg.allow_remote_ollama is True
    assert cfg.models == ("a", "b")
    assert cfg.max_concurrency == 3
    assert cfg.daily_limit == 50
    assert verbose is True


def test_env_fallback(monkeypatch):
    monkeypatch.setenv("AGENT_TOKEN", "ENVTOK")
    monkeypatch.setenv("RELAY_URL", "ws://envhost/agent")
    monkeypatch.setenv("OLLAMA_BASE_URL", "http://127.0.0.1:9999")  # 루프백이라 통과
    cfg, _ = config_from_args([])
    assert cfg.token == "ENVTOK"
    assert cfg.relay_url == "ws://envhost/agent"
    assert cfg.ollama_url == "http://127.0.0.1:9999"


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


def test_daily_limit_safe_default(monkeypatch, tmp_path):
    """기본 일일 한도는 무제한이 아니라 안전값(DEFAULT_DAILY_LIMIT)이어야 한다."""
    monkeypatch.setenv("XDG_CONFIG_HOME", str(tmp_path))
    from provider_agent.constants import DEFAULT_DAILY_LIMIT

    cfg, _ = config_from_args(["--token", "T"])
    assert cfg.daily_limit == DEFAULT_DAILY_LIMIT
    assert cfg.daily_limit > 0  # 무제한(0) 아님
    assert cfg.max_concurrency == 1  # 동시성 기본 1 유지


def test_unlimited_requires_explicit_flag(monkeypatch, tmp_path):
    """--daily-limit 0(무제한)은 --allow-unlimited 없이는 거부된다."""
    monkeypatch.setenv("XDG_CONFIG_HOME", str(tmp_path))
    with pytest.raises(SystemExit):
        config_from_args(["--token", "T", "--daily-limit", "0"])


def test_unlimited_with_flag_ok(monkeypatch, tmp_path):
    monkeypatch.setenv("XDG_CONFIG_HOME", str(tmp_path))
    cfg, _ = config_from_args(["--token", "T", "--daily-limit", "0", "--allow-unlimited"])
    assert cfg.daily_limit == 0


def test_remote_ollama_blocked_by_default(monkeypatch, tmp_path):
    monkeypatch.setenv("XDG_CONFIG_HOME", str(tmp_path))
    with pytest.raises(SystemExit):
        config_from_args(["--token", "T", "--ollama-url", "http://192.168.0.10:11434"])


def test_remote_ollama_allowed_with_flag(monkeypatch, tmp_path):
    monkeypatch.setenv("XDG_CONFIG_HOME", str(tmp_path))
    cfg, _ = config_from_args(
        ["--token", "T", "--ollama-url", "http://192.168.0.10:11434", "--allow-remote-ollama"]
    )
    assert cfg.ollama_url == "http://192.168.0.10:11434"
    assert cfg.allow_remote_ollama is True


def test_image_sd_localhost_default(monkeypatch, tmp_path):
    monkeypatch.setenv("XDG_CONFIG_HOME", str(tmp_path))
    cfg, _ = config_from_args(["--token", "T", "--enable-image"])
    assert cfg.enable_image is True
    assert cfg.sd_url == "http://127.0.0.1:7860"


def test_remote_sd_blocked_by_default(monkeypatch, tmp_path):
    monkeypatch.setenv("XDG_CONFIG_HOME", str(tmp_path))
    with pytest.raises(SystemExit):
        config_from_args(["--token", "T", "--enable-image", "--sd-url", "http://192.168.0.9:7860"])


def test_remote_sd_allowed_with_flag(monkeypatch, tmp_path):
    monkeypatch.setenv("XDG_CONFIG_HOME", str(tmp_path))
    cfg, _ = config_from_args(
        ["--token", "T", "--enable-image", "--sd-url", "http://192.168.0.9:7860", "--allow-remote-sd"]
    )
    assert cfg.sd_url == "http://192.168.0.9:7860"
    assert cfg.allow_remote_sd is True
