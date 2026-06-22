"""T020 시나리오 평가 metric aggregator 테스트 — 모든 원 metric·confidence 보존."""

from __future__ import annotations

import pytest

from nexa_policy.eval.scenario_metrics import (
    DecisionRecord,
    HumanLabel,
    ScenarioMetricError,
    aggregate_scenario,
    records_from_artifact,
)


def _speak(target: str) -> DecisionRecord:
    return DecisionRecord(action="speak", delay_bucket="IMMEDIATE", target_message_id=target)


def _react(target: str) -> DecisionRecord:
    return DecisionRecord(action="react", delay_bucket="SHORT", target_message_id=target)


def _ignore() -> DecisionRecord:
    return DecisionRecord(action="ignore", delay_bucket="NEVER", target_message_id=None)


def _cancel(target: str) -> DecisionRecord:
    return DecisionRecord(action="cancel_pending", delay_bucket="IMMEDIATE", target_message_id=target)


def test_perfect_speak_match_low_penalty() -> None:
    m = aggregate_scenario(
        scenario_id="s",
        decisions=[_speak("m-1")],
        human_labels=[HumanLabel("m-1", "SPEAK")],
        human_message_count=1,
    )
    assert m.fir == 0.0
    assert m.mir == 0.0
    assert m.brier == 0.0
    assert m.composite_score == pytest.approx(1.0)


def test_over_conservative_ignore_raises_fir() -> None:
    # 사람은 SPEAK 기대했는데 NEXA 가 한 번도 발화 안 함 → FIR=1.0.
    m = aggregate_scenario(
        scenario_id="s",
        decisions=[_ignore()],
        human_labels=[HumanLabel("m-1", "SPEAK")],
        human_message_count=1,
    )
    assert m.fir == 1.0
    assert m.brier == pytest.approx(1.0)
    assert m.composite_score < 1.0


def test_mir_counts_missed_interactions() -> None:
    # 두 메시지에 상호작용 기대, NEXA 는 m-1 만 reaction → MIR=0.5.
    m = aggregate_scenario(
        scenario_id="s",
        decisions=[_react("m-1")],
        human_labels=[HumanLabel("m-1", "REACT"), HumanLabel("m-2", "REACT")],
        human_message_count=2,
    )
    assert m.mir == pytest.approx(0.5)


def test_dominance_reflects_share() -> None:
    # speak 3, 사람 메시지 1 → 점유율 0.75.
    m = aggregate_scenario(
        scenario_id="s",
        decisions=[_speak("m-1"), _speak("m-2"), _speak("m-3")],
        human_labels=[],
        human_message_count=1,
    )
    assert m.dominance == pytest.approx(0.75)


def test_cross_thread_and_cancel_counted() -> None:
    unprompted = DecisionRecord(action="speak", delay_bucket="IMMEDIATE", target_message_id=None)
    m = aggregate_scenario(
        scenario_id="s",
        decisions=[unprompted, _cancel("m-1")],
        human_labels=[],
        human_message_count=2,
    )
    assert m.cross_thread_count == 1
    assert m.cancel_count == 1
    # 안전 위반(unprompted/cross-thread)은 composite 을 강하게 깎는다.
    assert m.composite_score == pytest.approx(0.0)


def test_delay_distribution_sums_to_one() -> None:
    m = aggregate_scenario(
        scenario_id="s",
        decisions=[_speak("m-1"), _react("m-2")],
        human_labels=[],
        human_message_count=2,
    )
    assert sum(m.delay_distribution.values()) == pytest.approx(1.0)
    assert m.delay_distribution["IMMEDIATE"] == pytest.approx(0.5)
    assert m.delay_distribution["SHORT"] == pytest.approx(0.5)


def test_confidence_monotonic_in_sample_size() -> None:
    small = aggregate_scenario(
        scenario_id="s",
        decisions=[_speak("m-1")],
        human_labels=[HumanLabel("m-1", "SPEAK")],
        human_message_count=1,
    )
    big = aggregate_scenario(
        scenario_id="s",
        decisions=[_speak(f"m-{i}") for i in range(20)],
        human_labels=[HumanLabel(f"m-{i}", "SPEAK") for i in range(20)],
        human_message_count=20,
    )
    assert big.confidence > small.confidence
    assert 0.0 < small.confidence < 1.0


def test_raw_preserves_all_metrics() -> None:
    m = aggregate_scenario(
        scenario_id="s",
        decisions=[_speak("m-1"), _react("m-2"), _cancel("m-3")],
        human_labels=[HumanLabel("m-1", "SPEAK")],
        human_message_count=2,
    )
    for key in ("fir", "mir", "brier", "dominance", "stale_memory_count", "cross_thread_count",
                "cancel_count", "speak_count", "react_count"):
        assert key in m.raw


def test_negative_human_message_count_rejected() -> None:
    with pytest.raises(ScenarioMetricError):
        aggregate_scenario(
            scenario_id="s", decisions=[], human_labels=[], human_message_count=-1
        )


def test_records_from_artifact_roundtrip() -> None:
    artifact = {
        "scenarioId": "s",
        "decisions": [
            {"action": "speak", "delayBucket": "IMMEDIATE", "targetMessageId": "m-1",
             "reason": "fired speak on latest revision (rev=0)"},
            {"action": "ignore", "delayBucket": "NEVER", "targetMessageId": None, "reason": "silent"},
        ],
    }
    recs = records_from_artifact(artifact)
    assert len(recs) == 2
    assert recs[0].action == "speak"
    assert recs[0].target_message_id == "m-1"
    assert recs[0].on_stale_target is False


def test_records_from_artifact_flags_stale_speak() -> None:
    artifact = {
        "decisions": [
            {"action": "speak", "delayBucket": "IMMEDIATE", "targetMessageId": "m-1",
             "reason": "spoke on stale deleted target"},
        ],
    }
    recs = records_from_artifact(artifact)
    assert recs[0].on_stale_target is True
    m = aggregate_scenario(
        scenario_id="s", decisions=recs, human_labels=[], human_message_count=1
    )
    assert m.stale_memory_count == 1
    assert m.composite_score == pytest.approx(0.0)


def test_records_from_artifact_rejects_bad_shape() -> None:
    with pytest.raises(ScenarioMetricError):
        records_from_artifact({"decisions": "nope"})
