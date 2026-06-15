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

타이밍 가정 없음: pywebview 는 창 생성 중 자기 NSApp 델리게이트를 건다. 그 시점이 머신 속도에 따라
언제든 될 수 있으므로, **고정 지연을 가정하지 않고** ``schedule_install`` 이 "우리 델리게이트가
현재 델리게이트로 안정될 때까지" 반복 재확인(re-assert)한다(``install`` 은 호출마다 재-set, 멱등).

macOS 가 아니거나 pyobjc 가 없으면 모든 함수가 조용히 no-op(기존 동작 보존). SRP: 이 모듈은 macOS
앱 생명주기 한 가지 책임만 가진다.
"""
from __future__ import annotations

import logging
import os
import sys
from typing import Any, Callable

logger = logging.getLogger("provider_agent.macos_app")

# PyObjC 객체 전역 보관(GC 방지) + 델리게이트가 참조할 창/콜백. 델리게이트/메뉴는 프로세스당 1회 생성.
_delegate: Any = None
_status_item: Any = None
_menu: Any = None
_menu_installed: bool = False
_window: Any = None
_on_quit: "Callable[[], None] | None" = None


def _appkit() -> Any:
    """AppKit 모듈(없으면 None) — macOS/pyobjc 가드. 비-macOS·pyobjc 부재 시 전부 no-op."""
    if sys.platform != "darwin":
        return None
    try:
        import AppKit  # type: ignore[import-not-found]
    except Exception as exc:  # noqa: BLE001 - pyobjc 없으면 기존 pywebview 동작 유지
        logger.debug("AppKit 없음 — macOS 생명주기 비활성: %s", exc)
        return None
    return AppKit


def _quit() -> None:
    """완전 종료. 콜백이 있으면 먼저 부르고 즉시 강제 종료한다.

    데몬 스레드·asyncio 루프·웹뷰 transport 가 정상 종료를 지연(hang)시키던 실증 때문에 ``os._exit``
    로 확실히 끝낸다(run_gui 의 종료 경로와 동일 의도 — "반드시 꺼진다"를 보장).
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


def _build_delegate(appkit: Any) -> None:
    """NSApplicationDelegate 객체를 **1회** 생성(이후 동일 identity 재사용). 상태는 모듈 전역에서 읽음."""
    global _delegate
    if _delegate is not None:
        return

    class _NexaAppDelegate(appkit.NSObject):
        """표준 NSApplicationDelegate 콜백(ObjC 셀렉터명 고정)."""

        def applicationShouldTerminate_(self, _app):  # noqa: N802
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

    _delegate = _NexaAppDelegate.alloc().init()


def _delegate_is_current(app: Any) -> bool:
    """현재 NSApp 델리게이트가 우리 것인지(PyObjC 프록시 identity 가 다를 수 있어 isEqual_ 폴백)."""
    cur = app.delegate()
    if cur is None or _delegate is None:
        return False
    if cur is _delegate:
        return True
    try:
        return bool(cur.isEqual_(_delegate))
    except Exception:  # noqa: BLE001
        return False


def _ensure_menu_bar(appkit: Any) -> None:
    """메뉴바(NSStatusItem)에 'Nexa 열기'/'종료' 를 **1회** 단다 — 확실한 종료 경로 안전망.

    best-effort: 실패해도 델리게이트(핵심 종료 동작)는 별개로 적용된다. 반드시 메인 스레드에서 호출
    (NSStatusBar 는 메인 스레드 전용) — schedule_install 의 타이머가 메인 런루프에서 부른다.
    """
    global _status_item, _menu, _menu_installed
    if _menu_installed:
        return
    try:
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
        _status_item = item  # GC 방지
        _menu = menu
        _menu_installed = True
    except Exception as exc:  # noqa: BLE001 - 메뉴바 실패는 델리게이트와 무관(핵심 종료는 동작)
        logger.warning("메뉴바 종료 아이템 설치 실패(델리게이트는 적용됨): %s", exc)


