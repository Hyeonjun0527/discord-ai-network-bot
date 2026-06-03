"""macOS `.app`(데스크톱 앱) 전용 엔트리포인트.

Finder/런치패드에서 더블클릭하면 곧바로 설정 GUI 를 연다(헤드리스 에이전트가 아니라).
CLI onefile 실행파일은 기존 ``pyinstaller_entry.py`` 를 그대로 쓴다(서비스/헤드리스용).
패키지 밖에 두어 ``__package__`` 가 비어도 절대 import 로 동작하게 한다.
"""
from __future__ import annotations

from provider_agent.webui import run_gui

if __name__ == "__main__":  # pragma: no cover
    run_gui()
