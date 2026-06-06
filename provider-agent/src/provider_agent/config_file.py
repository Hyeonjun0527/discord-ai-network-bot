"""설정 파일 저장/로드 (차수 9 #113).

토큰·접속 설정을 ``~/.config/nexa/config.json`` 에 보관한다.
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
    "auto_update",
)


def config_dir() -> pathlib.Path:
    base = os.getenv("XDG_CONFIG_HOME") or os.path.join(pathlib.Path.home(), ".config")
    return pathlib.Path(base) / "nexa"


def config_path() -> pathlib.Path:
    return config_dir() / "config.json"


def save_config(cfg, path: pathlib.Path | None = None) -> pathlib.Path:
    """AgentConfig 의 저장 가능한 필드를 JSON 으로 기록(0600).

    기존 파일을 **병합** 갱신한다: SAVEABLE 필드만 cfg 값으로 덮고, SAVEABLE 에 없는 키
    (``connections``·온보딩 토글 ``auto_connect``/``background``/``autostart_pref``·``tray`` 등)는
    보존한다. (과거엔 전체를 덮어써서 설정 저장 시 저장된 서버 연결·온보딩 선택이 사라졌다.)
    """
    path = path or config_path()
    path.parent.mkdir(parents=True, exist_ok=True)
    data = load_config(path)  # 기존 값 로드 후 SAVEABLE 만 갱신(비-SAVEABLE 키 보존)
    for k in SAVEABLE:
        data[k] = getattr(cfg, k)
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


def persist_partial(updates: dict, path: pathlib.Path | None = None) -> None:
    """저장 설정에 **일부 필드만** 병합 갱신한다(0600). 토큰 등 다른 필드는 유지.

    토글류 단일 설정(예: auto_update)을 다른 값에 영향 없이 즉시 저장할 때 쓴다.
    """
    path = path or config_path()
    try:
        data: dict = {}
        if path.exists():
            loaded = json.loads(path.read_text(encoding="utf-8"))
            if isinstance(loaded, dict):
                data = loaded
        data.update(updates)
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_text(json.dumps(data, ensure_ascii=False, indent=2), encoding="utf-8")
        os.chmod(path, 0o600)
    except OSError:
        pass


def load_connections(path: pathlib.Path | None = None) -> list[dict]:
    """저장된 서버 연결 목록 ``[{token, guild_id, guild_name}]``.

    멀티-서버: 한 에이전트가 여러 디스코드 서버(길드)의 프로바이더로 동시에 붙는다. 구버전의 단일
    ``token`` 은 자동으로 connections[0] 로 변환한다(하위호환).
    """
    data = load_config(path)
    out: list[dict] = []
    raw = data.get("connections")
    if isinstance(raw, list):
        for c in raw:
            if isinstance(c, dict) and str(c.get("token") or "").strip():
                out.append(
                    {"token": str(c["token"]), "guild_id": c.get("guild_id"), "guild_name": c.get("guild_name")}
                )
    if not out:
        tok = str(data.get("token") or "").strip()
        if tok:
            out.append({"token": tok, "guild_id": None, "guild_name": None})
    return out


def save_connections(conns: list[dict], path: pathlib.Path | None = None) -> None:
    """연결 목록을 병합 저장(0600). 첫 토큰을 ``token`` 에도 미러(구버전·기존 코드 호환)."""
    norm = [
        {"token": str(c["token"]), "guild_id": c.get("guild_id"), "guild_name": c.get("guild_name")}
        for c in conns
        if str(c.get("token") or "").strip()
    ]
    persist_partial({"connections": norm, "token": norm[0]["token"] if norm else ""}, path)


def _same_connection(c: dict, token: str, guild_id: int | None) -> bool:
    """같은 연결인지: 길드ID 가 둘 다 있으면 그걸로, 아니면 토큰으로 판단."""
    if guild_id is not None and c.get("guild_id") is not None:
        return bool(c["guild_id"] == guild_id)
    return bool(c["token"] == token)


def add_connection(
    token: str, guild_id: int | None = None, guild_name: str | None = None, path: pathlib.Path | None = None
) -> list[dict]:
    """서버 연결을 추가(같은 길드/토큰이면 교체). 갱신된 목록 반환."""
    conns = [c for c in load_connections(path) if not _same_connection(c, token, guild_id)]
    conns.append({"token": token, "guild_id": guild_id, "guild_name": guild_name})
    save_connections(conns, path)
    return conns


def remove_connection(
    guild_id: int | None = None, token: str | None = None, path: pathlib.Path | None = None
) -> list[dict]:
    """서버 연결을 제거(길드ID 우선, 없으면 토큰). 갱신된 목록 반환."""
    def keep(c: dict) -> bool:
        if guild_id is not None:
            return bool(c.get("guild_id") != guild_id)
        if token is not None:
            return bool(c["token"] != token)
        return True

    conns = [c for c in load_connections(path) if keep(c)]
    save_connections(conns, path)
    return conns


def rename_connection(index: int, name: str | None, path: pathlib.Path | None = None) -> list[dict]:
    """index 번째 연결의 표시 이름(guild_name)을 바꾼다(토큰-추가 연결의 '이름 미상' 라벨링)."""
    conns = load_connections(path)
    if 0 <= index < len(conns):
        conns[index]["guild_name"] = (name or "").strip() or None
        save_connections(conns, path)
    return conns


def remove_connection_at(index: int, path: pathlib.Path | None = None) -> list[dict]:
    """index 번째 연결을 제거(길드ID 가 없는 토큰-추가 연결도 정확히 지목)."""
    conns = load_connections(path)
    if 0 <= index < len(conns):
        conns.pop(index)
        save_connections(conns, path)
    return conns


def set_connection_token(
    new_token: str, guild_id: int | None = None, old_token: str | None = None, path: pathlib.Path | None = None
) -> None:
    """durable 토큰 갱신: 해당 연결(길드ID 또는 옛 토큰)의 token 을 교체 저장."""
    conns = load_connections(path)
    for c in conns:
        if (guild_id is not None and c.get("guild_id") == guild_id) or (
            old_token is not None and c["token"] == old_token
        ):
            c["token"] = new_token
            break
    save_connections(conns, path)


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


def load_guild_policies(path: pathlib.Path | None = None) -> dict[int, dict]:
    """서버(guild)별 내 제공 정책 override ``{guild_id: {daily_limit, max_concurrency, max_seconds, scope}}``.

    전역 기본값(cfg.daily_limit 등)을 서버마다 덮어쓰기 위한 맵(데스크톱 앱 서버 상세 G3 가 설정).
    JSON 키는 문자열이므로 int 로 복원한다.
    """
    raw = load_config(path).get("guild_policies") or {}
    out: dict[int, dict] = {}
    if isinstance(raw, dict):
        for k, v in raw.items():
            if isinstance(v, dict):
                try:
                    out[int(k)] = dict(v)
                except (ValueError, TypeError):
                    continue
    return out


def set_guild_policy(guild_id: int, policy: dict, path: pathlib.Path | None = None) -> None:
    """한 서버의 정책 override 를 병합 저장(0600). 다른 서버 정책·설정은 보존."""
    gp = {str(g): p for g, p in load_guild_policies(path).items()}
    key = str(guild_id)
    gp[key] = {**(gp.get(key) or {}), **policy}
    persist_partial({"guild_policies": gp}, path)
