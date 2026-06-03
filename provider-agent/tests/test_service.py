"""자동 시작 서비스 등록 테스트(파일 생성은 검증, 시스템 호출은 mock)."""
from __future__ import annotations

from pathlib import Path

import provider_agent.service as svc


def test_launchd_plist_runs_headless_service():
    # macOS .app 바이너리를 헤드리스(`--service`)로 실행하고, KeepAlive 는 '크래시 시에만'이어야
    # GUI 를 열어 둔 동안 재실행 폭주/창 2개가 생기지 않는다.
    p = svc.launchd_plist("/usr/local/bin/discord-ai-network-bot")
    assert "/usr/local/bin/discord-ai-network-bot" in p
    assert "<string>--service</string>" in p
    assert "<string>--yes</string>" not in p  # GUI 창을 또 띄우던 옛 동작 회귀 방지
    assert "RunAtLoad" in p
    assert "<key>KeepAlive</key><dict><key>Crashed</key><true/></dict>" in p
    assert "<key>ProcessType</key><string>Background</string>" in p


def test_systemd_unit_has_exe_and_yes():
    u = svc.systemd_unit("/home/u/.local/bin/discord-ai-network-bot")
    assert "ExecStart=/home/u/.local/bin/discord-ai-network-bot --yes" in u
    assert "WantedBy=default.target" in u
    assert "Restart=on-failure" in u


def test_install_service_macos(monkeypatch, tmp_path):
    monkeypatch.setattr(svc.platform, "system", lambda: "Darwin")
    monkeypatch.setattr(svc, "executable_path", lambda: "/opt/homebrew/bin/discord-ai-network-bot")
    monkeypatch.setattr(svc.Path, "home", staticmethod(lambda: tmp_path))
    calls = []
    monkeypatch.setattr(svc.subprocess, "run", lambda *a, **k: calls.append(a[0]))
    where = svc.install_service()
    plist = Path(where)
    assert plist.exists()
    assert plist.name == "world.yeon.discord-ai-network-bot.plist"
    assert "discord-ai-network-bot" in plist.read_text()
    assert any("launchctl" in c[0] for c in calls)  # load 시도


def test_install_service_linux(monkeypatch, tmp_path):
    monkeypatch.setattr(svc.platform, "system", lambda: "Linux")
    monkeypatch.setattr(svc, "executable_path", lambda: "/home/u/.local/bin/discord-ai-network-bot")
    monkeypatch.setattr(svc.Path, "home", staticmethod(lambda: tmp_path))
    monkeypatch.setattr(svc.subprocess, "run", lambda *a, **k: None)
    where = svc.install_service()
    unit = Path(where)
    assert unit.exists() and unit.name == "discord-ai-network-bot.service"
    assert "ExecStart=" in unit.read_text()
