"""시스템 트레이 아이콘 (차수 9 #112, 선택).

`pystray` + `Pillow` 가 설치된 데스크톱 환경에서만 동작한다(헤드리스/서버는 자동 비활성).
라이브 상태 표시 + 설정 열기 + 종료 메뉴를 제공한다. 의존성 미설치 시 graceful no-op(False 반환).

설치: pip install 'nexa[tray]'
"""
from __future__ import annotations

import logging
import threading
import webbrowser
from typing import Callable, NamedTuple

logger = logging.getLogger("provider_agent.tray")


class MenuItemSpec(NamedTuple):
    """디스플레이 없이도 검증 가능한 메뉴 항목(label·action·활성여부)."""

    label: Callable[[], str] | str
    action: Callable[[], None] | None
    enabled: bool


def tray_available() -> bool:
    """pystray/Pillow 사용 가능 여부."""
    try:
        import PIL  # noqa: F401
        import pystray  # noqa: F401

        return True
    except ImportError:
        return False


def build_menu_items(
    status_text: Callable[[], str],
    on_quit: Callable[[], None],
    on_open_settings: Callable[[], None] | None = None,
) -> list[MenuItemSpec]:
    """트레이 메뉴 사양을 만든다(pystray 없이도 테스트 가능한 순수 함수).

    첫 항목은 라이브 상태(비활성·표시 전용), 그다음 설정 열기(옵션), 종료.
    """
    items = [MenuItemSpec(status_text, None, False)]
    if on_open_settings is not None:
        items.append(MenuItemSpec("설정 열기(브라우저)", on_open_settings, True))
    items.append(MenuItemSpec("종료", on_quit, True))
    return items


def open_settings_in_browser(url: str) -> None:
    """기본 브라우저로 로컬 설정 페이지를 연다(트레이 '설정 열기')."""
    try:
        webbrowser.open(url)
    except Exception as exc:  # noqa: BLE001 - 브라우저 없음 등은 치명적 아님
        logger.warning("브라우저 열기 실패: %s", exc)


def run_tray(
    status_text: Callable[[], str],
    on_quit: Callable[[], None],
    on_open_settings: Callable[[], None] | None = None,
) -> bool:
    """트레이 아이콘을 별도 스레드에서 띄운다. 사용 불가 환경이면 False(no-op).

    status_text: 현재 상태 문자열 콜백. on_quit: 종료 핸들러. on_open_settings: 설정 페이지 열기(옵션).
    """
    if not tray_available():
        logger.info("트레이 미지원(pystray/Pillow 없음) — 콘솔 모드로 진행")
        return False

    import pystray
    from PIL import Image, ImageDraw

    def _icon_image() -> "Image.Image":
        img = Image.new("RGB", (64, 64), (88, 101, 242))  # blurple
        d = ImageDraw.Draw(img)
        d.ellipse((16, 16, 48, 48), fill=(255, 255, 255))
        return img

    specs = build_menu_items(status_text, on_quit, on_open_settings)
    menu_items = []
    for spec in specs:
        if spec.action is None:
            # 상태 표시 전용(비활성). pystray 는 label 콜러블을 매번 호출해 라이브 갱신.
            label = spec.label
            menu_items.append(pystray.MenuItem(lambda _i, _l=label: _l(), None, enabled=False))
            continue
        action = spec.action

        def _cb(icon, _item, _a=action, _quit=(spec.action is on_quit)):  # pragma: no cover - GUI 콜백
            _a()
            if _quit:
                icon.stop()

        menu_items.append(pystray.MenuItem(spec.label, _cb, enabled=spec.enabled))

    icon = pystray.Icon("provider-agent", _icon_image(), "Provider Agent", pystray.Menu(*menu_items))
    threading.Thread(target=icon.run, daemon=True).start()
    logger.info("트레이 아이콘 시작")
    return True
