"""연결 레지스트리 & 라우팅 (ADR 0002, 차수 3).

중앙 봇 릴레이에 연결된 에이전트들을 보관하고, 라우팅 모드(개인/공유)에 따라 추론 요청을
어느 연결로 보낼지 결정한다.

- 개인 모드(``RoutingMode.PERSONAL``): 라우팅 키 = ``user_id`` → 그 유저의 PC 에이전트.
- 공유 모드(``RoutingMode.SHARED``): 라우팅 키 = ``guild_id`` → 서버 대표(호스트) 에이전트.

동시성(항목 70): 이 레지스트리는 **단일 asyncio 이벤트 루프**에서 호출된다고 가정한다(봇과
릴레이가 같은 루프에서 동작). dict 연산은 원자적이며, "이전 연결을 닫고 새로 등록"처럼 await
가 끼는 구간만 ``asyncio.Lock`` 으로 직렬화한다. 스레드 간 공유는 지원하지 않는다.
"""

from __future__ import annotations

import asyncio
import logging
import time
from collections.abc import Iterator
from dataclasses import dataclass
from typing import Any, Callable, Literal, Protocol, runtime_checkable

from ..models import RoutingMode
from .protocol import Frame

logger = logging.getLogger(__name__)

__all__ = [
    "OwnerKey",
    "AgentConnection",
    "AgentOfflineError",
    "ConnectionRegistry",
    "user_key",
    "guild_key",
]


class AgentOfflineError(Exception):
    """라우팅 대상 에이전트가 연결돼 있지 않을 때(항목 68)."""


@dataclass(frozen=True, slots=True)
class OwnerKey:
    """연결 소유자 식별 키. user 와 guild 네임스페이스를 분리해 ID 충돌을 막는다(항목 58, 78)."""

    kind: Literal["user", "guild"]
    id: int

    def __str__(self) -> str:
        return f"{self.kind}:{self.id}"


def user_key(user_id: int) -> OwnerKey:
    return OwnerKey(kind="user", id=user_id)


def guild_key(guild_id: int) -> OwnerKey:
    return OwnerKey(kind="guild", id=guild_id)


@runtime_checkable
class AgentConnection(Protocol):
    """에이전트 연결 추상(항목 57). 릴레이의 RelayConnection 이 구현하고, 테스트는 가짜로 대체.

    레지스트리는 이 인터페이스에만 의존한다(보내기/닫기 + 메타데이터). 수신(read loop)은
    릴레이가 담당하므로 여기엔 없다.
    """

    owner_key: OwnerKey | None
    agent_version: str
    platform: str
    connected_at: float
    # 마지막으로 살아 있음을 확인한 monotonic 시각(heartbeat pong 등에서 갱신). 좀비 청소 기준.
    last_seen_monotonic: float

    async def send(self, frame: Frame) -> None: ...

    async def close(self, reason: str = "") -> None: ...


