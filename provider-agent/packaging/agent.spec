# PyInstaller spec — CLI onefile(전 OS 공통) + 데스크톱 네이티브 GUI(macOS .app / Windows .exe).
# 빌드: cd provider-agent && pyinstaller packaging/agent.spec   (GUI 는 pywebview 필요: pip install .[gui])
#   결과: dist/nexa-agent-cli                   (플랫폼별 CLI 실행파일 — 서비스/헤드리스용)
#         dist/Nexa.app                         (macOS 에서만 — Finder/응용 프로그램용 GUI 앱)
#         dist/Nexa.exe                         (Windows 에서만 — 네이티브 창 GUI 앱, mac .app 과 동일 UX)
# block_cipher 미사용.
import os
import re
import sys
from pathlib import Path

from PyInstaller.utils.hooks import collect_data_files

project_root = Path(SPECPATH).parent
icons = project_root / "packaging" / "icons"
IS_MAC = sys.platform == "darwin"
IS_WIN = sys.platform.startswith("win")


# 번들 버전 SSOT = pyproject.toml(릴리스마다 bump). plist 에 실제 버전을 박아야 새 버전
# 설치 시 macOS 가 아이콘/메타데이터 캐시를 자동 무효화한다 — "1.0" 고정이면 같은 앱으로
# 보고 캐시를 재사용해 옛 아이콘(각진 사각형 등)이 Dock/Cmd+Tab 에 잔존한다.
# CI 는 NEXA_VERSION 으로 덮어쓸 수 있고, 없으면 pyproject 에서 읽는다(tag↔pyproject 동기).
def _agent_version() -> str:
    env = os.environ.get("NEXA_VERSION")
    if env and env.strip():
        return env.strip().lstrip("v")
    try:
        text = (project_root / "pyproject.toml").read_text(encoding="utf-8")
        m = re.search(r'(?m)^version\s*=\s*"([^"]+)"', text)
        if m:
            return m.group(1)
    except OSError:
        pass
    return "0.0.0"


APP_VERSION = _agent_version()

# EXE 아이콘은 플랫폼별 포맷(.icns/.ico). 리눅스는 아이콘 미지원이라 None.
exe_icon = str(icons / "app.icns") if IS_MAC else (str(icons / "app.ico") if IS_WIN else None)


# webui_assets(데스크톱 앱 UI)는 sync-desktop 이 src 트리에 생성하는 gitignore 산출물이다.
# 비editable `pip install .[gui]` 는 이 산출물을 site-packages 로 복사하지 않으므로
# collect_data_files("provider_agent") 가 놓친다 → 실제로 v0.31.0~v0.32.1 번들에서 webUI 가 누락돼
# 옛 인라인/폴백 화면이 떴다. 설치 모드·CI 순서에 의존하지 않게 소스 트리에서 명시적으로 번들하고,
# 누락이면 빌드를 즉시 실패시켜 "조용한 webUI 누락"이 재발하지 못하게 한다.
def _webui_assets_datas():
    src_pkg = project_root / "src" / "provider_agent"
    assets = src_pkg / "webui_assets"
    if not (assets / "index.html").is_file():
        raise SystemExit(
            "[agent.spec] webui_assets/index.html 이 없습니다 — 빌드 전에 "
            "`python scripts/sync_desktop_app.py`(make sync-desktop) 를 실행해야 데스크톱 앱 UI 가 "
            "번들됩니다(누락 시 옛 화면/폴백)."
        )
    out = []
    for f in sorted(assets.rglob("*")):
        if f.is_file():
            dest = Path("provider_agent") / f.relative_to(src_pkg).parent
            out.append((str(f), str(dest)))
    return out


webui_datas = _webui_assets_datas()

# ── CLI onefile(서비스/헤드리스/파워유저용) — 기존 동작 유지 ───────────────────
a = Analysis(
    [str(project_root / "packaging" / "pyinstaller_entry.py")],
    pathex=[str(project_root / "src")],
    binaries=[],
    datas=collect_data_files("certifi") + collect_data_files("provider_agent") + webui_datas,
    hiddenimports=["provider_agent", "aiohttp", "certifi"],
    hookspath=[],
    runtime_hooks=[],
    excludes=[],
    noarchive=False,
)
pyz = PYZ(a.pure)
exe = EXE(
    pyz,
    a.scripts,
    a.binaries,
    a.datas,
    [],
    name="nexa-agent-cli",
    console=True,
    onefile=True,
    upx=True,
    icon=exe_icon,
)

