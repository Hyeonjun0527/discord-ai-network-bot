"""설정 파일 저장/로드(#113) + 텔레메트리 opt-in(#130) 테스트."""
from __future__ import annotations

import os
import stat

from provider_agent.config import config_from_args
from provider_agent.config_file import config_path, load_config, save_config


def test_config_dir_follows_xdg(monkeypatch, tmp_path):
    monkeypatch.setenv("XDG_CONFIG_HOME", str(tmp_path))
    assert config_path() == tmp_path / "nexa" / "config.json"


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


def test_save_config_preserves_non_saveable_keys(monkeypatch, tmp_path):
    """save_config 는 SAVEABLE 만 갱신하고 그 외 키(connections·온보딩 토글)는 보존해야 한다.
    (회귀: 과거엔 전체를 덮어써서 설정 저장 시 저장된 서버 연결·온보딩 선택이 통째로 사라졌다.)"""
    monkeypatch.setenv("XDG_CONFIG_HOME", str(tmp_path))
    from provider_agent.config_file import persist_partial

    # 온보딩/연결이 먼저 비-SAVEABLE 키를 기록한 상황
    persist_partial(
        {
            "auto_connect": True,
            "tray": True,
            "autostart_pref": True,
            "connections": [{"token": "C1", "guild_id": 1, "guild_name": "g"}],
        }
    )
    # 이후 설정 저장(예: 모델 변경)이 일어나도 비-SAVEABLE 키는 살아남아야 한다.
    cfg, _ = config_from_args(["--token", "TOK", "--model", "x"])
    save_config(cfg)
    data = load_config()
    assert data["token"] == "TOK" and data["models"] == ["x"]  # SAVEABLE 은 갱신
    assert data["auto_connect"] is True  # 비-SAVEABLE 보존
    assert data["tray"] is True
    assert data["autostart_pref"] is True
    assert data["connections"] == [{"token": "C1", "guild_id": 1, "guild_name": "g"}]


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


# ── 멀티-서버 연결 목록 ───────────────────────────────────────────────


def test_load_connections_migrates_single_token(monkeypatch, tmp_path):
    monkeypatch.setenv("XDG_CONFIG_HOME", str(tmp_path))
    from provider_agent.config_file import load_connections, persist_partial

    persist_partial({"token": "TOK1"})  # 구버전: 단일 token
    conns = load_connections()
    assert conns == [{"token": "TOK1", "guild_id": None, "guild_name": None}]


def test_add_remove_connection_by_guild(monkeypatch, tmp_path):
    monkeypatch.setenv("XDG_CONFIG_HOME", str(tmp_path))
    from provider_agent.config_file import add_connection, load_connections, remove_connection

    add_connection("TA", guild_id=100, guild_name="A")
    add_connection("TB", guild_id=200, guild_name="B")
    conns = load_connections()
    assert {c["guild_id"] for c in conns} == {100, 200}
    # 같은 길드 재추가 → 교체(중복 아님)
    add_connection("TA2", guild_id=100, guild_name="A2")
    conns = load_connections()
    a = next(c for c in conns if c["guild_id"] == 100)
    assert a["token"] == "TA2" and len(conns) == 2
    # 길드 200 해제
    remove_connection(guild_id=200)
    conns = load_connections()
    assert [c["guild_id"] for c in conns] == [100]


def test_set_connection_token_durable_refresh(monkeypatch, tmp_path):
    monkeypatch.setenv("XDG_CONFIG_HOME", str(tmp_path))
    from provider_agent.config_file import add_connection, load_connections, set_connection_token

    add_connection("ONBOARD", guild_id=100, guild_name="A")
    set_connection_token("DURABLE", guild_id=100, old_token="ONBOARD")
    conns = load_connections()
    assert conns[0]["token"] == "DURABLE"


def test_guild_policies_roundtrip(monkeypatch, tmp_path):
    monkeypatch.setenv("XDG_CONFIG_HOME", str(tmp_path))
    from provider_agent.config_file import load_guild_policies, set_guild_policy

    set_guild_policy(100, {"daily_limit": 30, "scope": "ALL"})
    set_guild_policy(200, {"daily_limit": 50})
    pols = load_guild_policies()
    assert pols[100]["daily_limit"] == 30 and pols[100]["scope"] == "ALL"
    assert pols[200]["daily_limit"] == 50
    # 병합 갱신(기존 키 보존)
    set_guild_policy(100, {"max_concurrency": 3})
    assert load_guild_policies()[100] == {"daily_limit": 30, "scope": "ALL", "max_concurrency": 3}
