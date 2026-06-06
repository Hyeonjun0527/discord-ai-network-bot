"""앱 업데이트 — 현재 버전 확인·최신 비교·다운로드/교체(자동 또는 고급에서 수동).

- 최신 버전: GitHub Releases `latest`(`agent-v*` 태그)에서 가져온다.
- 적용: **빌드된 앱**에서만(소스 실행은 비활성). macOS 는 릴리스의 `.app` zip 을 받아
  현재 위치(보통 `/Applications/NEXA.app`)에 교체하고 재실행한다. Windows 는 exe 를 교체.
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
import threading
import urllib.error
import urllib.request
from pathlib import Path
from urllib.parse import quote

from .constants import AGENT_VERSION, APP_DISPLAY_NAME, GUI_MAC_ASSET, GUI_WIN_ASSET
from .version_check import is_outdated

REPO = "Hyeonjun0527/discord-ai-network-bot"
# API(api.github.com)는 미인증 60req/hr(공유 IP면 부족) → 'releases/latest' 리다이렉트로 태그만 얻고
# 다운로드 URL 은 규칙으로 구성한다(rate limit 회피).
_RELEASES_LATEST = f"https://github.com/{REPO}/releases/latest"
_DOWNLOAD_BASE = f"https://github.com/{REPO}/releases/download"
APP_NAME = APP_DISPLAY_NAME
MAC_ASSET = GUI_MAC_ASSET  # agent-build.yml 이 릴리스에 올리는 .app zip
WIN_ASSET = GUI_WIN_ASSET  # Windows 네이티브 GUI exe(릴리스 자산)
SUMS_ASSET = "SHA256SUMS.txt"


# ── 진행 상태(다운로드 프로그래스바용) — UI 가 /api/update-progress 로 폴링 ──────────────
# phase: idle | downloading | verifying | installing | restarting | done | error
_progress_lock = threading.Lock()
_progress: dict = {"phase": "idle", "downloaded": 0, "total": 0, "percent": 0, "message": "", "error": None}


def update_progress() -> dict:
    """현재 업데이트 진행 상태의 스냅샷(폴링용)."""
    with _progress_lock:
        return dict(_progress)


def is_updating() -> bool:
    with _progress_lock:
        return _progress["phase"] in ("downloading", "verifying", "installing", "restarting")


def _set_progress(**kw: object) -> None:
    with _progress_lock:
        _progress.update(kw)
        total = int(_progress["total"] or 0)
        done = int(_progress["downloaded"] or 0)
        _progress["percent"] = int(done * 100 / total) if total > 0 else 0


def current_version() -> str:
    return AGENT_VERSION


def _frozen() -> bool:
    return bool(getattr(sys, "frozen", False))


# HTTP 헤더는 latin-1 만 허용 → User-Agent 는 반드시 ASCII.
_USER_AGENT = f"nexa-updater/{AGENT_VERSION}"


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


def _download(url: str, dest: Path, timeout: float = 180.0) -> None:
    """url 을 dest 로 스트리밍 다운로드하며 진행률(_progress)을 갱신한다."""
    req = urllib.request.Request(url, headers={"User-Agent": _USER_AGENT})
    with urllib.request.urlopen(req, timeout=timeout, context=_ssl_context()) as resp:  # noqa: S310
        total = int(resp.headers.get("Content-Length") or 0)
        _set_progress(phase="downloading", downloaded=0, total=total, message="새 버전 내려받는 중…", error=None)
        with open(dest, "wb") as f:
            while True:
                chunk = resp.read(262144)
                if not chunk:
                    break
                f.write(chunk)
                with _progress_lock:
                    _progress["downloaded"] = int(_progress["downloaded"]) + len(chunk)
                    tot = int(_progress["total"] or 0)
                    _progress["percent"] = int(int(_progress["downloaded"]) * 100 / tot) if tot > 0 else 0


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


def apply_update(relaunch: str = "gui") -> dict:
    """현재 OS 에 맞게 최신 버전을 받아 교체·재실행한다. `{ok, message|error, restarting?}`.

    relaunch: 교체 후 다시 띄울 방식 — "gui"(창) 또는 "service"(헤드리스, 창 없음). 헤드리스
    자동실행 서비스가 자기 자신을 업데이트할 땐 "service" 로 재실행해 창이 뜨지 않게 한다.
    진행률은 _progress 에 갱신되어 UI 가 /api/update-progress 로 폴링한다(프로그래스바).
    """
    if not _frozen():
        return {"ok": False, "error": "빌드된 앱에서만 업데이트할 수 있어요(개발 빌드는 git 으로 갱신)."}
    _set_progress(phase="downloading", downloaded=0, total=0, percent=0, message="업데이트 준비 중…", error=None)
    if sys.platform == "darwin":
        result = _apply_macos(relaunch)
    elif sys.platform.startswith("win"):
        result = _apply_windows(relaunch)
    else:
        result = {"ok": False, "error": "이 OS 는 인앱 업데이트를 지원하지 않아요."}
    if result.get("ok") and result.get("restarting"):
        _set_progress(phase="restarting", message=str(result.get("message") or "업데이트 적용 중…"))
    elif result.get("ok"):
        _set_progress(phase="done", message=str(result.get("message") or "최신입니다."))
    else:
        _set_progress(phase="error", error=str(result.get("error") or "업데이트 실패"))
    return result


def _apply_macos(relaunch: str = "gui") -> dict:
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

    tmp = Path(tempfile.mkdtemp(prefix="nexa-update-"))
    zip_path = tmp / MAC_ASSET
    try:
        _download(url, zip_path)
    except Exception as exc:  # noqa: BLE001
        return {"ok": False, "error": f"다운로드 실패: {exc}"}

    _set_progress(phase="verifying", message="무결성 검증 중…")
    bad = _verify_checksum(zip_path, MAC_ASSET, latest["assets"].get(SUMS_ASSET))
    if bad:
        return {"ok": False, "error": bad}

    _set_progress(phase="installing", message="설치 중…")
    extract = tmp / "x"
    try:
        subprocess.run(["ditto", "-x", "-k", str(zip_path), str(extract)], check=True, capture_output=True, text=True)
    except (subprocess.CalledProcessError, OSError) as exc:
        return {"ok": False, "error": f"압축 해제 실패: {exc}"}
    new_app = next(iter(extract.glob("*.app")), None)
    if new_app is None or not new_app.exists():
        return {"ok": False, "error": "패키지에서 앱을 찾지 못했어요."}

    # 현재 프로세스가 종료된 뒤 교체·재실행하는 헬퍼(데몬). 실행 중 번들을 자기 자신이 덮어쓰지 않도록 분리.
    # gui → 창으로 다시 열고(open), service → 헤드리스로 창 없이 재실행.
    # 헤드리스는 번들된 콘솔 helper(nexa-service)를 우선한다 — GUI 바이너리를 헤드리스로 띄우면
    # 번들이 'GUI 앱 실행 중'으로 등록돼 응용 프로그램 재오픈이 막히기 때문(P2). 없으면 GUI 바이너리로 폴백.
    binname = bundle.name[:-4] if bundle.name.endswith(".app") else bundle.name
    macos_bin = f"{bundle}/Contents/MacOS/{binname}"
    helper_bin = f"{bundle}/Contents/MacOS/nexa-service"
    relaunch_line = (
        f'open "{bundle}"\n'
        if relaunch != "service"
        else (
            f'SVC="{helper_bin}"; [ -x "$SVC" ] || SVC="{macos_bin}"\n'
            'nohup "$SVC" --service >/dev/null 2>&1 &\n'
        )
    )
    pid = os.getpid()
    helper = tmp / "swap.sh"
    helper.write_text(
        "#!/bin/bash\n"
        f"while kill -0 {pid} 2>/dev/null; do sleep 0.3; done\n"
        f'rm -rf "{bundle}"\n'
        f'ditto "{new_app}" "{bundle}"\n'
        f'xattr -dr com.apple.quarantine "{bundle}" 2>/dev/null\n'
        + relaunch_line
        + f'rm -rf "{tmp}"\n',
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


def start_service_update_watcher(interval_s: float | None = None) -> None:
    """헤드리스 자동실행 서비스용 주기 자동 업데이트 워처(데몬 스레드).

    창 없이 도는 서비스도 새 버전을 주기적으로 받아 **헤드리스로** 교체·재실행한다(껐다 켜야만
    적용되던 문제 해소). 빌드된 앱에서만 동작하고, auto_update 가 꺼져 있으면 적용하지 않는다.
    """
    import threading
    import time

    if not _frozen():
        return
    interval = interval_s if interval_s is not None else max(30.0, float(os.getenv("AGENT_UPDATE_INTERVAL_S") or 7200))

    def _loop() -> None:
        from .config_file import load_config

        while True:
            try:
                if bool(load_config().get("auto_update", True)) and not is_updating():
                    info = check()
                    if info.get("outdated") and info.get("supported"):
                        result = apply_update(relaunch="service")
                        if result.get("ok") and result.get("restarting"):
                            time.sleep(0.5)
                            os._exit(0)  # 헬퍼가 교체·헤드리스 재실행
            except Exception:  # noqa: BLE001 - 자동 업데이트 실패는 서비스 동작을 막지 않는다
                pass
            time.sleep(interval)

    threading.Thread(target=_loop, daemon=True).start()


def _apply_windows(relaunch: str = "gui") -> dict:
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
    tmp = Path(tempfile.mkdtemp(prefix="nexa-update-"))
    new_exe = tmp / WIN_ASSET
    try:
        _download(url, new_exe)
    except Exception as exc:  # noqa: BLE001
        return {"ok": False, "error": f"다운로드 실패: {exc}"}
    _set_progress(phase="verifying", message="무결성 검증 중…")
    bad = _verify_checksum(new_exe, WIN_ASSET, latest["assets"].get(SUMS_ASSET))
    if bad:
        return {"ok": False, "error": bad}
    _set_progress(phase="installing", message="설치 중…")
    # 실행 중 exe 는 못 덮어쓰므로, 종료를 기다렸다가 교체·재실행하는 배치를 분리 실행.
    pid = os.getpid()
    arg = "--service" if relaunch == "service" else "--gui"
    bat = tmp / "swap.bat"
    bat.write_text(
        "@echo off\r\n"
        f':wait\r\ntasklist /FI "PID eq {pid}" | find "{pid}" >nul && (timeout /t 1 >nul & goto wait)\r\n'
        f'copy /Y "{new_exe}" "{exe}" >nul\r\n'
        f'start "" "{exe}" {arg}\r\n',
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
