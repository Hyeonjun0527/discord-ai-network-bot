from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]


def test_pyinstaller_spec_uses_launcher_instead_of_package_main() -> None:
    spec = (ROOT / "packaging" / "agent.spec").read_text(encoding="utf-8")

    assert 'project_root / "packaging" / "pyinstaller_entry.py"' in spec
    assert 'project_root / "src"' in spec
    assert "../src/provider_agent/__main__.py" not in spec


def test_pyinstaller_launcher_imports_console_entrypoint_absolutely() -> None:
    launcher = (ROOT / "packaging" / "pyinstaller_entry.py").read_text(encoding="utf-8")

    assert "from provider_agent.__main__ import main" in launcher


def test_pyinstaller_spec_bundles_certifi_ca_data() -> None:
    spec = (ROOT / "packaging" / "agent.spec").read_text(encoding="utf-8")

    assert 'collect_data_files("certifi")' in spec
    assert '"certifi"' in spec


def test_runtime_uses_certifi_for_tls_verification() -> None:
    connection = (ROOT / "src" / "provider_agent" / "connection.py").read_text(encoding="utf-8")

    assert "ssl.create_default_context(cafile=certifi.where())" in connection
    assert "aiohttp.TCPConnector(ssl=ssl_context)" in connection


def test_winget_templates_keep_korean_default_locale_and_localized_copy() -> None:
    winget = ROOT / "packaging" / "winget"
    version = (winget / "Nexa.Nexa.yaml").read_text(encoding="utf-8")
    ko = (winget / "Nexa.Nexa.locale.ko-KR.yaml").read_text(encoding="utf-8")
    en = (winget / "Nexa.Nexa.locale.en-US.yaml").read_text(encoding="utf-8")
    ja = (winget / "Nexa.Nexa.locale.ja-JP.yaml").read_text(encoding="utf-8")

    assert "DefaultLocale: ko-KR" in version
    assert "PackageName: Nexa" in ko
    assert "Publisher: Nexa" in ko
    assert "ShortDescription: Nexa 데스크톱 앱 — 내 로컬 AI모델을 디스코드 서버/채널에 공유합니다." in ko
    assert "ManifestType: defaultLocale" in ko
    assert "Moniker: nexa" in ko

    assert "ShortDescription: Nexa desktop app — share your local AI models with Discord servers and channels." in en
    assert "ManifestType: locale" in en
    assert "Moniker: nexa" not in en

    assert "ShortDescription: Nexa デスクトップアプリ — ローカルAIモデルをDiscordサーバー/チャンネルに共有します。" in ja
    assert "ManifestType: locale" in ja
    assert "Moniker: nexa" not in ja


def test_macos_app_bundle_keeps_gui_foreground_entrypoint() -> None:
    spec = (ROOT / "packaging" / "agent.spec").read_text(encoding="utf-8")

    assert 'project_root / "packaging" / "gui_entry.py"' in spec
    assert '"LSBackgroundOnly": False' in spec
    assert '"LSUIElement": False' in spec
    assert 'svc_exe,  # 헤드리스 helper 를 같은 번들에 포함' not in spec


def test_gui_entry_has_ci_smoke_mode() -> None:
    entry = (ROOT / "packaging" / "gui_entry.py").read_text(encoding="utf-8")

    assert "NEXA_GUI_ENTRY_SMOKE" in entry
    assert "nexa gui entry ok" in entry
