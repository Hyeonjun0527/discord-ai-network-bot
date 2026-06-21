"""T010 negative window·T011 sessionize 테스트."""

from __future__ import annotations

from nexa_policy.data.sessionize import sessionize
from nexa_policy.data.windows.negative import sample_negative_windows
from tests.conftest import make_event


# ---- T010 negative windows ----
def test_negative_only_when_silent_after_opportunity() -> None:
    events = [
        # 기회: other 가 질문 → a2 가 침묵.
        make_event(event_id="q1", time_ms=1_000, actor="other",
                   features={"is_question": True}),
        # 기회: other 가 또 질문 → 이번엔 a2 가 응답(양성, negative 제외).
        make_event(event_id="q2", time_ms=20_000, actor="other",
                   features={"is_question": True}),
        make_event(event_id="resp", time_ms=21_000, actor="a2"),
    ]
    negs = sample_negative_windows(
        masked_actor="a2", events=events, response_window_ms=5_000, max_per_silence=10,
    )
    cuts = {n.cut_time_ms for n in negs}
    assert 1_000 in cuts  # 침묵 기회
    assert 20_000 not in cuts  # 응답한 기회는 negative 아님


def test_negative_does_not_explode_every_millisecond() -> None:
    # 5개의 빠른 연속 기회 → min_gap 으로 thin-out + max_per_silence 캡.
    events = [
        make_event(event_id=f"q{i}", time_ms=1_000 + i * 500, actor="other")
        for i in range(5)
    ]
    negs = sample_negative_windows(
        masked_actor="a2", events=events, response_window_ms=100,
        max_per_silence=2, min_gap_ms=5_000, seed=7,
    )
    assert len(negs) <= 2  # 폭발하지 않음


def test_negative_deterministic_with_seed() -> None:
    events = [
        make_event(event_id=f"q{i}", time_ms=1_000 + i * 10_000, actor="other")
        for i in range(6)
    ]
    a = sample_negative_windows(masked_actor="a2", events=events,
                                response_window_ms=100, max_per_silence=3, seed=42)
    b = sample_negative_windows(masked_actor="a2", events=events,
                                response_window_ms=100, max_per_silence=3, seed=42)
    assert [n.cut_time_ms for n in a] == [n.cut_time_ms for n in b]


def test_negative_own_message_not_opportunity() -> None:
    events = [make_event(event_id="m", time_ms=1_000, actor="a2")]
    negs = sample_negative_windows(
        masked_actor="a2", events=events, response_window_ms=5_000, max_per_silence=10,
    )
    assert negs == []


# ---- T011 sessionize ----
def test_sessionize_splits_on_inactivity() -> None:
    events = [
        make_event(event_id="e1", time_ms=0, actor="a1"),
        make_event(event_id="e2", time_ms=1_000, actor="a2"),
        # 31분 후 → 새 세션.
        make_event(event_id="e3", time_ms=1_000 + 31 * 60_000, actor="a1"),
    ]
    sessions = sessionize(events)
    assert len(sessions) == 2
    assert {e.event_id for e in sessions[0].events} == {"e1", "e2"}
    assert {e.event_id for e in sessions[1].events} == {"e3"}


def test_sessionize_splits_on_channel_move() -> None:
    events = [
        make_event(event_id="e1", time_ms=0, actor="a1", channel="chan-1"),
        make_event(event_id="e2", time_ms=1_000, actor="a1", channel="chan-2"),
    ]
    sessions = sessionize(events)
    assert len(sessions) == 2


def test_sessionize_splits_on_consent_change() -> None:
    events = [
        make_event(event_id="e1", time_ms=0, actor="a1", opt_in=True),
        make_event(event_id="e2", time_ms=1_000, actor="a1", opt_in=False),
    ]
    sessions = sessionize(events)
    assert len(sessions) == 2


def test_session_events_are_self_contained_no_future_leak() -> None:
    events = [
        make_event(event_id="e1", time_ms=0, actor="a1", channel="chan-1"),
        make_event(event_id="e2", time_ms=1_000, actor="a1", channel="chan-2"),
    ]
    sessions = sessionize(events)
    # 첫 세션은 미래(chan-2) 이벤트를 포함하지 않는다.
    assert all(e.channel_pseudonym == "chan-1" for e in sessions[0].events)
    assert sessions[0].end_ms < sessions[1].start_ms
