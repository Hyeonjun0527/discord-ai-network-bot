# PyInstaller spec — 단일 실행파일(Windows/macOS/Linux 공통).
# 빌드: cd provider-agent && pyinstaller packaging/agent.spec
#   결과: dist/discord-ai-network-bot (플랫폼별 실행파일)
# block_cipher 미사용.
from pathlib import Path

from PyInstaller.utils.hooks import collect_data_files

project_root = Path(SPECPATH).parent

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
)
