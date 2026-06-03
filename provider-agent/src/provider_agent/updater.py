"""앱 업데이트 — 현재 버전 확인·최신 비교·다운로드/교체(자동 또는 고급에서 수동).

- 최신 버전: GitHub Releases `latest`(`agent-v*` 태그)에서 가져온다.
- 적용: **빌드된 앱**에서만(소스 실행은 비활성). macOS 는 릴리스의 `.app` zip 을 받아
  현재 위치(보통 `/Applications/냥시스턴트.app`)에 교체하고 재실행한다. Windows 는 exe 를 교체.
- 자동 업데이트(config.auto_update, 기본 ON): 앱 시작 시 검사·적용.
모두 사용자 권한(관리자/sudo 불필요).
"""
from __future__ import annotations

import hashlib
import os
import ssl
import subprocess
import sys
import tempfile
import urllib.error
import urllib.request
from pathlib import Path
from urllib.parse import quote

from .constants import AGENT_VERSION
from .version_check import is_outdated

REPO = "Hyeonjun0527/discord-ai-network-bot"
# API(api.github.com)는 미인증 60req/hr(공유 IP면 부족) → 'releases/latest' 리다이렉트로 태그만 얻고
# 다운로드 URL 은 규칙으로 구성한다(rate limit 회피).
_RELEASES_LATEST = f"https://github.com/{REPO}/releases/latest"
_DOWNLOAD_BASE = f"https://github.com/{REPO}/releases/download"
APP_NAME = "냥시스턴트"
MAC_ASSET = "냥시스턴트-macos.zip"  # agent-build.yml 이 릴리스에 올리는 .app zip
WIN_ASSET = "냥시스턴트-windows.exe"  # Windows 네이티브 GUI exe(릴리스 자산)
SUMS_ASSET = "SHA256SUMS.txt"


def current_version() -> str:
    return AGENT_VERSION


def _frozen() -> bool:
    return bool(getattr(sys, "frozen", False))


# HTTP 헤더는 latin-1 만 허용 → User-Agent 는 반드시 ASCII(앱 이름이 한글이라 별도 ASCII 슬러그).
_USER_AGENT = f"nyassistant-updater/{AGENT_VERSION}"


def _ssl_context() -> ssl.SSLContext:
    """frozen 번들에서도 CA 를 찾도록 certifi 번들을 우선 사용(없으면 시스템 기본)."""
    try:
        import certifi

        return ssl.create_default_context(cafile=certifi.where())
    except Exception:  # noqa: BLE001 - certifi 없으면 시스템 기본 CA
        return ssl.create_default_context()


def _http_get(url: str, accept: str, timeout: float = 20.0) -> bytes:
    req = urllib.request.Request(url, headers={"Accept": accept, "User-Agent": _USER_AGENT})
    with urllib.request.urlopen(req, timeout=timeout, context=_ssl_context()) as resp:  # noqa: S310 - https 고정
        return bytes(resp.read())


def _url_ok(url: str, timeout: float = 15.0) -> bool:
    """해당 URL(릴리스 자산)이 실제로 존재하는지 HEAD 로 확인(다운로드 호스트라 rate limit 무관)."""
    req = urllib.request.Request(url, method="HEAD", headers={"User-Agent": _USER_AGENT})
    try:
        with urllib.request.urlopen(req, timeout=timeout, context=_ssl_context()) as resp:  # noqa: S310
            return 200 <= int(resp.status) < 400
    except Exception:  # noqa: BLE001
        return False


def _tag_to_version(tag: str) -> str:
    """'agent-v1.2.3' → '1.2.3'."""
    if tag.startswith("agent-v"):
        return tag[len("agent-v") :]
    return tag.lstrip("vV")


def _asset_url(tag: str, name: str) -> str:
    return f"{_DOWNLOAD_BASE}/{tag}/{quote(name)}"


def _latest_tag() -> str:
    """최신 릴리스 태그. 'releases/latest' 가 리다이렉트하는 최종 URL 의 끝(tag 명)을 읽는다.

    예: …/releases/latest → …/releases/tag/agent-v0.20.0 → 'agent-v0.20.0'. 본문은 읽지 않는다.
    """
    req = urllib.request.Request(_RELEASES_LATEST, headers={"User-Agent": _USER_AGENT})
    with urllib.request.urlopen(req, timeout=20.0, context=_ssl_context()) as resp:  # noqa: S310
        final = str(resp.geturl())
    return final.rstrip("/").rsplit("/", 1)[-1]


