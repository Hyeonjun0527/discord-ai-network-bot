"""macOS `.app`(데스크톱 앱) 엔트리포인트 — GUI 창과 헤드리스 서비스를 한 바이너리로.

- 더블클릭 / `open` → 설정 GUI 창(`run_gui`).
- 자동 실행 LaunchAgent 가 `--service`(또는 `AGENT_SERVICE=1`)로 부르면 **창 없이 헤드리스
  에이전트**로 돈다. 이때 Dock/메뉴바 아이콘도 숨겨서(Activation Policy) 사용자가 연 앱과
  **아이콘이 2개로 보이는 일이 없게** 한다.

번들은 GUI 바이너리(`Nexa`) 하나뿐이라, 자동 실행도 이 바이너리를 재사용한다. 패키지 밖에
두어 ``__package__`` 가 비어도 절대 import 로 동작하게 한다.
"""
from __future__ import annotations

import os
import sys


def _is_service_invocation() -> bool:
    """자동 실행(LaunchAgent/스케줄러)이 부른 헤드리스 호출인지. `--gui` 가 있으면 항상 GUI."""
    argv = sys.argv[1:]
    if "--gui" in argv:
        return False
    return "--service" in argv or "--yes" in argv or os.getenv("AGENT_SERVICE") == "1"


def _hide_dock_icon() -> None:
    """헤드리스 서비스 프로세스의 Dock/메뉴바 아이콘을 숨긴다(macOS 전용, 실패해도 무해)."""
    if sys.platform != "darwin":
        return
    try:
        from AppKit import NSApplication  # type: ignore[import-not-found]

        # NSApplicationActivationPolicyProhibited = 2 → Dock·메뉴바에 표시하지 않음.
        NSApplication.sharedApplication().setActivationPolicy_(2)
    except Exception:  # noqa: BLE001 - 정책 설정 실패는 치명적이지 않음
        pass


if __name__ == "__main__":  # pragma: no cover
    if _is_service_invocation():
        _hide_dock_icon()
        from provider_agent.__main__ import main

        # 저장된 설정으로 헤드리스 연결(동의 사전 수락). 이미 GUI 인스턴스가 연결돼 있으면
        # singleton 락 때문에 run_agent 가 깔끔히 종료(중복 연결 방지) → 창/아이콘이 늘지 않는다.
        raise SystemExit(main(["--yes"]))
    from provider_agent.webui import run_gui

    run_gui()
