"""설정 파일 저장/로드(#113) + 텔레메트리 opt-in(#130) 테스트."""
from __future__ import annotations

import os
import stat

from provider_agent.config import config_from_args
from provider_agent.config_file import config_path, load_config, save_config


def test_config_dir_follows_xdg(monkeypatch, tmp_path):
    monkeypatch.setenv("XDG_CONFIG_HOME", str(tmp_path))
    assert config_path() == tmp_path / "discord-ai-network-bot" / "config.json"


def test_save_then_load_roundtrip(monkeypatch, tmp_path):
    monkeypatch.setenv("XDG_CONFIG_HOME", str(tmp_path))
    cfg, _ = config_from_args(["--token", "TOK", "--model", "a", "--model", "b"])
    path = save_config(cfg)
    assert path.exists()
    data = load_config()
    assert data["token"] == "TOK"
    assert data["models"] == ["a", "b"]
    # 시크릿 보호: 0600
    mode = stat.S_IMODE(os.stat(path).st_mode)
    assert mode == 0o600


def test_saved_token_used_when_no_cli(monkeypatch, tmp_path):
    monkeypatch.setenv("XDG_CONFIG_HOME", str(tmp_path))
    # 먼저 저장
    cfg, _ = config_from_args(["--token", "SAVED", "--save-config"])
    assert config_path().exists()
    # CLI/env 토큰 없이 → 저장된 토큰 사용
    monkeypatch.delenv("AGENT_TOKEN", raising=False)
    cfg2, _ = config_from_args([])
    assert cfg2.token == "SAVED"


def test_telemetry_default_off_and_optin():
    cfg, _ = config_from_args(["--token", "T"])
    assert cfg.telemetry is False
    cfg2, _ = config_from_args(["--token", "T", "--telemetry"])
    assert cfg2.telemetry is True


def test_telemetry_emit_noop_when_off():
    from provider_agent import telemetry
    from provider_agent.config import config_from_args
    cfg_off, _ = config_from_args(["--token", "T"])
    assert telemetry.emit(cfg_off, "start", agent_version="x") is False
    cfg_on, _ = config_from_args(["--token", "T", "--telemetry"])
    assert telemetry.emit(cfg_on, "start", agent_version="x") is True


def test_tray_graceful_without_deps():
    """트레이(#112): pystray 미설치 환경에서 graceful no-op."""
    from provider_agent import tray
    # tray_available 은 bool 을 반환(설치 여부와 무관하게 예외 없이).
    assert isinstance(tray.tray_available(), bool)
    if not tray.tray_available():
        assert tray.run_tray(lambda: "ok", lambda: None) is False