def fetch_latest() -> dict:
    """최신 릴리스 메타. {version, tag, assets:{name:url}}. 네트워크 실패 시 예외.

    자산 URL 은 태그+이름으로 구성한다(GitHub 다운로드 URL 규칙). 실제 존재는 download 시 확인.
    """
    tag = _latest_tag()
    assets = {n: _asset_url(tag, n) for n in (MAC_ASSET, WIN_ASSET, SUMS_ASSET)}
    return {"version": _tag_to_version(tag), "tag": tag, "assets": assets}


def _update_asset_name() -> str | None:
    if sys.platform == "darwin":
        return MAC_ASSET
    if sys.platform.startswith("win"):
        return WIN_ASSET
    return None


def check() -> dict:
    """버전 상태(네트워크 사용). UI 용 — 실패해도 예외 없이 상태를 반환한다.

    supported: 이 빌드/OS 에서 인앱 업데이트가 가능한지(빌드된 앱 + 지원 OS).
    """
    cur = current_version()
    supported = _frozen() and _update_asset_name() is not None
    info: dict = {"current": cur, "latest": None, "outdated": False, "supported": supported, "error": None}
    try:
        latest = fetch_latest()
        info["latest"] = latest["version"]
        info["outdated"] = bool(latest["version"]) and is_outdated(cur, latest["version"])
        # 구버전이고 적용 가능한 OS 면, 해당 OS 자산이 실제로 릴리스에 있는지 HEAD 로 확인
        # (이전 CI 로 만든 릴리스엔 .app zip 이 없을 수 있음 → 버튼을 헛되이 띄우지 않는다).
        asset = _update_asset_name()
        if supported and info["outdated"] and asset:
            if not _url_ok(latest["assets"][asset]):
                info["supported"] = False
                info["error"] = "이 릴리스에 설치형 패키지가 아직 없어요."
    except Exception:  # noqa: BLE001 - 네트워크/파싱 실패는 UI 에 '확인 실패'로만
        info["error"] = "최신 버전 확인 실패(네트워크)"
    return info


def _sha256(path: Path) -> str:
    h = hashlib.sha256()
    with open(path, "rb") as f:
        for block in iter(lambda: f.read(65536), b""):
            h.update(block)
    return h.hexdigest()


def _verify_checksum(zip_path: Path, asset_name: str, sums_url: str | None) -> str | None:
    """SHA256SUMS.txt 로 무결성 검증. 일치하면 None, 불일치면 에러문구. 합계를 못 받으면 None(통과)."""
    if not sums_url:
        return None
    try:
        sums = _http_get(sums_url, "application/octet-stream").decode("utf-8")
    except Exception:  # noqa: BLE001 - 합계 못 받으면 베스트에포트로 진행
        return None
    want = next((ln.split()[0] for ln in sums.splitlines() if asset_name in ln and ln.split()), None)
    if want and want.lower() != _sha256(zip_path).lower():
        return "무결성 검증 실패(체크섬 불일치)"
    return None


def apply_update() -> dict:
    """현재 OS 에 맞게 최신 버전을 받아 교체·재실행한다. `{ok, message|error, restarting?}`."""
    if not _frozen():
        return {"ok": False, "error": "빌드된 앱에서만 업데이트할 수 있어요(개발 빌드는 git 으로 갱신)."}
    if sys.platform == "darwin":
        return _apply_macos()
    if sys.platform.startswith("win"):
        return _apply_windows()
    return {"ok": False, "error": "이 OS 는 인앱 업데이트를 지원하지 않아요."}


