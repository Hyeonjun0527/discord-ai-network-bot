"""Model-level invariant tests for ``discord_assistant.models``.

Focuses on ``GuildConfig.__post_init__`` validation so the dataclass is a single
source of truth for its own invariants (#85): summary_limit must be >= 1.
The upper bound (200) is intentionally NOT enforced at the model layer — callers
(_effective_limit / set_summary_limit) clamp it and bot.py keeps a defensive
">200" branch — so out-of-range-high values must still construct.
"""
from __future__ import annotations

import unittest
from dataclasses import replace

from discord_assistant.models import MIN_SUMMARY_LIMIT, GuildConfig


def _cfg(**overrides: object) -> GuildConfig:
    defaults: dict[str, object] = dict(
        guild_id=1,
        model="llama3.1:8b",
        summary_limit=50,
        language="auto",
    )
    defaults.update(overrides)
    return GuildConfig(**defaults)  # type: ignore[arg-type]


class SummaryLimitValidationTest(unittest.TestCase):
    def test_min_constant(self) -> None:
        self.assertEqual(MIN_SUMMARY_LIMIT, 1)

    def test_lower_bound_accepted(self) -> None:
        cfg = _cfg(summary_limit=MIN_SUMMARY_LIMIT)
        self.assertEqual(cfg.summary_limit, 1)

    def test_zero_rejected(self) -> None:
        with self.assertRaises(ValueError):
            _cfg(summary_limit=0)

    def test_negative_rejected(self) -> None:
        with self.assertRaises(ValueError):
            _cfg(summary_limit=-5)

    def test_upper_bound_not_enforced_at_model(self) -> None:
        # 모델은 상한을 강제하지 않는다 — bot.py ">200" 방어 분기/테스트를 살려둔다.
        cfg = _cfg(summary_limit=300)
        self.assertEqual(cfg.summary_limit, 300)
        # frozen dataclass 의 replace 경로(/usage limit_note 테스트가 사용)도 통과해야 한다.
        self.assertEqual(replace(cfg, summary_limit=500).summary_limit, 500)


if __name__ == "__main__":  # pragma: no cover
    unittest.main()
