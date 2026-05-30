"""에이전트 설정 & CLI (차수 1).

우선순위: CLI 인자 > 환경변수 > 기본값. 토큰은 ``--token`` 또는 ``AGENT_TOKEN``.
"""
from __future__ import annotations

import argparse
import os
import platform as _platform
from dataclasses import dataclass, field

from .constants import AGENT_VERSION


@dataclass(frozen=True, slots=True)
class AgentConfig:
    token: str
    relay_url: str = "ws://localhost:8080/agent"
    ollama_url: str = "http://localhost:11434"
    models: tuple[str, ...] = ()
    max_concurrency: int = 1
    daily_limit: int = 0  # 0 = 무제한
    request_timeout: float = 120.0
    heartbeat_seconds: float = 30.0
    reconnect_max_seconds: float = 30.0
    agent_version: str = AGENT_VERSION
    platform: str = field(default_factory=lambda: _platform.platform())

    def masked(self) -> str:
        """토큰을 가린 요약(로그용)."""
        return (
            f"AgentConfig(relay_url={self.relay_url!r}, ollama_url={self.ollama_url!r}, "
            f"models={self.models}, max_concurrency={self.max_concurrency}, "
            f"daily_limit={self.daily_limit}, token={'***' if self.token else '(없음)'})"
        )


def _env(name: str, default: str = "") -> str:
    return os.getenv(name, default).strip()


def build_parser() -> argparse.ArgumentParser:
    p = argparse.ArgumentParser(
        prog="discord-ai-provider-agent",
        description="내 PC의 로컬 Ollama 를 커뮤니티 중앙 서버에 연결하는 프로바이더 에이전트",
    )
    p.add_argument("--token", help="중앙 서버에서 발급받은 일회용 토큰 (또는 AGENT_TOKEN)")
    p.add_argument("--relay-url", help="중앙 서버 WS 주소 (또는 RELAY_URL)")
    p.add_argument("--ollama-url", help="로컬 Ollama 주소 (또는 OLLAMA_BASE_URL)")
    p.add_argument("--model", action="append", dest="models", help="제공 모델(여러 번 지정 가능)")
    p.add_argument("--max-concurrency", type=int, help="동시 처리 요청 수 (기본 1)")
    p.add_argument("--daily-limit", type=int, help="하루 처리 한도 (0=무제한)")
    p.add_argument("--request-timeout", type=float, help="요청당 타임아웃 초 (기본 120)")
    p.add_argument("--heartbeat", type=float, dest="heartbeat_seconds", help="heartbeat 주기 초 (기본 30)")
    p.add_argument("-v", "--verbose", action="store_true", help="디버그 로그")
    p.add_argument("--version", action="version", version=f"%(prog)s {AGENT_VERSION}")
    return p


def config_from_args(argv: list[str] | None = None) -> tuple[AgentConfig, bool]:
    """CLI/env 로부터 (config, verbose) 를 만든다. 토큰이 없으면 SystemExit."""
    args = build_parser().parse_args(argv)

    token = (args.token or _env("AGENT_TOKEN")).strip()
    if not token:
        build_parser().error("토큰이 필요합니다: --token 또는 AGENT_TOKEN 환경변수")

    relay_url = (args.relay_url or _env("RELAY_URL") or "ws://localhost:8080/agent").rstrip("/")
    ollama_url = (args.ollama_url or _env("OLLAMA_BASE_URL") or "http://localhost:11434").rstrip("/")
    models = tuple(args.models) if args.models else ()

    cfg = AgentConfig(
        token=token,
        relay_url=relay_url,
        ollama_url=ollama_url,
        models=models,
        max_concurrency=max(1, args.max_concurrency if args.max_concurrency is not None else 1),
        daily_limit=max(0, args.daily_limit if args.daily_limit is not None else 0),
        request_timeout=args.request_timeout if args.request_timeout is not None else 120.0,
        heartbeat_seconds=args.heartbeat_seconds if args.heartbeat_seconds is not None else 30.0,
    )
    return cfg, bool(args.verbose)
