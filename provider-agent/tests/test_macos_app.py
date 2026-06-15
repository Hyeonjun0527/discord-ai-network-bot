"""macOS 앱 생명주기 모듈 — 비-macOS/미설치 가드(안전 폴백) 검증.

실제 NSApp 델리게이트 설치(darwin 경로)는 살아있는 NSApplication 과 ``os._exit`` 부작용이 있어
단위 테스트하지 않는다(라이브 검증으로 대체). 여기서는 **가드와 no-op 안전성**만 본다.
"""
from __future__ import annotations

from provider_agent import macos_app


def test_install_noop_on_non_macos(monkeypatch) -> None:
    """macOS 가 아니면 install 은 부작용 없이 False(기존 동작 보존)."""
    monkeypatch.setattr(macos_app.sys, "platform", "linux")
    assert macos_app.install(object()) is False


def test_install_noop_when_pyobjc_missing(monkeypatch) -> None:
    """darwin 이라도 AppKit(pyobjc) 임포트가 실패하면 False(폴백)."""
    monkeypatch.setattr(macos_app.sys, "platform", "darwin")
    monkeypatch.setattr(macos_app, "_installed", False)
    import builtins

    real_import = builtins.__import__

    def _no_appkit(name, *a, **k):
        if name == "AppKit":
            raise ImportError("simulated: no pyobjc")
        return real_import(name, *a, **k)

    monkeypatch.setattr(builtins, "__import__", _no_appkit)
    assert macos_app.install(object()) is False


def test_show_window_safe_when_none(monkeypatch) -> None:
    """창 참조가 없으면 _show_window 는 예외 없이 no-op."""
    monkeypatch.setattr(macos_app, "_window", None)
    macos_app._show_window()  # 예외가 나면 실패


def test_show_window_calls_window_show(monkeypatch) -> None:
    """창 참조가 있으면 _show_window 가 window.show() 를 부른다."""

    class _W:
        def __init__(self) -> None:
            self.shown = False

        def show(self) -> None:
            self.shown = True

    w = _W()
    monkeypatch.setattr(macos_app, "_window", w)
    macos_app._show_window()
    assert w.shown is True
