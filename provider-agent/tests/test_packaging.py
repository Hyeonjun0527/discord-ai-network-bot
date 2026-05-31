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
