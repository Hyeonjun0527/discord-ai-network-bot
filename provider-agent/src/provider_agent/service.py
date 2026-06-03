"""자동 시작 서비스 등록 (set-and-forget).

`--install-service` 로 로그인 시 자동 실행되는 **사용자 단위** 서비스를 등록한다(관리자 불필요).
서비스는 저장된 설정(`--save-config`)과 durable 토큰으로 무인자 실행하므로, 한 번 설정하면
이후 터미널 재입력 없이 알아서 풀에 연결된다.

- macOS: LaunchAgent(`~/Library/LaunchAgents`)
- Linux: systemd `--user` 유닛(`~/.config/systemd/user`)
- Windows: 작업 스케줄러(로그온 트리거, 현재 사용자)
"""
from __future__ import annotations

import platform
import shutil
import subprocess
import sys
from pathlib import Path

SERVICE_LABEL = "world.yeon.discord-ai-network-bot"
SERVICE_NAME = "discord-ai-network-bot"


def executable_path() -> str:
    """설치된 실행파일 경로. PATH(brew/scoop) → 콘솔스크립트/프리즌 바이너리 순."""
    return shutil.which(SERVICE_NAME) or str(Path(sys.argv[0]).resolve())


def launchd_plist(exe: str, label: str = SERVICE_LABEL) -> str:
    log = f"{Path.home()}/Library/Logs/{label}.log"
    # `--service`: .app 번들 바이너리를 **헤드리스**(창·Dock 아이콘 없음)로 실행한다.
    # KeepAlive 는 '크래시 시에만' — 정상 종료(예: GUI 가 이미 연결돼 singleton 으로 빠지는 경우)에는
    # 재실행하지 않아 앱을 열어 둔 동안 재실행 폭주/창 깜빡임이 생기지 않는다.
    return (
        '<?xml version="1.0" encoding="UTF-8"?>\n'
        '<!DOCTYPE plist PUBLIC "-//Apple//DTD PLIST 1.0//EN" '
        '"http://www.apple.com/DTDs/PropertyList-1.0.dtd">\n'
        '<plist version="1.0"><dict>\n'
        f"  <key>Label</key><string>{label}</string>\n"
        f"  <key>ProgramArguments</key><array><string>{exe}</string><string>--service</string></array>\n"
        "  <key>RunAtLoad</key><true/>\n"
        "  <key>KeepAlive</key><dict><key>Crashed</key><true/></dict>\n"
        "  <key>ProcessType</key><string>Background</string>\n"
        f"  <key>StandardOutPath</key><string>{log}</string>\n"
        f"  <key>StandardErrorPath</key><string>{log}</string>\n"
        "</dict></plist>\n"
    )


def systemd_unit(exe: str) -> str:
    return (
        "[Unit]\n"
        "Description=Discord AI Network Bot Provider Agent\n"
        "After=network-online.target\n"
        "Wants=network-online.target\n\n"
        "[Service]\n"
        "Type=simple\n"
        f"ExecStart={exe} --yes\n"
        "Restart=on-failure\n"
        "RestartSec=5\n\n"
        "[Install]\n"
        "WantedBy=default.target\n"
    )


def install_service() -> str:
    """현재 OS에 자동 시작 서비스를 등록하고, 등록 위치(설명)를 반환한다."""
    exe = executable_path()
    system = platform.system()
    if system == "Darwin":
        target = Path.home() / "Library" / "LaunchAgents" / f"{SERVICE_LABEL}.plist"
        target.parent.mkdir(parents=True, exist_ok=True)
        target.write_text(launchd_plist(exe), encoding="utf-8")
        subprocess.run(["launchctl", "unload", str(target)], check=False, capture_output=True)
        subprocess.run(["launchctl", "load", str(target)], check=False, capture_output=True)
        return str(target)
    if system == "Linux":
        target = Path.home() / ".config" / "systemd" / "user" / f"{SERVICE_NAME}.service"
        target.parent.mkdir(parents=True, exist_ok=True)
        target.write_text(systemd_unit(exe), encoding="utf-8")
        subprocess.run(["systemctl", "--user", "daemon-reload"], check=False, capture_output=True)
        subprocess.run(["systemctl", "--user", "enable", "--now", SERVICE_NAME], check=False, capture_output=True)
        return str(target)
    if system == "Windows":
        subprocess.run(
            ["schtasks", "/create", "/tn", SERVICE_NAME, "/tr", f'"{exe}" --yes', "/sc", "onlogon", "/f"],
            check=False,
            capture_output=True,
        )
        return f"작업 스케줄러: {SERVICE_NAME} (로그온 시 실행)"
    raise RuntimeError(f"지원하지 않는 OS: {system}")
