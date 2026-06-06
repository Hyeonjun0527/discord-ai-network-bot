"""앱을 OS 표준 위치에 설치(맥: `/Applications`, 윈도우: 시작 메뉴).

GUI '고급'의 '응용 프로그램에 추가하기' 버튼이 호출한다. 모두 **사용자 권한**으로 동작한다
(관리자/sudo 불필요). 빌드된 앱(PyInstaller frozen)에서만 의미가 있고, 소스/스크립트
실행 중에는 설치할 대상 번들이 없으므로 비활성으로 안내한다.

- macOS: 현재 실행 중인 `.app` 번들을 `/Applications/NEXA.app` 으로 복사(ditto, 서명 보존)
  + quarantine 제거 + Launch Services 등록(Launchpad/Spotlight 반영).
- Windows: 실행파일을 사용자 Programs 폴더(%LOCALAPPDATA%)로 복사하고 시작 메뉴에
  바로가기(.lnk)를 만든다(현재 사용자, 관리자 불필요).
"""
from __future__ import annotations

import os
import shutil
import subprocess
import sys
from pathlib import Path

from .constants import APP_DISPLAY_NAME, MAC_APP_BUNDLE

APP_NAME = APP_DISPLAY_NAME
_LSREGISTER = (
    "/System/Library/Frameworks/CoreServices.framework/Frameworks/"
    "LaunchServices.framework/Support/lsregister"
)


def _is_frozen() -> bool:
    """PyInstaller 등으로 번들된 실행파일인지(소스 실행이면 False)."""
    return bool(getattr(sys, "frozen", False))


def _macos_bundle_path() -> Path | None:
    """현재 실행이 `.app` 번들 안이면 그 `.app` 경로, 아니면 None(소스/비번들 실행)."""
    if not _is_frozen() or sys.platform != "darwin":
        return None
    exe = Path(sys.executable).resolve()
    for parent in (exe, *exe.parents):
        if parent.suffix == ".app":
            return parent
    return None


def _macos_target() -> Path:
    return Path("/Applications") / MAC_APP_BUNDLE


def _win_start_menu_dir() -> Path:
    base = os.environ.get("APPDATA") or str(Path.home() / "AppData" / "Roaming")
    return Path(base) / "Microsoft" / "Windows" / "Start Menu" / "Programs"


def _win_programs_dir() -> Path:
    base = os.environ.get("LOCALAPPDATA") or str(Path.home() / "AppData" / "Local")
    return Path(base) / "Programs" / APP_NAME


def install_info() -> dict:
    """버튼 노출/문구 결정용 상태. `platform` 은 mac/win/other, `supported` 면 버튼 활성."""
    if sys.platform == "darwin":
        bundle = _macos_bundle_path()
        target = _macos_target()
        if bundle is None:
            return {
                "platform": "mac",
                "supported": False,
                "reason": "빌드된 앱(.app)으로 실행할 때만 응용 프로그램에 넣을 수 있어요.",
                "label": "응용 프로그램에 추가하기",
            }
        return {
            "platform": "mac",
            "supported": True,
            "installed": bundle.resolve() == target.resolve(),
            "label": "응용 프로그램에 추가하기",
            "target": str(target),
        }
    if sys.platform.startswith("win"):
        if not _is_frozen():
            return {
                "platform": "win",
                "supported": False,
                "reason": "빌드된 실행파일에서만 시작 메뉴에 추가할 수 있어요.",
                "label": "시작 메뉴에 추가",
            }
        link = _win_start_menu_dir() / f"{APP_NAME}.lnk"
        return {
            "platform": "win",
            "supported": True,
            "installed": link.exists(),
            "label": "시작 메뉴에 추가",
            "target": str(link),
        }
    return {
        "platform": "other",
        "supported": False,
        "reason": "이 OS 는 자동 설치를 지원하지 않아요.",
        "label": "설치",
    }


