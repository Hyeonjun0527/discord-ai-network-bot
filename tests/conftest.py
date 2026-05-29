"""Shared pytest fixtures for the discord_assistant test suite."""
from __future__ import annotations

from types import SimpleNamespace
from unittest.mock import AsyncMock, MagicMock

import pytest

from discord_assistant.storage import ConfigStore


@pytest.fixture
async def store() -> ConfigStore:
    """In-memory ConfigStore ready for use in async tests."""
    s = ConfigStore(
        ":memory:",
        default_model="test-model",
        default_summary_limit=10,
        default_language="ko",
    )
    await s.initialize()
    return s


# ---------------------------------------------------------------------------
# #56: discord.Interaction 목 픽스처 — 향후 슬래시 핸들러 테스트의 토대.
#
# 실제 discord.Interaction 을 생성하려면 게이트웨이/HTTP 컨텍스트가 필요하므로,
# 핸들러가 실제로 사용하는 속성/메서드만 duck-typing 으로 흉내낸다.
# - response.defer / response.send_message 는 AsyncMock (await 가능, 호출 검증 가능).
# - followup.send 도 AsyncMock.
# - user / guild / channel 은 핸들러가 흔히 읽는 id·name 등을 가진 단순 네임스페이스.
# 핸들러 동작에 의존하는 단언이 아니라, "핸들러에 넘길 수 있는 안전한 더블" 제공이 목적이다.
# ---------------------------------------------------------------------------


def _make_user(
    *, user_id: int = 111, name: str = "tester", is_bot: bool = False
) -> SimpleNamespace:
    """interaction.user 로 쓸 duck-typed 사용자 더블을 만든다."""
    return SimpleNamespace(
        id=user_id,
        name=name,
        display_name=name,
        mention=f"<@{user_id}>",
        bot=is_bot,
        # 역할 기반 권한 체크가 비어 있어도 안전하도록 빈 roles/guild_permissions 제공.
        roles=[],
        guild_permissions=SimpleNamespace(administrator=False, manage_guild=False),
    )


def _make_guild(*, guild_id: int = 222, name: str = "test-guild") -> SimpleNamespace:
    """interaction.guild 로 쓸 duck-typed 길드 더블을 만든다."""
    return SimpleNamespace(id=guild_id, name=name)


def _make_channel(
    *, channel_id: int = 333, name: str = "general"
) -> SimpleNamespace:
    """interaction.channel 로 쓸 duck-typed 채널 더블을 만든다.

    핸들러가 채널에 메시지를 보내거나 히스토리를 읽는 경우를 대비해
    send/history 를 AsyncMock/MagicMock 으로 둔다(필요 시 테스트에서 재설정).
    """
    return SimpleNamespace(
        id=channel_id,
        name=name,
        mention=f"<#{channel_id}>",
        send=AsyncMock(),
        history=MagicMock(),
    )


@pytest.fixture
def interaction() -> MagicMock:
    """슬래시 명령 핸들러에 넘길 discord.Interaction 목 (#56).

    핸들러가 흔히 사용하는 인터페이스만 갖춘 더블:
      - ``interaction.response.defer(...)`` / ``response.send_message(...)`` → AsyncMock
      - ``interaction.followup.send(...)`` → AsyncMock
      - ``interaction.user`` / ``guild`` / ``guild_id`` / ``channel`` / ``channel_id``
      - ``interaction.response.is_done()`` → False (아직 응답 전)

    반환값은 MagicMock 이므로 테스트에서 자유롭게 속성을 덮어쓸 수 있다.
    예) ``interaction.guild = None`` 으로 DM 시나리오를 흉내내기.
    """
    user = _make_user()
    guild = _make_guild()
    channel = _make_channel()

    response = MagicMock(name="InteractionResponse")
    response.defer = AsyncMock()
    response.send_message = AsyncMock()
    response.edit_message = AsyncMock()
    # 아직 응답을 보내지 않은 상태(핸들러가 defer/응답 분기 판단에 사용).
    response.is_done = MagicMock(return_value=False)

    followup = MagicMock(name="InteractionFollowup")
    followup.send = AsyncMock()

    inter = MagicMock(name="Interaction")
    inter.response = response
    inter.followup = followup
    inter.user = user
    inter.guild = guild
    inter.guild_id = guild.id
    inter.channel = channel
    inter.channel_id = channel.id
    # edit_original_response 도 핸들러가 자주 쓰므로 AsyncMock 으로 제공.
    inter.edit_original_response = AsyncMock()
    return inter
