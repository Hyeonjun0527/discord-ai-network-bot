"""인앱 업데이트 로직 테스트 — 네트워크/서브프로세스는 모킹(결정적)."""
from __future__ import annotations

from pathlib import Path

from provider_agent import updater


def test_tag_to_version():
    assert updater._tag_to_version("agent-v0.20.1") == "0.20.1"
    assert updater._tag_to_version("v1.2.3") == "1.2.3"
    assert updater._tag_to_version("1.2.3") == "1.2.3"


def test_check_reports_outdated(monkeypatch):
    monkeypatch.setattr(updater, "current_version", lambda: "0.19.0")
    monkeypatch.setattr(updater.sys, "frozen", True, raising=False)
    monkeypatch.setattr(updater.sys, "platform", "darwin")
    monkeypatch.setattr(
        updater, "fetch_latest",
        lambda: {"version": "0.20.0", "tag": "agent-v0.20.0", "assets": {updater.MAC_ASSET: "https://x/app.zip"}},
    )
    monkeypatch.setattr(updater, "_url_ok", lambda url: True)  # 자산 존재
    info = updater.check()
    assert info["current"] == "0.19.0" and info["latest"] == "0.20.0"
    assert info["outdated"] is True and info["supported"] is True and info["error"] is None


def test_check_up_to_date(monkeypatch):
    monkeypatch.setattr(updater, "current_version", lambda: "0.20.0")
    monkeypatch.setattr(updater.sys, "frozen", True, raising=False)
    monkeypatch.setattr(updater.sys, "platform", "darwin")
    monkeypatch.setattr(
        updater, "fetch_latest",
        lambda: {"version": "0.20.0", "tag": "agent-v0.20.0", "assets": {updater.MAC_ASSET: "u"}},
    )
    info = updater.check()
    assert info["outdated"] is False


def test_check_network_error_is_soft(monkeypatch):
    monkeypatch.setattr(updater.sys, "frozen", True, raising=False)
    monkeypatch.setattr(updater.sys, "platform", "darwin")

    def boom():
        raise OSError("no network")

    monkeypatch.setattr(updater, "fetch_latest", boom)
    info = updater.check()
    assert info["error"] is not None and info["outdated"] is False  # 예외 없이 상태 반환


def test_check_unsupported_when_not_frozen(monkeypatch):
    monkeypatch.setattr(updater.sys, "frozen", False, raising=False)
    monkeypatch.setattr(updater.sys, "platform", "darwin")
    monkeypatch.setattr(updater, "fetch_latest", lambda: {"version": "9.9.9", "tag": "t", "assets": {}})
    info = updater.check()
    assert info["supported"] is False


def test_check_supported_false_when_asset_missing(monkeypatch):
    monkeypatch.setattr(updater, "current_version", lambda: "0.19.0")
    monkeypatch.setattr(updater.sys, "frozen", True, raising=False)
    monkeypatch.setattr(updater.sys, "platform", "darwin")
    monkeypatch.setattr(
        updater, "fetch_latest",
        lambda: {"version": "0.20.0", "tag": "agent-v0.20.0", "assets": {updater.MAC_ASSET: "https://x/app.zip"}},
    )
    monkeypatch.setattr(updater, "_url_ok", lambda url: False)  # .app zip 이 릴리스에 아직 없음
    info = updater.check()
    assert info["supported"] is False and info["error"] is not None


def test_apply_update_blocked_when_not_frozen(monkeypatch):
    monkeypatch.setattr(updater.sys, "frozen", False, raising=False)
    r = updater.apply_update()
    assert r["ok"] is False


def test_verify_checksum_mismatch(monkeypatch, tmp_path):
    f = tmp_path / "a.zip"
    f.write_bytes(b"hello")
    monkeypatch.setattr(updater, "_http_get", lambda url, accept, timeout=20.0: b"deadbeef  a.zip\n")
    assert updater._verify_checksum(f, "a.zip", "https://sums") is not None  # 불일치 → 에러문구