def _install_macos() -> dict:
    bundle = _macos_bundle_path()
    if bundle is None:
        return {"ok": False, "error": "빌드된 앱(.app)으로 실행할 때만 가능합니다."}
    target = _macos_target()
    if bundle.resolve() == target.resolve():
        return {"ok": True, "already": True, "target": str(target),
                "message": "이미 응용 프로그램 폴더에서 실행 중입니다."}
    try:
        if target.exists() or target.is_symlink():
            shutil.rmtree(target, ignore_errors=True)
            if target.exists():  # symlink 등 rmtree 로 안 지워진 경우
                target.unlink()
        # ditto: 서명/리소스포크/메타데이터 보존 복사. 없으면 copytree 폴백.
        if shutil.which("ditto"):
            subprocess.run(
                ["ditto", str(bundle), str(target)],
                check=True, capture_output=True, text=True,
            )
        else:
            shutil.copytree(bundle, target, symlinks=True)
    except PermissionError:
        return {"ok": False, "error": "응용 프로그램 폴더에 쓸 권한이 없습니다(관리자 계정 필요)."}
    except subprocess.CalledProcessError as exc:
        detail = (exc.stderr or "").strip()[:200]
        return {"ok": False, "error": f"복사에 실패했어요: {detail or exc}"}
    except OSError as exc:
        return {"ok": False, "error": f"복사에 실패했어요: {exc}"}
    # 로컬 빌드 quarantine 제거 + Launch Services 등록(아이콘/Spotlight 반영) — 실패해도 치명적 아님.
    subprocess.run(
        ["xattr", "-dr", "com.apple.quarantine", str(target)], capture_output=True
    )
    if Path(_LSREGISTER).exists():
        subprocess.run([_LSREGISTER, "-f", str(target)], capture_output=True)
    return {"ok": True, "target": str(target),
            "message": "응용 프로그램에 추가했어요. Launchpad·Spotlight 에서 ‘NEXA’로 열 수 있어요."}


def _ps_quote(value: str) -> str:
    """PowerShell 작은따옴표 문자열 안전화(작은따옴표를 두 번으로 이스케이프)."""
    return value.replace("'", "''")


def _install_windows() -> dict:
    if not _is_frozen():
        return {"ok": False, "error": "빌드된 실행파일에서만 가능합니다."}
    exe = Path(sys.executable).resolve()
    programs = _win_programs_dir()
    try:
        programs.mkdir(parents=True, exist_ok=True)
        dest_exe = programs / exe.name
        if dest_exe.resolve() != exe:
            shutil.copy2(exe, dest_exe)
    except OSError as exc:
        return {"ok": False, "error": f"복사에 실패했어요: {exc}"}
    link = _win_start_menu_dir() / f"{APP_NAME}.lnk"
    try:
        link.parent.mkdir(parents=True, exist_ok=True)
    except OSError as exc:
        return {"ok": False, "error": f"시작 메뉴 폴더를 만들 수 없어요: {exc}"}
    # 바로가기(.lnk)는 WScript.Shell COM 으로 생성 — 추가 의존성(pywin32) 없이 PowerShell 만으로.
    ps = (
        "$w=New-Object -ComObject WScript.Shell;"
        f"$s=$w.CreateShortcut('{_ps_quote(str(link))}');"
        f"$s.TargetPath='{_ps_quote(str(dest_exe))}';"
        "$s.Arguments='--gui';"
        f"$s.WorkingDirectory='{_ps_quote(str(programs))}';"
        f"$s.IconLocation='{_ps_quote(str(dest_exe))}';"
        "$s.Save()"
    )
    try:
        subprocess.run(
            ["powershell", "-NoProfile", "-NonInteractive", "-Command", ps],
            check=True, capture_output=True, text=True,
        )
    except subprocess.CalledProcessError as exc:
        detail = (exc.stderr or "").strip()[:200]
        return {"ok": False, "error": f"바로가기 생성에 실패했어요: {detail or exc}"}
    except OSError as exc:
        return {"ok": False, "error": f"바로가기 생성에 실패했어요: {exc}"}
    return {"ok": True, "target": str(link),
            "message": "시작 메뉴에 ‘NEXA’를 추가했어요. 시작 메뉴에서 검색해 열 수 있어요."}


def install_app() -> dict:
    """현재 OS 에 맞게 앱을 표준 위치에 설치한다. `{ok, message|error, target}` 반환."""
    if sys.platform == "darwin":
        return _install_macos()
    if sys.platform.startswith("win"):
        return _install_windows()
    return {"ok": False, "error": "이 OS 는 자동 설치를 지원하지 않아요."}
