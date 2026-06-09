"""설정 파일 저장/로드 (차수 9 #113).

토큰·접속 설정을 ``~/.config/nexa/config.json`` 에 보관한다.
시크릿 보호를 위해 파일 권한은 0600. ``XDG_CONFIG_HOME`` 을 따른다.
"""
from __future__ import annotations

import json
import logging
import os
import pathlib

logger = logging.getLogger("provider_agent.config_file")


def _chmod_600(path: pathlib.Path) -> None:
    """설정 파일 권한을 0600 으로 강화(시크릿 보호) — 베스트에포트.

    일부 파일시스템(Windows 등)은 POSIX 권한을 지원하지 않는다. 그건 정상적 한계이므로
    조용히 무시하되, 완전히 숨기지는 않고 debug 로 흔적을 남긴다(예외 원칙 3).
    쓰기 자체의 실패(더 심각)와 분리해, chmod 미지원이 쓰기 실패를 가리지 않게 한다(예외 원칙 2).
    """
    try:
        os.chmod(path, 0o600)
    except OSError as exc:
        logger.debug("config 권한 0600 미지원(무시): %s (%s)", path, exc)


SAVEABLE = (
    "token",
    "relay_url",
    "ollama_url",
    "models",
    "default_model",
    "max_concurrency",
    "daily_limit",
    "allow_remote_ollama",
    "enable_image",
    "comfy_url",
    "hf_token",
    "civitai_token",
    "comfy_push_enabled",
    "comfy_push_guild",
    "comfy_push_channel",
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
    _chmod_600(path)  # 시크릿 보호(소유자만 read/write)
    return path


def persist_token(token: str, path: pathlib.Path | None = None) -> None:
    """저장 설정에 **토큰만** 갱신한다(durable 토큰 재사용용). 다른 필드는 유지, 권한 0600.

    인증 성공 시 서버가 내려준 durable 토큰을 저장해, 다음 실행/재연결에 같은 토큰으로 인증한다.
    """
    # persist_partial 과 완전히 같은 로직(load→merge→write→chmod)이었다 — 위임으로 중복 제거(SRP/DRY).
    persist_partial({"token": token}, path)


def persist_partial(updates: dict, path: pathlib.Path | None = None) -> None:
    """저장 설정에 **일부 필드만** 병합 갱신한다(0600). 토큰 등 다른 필드는 유지.

    토글류 단일 설정(예: auto_update)을 다른 값에 영향 없이 즉시 저장할 때 쓴다.
    """
    path = path or config_path()
    data = load_config(path)  # 부재/손상 파일도 안전하게 {} 로(JSONDecodeError 전파 방지)
    data.update(updates)
    try:
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_text(json.dumps(data, ensure_ascii=False, indent=2), encoding="utf-8")
    except OSError as exc:
        # 설정 저장 실패를 삼키면(예외 원칙 3 위반) 토큰/설정이 보존되지 않아 다음 실행이 조용히 망가진다.
        # 호출부 계약(예외 비전파)은 유지하되, 최소한 원인을 로그로 남긴다(예외 원칙 4).
        logger.warning("config 저장 실패: %s keys=%s (%s)", path, list(updates.keys()), exc)
        return
    _chmod_600(path)


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
    except json.JSONDecodeError as exc:
        # 손상(파싱 실패)과 IO 실패를 구분해 로그한다(예외 원칙 4) — 호출부 계약(비-raising)은 유지.
        logger.warning("config 파일 손상(JSON 파싱 실패) — 빈 설정으로 진행: %s (%s)", path, exc)
        return {}
    except OSError as exc:
        logger.warning("config 파일 읽기 실패 — 빈 설정으로 진행: %s (%s)", path, exc)
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
            if not isinstance(v, dict):
                continue
            # 예외를 흐름제어로 쓰지 않고(예외 원칙 5) 먼저 검사한다. 잘못된 키는 조용히 건너뛰지 않고 남긴다.
            if not (isinstance(k, str) and k.isdigit()):
                logger.debug("guild_policies 의 비정수 키 건너뜀: %r", k)
                continue
            out[int(k)] = dict(v)
    return out


def set_guild_policy(guild_id: int, policy: dict, path: pathlib.Path | None = None) -> None:
    """한 서버의 정책 override 를 병합 저장(0600). 다른 서버 정책·설정은 보존."""
    gp = {str(g): p for g, p in load_guild_policies(path).items()}
    key = str(guild_id)
    gp[key] = {**(gp.get(key) or {}), **policy}
    persist_partial({"guild_policies": gp}, path)