def test_verify_checksum_ok(monkeypatch, tmp_path):
    import hashlib

    f = tmp_path / "a.zip"
    f.write_bytes(b"hello")
    digest = hashlib.sha256(b"hello").hexdigest()
    monkeypatch.setattr(updater, "_http_get", lambda url, accept, timeout=20.0: f"{digest}  a.zip\n".encode())
    assert updater._verify_checksum(f, "a.zip", "https://sums") is None  # 일치 → None


def test_macos_apply_downloads_swaps_and_relaunches(monkeypatch, tmp_path):
    bundle = tmp_path / "Applications" / "냥시스턴트.app"
    bundle.mkdir(parents=True)
    monkeypatch.setattr(updater.sys, "frozen", True, raising=False)
    monkeypatch.setattr(updater.sys, "platform", "darwin")
    monkeypatch.setattr(updater, "current_version", lambda: "0.19.0")
    monkeypatch.setattr("provider_agent.installer._macos_bundle_path", lambda: bundle)
    monkeypatch.setattr(
        updater, "fetch_latest",
        lambda: {"version": "0.20.0", "tag": "t", "assets": {updater.MAC_ASSET: "https://x/app.zip"}},
    )
    monkeypatch.setattr(updater, "_download", lambda url, dest, timeout=180.0: Path(dest).write_bytes(b"ZIPBYTES"))
    monkeypatch.setattr(updater, "_verify_checksum", lambda *a, **k: None)

    def fake_run(cmd, **kw):
        # ditto -x -k <zip> <extract> → 새 .app 흉내
        extract = Path(cmd[-1])
        (extract / "냥시스턴트.app").mkdir(parents=True, exist_ok=True)

        class R:
            returncode = 0

        return R()

    popen_calls = []
    monkeypatch.setattr(updater.subprocess, "run", fake_run)
    monkeypatch.setattr(updater.subprocess, "Popen", lambda *a, **k: popen_calls.append(a) or object())
    r = updater.apply_update()
    assert r["ok"] is True and r["restarting"] is True and r["version"] == "0.20.0"
    assert popen_calls, "교체·재실행 헬퍼(Popen)가 호출돼야 한다"


def test_windows_apply_downloads_and_relaunches(monkeypatch, tmp_path):
    exe = tmp_path / "app.exe"
    exe.write_text("old")
    monkeypatch.setattr(updater.sys, "frozen", True, raising=False)
    monkeypatch.setattr(updater.sys, "platform", "win32")
    monkeypatch.setattr(updater.sys, "executable", str(exe))
    monkeypatch.setattr(updater, "current_version", lambda: "0.19.0")
    monkeypatch.setattr(
        updater, "fetch_latest",
        lambda: {"version": "0.20.0", "tag": "agent-v0.20.0", "assets": {updater.WIN_ASSET: "https://x/app.exe"}},
    )
    monkeypatch.setattr(updater, "_download", lambda url, dest, timeout=180.0: Path(dest).write_bytes(b"NEWEXE"))
    monkeypatch.setattr(updater, "_verify_checksum", lambda *a, **k: None)
    calls = []
    monkeypatch.setattr(updater.subprocess, "Popen", lambda *a, **k: calls.append(a) or object())
    r = updater.apply_update()
    assert r["ok"] is True and r["restarting"] is True and r["version"] == "0.20.0"
    assert calls, "교체·재실행 배치(Popen)가 호출돼야 한다"
    assert updater.update_progress()["phase"] == "restarting"  # 진행상태가 재시작으로


def test_macos_apply_noop_when_already_latest(monkeypatch, tmp_path):
    bundle = tmp_path / "냥시스턴트.app"
    bundle.mkdir()
    monkeypatch.setattr(updater.sys, "frozen", True, raising=False)
    monkeypatch.setattr(updater.sys, "platform", "darwin")
    monkeypatch.setattr(updater, "current_version", lambda: "0.20.0")
    monkeypatch.setattr("provider_agent.installer._macos_bundle_path", lambda: bundle)
    monkeypatch.setattr(updater, "fetch_latest", lambda: {"version": "0.20.0", "tag": "t", "assets": {}})
    r = updater.apply_update()
    assert r["ok"] is True and r.get("already") is True
