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

SERVICE_LABEL = "world.yeon.nexa"
SERVICE_NAME = "nexa"
# macOS `.app` 안에 함께 번들되는 헤드리스 서비스 helper 바이너리 이름(P2 reopen 보존).
SERVICE_HELPER_NAME = "nexa-service"


def executable_path() -> str:
    """설치된 실행파일 경로. PATH(brew/scoop) → 콘솔스크립트/프리즌 바이너리 순."""
    return shutil.which(SERVICE_NAME) or str(Path(sys.argv[0]).resolve())


def _macos_bundled_helper() -> str | None:
    """`.app` 안에 함께 번들된 헤드리스 서비스 helper(`Contents/MacOS/nexa-service`) 경로. 없으면 None.

    P2: LaunchAgent 가 GUI `.app` 메인 바이너리(NEXA) 대신 이 **콘솔** helper 를 실행하면, 번들이
    'GUI 앱 실행 중'으로 LaunchServices 에 등록되지 않는다. 그래서 응용 프로그램에서 NEXA 를 다시 열 때
    헤드리스 프로세스가 reopen 이벤트를 흡수하지 않고 설정 창이 정상적으로 새로 열린다.
    빌드에 helper 가 없으면(구버전/빌드 이슈) None → 호출부가 기존 실행파일로 폴백한다(앱 동작 유지).
    """
    if platform.system() != "Darwin":
        return None
    try:
        main_exe = Path(sys.argv[0]).resolve()
    except (OSError, ValueError):
        return None
    if "/Contents/MacOS/" not in str(main_exe):
        return None  # .app 안에서 실행된 게 아니면(개발/CLI) helper 개념 없음
    helper = main_exe.parent / SERVICE_HELPER_NAME
    return str(helper) if helper.exists() else None


def service_executable_path() -> str:
    """자동 시작 서비스(plist/유닛)가 **실행할** 바이너리.

    macOS 는 번들 helper 우선(reopen 보존), 없으면 일반 ``executable_path()`` 로 폴백.
    brew/scoop CLI 설치는 PATH 의 ``nexa``(콘솔 바이너리)라 이미 GUI 앱 등록 문제가 없다.
    """
    return _macos_bundled_helper() or executable_path()


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
    # `--service`: exe 를 **헤드리스**(창·Dock 아이콘 없음)로 실행한다. macOS .app 설치는 번들된
    # 콘솔 helper(nexa-service)를 가리켜, GUI .app 메인 바이너리를 잡지 않아 응용 프로그램 재오픈이 막히지 않는다(P2).
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
        "Description=Nexa Provider Agent\n"
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
    exe = service_executable_path()
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


def _spawn_detached(cmd: list[str]) -> None:
    """명령을 띄우고 **기다리지 않는다**(fire-and-forget, 독립 세션). 결과가 불필요하고 블로킹이
    치명적인 경우 전용(GUI 종료 인계). ``launchctl kickstart`` 는 일부 머신에서 반환이 매우 늦거나
    사실상 블록되는데(실증: 6s 이상 hang), 이걸 동기 대기하면 앱 종료가 hang('응답없음')한다.
    start_new_session 으로 분리해 부모가 os._exit 해도 살아남아 인계를 마친다."""
    subprocess.Popen(
        cmd,
        stdin=subprocess.DEVNULL,
        stdout=subprocess.DEVNULL,
        stderr=subprocess.DEVNULL,
        start_new_session=True,
    )


def kickstart() -> bool:
    """등록된 백그라운드 서비스를 **지금 즉시** 띄운다(GUI 종료 시 연결 인계용). 요청을 보냈으면 True.

    GUI 가 열려 있는 동안엔 서비스가 singleton 으로 빠지므로(정상종료·KeepAlive 미적용) 백그라운드가
    비어 있다. 창을 닫을 때 이 함수로 서비스를 명시적으로 띄워 끊김 없이 백그라운드 연결로 넘긴다.
    **반드시 비블로킹**(detached) — 종료 경로에서 호출되므로 대기하면 앱이 hang 한다(실증).
    """
    if not is_installed():
        return False
    system = platform.system()
    try:
        if system == "Darwin":
            _spawn_detached(["launchctl", "kickstart", "-k", _mac_domain_target()])
            return True
        if system == "Linux":
            _spawn_detached(["systemctl", "--user", "restart", SERVICE_NAME])
            return True
        if system == "Windows":
            _spawn_detached(["schtasks", "/run", "/tn", SERVICE_NAME])
            return True
    except OSError as exc:
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


def uninstall_service() -> bool:
    """자동 시작 서비스를 **해제**한다(설정 화면 autostart 토글 OFF). 미설치/미지원이면 False.

    install_service 의 역연산: launchd 언로드+plist 삭제 / systemd disable+unit 삭제 / 작업 스케줄러 삭제.
    """
    if not is_installed():
        return False
    system = platform.system()
    try:
        if system == "Darwin":
            target = _mac_plist_path()
            subprocess.run(["launchctl", "unload", str(target)], check=False, capture_output=True)
            target.unlink(missing_ok=True)
            return True
        if system == "Linux":
            _run(["systemctl", "--user", "disable", "--now", SERVICE_NAME], ignore=("not loaded", "No such file"))
            _linux_unit_path().unlink(missing_ok=True)
            _run(["systemctl", "--user", "daemon-reload"])
            return True
        if system == "Windows":
            _run(["schtasks", "/delete", "/tn", SERVICE_NAME, "/f"], ignore=("does not exist", "cannot find"))
            return True
    except (RuntimeError, OSError) as exc:
        logger.warning("백그라운드 서비스 해제 실패: %s", exc)
    return False