class ConnectionRegistry:
    """user_id/guild_id → AgentConnection 매핑과 라우팅을 담당한다."""

    def __init__(self) -> None:
        self._by_user: dict[int, AgentConnection] = {}
        self._by_guild: dict[int, AgentConnection] = {}
        # guild 호스트가 누구(어느 user)인지 보관(항목 74).
        self._guild_host_user: dict[int, int] = {}
        # 연결 종료 콜백 훅(항목 79). register/unregister 후 호출.
        self._on_close: list[Callable[[OwnerKey], None]] = []
        # await 가 끼는 등록 시퀀스 직렬화(항목 69, 70).
        self._lock = asyncio.Lock()

    # ── 콜백 ────────────────────────────────────────────────────────────
    def add_close_callback(self, cb: Callable[[OwnerKey], None]) -> None:
        self._on_close.append(cb)

    def _fire_close(self, key: OwnerKey) -> None:
        for cb in self._on_close:
            try:
                cb(key)
            except Exception:  # 콜백 오류가 레지스트리를 깨지 않게 격리
                logger.exception("연결 종료 콜백 오류: %s", key)

    # ── 등록/해제 ───────────────────────────────────────────────────────
    async def register_user(self, user_id: int, conn: AgentConnection) -> None:
        """개인 모드 연결 등록(항목 60, 62). 동일 user 재연결이면 이전 연결을 정리(항목 69)."""
        key = user_key(user_id)
        async with self._lock:
            old = self._by_user.get(user_id)
            if old is not None and old is not conn:
                await self._graceful_close(old, "다른 연결로 교체됨")
            conn.owner_key = key
            self._by_user[user_id] = conn
        logger.info("에이전트 등록: %s (agent=%s, platform=%s)", key, conn.agent_version, conn.platform)

    async def register_host(self, guild_id: int, user_id: int, conn: AgentConnection) -> None:
        """공유 모드 호스트 연결 등록(항목 61, 74). 동일 guild 호스트 재연결이면 이전 정리."""
        key = guild_key(guild_id)
        async with self._lock:
            old = self._by_guild.get(guild_id)
            if old is not None and old is not conn:
                await self._graceful_close(old, "다른 호스트 연결로 교체됨")
            conn.owner_key = key
            self._by_guild[guild_id] = conn
            self._guild_host_user[guild_id] = user_id
        logger.info("호스트 에이전트 등록: %s (host_user=%s)", key, user_id)

    async def _graceful_close(self, conn: AgentConnection, reason: str) -> None:
        try:
            await conn.close(reason)
        except Exception:
            logger.debug("이전 연결 graceful close 실패(무시): %s", reason)

    def unregister(self, conn: AgentConnection) -> None:
        """연결 해제(항목 63). 보관된 것과 동일 객체일 때만 제거(stale 해제 방지)."""
        key = conn.owner_key
        if key is None:
            return
        removed = False
        if key.kind == "user" and self._by_user.get(key.id) is conn:
            del self._by_user[key.id]
            removed = True
        elif key.kind == "guild" and self._by_guild.get(key.id) is conn:
            del self._by_guild[key.id]
            self._guild_host_user.pop(key.id, None)
            removed = True
        if removed:
            logger.info("에이전트 해제: %s", key)
            self._fire_close(key)

    # ── 조회 ────────────────────────────────────────────────────────────
    def get_for_user(self, user_id: int) -> AgentConnection | None:
        return self._by_user.get(user_id)

    def get_for_guild(self, guild_id: int) -> AgentConnection | None:
        return self._by_guild.get(guild_id)

    def host_user_id(self, guild_id: int) -> int | None:
        return self._guild_host_user.get(guild_id)

    # ── 라우팅(항목 65~68, 순수 조회) ───────────────────────────────────
    def route(
        self,
        mode: RoutingMode,
        *,
        user_id: int | None,
        guild_id: int | None,
    ) -> AgentConnection:
        """모드+키로 연결을 고른다. 없으면 AgentOfflineError.

        - PERSONAL: user_id 필수 → get_for_user (항목 66).
        - SHARED: guild_id 필수 → get_for_guild (항목 67).
        """
        if mode is RoutingMode.PERSONAL:
            if user_id is None:
                raise AgentOfflineError("개인 모드인데 user_id 가 없습니다")
            conn = self._by_user.get(user_id)
            if conn is None:
                raise AgentOfflineError(
                    "당신의 LLM 에이전트가 오프라인입니다. PC 에서 에이전트를 켜주세요."
                )
            return conn
        if mode is RoutingMode.SHARED:
            if guild_id is None:
                raise AgentOfflineError("공유 모드인데 guild_id 가 없습니다")
            conn = self._by_guild.get(guild_id)
            if conn is None:
                raise AgentOfflineError(
                    "이 서버의 LLM 호스트가 오프라인입니다. 방장 PC 에서 에이전트를 켜주세요."
                )
            return conn
        raise AgentOfflineError(f"알 수 없는 라우팅 모드: {mode}")

    # ── 진단/메트릭 ─────────────────────────────────────────────────────
    def active_count(self) -> int:
        """활성 연결 수(항목 71). user + guild 호스트 합."""
        return len(self._by_user) + len(self._by_guild)

    def iter_connections(self) -> Iterator[AgentConnection]:
        """진단용 전체 연결 이터레이터(항목 75)."""
        yield from self._by_user.values()
        yield from self._by_guild.values()

    def snapshot(self) -> dict[str, Any]:
        """상태 표시용 스냅샷(항목 76)."""
        return {
            "users": sorted(self._by_user.keys()),
            "guild_hosts": {gid: self._guild_host_user.get(gid) for gid in self._by_guild},
            "active": self.active_count(),
        }

    # ── 좀비 청소(항목 73) ──────────────────────────────────────────────
    async def reap_stale(self, *, timeout_seconds: float, now: float | None = None) -> int:
        """last_seen 이 timeout 을 넘은 연결을 닫고 제거한다. 제거 수를 반환."""
        current = time.monotonic() if now is None else now
        stale = [
            conn
            for conn in list(self.iter_connections())
            if current - conn.last_seen_monotonic > timeout_seconds
        ]
        for conn in stale:
            await self._graceful_close(conn, "heartbeat 만료(좀비 정리)")
            self.unregister(conn)
        if stale:
            logger.info("좀비 연결 %d개 정리(timeout=%.0fs)", len(stale), timeout_seconds)
        return len(stale)
