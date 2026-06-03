"""설정 파일 저장/로드 (차수 9 #113).

토큰·접속 설정을 ``~/.config/discord-ai-network-bot/config.json`` 에 보관한다.
시크릿 보호를 위해 파일 권한은 0600. ``XDG_CONFIG_HOME`` 을 따른다.
"""
from __future__ import annotations

import json
import os
import pathlib

SAVEABLE = (
    "token",
    "relay_url",
    "ollama_url",
    "models",
    "max_concurrency",
    "daily_limit",
    "allow_remote_ollama",
    "enable_image",
    "sd_url",
    "allow_remote_sd",
)


def config_dir() -> pathlib.Path:
    base = os.getenv("XDG_CONFIG_HOME") or os.path.join(pathlib.Path.home(), ".config")
    return pathlib.Path(base) / "discord-ai-network-bot"


def config_path() -> pathlib.Path:
    return config_dir() / "config.json"


def save_config(cfg, path: pathlib.Path | None = None) -> pathlib.Path:
    """AgentConfig 의 저장 가능한 필드를 JSON 으로 기록(0600)."""
    path = path or config_path()
    path.parent.mkdir(parents=True, exist_ok=True)
    data = {k: getattr(cfg, k) for k in SAVEABLE}
    data["models"] = list(data["models"])  # tuple → list
    path.write_text(json.dumps(data, ensure_ascii=False, indent=2), encoding="utf-8")
    try:
        os.chmod(path, 0o600)  # 시크릿 보호(소유자만 read/write)
    except OSError:
        pass  # 일부 FS(Windows)에서 미지원 — 무시
    return path


def persist_token(token: str, path: pathlib.Path | None = None) -> None:
    """저장 설정에 **토큰만** 갱신한다(durable 토큰 재사용용). 다른 필드는 유지, 권한 0600.

    인증 성공 시 서버가 내려준 durable 토큰을 저장해, 다음 실행/재연결에 같은 토큰으로 인증한다.
    """
    path = path or config_path()
    try:
        data: dict = {}
        if path.exists():
            loaded = json.loads(path.read_text(encoding="utf-8"))
            if isinstance(loaded, dict):
                data = loaded
        data["token"] = token
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_text(json.dumps(data, ensure_ascii=False, indent=2), encoding="utf-8")
        os.chmod(path, 0o600)
    except OSError:
        pass


def load_config(path: pathlib.Path | None = None) -> dict:
    """저장된 설정을 dict 로 로드. 없으면 빈 dict."""
    path = path or config_path()
    if not path.exists():
        return {}
    try:
        data = json.loads(path.read_text(encoding="utf-8"))
        return data if isinstance(data, dict) else {}
    except (json.JSONDecodeError, OSError):
        return {}