# ── macOS 데스크톱 .app(Finder/런치패드/응용 프로그램용 GUI) — macOS 에서만 ─────
if IS_MAC:
    gui_a = Analysis(
        [str(project_root / "packaging" / "gui_entry.py")],
        pathex=[str(project_root / "src")],
        binaries=[],
        datas=collect_data_files("certifi") + collect_data_files("provider_agent") + webui_datas,
        hiddenimports=["provider_agent", "aiohttp", "certifi", "webview"],
        hookspath=[],
        runtime_hooks=[],
        excludes=[],
        noarchive=False,
    )
    gui_pyz = PYZ(gui_a.pure)
    gui_exe = EXE(
        gui_pyz,
        gui_a.scripts,
        [],
        exclude_binaries=True,
        name="Nexa",
        console=False,  # windowed(터미널 없이 GUI 로 뜸)
        icon=str(icons / "app.icns"),
    )
    # 헤드리스 서비스 helper 는 BUNDLE 에 직접 섞지 않는다.
    # PyInstaller 가 콘솔 helper 를 같은 macOS BUNDLE 입력으로 받으면 Info.plist 에
    # LSBackgroundOnly=true 를 넣어 Finder 더블클릭 시 창 없는 백그라운드 앱이 된다.
    # CI 패키징 단계가 이미 만든 CLI onefile(dist/nexa-agent-cli)을
    # Contents/MacOS/nexa-service 로 복사해 서비스 helper 로 사용한다.
    gui_coll = COLLECT(
        gui_exe,
        gui_a.binaries,
        gui_a.datas,
        upx=True,
        # macOS 기본 파일시스템은 대소문자를 구분하지 않아 CLI `nexa-agent-cli`와
        # GUI 번들 준비 디렉터리가 같은 이름 계열이면 release asset glob에 섞일 수 있다.
        name="Nexa-gui",
    )
    app = BUNDLE(
        gui_coll,
        name="Nexa.app",
        icon=str(icons / "app.icns"),
        bundle_identifier="world.yeon.nexa.provider-agent",
        info_plist={
            "CFBundleName": "Nexa",
            "CFBundleDisplayName": "Nexa",
            "CFBundleShortVersionString": APP_VERSION,
            "CFBundleVersion": APP_VERSION,
            "LSBackgroundOnly": False,
            "LSUIElement": False,
            "NSHighResolutionCapable": True,
            "LSApplicationCategoryType": "public.app-category.utilities",
            # 네트워크 사용 안내(로컬 서버/풀 연결). App Transport Security 는 기본 유지.
            "LSMinimumSystemVersion": "11.0",
        },
    )

# ── Windows 데스크톱 GUI exe(네이티브 창 — mac .app 과 동일 UX) — Windows 에서만 ──
#   결과: dist/Nexa.exe  (windowed, WebView2 네이티브 창; gui_entry → run_gui)
#   WebView2 런타임이 없으면 run_gui 가 자동으로 브라우저로 폴백한다(graceful).
if IS_WIN:
    win_gui_a = Analysis(
        [str(project_root / "packaging" / "gui_entry.py")],
        pathex=[str(project_root / "src")],
        binaries=[],
        datas=(
            collect_data_files("certifi")
            + collect_data_files("provider_agent")
            + collect_data_files("webview")
            + webui_datas
        ),
        # pywebview Windows 백엔드(EdgeChromium=pythonnet/clr). hooks-contrib 가 대부분 처리하지만
        # 명시해 누락을 방지한다.
        hiddenimports=[
            "provider_agent",
            "aiohttp",
            "certifi",
            "webview",
            "webview.platforms.edgechromium",
            "webview.platforms.winforms",
            "clr",
        ],
        hookspath=[],
        runtime_hooks=[],
        excludes=[],
        noarchive=False,
    )
    win_gui_pyz = PYZ(win_gui_a.pure)
    win_gui_exe = EXE(
        win_gui_pyz,
        win_gui_a.scripts,
        win_gui_a.binaries,
        win_gui_a.datas,
        [],
        name="Nexa",
        console=False,  # windowed(콘솔 없이 네이티브 창)
        onefile=True,
        upx=True,
        icon=str(icons / "app.ico"),
    )
