"""Shared pytest fixtures for the discord_assistant test suite."""
from __future__ import annotations

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
