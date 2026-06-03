"""트레이 메뉴 로직 테스트(디스플레이 불필요 — 순수 함수/no-op 경로만)."""
from __future__ import annotations

import builtins

from provider_agent import tray


def test_build_menu_items_status_and_quit():
    items = tray.build_menu_items(lambda: "상태", on_quit=lambda: None)
    assert len(items) == 2
    # 첫 항목은 비활성 상태 표시(콜러블 라벨), 마지막은 종료(활성·동작 있음).
    assert items[0].action is None and items[0].enabled is False
    assert callable(items[0].label) and items[0].label() == "상태"
    assert items[-1].label == "종료" and items[-1].enabled is True


def test_build_menu_items_with_settings():
    opened = {}
    items = tray.build_menu_items(
        lambda: "s",
        on_quit=lambda: None,
        on_open_settings=lambda: opened.setdefault("hit", True),
    )
    assert [i.label for i in items if isinstance(i.label, str)] == ["설정 열기(브라우저)", "종료"]
    settings = next(i for i in items if i.label == "설정 열기(브라우저)")
    settings.action()
    assert opened["hit"] is True


def test_open_settings_in_browser(monkeypatch):
    called = {}
    monkeypatch.setattr(tray.webbrowser, "open", lambda url: called.setdefault("url", url))
    tray.open_settings_in_browser("http://127.0.0.1:9/")
    assert called["url"] == "http://127.0.0.1:9/"


def test_open_settings_swallows_errors(monkeypatch):
    def boom(_url):
        raise RuntimeError("no browser")

    monkeypatch.setattr(tray.webbrowser, "open", boom)
    tray.open_settings_in_browser("http://x/")  # 예외를 삼켜야 함(치명적 아님)


def test_run_tray_noop_when_unavailable(monkeypatch):
    monkeypatch.setattr(tray, "tray_available", lambda: False)
    assert tray.run_tray(lambda: "s", on_quit=lambda: None) is False


def test_tray_available_false_without_deps(monkeypatch):
    real_import = builtins.__import__

    def fake_import(name, *a, **k):
        if name in ("pystray", "PIL"):
            raise ImportError(name)
        return real_import(name, *a, **k)

    monkeypatch.setattr(builtins, "__import__", fake_import)
    assert tray.tray_available() is False


def test_agent_status_line():
    from provider_agent.agent import ProviderAgent
    from provider_agent.config import AgentConfig

    class _Ollama:
        async def list_models(self):  # pragma: no cover - 미사용
            return []

    agent = ProviderAgent(AgentConfig(token="t", daily_limit=10), ollama=_Ollama())
    line = agent.status_line()
    assert "연결 끊김" in line and "처리 0" in line and "잔여 10" in line
