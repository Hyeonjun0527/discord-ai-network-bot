"""합성 fixture(운영 데이터 미접근). 모든 값은 가명·신호만 — 원문/실제 user id 없음."""

from __future__ import annotations

import pytest

from nexa_policy.data.schema import EventRecord


def make_event(
    *,
    event_id: str,
    time_ms: int,
    actor: str,
    kind: str = "message",
    guild: str = "guild-A",
    channel: str = "chan-1",
    thread: str | None = None,
    burst: str = "burst-1",
    scene: str = "scene-1",
    features: dict[str, object] | None = None,
    observable: bool = True,
    opt_in: bool = True,
    eligible: bool = True,
) -> EventRecord:
    """합성 EventRecord 빌더. 가명·신호만."""
    return EventRecord(
        guild_pseudonym=guild,
        channel_pseudonym=channel,
        thread_pseudonym=thread,
        event_id=event_id,
        event_time_ms=time_ms,
        burst_id=burst,
        scene_id=scene,
        actor_pseudonym=actor,
        event_kind=kind,
        features=features or {"mentions_nexa": False},
        masks={"is_observable": observable, "consent_opt_in": opt_in},
        training_eligible=eligible,
    )


@pytest.fixture
def synthetic_events() -> list[EventRecord]:
    """작은 합성 대화: actor-1 이 질문, actor-2 가 답장+버스트, 침묵 구간 포함."""
    return [
        make_event(event_id="e1", time_ms=1_000, actor="actor-1", features={"is_question": True}),
        make_event(event_id="e2", time_ms=3_000, actor="actor-2", kind="reply",
                   features={"reply_to_event_id": "e1", "char_len_bucket": 2}),
        make_event(event_id="e3", time_ms=4_000, actor="actor-2", kind="message",
                   features={"char_len_bucket": 1}),
        make_event(event_id="e4", time_ms=5_000, actor="actor-2", kind="reaction",
                   features={"reaction_code": "thumbsup"}),
    ]
