# PyInstaller spec — CLI onefile(전 OS 공통) + macOS 데스크톱 .app 번들.
# 빌드: cd provider-agent && pyinstaller packaging/agent.spec
#   결과: dist/discord-ai-network-bot           (플랫폼별 CLI 실행파일 — 서비스/헤드리스용)
#         dist/냥시스턴트.app                    (macOS 에서만 — Finder/응용 프로그램용 GUI 앱)
# block_cipher 미사용.
import sys
from pathlib import Path

from PyInstaller.utils.hooks import collect_data_files

project_root = Path(SPECPATH).parent
icons = project_root / "packaging" / "icons"
IS_MAC = sys.platform == "darwin"
IS_WIN = sys.platform.startswith("win")

# EXE 아이콘은 플랫폼별 포맷(.icns/.ico). 리눅스는 아이콘 미지원이라 None.
exe_icon = str(icons / "app.icns") if IS_MAC else (str(icons / "app.ico") if IS_WIN else None)

# ── CLI onefile(서비스/헤드리스/파워유저용) — 기존 동작 유지 ───────────────────
a = Analysis(
    [str(project_root / "packaging" / "pyinstaller_entry.py")],
    pathex=[str(project_root / "src")],
    binaries=[],
    datas=collect_data_files("certifi"),
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
    name="discord-ai-network-bot",
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
        datas=collect_data_files("certifi"),
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
        name="냥시스턴트",
        console=False,  # windowed(터미널 없이 GUI 로 뜸)
        icon=str(icons / "app.icns"),
    )
    gui_coll = COLLECT(
        gui_exe,
        gui_a.binaries,
        gui_a.datas,
        upx=True,
        name="냥시스턴트",
    )
    app = BUNDLE(
        gui_coll,
        name="냥시스턴트.app",
        icon=str(icons / "app.icns"),
        bundle_identifier="world.yeon.nyassistant.provider-agent",
        info_plist={
            "CFBundleName": "냥시스턴트",
            "CFBundleDisplayName": "냥시스턴트",
            "CFBundleShortVersionString": "1.0",
            "NSHighResolutionCapable": True,
            "LSApplicationCategoryType": "public.app-category.utilities",
            # 네트워크 사용 안내(로컬 서버/풀 연결). App Transport Security 는 기본 유지.
            "LSMinimumSystemVersion": "11.0",
        },
    )
