"""시스템 트레이 아이콘 (차수 9 #112, 선택).

`pystray` + `Pillow` 가 설치된 데스크톱 환경에서만 동작한다(헤드리스/서버는 자동 비활성).
상태 표시 + 종료 메뉴를 제공한다. 의존성 미설치 시 graceful no-op(False 반환).

설치: pip install 'discord-ai-network-bot[tray]'
"""
from __future__ import annotations

import logging
import threading
from typing import Callable

logger = logging.getLogger("provider_agent.tray")


def tray_available() -> bool:
    """pystray/Pillow 사용 가능 여부."""
    try:
        import PIL  # noqa: F401
        import pystray  # noqa: F401

        return True
    except ImportError:
        return False


def run_tray(status_text: Callable[[], str], on_quit: Callable[[], None]) -> bool:
    """트레이 아이콘을 별도 스레드에서 띄운다. 사용 불가 환경이면 False(no-op).

    status_text: 현재 상태 문자열을 돌려주는 콜백. on_quit: 종료 메뉴 클릭 핸들러.
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

    def _on_quit(icon, _item):  # pragma: no cover - GUI 콜백
        on_quit()
        icon.stop()

    menu = pystray.Menu(
        pystray.MenuItem(lambda _i: status_text(), None, enabled=False),
        pystray.MenuItem("종료", _on_quit),
    )
    icon = pystray.Icon("provider-agent", _icon_image(), "Provider Agent", menu)
    threading.Thread(target=icon.run, daemon=True).start()
    logger.info("트레이 아이콘 시작")
    return True
