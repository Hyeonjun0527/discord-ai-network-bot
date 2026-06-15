"""macOS 네이티브 앱 생명주기 통합 — NSApplicationDelegate + 메뉴바 종료.

GUI 는 pywebview(WKWebView 웹창)로 띄운다. pywebview 는 창 닫기 veto(``events.closing``)를 앱 종료
(``applicationShouldTerminate``)에까지 그대로 적용한다. 그래서 "백그라운드 상주"를 위해 빨간 닫기(X)를
가로채 창만 숨기면 **Cmd+Q·Dock-종료까지 함께 막혀** 프로세스가 안 죽는 문제가 생겼다(실증).

macOS 표준 동작은 다음과 같고, 정석은 ``NSApplicationDelegate`` 로 구현한다(Swift 의 AppDelegate 와
동일한 콜백을 PyObjC 로 구현 — pywebview 도 내부적으로 PyObjC 를 쓰므로 충돌 없이 같은 NSApplication
위에 얹힌다):

- 빨간 닫기(X)  → 창만 닫힘, 앱·에이전트는 같은 프로세스에서 계속 기여 (창 숨김은 webui 의
  ``_handle_webview_closing`` 가 담당).
- Cmd+Q / Dock-우클릭→종료 → **완전 종료**(``applicationShouldTerminate`` → ``os._exit``).
- Dock 아이콘 재클릭 → 숨긴 창 복원(``applicationShouldHandleReopen``).
- 마지막 창이 숨겨져도 앱 유지(``applicationShouldTerminateAfterLastWindowClosed = NO``).

추가로 **메뉴바(NSStatusItem)에 '열기'/'종료'** 를 달아, 어떤 이유로 단축키가 막혀도 **항상 보이는
확실한 종료 경로**를 보장한다(과거 "못 끄는" 사고의 안전망).

macOS 가 아니거나 pyobjc 가 없으면 모든 함수가 조용히 no-op 으로 빠진다(기존 동작 보존).
이 모듈은 macOS 앱 생명주기 한 가지 책임만 가진다(SRP).
"""
from __future__ import annotations

import logging
import os
import sys
from typing import Any, Callable

logger = logging.getLogger("provider_agent.macos_app")

# 설치된 PyObjC 객체 전역 보관(GC 방지) + 델리게이트가 참조할 창/콜백. 설치는 프로세스당 1회.
_installed: bool = False
_delegate: Any = None
_status_item: Any = None
_menu: Any = None
_window: Any = None
_on_quit: "Callable[[], None] | None" = None


def _quit() -> None:
    """완전 종료. 콜백이 있으면 먼저 부르고 즉시 강제 종료한다.

    데몬 스레드·asyncio 루프·웹뷰 transport 가 정상 종료를 지연(hang)시키던 실증 때문에 ``os._exit``
    로 확실히 끝낸다(run_gui 의 종료 경로와 동일 의도).
    """
    if _on_quit is not None:
        try:
            _on_quit()
        except Exception as exc:  # noqa: BLE001 - 종료 콜백 실패가 종료 자체를 막으면 안 된다
            logger.warning("종료 콜백 실패(무시하고 강제 종료): %s", exc)
    os._exit(0)


def _show_window() -> None:
    """숨긴 창을 다시 표시(Dock 재클릭 / 메뉴바 '열기')."""
    if _window is None:
        return
    try:
        _window.show()
    except Exception as exc:  # noqa: BLE001 - 복원 실패는 비치명적(앱은 계속 동작)
        logger.warning("창 복원 실패: %s", exc)