def install(window: Any, on_quit: "Callable[[], None] | None" = None) -> bool:
    """우리 NSApp 델리게이트를 (필요 시 생성하고) **현재 델리게이트로 재확인(re-assert)** 한다.

    반환: 호출 직후 NSApp 델리게이트가 우리 것이면 True. macOS/pyobjc 아니면 False.
    멱등 — 여러 번 불러도 안전하다. pywebview 가 자기 델리게이트를 거는 타이밍과 무관하게, 호출마다
    우리 것으로 다시 set 하므로 ``schedule_install`` 이 '안정될 때까지' 반복하면 고정 지연 가정이 없다.
    **메인 스레드에서** 호출해야 한다(NSApp/NSStatusBar 메인 스레드 요건).
    """
    global _window, _on_quit
    appkit = _appkit()
    if appkit is None:
        return False
    _window = window
    _on_quit = on_quit
    try:
        _build_delegate(appkit)
        app = appkit.NSApplication.sharedApplication()
        if not _delegate_is_current(app):
            app.setDelegate_(_delegate)
        _ensure_menu_bar(appkit)
        return _delegate_is_current(app)
    except Exception as exc:  # noqa: BLE001 - 설치 실패는 기존 pywebview 동작으로 폴백
        logger.warning("macOS 생명주기 설치 실패(무시): %s", exc)
        return False


def schedule_install(window: Any, on_quit: "Callable[[], None] | None" = None) -> None:
    """메인 런루프에 **반복 NSTimer** 를 걸어, 우리 델리게이트가 현재 델리게이트로 **안정될 때까지**
    재확인한다(고정 지연 가정 없이 머신 속도에 적응).

    - 매 틱 ``install`` 호출(우리 델리게이트 재-set, 메뉴바 1회 설치).
    - 우리 델리게이트로 ``STABLE_NEEDED`` 틱 연속 안정되면(= pywebview 가 더는 덮어쓰지 않음 = 초기화
      완료) 타이머 종료. → 빠른 맥은 1초 내, 느린 맥은 더 걸려도 **확정되면 즉시** 멈춘다.
    - ``MAX_TICKS`` 는 무한 타이머 방지용 안전 상한일 뿐(정상 경로는 훨씬 일찍 종료) — 상한에 닿으면
      현재 적용 상태로 진행하고 경고만 남긴다.

    GUI 루프(NSApp.run) 시작 전 **메인 스레드**에서 호출한다 — 타이머는 메인 런루프에 등록되어
    NSApp.run() 이 그 루프를 돌릴 때 메인 스레드에서 발화한다. macOS/pyobjc 아니면 no-op.
    """
    appkit = _appkit()
    if appkit is None:
        return
    try:
        from Foundation import NSTimer  # type: ignore[import-not-found]
    except Exception as exc:  # noqa: BLE001 - NSTimer 불가 시 1회 설치로 폴백
        logger.warning("NSTimer 불가 — 1회 설치로 폴백: %s", exc)
        install(window, on_quit)
        return

    state = {"stable": 0, "ticks": 0}
    stable_needed = 3   # 연속 3틱(약 0.75s) 안정 = pywebview 초기화 완료 → 종료
    max_ticks = 80      # 안전 상한(약 20s). 성공 시 훨씬 일찍 종료되므로 지연 '가정'이 아님.

    def _tick(timer: Any) -> None:
        state["ticks"] += 1
        ok = install(window, on_quit)
        state["stable"] = state["stable"] + 1 if ok else 0
        if state["stable"] >= stable_needed:
            timer.invalidate()
            logger.info(
                "macOS 생명주기 확정 — 빨간X=창 숨김 / Cmd+Q·Dock-종료=완전 종료 / Dock 재클릭=복원 / 메뉴바 종료"
            )
        elif state["ticks"] >= max_ticks:
            timer.invalidate()
            logger.warning("macOS 생명주기 안정 미확정(안전 상한 도달) — 현재 적용 상태로 진행")

    NSTimer.scheduledTimerWithTimeInterval_repeats_block_(0.25, True, _tick)