def _apply_macos() -> dict:
    from .installer import _macos_bundle_path  # .app 경로 탐색 재사용

    bundle = _macos_bundle_path()
    if bundle is None:
        return {"ok": False, "error": "빌드된 앱(.app)에서만 업데이트할 수 있어요."}
    try:
        latest = fetch_latest()
    except Exception:  # noqa: BLE001
        return {"ok": False, "error": "최신 버전 확인 실패(네트워크)"}
    if not latest["version"] or not is_outdated(current_version(), latest["version"]):
        return {"ok": True, "already": True, "message": "이미 최신 버전이에요."}
    url = latest["assets"].get(MAC_ASSET)
    if not url:
        return {"ok": False, "error": "이 릴리스에 macOS 앱 패키지가 아직 없어요."}

    tmp = Path(tempfile.mkdtemp(prefix="nyas-update-"))
    zip_path = tmp / MAC_ASSET
    try:
        zip_path.write_bytes(_http_get(url, "application/octet-stream", timeout=180))
    except Exception as exc:  # noqa: BLE001
        return {"ok": False, "error": f"다운로드 실패: {exc}"}

    bad = _verify_checksum(zip_path, MAC_ASSET, latest["assets"].get(SUMS_ASSET))
    if bad:
        return {"ok": False, "error": bad}

    extract = tmp / "x"
    try:
        subprocess.run(["ditto", "-x", "-k", str(zip_path), str(extract)], check=True, capture_output=True, text=True)
    except (subprocess.CalledProcessError, OSError) as exc:
        return {"ok": False, "error": f"압축 해제 실패: {exc}"}
    new_app = next(iter(extract.glob("*.app")), None)
    if new_app is None or not new_app.exists():
        return {"ok": False, "error": "패키지에서 앱을 찾지 못했어요."}

    # 현재 프로세스가 종료된 뒤 교체·재실행하는 헬퍼(데몬). 실행 중 번들을 자기 자신이 덮어쓰지 않도록 분리.
    pid = os.getpid()
    helper = tmp / "swap.sh"
    helper.write_text(
        "#!/bin/bash\n"
        f"while kill -0 {pid} 2>/dev/null; do sleep 0.3; done\n"
        f'rm -rf "{bundle}"\n'
        f'ditto "{new_app}" "{bundle}"\n'
        f'xattr -dr com.apple.quarantine "{bundle}" 2>/dev/null\n'
        f'open "{bundle}"\n'
        f'rm -rf "{tmp}"\n',
        encoding="utf-8",
    )
    helper.chmod(0o755)
    subprocess.Popen(  # noqa: S603 - 우리가 만든 헬퍼 실행
        ["/bin/bash", str(helper)],
        start_new_session=True,
        stdout=subprocess.DEVNULL,
        stderr=subprocess.DEVNULL,
    )
    return {
        "ok": True,
        "restarting": True,
        "version": latest["version"],
        "message": f"v{latest['version']} 로 업데이트 중 — 앱이 자동으로 다시 열립니다.",
    }


def _apply_windows() -> dict:
    latest = None
    try:
        latest = fetch_latest()
    except Exception:  # noqa: BLE001
        return {"ok": False, "error": "최신 버전 확인 실패(네트워크)"}
    if not latest["version"] or not is_outdated(current_version(), latest["version"]):
        return {"ok": True, "already": True, "message": "이미 최신 버전이에요."}
    url = latest["assets"].get(WIN_ASSET)
    if not url:
        return {"ok": False, "error": "이 릴리스에 Windows 패키지가 아직 없어요."}
    exe = Path(sys.executable).resolve()
    tmp = Path(tempfile.mkdtemp(prefix="nyas-update-"))
    new_exe = tmp / WIN_ASSET
    try:
        new_exe.write_bytes(_http_get(url, "application/octet-stream", timeout=180))
    except Exception as exc:  # noqa: BLE001
        return {"ok": False, "error": f"다운로드 실패: {exc}"}
    bad = _verify_checksum(new_exe, WIN_ASSET, latest["assets"].get(SUMS_ASSET))
    if bad:
        return {"ok": False, "error": bad}
    # 실행 중 exe 는 못 덮어쓰므로, 종료를 기다렸다가 교체·재실행하는 배치를 분리 실행.
    pid = os.getpid()
    bat = tmp / "swap.bat"
    bat.write_text(
        "@echo off\r\n"
        f':wait\r\ntasklist /FI "PID eq {pid}" | find "{pid}" >nul && (timeout /t 1 >nul & goto wait)\r\n'
        f'copy /Y "{new_exe}" "{exe}" >nul\r\n'
        f'start "" "{exe}" --gui\r\n',
        encoding="utf-8",
    )
    subprocess.Popen(  # noqa: S603
        ["cmd", "/c", str(bat)],
        creationflags=getattr(subprocess, "CREATE_NEW_PROCESS_GROUP", 0),
        close_fds=True,
    )
    return {
        "ok": True,
        "restarting": True,
        "version": latest["version"],
        "message": f"v{latest['version']} 로 업데이트 중 — 앱이 자동으로 다시 열립니다.",
    }
