"""T004 masked member 샘플 단위 테스트 — 미래 미포함·정답은 분포."""

from __future__ import annotations

import pytest

from nexa_policy.data.masking import LabelTargets, MaskedMemberExample, build_masked_example
from tests.conftest import make_event


def test_prior_events_exclude_future(synthetic_events: list) -> None:
    ex = build_masked_example(
        guild_pseudonym="guild-A",
        session_id="guild-A:sess0",
        masked_actor="actor-2",
        cut_time_ms=3_000,
        all_events=synthetic_events,
        target=LabelTargets(action={"action": "speak"}),
    )
    assert all(e.event_time_ms <= 3_000 for e in ex.prior_events)
    assert {e.event_id for e in ex.prior_events} == {"e1", "e2"}


def test_future_event_in_prior_rejected() -> None:
    future = make_event(event_id="future", time_ms=10_000, actor="a1")
    with pytest.raises(ValueError, match="leakage"):
        MaskedMemberExample(
            guild_pseudonym="g",
            session_id="s",
            masked_actor="a1",
            cut_time_ms=1_000,
            prior_events=(future,),
            target=LabelTargets(),
        )


def test_target_is_distribution_not_single_text() -> None:
    target = LabelTargets(
        action={"action": "speak"},
        target={"targets": []},
        delay={"delay_ms": 500},
        burst={"message_count": 2},
        social_act={"act": "ask"},
    )
    # 정답은 문장 하나가 아니라 action/target/time/burst 분포다.
    assert target.action is not None
    assert target.target is not None
    assert target.delay is not None
    assert target.burst is not None
    assert not isinstance(target.action, str)
