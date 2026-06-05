"""자동 시작 서비스 등록 테스트(파일 생성은 검증, 시스템 호출은 mock)."""
from __future__ import annotations

from pathlib import Path

import pytest

import provider_agent.service as svc


class _FakeProc:
    """subprocess.CompletedProcess 대용(반환코드/출력만). svc._run 이 .returncode 를 읽는다."""

    def __init__(self, returncode: int = 0, stdout: str = "", stderr: str = "") -> None:
        self.returncode = returncode
        self.stdout = stdout
        self.stderr = stderr


def _ok_run(monkeypatch):
    """모든 subprocess.run 을 성공(rc=0)으로 만들고 호출 인자를 기록한다."""
    calls: list = []
    monkeypatch.setattr(svc.subprocess, "run", lambda *a, **k: (calls.append(a[0]), _FakeProc(0))[1])
    return calls


def test_launchd_plist_runs_headless_service():
    # macOS .app 바이너리를 헤드리스(`--service`)로 실행하고, KeepAlive 는 '크래시 시에만'이어야
    # GUI 를 열어 둔 동안 재실행 폭주/창 2개가 생기지 않는다.
    p = svc.launchd_plist("/usr/local/bin/nexa")
    assert "/usr/local/bin/nexa" in p
    assert "<string>--service</string>" in p
    assert "<string>--yes</string>" not in p  # GUI 창을 또 띄우던 옛 동작 회귀 방지
    assert "RunAtLoad" in p
    assert "<key>KeepAlive</key><dict><key>Crashed</key><true/></dict>" in p
    assert "<key>ProcessType</key><string>Background</string>" in p


def test_systemd_unit_has_exe_and_yes():
    u = svc.systemd_unit("/home/u/.local/bin/nexa")
    assert "ExecStart=/home/u/.local/bin/nexa --yes" in u
    assert "WantedBy=default.target" in u
    assert "Restart=on-failure" in u


def test_install_service_macos(monkeypatch, tmp_path):
    monkeypatch.setattr(svc.platform, "system", lambda: "Darwin")
    monkeypatch.setattr(svc, "executable_path", lambda: "/opt/homebrew/bin/nexa")
    monkeypatch.setattr(svc.Path, "home", staticmethod(lambda: tmp_path))
    calls = _ok_run(monkeypatch)
    where = svc.install_service()
    plist = Path(where)
    assert plist.exists()
    assert plist.name == "world.yeon.nexa.plist"
    assert "nexa" in plist.read_text()
    assert any("launchctl" in c[0] for c in calls)  # load 시도


def test_install_service_linux(monkeypatch, tmp_path):
    monkeypatch.setattr(svc.platform, "system", lambda: "Linux")
    monkeypatch.setattr(svc, "executable_path", lambda: "/home/u/.local/bin/nexa")
    monkeypatch.setattr(svc.Path, "home", staticmethod(lambda: tmp_path))
    _ok_run(monkeypatch)
    where = svc.install_service()
    unit = Path(where)
    assert unit.exists() and unit.name == "nexa.service"
    assert "ExecStart=" in unit.read_text()


def test_install_service_raises_on_failure(monkeypatch, tmp_path):
    # 등록 명령이 실패(비0)하면 조용히 삼키지 않고 RuntimeError 로 표면화한다(GUI 가 사유 표시).
    monkeypatch.setattr(svc.platform, "system", lambda: "Linux")
    monkeypatch.setattr(svc, "executable_path", lambda: "/home/u/.local/bin/nexa")
    monkeypatch.setattr(svc.Path, "home", staticmethod(lambda: tmp_path))
    monkeypatch.setattr(svc.subprocess, "run", lambda *a, **k: _FakeProc(1, stderr="systemctl: 권한 없음"))
    with pytest.raises(RuntimeError, match="권한 없음"):
        svc.install_service()


def test_install_service_macos_ignores_already_loaded(monkeypatch, tmp_path):
    # 이미 로드된 서비스에 load 가 'already loaded' 를 내는 건 실패가 아니다(무시).
    monkeypatch.setattr(svc.platform, "system", lambda: "Darwin")
    monkeypatch.setattr(svc, "executable_path", lambda: "/x/nexa")
    monkeypatch.setattr(svc.Path, "home", staticmethod(lambda: tmp_path))
    monkeypatch.setattr(svc.subprocess, "run", lambda *a, **k: _FakeProc(1, stderr="service already loaded"))
    where = svc.install_service()  # 예외 없이 통과
    assert Path(where).exists()


def test_is_installed_macos(monkeypatch, tmp_path):
    monkeypatch.setattr(svc.platform, "system", lambda: "Darwin")
    monkeypatch.setattr(svc.Path, "home", staticmethod(lambda: tmp_path))
    assert svc.is_installed() is False
    plist = tmp_path / "Library" / "LaunchAgents" / f"{svc.SERVICE_LABEL}.plist"
    plist.parent.mkdir(parents=True, exist_ok=True)
    plist.write_text("x", encoding="utf-8")
    assert svc.is_installed() is True


def test_kickstart_macos(monkeypatch):
    monkeypatch.setattr(svc.platform, "system", lambda: "Darwin")
    monkeypatch.setattr(svc, "is_installed", lambda: True)
    monkeypatch.setattr(svc.os, "getuid", lambda: 501)
    calls = _ok_run(monkeypatch)
    assert svc.kickstart() is True
    assert any("kickstart" in c for c in calls)
    assert any("gui/501/" + svc.SERVICE_LABEL in c for c in calls)


def test_kickstart_noop_when_not_installed(monkeypatch):
    monkeypatch.setattr(svc, "is_installed", lambda: False)
    assert svc.kickstart() is False


def test_stop_service_macos(monkeypatch):
    monkeypatch.setattr(svc.platform, "system", lambda: "Darwin")
    monkeypatch.setattr(svc, "is_installed", lambda: True)
    monkeypatch.setattr(svc.os, "getuid", lambda: 501)
    calls = _ok_run(monkeypatch)
    assert svc.stop_service() is True
    assert any("kill" in c for c in calls)
