"""자동 시작 서비스 등록 (set-and-forget).

`--install-service` 로 로그인 시 자동 실행되는 **사용자 단위** 서비스를 등록한다(관리자 불필요).
서비스는 저장된 설정(`--save-config`)과 durable 토큰으로 무인자 실행하므로, 한 번 설정하면
이후 터미널 재입력 없이 알아서 풀에 연결된다.

- macOS: LaunchAgent(`~/Library/LaunchAgents`)
- Linux: systemd `--user` 유닛(`~/.config/systemd/user`)
- Windows: 작업 스케줄러(로그온 트리거, 현재 사용자)

설치/제어 명령은 **실패하면 RuntimeError 로 표면화**한다(옛날엔 조용히 삼켜 "토글 켰는데 안 됨"이
됐다). GUI 가 이 예외를 잡아 사용자에게 사유를 보여준다.
"""
from __future__ import annotations

import logging
import os
import platform
import shutil
import subprocess
import sys
from pathlib import Path

logger = logging.getLogger("provider_agent.service")

SERVICE_LABEL = "world.yeon.discord-ai-network-bot"
SERVICE_NAME = "discord-ai-network-bot"


def executable_path() -> str:
    """설치된 실행파일 경로. PATH(brew/scoop) → 콘솔스크립트/프리즌 바이너리 순."""
    return shutil.which(SERVICE_NAME) or str(Path(sys.argv[0]).resolve())


def _run(cmd: list[str], *, ignore: tuple[str, ...] = ()) -> subprocess.CompletedProcess:
    """명령 실행 후 실패(비0)면 RuntimeError. ``ignore`` 토큰이 출력에 있으면 성공으로 본다.

    (예: ``launchctl load`` 가 이미 로드된 서비스에 'already loaded' 를 내는 건 실패가 아니다.)
    """
    r = subprocess.run(cmd, check=False, capture_output=True, text=True)
    if r.returncode != 0:
        err = (r.stderr or r.stdout or "").strip()
        if any(tok.lower() in err.lower() for tok in ignore):
            return r
        raise RuntimeError(f"{cmd[0]} 실패(코드 {r.returncode}): {err[:300] or '(출력 없음)'}")
    return r


def launchd_plist(exe: str, label: str = SERVICE_LABEL) -> str:
    log = f"{Path.home()}/Library/Logs/{label}.log"
    # `--service`: .app 번들 바이너리를 **헤드리스**(창·Dock 아이콘 없음)로 실행한다.
    # KeepAlive 는 '크래시 시에만' — 정상 종료(예: GUI 가 이미 연결돼 singleton 으로 빠지는 경우)에는
    # 재실행하지 않아 앱을 열어 둔 동안 재실행 폭주/창 깜빡임이 생기지 않는다. GUI 를 닫을 때는
    # ``kickstart()`` 로 명시적으로 서비스를 띄워 백그라운드 연결을 인계한다(run_gui).
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


def _mac_plist_path() -> Path:
    return Path.home() / "Library" / "LaunchAgents" / f"{SERVICE_LABEL}.plist"


def _linux_unit_path() -> Path:
    return Path.home() / ".config" / "systemd" / "user" / f"{SERVICE_NAME}.service"


def _mac_domain_target() -> str:
    """launchctl 도메인 타겟(gui/<uid>/<label>) — kickstart·kill 용."""
    return f"gui/{os.getuid()}/{SERVICE_LABEL}"


def install_service() -> str:
    """현재 OS에 자동 시작 서비스를 등록하고, 등록 위치(설명)를 반환한다. 실패하면 RuntimeError."""
    exe = executable_path()
    system = platform.system()
    if system == "Darwin":
        target = _mac_plist_path()
        target.parent.mkdir(parents=True, exist_ok=True)
        target.write_text(launchd_plist(exe), encoding="utf-8")
        # 재등록을 위해 먼저 언로드(없으면 에러 — best-effort 무시), 이어 load 는 실패를 표면화.
        subprocess.run(["launchctl", "unload", str(target)], check=False, capture_output=True)
        _run(["launchctl", "load", "-w", str(target)], ignore=("already loaded", "Operation already in progress"))
        return str(target)
    if system == "Linux":
        target = _linux_unit_path()
        target.parent.mkdir(parents=True, exist_ok=True)
        target.write_text(systemd_unit(exe), encoding="utf-8")
        _run(["systemctl", "--user", "daemon-reload"])
        _run(["systemctl", "--user", "enable", "--now", SERVICE_NAME])
        return str(target)
    if system == "Windows":
        _run(["schtasks", "/create", "/tn", SERVICE_NAME, "/tr", f'"{exe}" --yes', "/sc", "onlogon", "/f"])
        return f"작업 스케줄러: {SERVICE_NAME} (로그온 시 실행)"
    raise RuntimeError(f"지원하지 않는 OS: {system}")


def is_installed() -> bool:
    """자동 시작 서비스가 등록돼 있는지(설치 위치 존재 여부). 미지원 OS·미설치면 False."""
    system = platform.system()
    if system == "Darwin":
        return _mac_plist_path().exists()
    if system == "Linux":
        return _linux_unit_path().exists()
    if system == "Windows":
        r = subprocess.run(["schtasks", "/query", "/tn", SERVICE_NAME], check=False, capture_output=True)
        return r.returncode == 0
    return False


def kickstart() -> bool:
    """등록된 백그라운드 서비스를 **지금 즉시** 띄운다(GUI 종료 시 연결 인계용). 성공하면 True.

    GUI 가 열려 있는 동안엔 서비스가 singleton 으로 빠지므로(정상종료·KeepAlive 미적용) 백그라운드가
    비어 있다. 창을 닫을 때 이 함수로 서비스를 명시적으로 띄워 끊김 없이 백그라운드 연결로 넘긴다.
    """
    if not is_installed():
        return False
    system = platform.system()
    try:
        if system == "Darwin":
            _run(["launchctl", "kickstart", "-k", _mac_domain_target()])
            return True
        if system == "Linux":
            _run(["systemctl", "--user", "restart", SERVICE_NAME])
            return True
        if system == "Windows":
            _run(["schtasks", "/run", "/tn", SERVICE_NAME])
            return True
    except (RuntimeError, OSError) as exc:
        logger.warning("백그라운드 서비스 kickstart 실패: %s", exc)
    return False


def stop_service() -> bool:
    """실행 중인 백그라운드 서비스를 정상 종료시킨다(앱 안에서 직접 연결하고 싶을 때). 성공하면 True.

    KeepAlive 가 '크래시 시에만'이라 정상 종료(SIGTERM)는 재실행을 부르지 않는다 — 임시 중지에 적합.
    """
    if not is_installed():
        return False
    system = platform.system()
    try:
        if system == "Darwin":
            _run(["launchctl", "kill", "SIGTERM", _mac_domain_target()], ignore=("No such process", "could not find"))
            return True
        if system == "Linux":
            _run(["systemctl", "--user", "stop", SERVICE_NAME])
            return True
        if system == "Windows":
            _run(["schtasks", "/end", "/tn", SERVICE_NAME], ignore=("not running",))
            return True
    except (RuntimeError, OSError) as exc:
        logger.warning("백그라운드 서비스 중지 실패: %s", exc)
    return False
