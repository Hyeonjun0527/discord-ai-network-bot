"""앱 설치(맥: /Applications, 윈도우: 시작 메뉴) 로직 테스트 — 실제 OS 명령은 모킹."""
from __future__ import annotations

import sys
from pathlib import Path

import pytest

from provider_agent import installer


def test_info_unsupported_when_not_frozen(monkeypatch):
    # 소스/스크립트 실행(비-frozen)에서는 넣을 대상 번들이 없으므로 비활성으로 안내한다.
    monkeypatch.setattr(installer.sys, "frozen", False, raising=False)
    monkeypatch.setattr(installer.sys, "platform", "darwin")
    info = installer.install_info()
    assert info["platform"] == "mac" and info["supported"] is False
    assert "reason" in info


def test_info_other_os(monkeypatch):
    monkeypatch.setattr(installer.sys, "platform", "linux")
    info = installer.install_info()
    assert info["platform"] == "other" and info["supported"] is False


def test_install_app_other_os_returns_error(monkeypatch):
    monkeypatch.setattr(installer.sys, "platform", "linux")
    r = installer.install_app()
    assert r["ok"] is False


def test_macos_install_copies_bundle_to_applications(monkeypatch, tmp_path):
    bundle = tmp_path / "Nexa.app"
    (bundle / "Contents" / "MacOS").mkdir(parents=True)
    (bundle / "Contents" / "MacOS" / "Nexa").write_text("bin")
    target = tmp_path / "Applications" / "Nexa.app"
    target.parent.mkdir()

    monkeypatch.setattr(installer.sys, "platform", "darwin")
    monkeypatch.setattr(installer, "_macos_bundle_path", lambda: bundle)
    monkeypatch.setattr(installer, "_macos_target", lambda: target)
    # ditto 없음 → copytree 폴백 경로를 타게 하고, 부수효과 명령은 모두 모킹.
    monkeypatch.setattr(installer.shutil, "which", lambda _name: None)
    calls = []
    monkeypatch.setattr(installer.subprocess, "run", lambda *a, **k: calls.append(a))

    r = installer.install_app()
    assert r["ok"] is True
    assert (target / "Contents" / "MacOS" / "Nexa").read_text() == "bin"
    # quarantine 제거(xattr)가 호출됐는지(부수효과 best-effort)
    assert any("xattr" in str(c) for c in calls)


def test_macos_install_noop_when_already_in_applications(monkeypatch, tmp_path):
    bundle = tmp_path / "Applications" / "Nexa.app"
    bundle.mkdir(parents=True)
    monkeypatch.setattr(installer.sys, "platform", "darwin")
    monkeypatch.setattr(installer, "_macos_bundle_path", lambda: bundle)
    monkeypatch.setattr(installer, "_macos_target", lambda: bundle)
    r = installer.install_app()
    assert r["ok"] is True and r.get("already") is True


@pytest.mark.skipif(sys.platform == "win32", reason="POSIX 경로 가정")
def test_windows_install_copies_and_makes_shortcut(monkeypatch, tmp_path):
    exe = tmp_path / "Nexa.exe"
    exe.write_text("exe")
    programs = tmp_path / "Programs"
    startmenu = tmp_path / "StartMenu"

    monkeypatch.setattr(installer.sys, "platform", "win32")
    monkeypatch.setattr(installer.sys, "frozen", True, raising=False)
    monkeypatch.setattr(installer.sys, "executable", str(exe))
    monkeypatch.setattr(installer, "_win_programs_dir", lambda: programs)
    monkeypatch.setattr(installer, "_win_start_menu_dir", lambda: startmenu)
    ran = {}

    def fake_run(cmd, **kw):
        ran["cmd"] = cmd
        Path(startmenu / "Nexa.lnk").write_text("lnk")  # PowerShell 이 만들 .lnk 흉내

        class R:
            returncode = 0

        return R()

    monkeypatch.setattr(installer.subprocess, "run", fake_run)
    r = installer.install_app()
    assert r["ok"] is True
    assert (programs / "Nexa.exe").read_text() == "exe"  # 사용자 폴더로 복사
    assert "powershell" in ran["cmd"][0]  # 바로가기 생성에 PowerShell 사용
    assert (startmenu / "Nexa.lnk").exists()


def test_ps_quote_escapes_single_quotes():
    assert installer._ps_quote("a'b") == "a''b"