def install(window: Any, on_quit: "Callable[[], None] | None" = None) -> bool:
    """NSApp 델리게이트(+메뉴바 종료)를 설치한다. 성공 True, 미지원/실패 시 False(no-op).

    **GUI 루프가 시작된 뒤 메인 스레드에서** 호출해야 한다(run_gui 가 ``webview.start(func)`` +
    ``AppHelper.callAfter`` 로 보장). pywebview 가 자기 델리게이트를 건 뒤라야 우리 것이 최종 적용된다.
    중복 호출은 무시한다(설치 1회).
    """
    global _installed, _delegate, _window, _on_quit
    if _installed:
        return True
    if sys.platform != "darwin":
        return False
    try:
        import AppKit  # type: ignore[import-not-found]
    except Exception as exc:  # noqa: BLE001 - pyobjc 없으면 기존 동작 유지(폴백)
        logger.debug("AppKit 없음 — macOS 생명주기 설치 건너뜀: %s", exc)
        return False

    _window = window
    _on_quit = on_quit

    class _NexaAppDelegate(AppKit.NSObject):
        """NSApplicationDelegate(표준 콜백). 상태는 모듈 전역(_window/_on_quit)에서 읽는다."""

        def applicationShouldTerminate_(self, _app):  # noqa: N802 - ObjC 셀렉터명 고정
            logger.info("Cmd+Q/Dock-종료 — 앱을 완전 종료합니다.")
            _quit()
            return 1  # NSTerminateNow (도달하지 않음)

        def applicationShouldTerminateAfterLastWindowClosed_(self, _app):  # noqa: N802
            return False  # 창을 닫아도(숨겨도) 앱은 살아서 계속 기여

        def applicationShouldHandleReopen_hasVisibleWindows_(self, _app, has_visible):  # noqa: N802
            if not has_visible:
                _show_window()  # Dock 아이콘 재클릭 → 숨긴 창 복원
            return True

        def applicationSupportsSecureRestorableState_(self, _app):  # noqa: N802
            return True

        def nexaShow_(self, _sender):  # noqa: N802 - 메뉴바 '열기'
            _show_window()

        def nexaQuit_(self, _sender):  # noqa: N802 - 메뉴바 '종료'
            _quit()

    try:
        _delegate = _NexaAppDelegate.alloc().init()
        AppKit.NSApplication.sharedApplication().setDelegate_(_delegate)
    except Exception as exc:  # noqa: BLE001 - 델리게이트 설치 실패 시 폴백(기존 pywebview 동작)
        logger.warning("macOS 델리게이트 설치 실패(무시): %s", exc)
        return False

    # 메뉴바는 best-effort: 실패해도 델리게이트(핵심 종료 동작)는 이미 적용됨.
    try:
        _install_menu_bar(AppKit)
    except Exception as exc:  # noqa: BLE001
        logger.warning("메뉴바 종료 아이템 설치 실패(델리게이트는 적용됨): %s", exc)

    _installed = True
    logger.info("macOS 생명주기 설치 — 빨간X=창 숨김 / Cmd+Q·Dock-종료=완전 종료 / Dock 재클릭=복원 / 메뉴바 종료")
    return True


def _install_menu_bar(appkit: Any) -> None:
    """메뉴바(NSStatusItem)에 'Nexa 열기' / '종료' 를 단다 — 확실한 종료 경로 보장."""
    global _status_item, _menu
    item = appkit.NSStatusBar.systemStatusBar().statusItemWithLength_(-1.0)  # NSVariableStatusItemLength
    button = item.button()
    if button is not None:
        button.setTitle_("Nexa")
    menu = appkit.NSMenu.alloc().init()
    show = appkit.NSMenuItem.alloc().initWithTitle_action_keyEquivalent_("Nexa 열기", "nexaShow:", "")
    show.setTarget_(_delegate)
    quit_item = appkit.NSMenuItem.alloc().initWithTitle_action_keyEquivalent_("종료", "nexaQuit:", "")
    quit_item.setTarget_(_delegate)
    menu.addItem_(show)
    menu.addItem_(appkit.NSMenuItem.separatorItem())
    menu.addItem_(quit_item)
    item.setMenu_(menu)
    _status_item = item  # GC 방지(전역 보관)
    _menu = menu
