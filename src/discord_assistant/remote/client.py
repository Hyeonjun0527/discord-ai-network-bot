"""RemoteAgentClient — 원격 에이전트를 BaseLLMClient 로 노출 (ADR 0002, 차수 5).

봇의 명령 핸들러는 ``_get_llm`` 이 돌려준 ``BaseLLMClient`` 에만 의존하므로(ADR 0001),
이 어댑터 하나만 추가하면 요약·Q&A·번역 등 모든 기존 경로가 그대로 유저/방장 PC 의 로컬
LLM 을 사용하게 된다.

라우팅: 호출마다 레지스트리에서 (mode, user_id, guild_id)로 연결을 다시 찾는다. 그래서
중간에 에이전트가 재연결돼도 다음 호출은 새 연결로 자동 라우팅된다.

재시도 정책(항목 141): 자동 재시도는 하지 않는다. 오프라인/타임아웃/BUSY 는 사용자에게
명확히 안내하고 끝낸다(상위 명령이 재시도를 결정). 요청 타임아웃은 연결(RelayConnection)이
settings 값으로 강제하므로 클라이언트는 별도 타임아웃을 두지 않는다(항목 140).
"""

from __future__ import annotations

import logging
from collections.abc import AsyncIterator
from typing import Any

from ..llm import BaseLLMClient, ImageInput, LLMError, TokenUsage
from ..models import RoutingMode
from .errors import (
    AgentBusyError,
    ConnectionClosedError,
    RemoteInferError,
    RemoteTimeoutError,
)
from .registry import AgentConnection, AgentOfflineError, ConnectionRegistry

logger = logging.getLogger(__name__)

__all__ = ["RemoteAgentClient"]


class RemoteAgentClient(BaseLLMClient):
    """원격 에이전트 LLM 어댑터. 라우팅 컨텍스트를 들고 매 호출 연결을 찾는다(항목 122~123)."""

    def __init__(
        self,
        *,
        registry: ConnectionRegistry,
        mode: RoutingMode,
        user_id: int | None = None,
        guild_id: int | None = None,
        default_model: str | None = None,
        options: dict[str, Any] | None = None,
    ) -> None:
        self._registry = registry
        self._mode = mode
        self._user_id = user_id
        self._guild_id = guild_id
        self._default_model = default_model
        self._options = options or {}
        self.last_usage = TokenUsage()

    def _route(self) -> AgentConnection:
        """연결을 찾는다. 없으면 LLMError(오프라인 안내)로 변환(항목 125/137)."""
        try:
            return self._registry.route(
                self._mode, user_id=self._user_id, guild_id=self._guild_id
            )
        except AgentOfflineError as exc:
            raise LLMError(str(exc)) from exc

    @staticmethod
    def _map_error(exc: Exception) -> LLMError:
        """원격 추론 예외를 사용자 친화 LLMError 로 변환(항목 126/127/136/137)."""
        if isinstance(exc, AgentBusyError):
            return LLMError(
                "지금 LLM 에이전트가 다른 요청을 처리 중입니다. 잠시 후 다시 시도해 주세요."
            )
        if isinstance(exc, RemoteTimeoutError):
            return LLMError(str(exc))
        if isinstance(exc, ConnectionClosedError):
            return LLMError(
                "LLM 에이전트 연결이 끊겼습니다. 에이전트가 켜져 있는지 확인해 주세요."
            )
        if isinstance(exc, RemoteInferError):
            return LLMError(f"원격 에이전트 오류: {exc.message or exc.code}")
        return LLMError(f"원격 에이전트 처리 실패: {exc}")

    async def generate(
        self,
        prompt: str,
        *,
        model: str | None = None,
        images: list[ImageInput] | None = None,
    ) -> str:
        if images:
            # 비전 미지원(차수 8 capability 게이트에서 사전 차단). 명확히 거부(항목 130).
            raise LLMError("원격 에이전트는 아직 이미지(비전) 입력을 지원하지 않습니다.")
        conn = self._route()
        try:
            result = await conn.send_infer(
                prompt=prompt, model=model or self._default_model, options=self._options
            )
        except (
            AgentBusyError,
            RemoteTimeoutError,
            ConnectionClosedError,
            RemoteInferError,
        ) as exc:
            logger.debug("원격 추론 실패: %s", type(exc).__name__)
            raise self._map_error(exc) from exc
        # usage → last_usage (없으면 0, 항목 131/146)
        self.last_usage = TokenUsage(
            prompt_tokens=result.usage.prompt_tokens,
            completion_tokens=result.usage.completion_tokens,
        )
        return result.text

    async def generate_stream(
        self, prompt: str, *, model: str | None = None
    ) -> AsyncIterator[str]:
        conn = self._route()
        stream = conn.send_infer_stream(
            prompt=prompt, model=model or self._default_model, options=self._options
        )
        try:
            async for delta in stream:
                yield delta
        except (
            AgentBusyError,
            RemoteTimeoutError,
            ConnectionClosedError,
            RemoteInferError,
        ) as exc:
            raise self._map_error(exc) from exc
