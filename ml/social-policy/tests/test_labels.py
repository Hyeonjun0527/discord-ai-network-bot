"""T005~T009 라벨 생성기 테스트."""

from __future__ import annotations

from nexa_policy.data.labels.action import ActionClass, label_action
from nexa_policy.data.labels.burst import label_burst_shape
from nexa_policy.data.labels.delay import label_delay
from nexa_policy.data.labels.social_act import (
    SocialActCode,
    WeakSocialActLabel,
    label_social_act,
)
from nexa_policy.data.labels.target import TargetKind, label_target
from tests.conftest import make_event


# ---- T005 action ----
def test_action_speak_when_actor_replies() -> None:
    events = [make_event(event_id="e2", time_ms=3_000, actor="actor-2", kind="reply")]
    label = label_action(
        masked_actor="actor-2", cut_time_ms=1_000, window_ms=10_000,
        events=events, is_observable=True,
    )
    assert label.action is ActionClass.SPEAK
    assert label.is_masked is False


def test_action_ignore_when_silent_but_observable() -> None:
    events = [make_event(event_id="e1", time_ms=3_000, actor="other")]
    label = label_action(
        masked_actor="actor-2", cut_time_ms=1_000, window_ms=10_000,
        events=events, is_observable=True,
    )
    assert label.action is ActionClass.IGNORE


def test_action_unknown_when_unobservable_not_forced_to_ignore() -> None:
    label = label_action(
        masked_actor="actor-2", cut_time_ms=1_000, window_ms=10_000,
        events=[], is_observable=False,
    )
    assert label.action is ActionClass.UNKNOWN
    assert label.is_masked is True


def test_action_react_when_only_reaction() -> None:
    events = [make_event(event_id="r", time_ms=2_000, actor="actor-2", kind="reaction")]
    label = label_action(
        masked_actor="actor-2", cut_time_ms=1_000, window_ms=10_000,
        events=events, is_observable=True,
    )
    assert label.action is ActionClass.REACT


# ---- T006 target ----
def test_target_reply_signal() -> None:
    action = make_event(event_id="e2", time_ms=3_000, actor="a2", kind="reply",
                        features={"reply_to_event_id": "e1"})
    label = label_target(action_event=action, prior_events=[])
    assert any(t.kind is TargetKind.MESSAGE and t.signal == "reply" for t in label.targets)
    assert not label.is_none


def test_target_none_when_no_signal() -> None:
    action = make_event(event_id="e2", time_ms=3_000, actor="a2", features={})
    label = label_target(action_event=action, prior_events=[])
    assert label.is_none


def test_target_multiple_candidates() -> None:
    action = make_event(
        event_id="e2", time_ms=3_000, actor="a2", kind="reply", thread="thread-1",
        features={"reply_to_event_id": "e1", "mention_target_pseudonym": "a3"},
    )
    label = label_target(action_event=action, prior_events=[])
    kinds = {t.kind for t in label.targets}
    assert TargetKind.MESSAGE in kinds
    assert TargetKind.MEMBER in kinds
    assert TargetKind.THREAD in kinds
    assert len(label.targets) >= 2


def test_target_adjacency_member() -> None:
    prior = [make_event(event_id="p1", time_ms=2_500, actor="a-other")]
    action = make_event(event_id="e2", time_ms=3_000, actor="a2", features={})
    label = label_target(action_event=action, prior_events=prior, adjacency_ms=10_000)
    assert any(t.signal == "adjacency" and t.ref_pseudonym == "a-other" for t in label.targets)


# ---- T007 delay ----
def test_delay_measured_when_action() -> None:
    events = [make_event(event_id="e", time_ms=2_500, actor="a2")]
    label = label_delay(
        masked_actor="a2", cut_time_ms=1_000, events=events,
        session_end_ms=10_000, observed_full_window=False,
    )
    assert label.delay_ms == 1_500
    assert label.censored is False
    assert label.is_never is False


def test_delay_session_end_is_censored_not_never() -> None:
    label = label_delay(
        masked_actor="a2", cut_time_ms=1_000, events=[],
        session_end_ms=5_000, observed_full_window=False,
    )
    assert label.censored is True
    assert label.is_never is False


def test_delay_true_never_only_with_full_window() -> None:
    label = label_delay(
        masked_actor="a2", cut_time_ms=1_000, events=[],
        session_end_ms=5_000, observed_full_window=True,
    )
    assert label.is_never is True
    assert label.censored is False


# ---- T008 burst ----
def test_burst_groups_consecutive_same_actor() -> None:
    events = [
        make_event(event_id="b1", time_ms=2_000, actor="a2", features={"char_len_bucket": 2}),
        make_event(event_id="b2", time_ms=3_000, actor="a2", features={"char_len_bucket": 1}),
        make_event(event_id="b3", time_ms=4_000, actor="a2", kind="reaction"),
    ]
    shape = label_burst_shape(masked_actor="a2", cut_time_ms=1_000, events=events)
    assert shape is not None
    assert shape.message_count == 2
    assert shape.total_char_len_bucket == 3
    assert shape.has_reaction is True
    assert shape.gaps_ms == (1_000,)


def test_burst_ends_on_speaker_change() -> None:
    events = [
        make_event(event_id="b1", time_ms=2_000, actor="a2", features={"char_len_bucket": 1}),
        make_event(event_id="x", time_ms=2_500, actor="other"),
        make_event(event_id="b2", time_ms=3_000, actor="a2", features={"char_len_bucket": 1}),
    ]
    shape = label_burst_shape(masked_actor="a2", cut_time_ms=1_000, events=events)
    assert shape is not None
    assert shape.message_count == 1


def test_burst_none_when_no_response() -> None:
    events = [make_event(event_id="x", time_ms=2_000, actor="other")]
    assert label_burst_shape(masked_actor="a2", cut_time_ms=1_000, events=events) is None


# ---- T009 social act ----
def test_social_act_is_always_weak_with_version() -> None:
    action = make_event(event_id="e", time_ms=2_000, actor="a2", kind="reaction")
    label = label_social_act(action_event=action)
    assert label.is_weak is True
    assert label.act is SocialActCode.ACKNOWLEDGE
    assert label.model_version
    assert 0.0 <= label.confidence <= 1.0


def test_social_act_question_lexical() -> None:
    action = make_event(event_id="e", time_ms=2_000, actor="a2", features={"is_question": True})
    label = label_social_act(action_event=action)
    assert label.act is SocialActCode.ASK


def test_social_act_unknown_normalizes_free_text() -> None:
    assert SocialActCode.from_wire("totally_made_up") is SocialActCode.UNKNOWN


def test_social_act_glm_assist_records_model_version() -> None:
    class FakeGlm:
        model_version = "glm-test-1"

        def classify(self, signals: dict[str, object]) -> tuple[str, float]:
            return ("disagree", 0.9)

    action = make_event(event_id="e", time_ms=2_000, actor="a2", kind="reply",
                        features={"reply_to_event_id": "e1"})
    label = label_social_act(action_event=action, glm=FakeGlm())
    assert label.source == "glm"
    assert label.model_version == "glm-test-1"
    assert label.act is SocialActCode.DISAGREE


def test_weak_label_rejects_non_weak() -> None:
    import pytest

    with pytest.raises(ValueError):
        WeakSocialActLabel(SocialActCode.ASK, 0.5, "rule", "v1", is_weak=False)
