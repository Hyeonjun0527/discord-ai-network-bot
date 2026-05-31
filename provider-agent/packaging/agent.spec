# PyInstaller spec — 단일 실행파일(Windows/macOS/Linux 공통).
# 빌드: cd provider-agent && pyinstaller packaging/agent.spec
#   결과: dist/discord-ai-provider-agent (플랫폼별 실행파일)
# block_cipher 미사용.
from pathlib import Path

project_root = Path(SPECPATH).parent

a = Analysis(
    [str(project_root / "packaging" / "pyinstaller_entry.py")],
    pathex=[str(project_root / "src")],
    binaries=[],
    datas=[],
    hiddenimports=["provider_agent", "aiohttp"],
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
    name="discord-ai-provider-agent",
    console=True,
    onefile=True,
    upx=True,
)
