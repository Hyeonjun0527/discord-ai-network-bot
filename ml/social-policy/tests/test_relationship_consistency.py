"""NEXA-P19-T003: 장기 relationship consistency — 관찰 신호만 쓰고 갑작스런 친밀도 점프를 탐지한다."""

from __future__ import annotations

from nexa_policy.eval.relationship_consistency import (
    RelationshipObservation,
    alignment_rate,
    evaluate_relationship_consistency,
    unexplained_jumps,
)


def _obs(fam, bursts=0, reactions=0):
    return RelationshipObservation(
        familiarity=fam, exchanged_bursts_delta=bursts, observed_reactions_delta=reactions
    )


def test_acceptance_상승은_실제_상호작용으로_설명돼야_정합():
    # 친밀도 상승 + 관찰 상호작용 있음 → 정합.
    aligned = [_obs(0.2, bursts=3), _obs(0.5, bursts=5)]
    assert alignment_rate(aligned) == 1.0
    # 친밀도 상승인데 관찰 상호작용 0 → 불일치.
    misaligned = [_obs(0.2), _obs(0.5)]
    assert alignment_rate(misaligned) == 0.0


def test_갑작스런_친밀도_점프_탐지():
    obs = [_obs(0.1), _obs(0.6)]  # 0.5 점프, 상호작용 0 → 설명 안 됨.
    flagged = unexplained_jumps(obs, jump_threshold=0.3)
    assert flagged == [1]


def test_상호작용으로_설명되는_점프는_플래그_안됨():
    obs = [_obs(0.1), _obs(0.6, bursts=10)]
    assert unexplained_jumps(obs, jump_threshold=0.3) == []


def test_하락은_감쇠로_설명_가능_정합():
    obs = [_obs(0.8, bursts=2), _obs(0.5)]  # 하락(무상호작용) → 정합.
    assert alignment_rate(obs) == 1.0


def test_심리정답_불요_관찰신호만_입력():
    # RelationshipObservation 에는 심리 라벨 필드가 없다(관찰 delta 만).
    o = _obs(0.5, bursts=1, reactions=2)
    assert o.interaction_volume == 3


def test_consistency_report():
    obs = [_obs(0.2, bursts=2), _obs(0.4, bursts=3), _obs(0.3)]
    report = evaluate_relationship_consistency(obs, jump_threshold=0.3)
    assert report.n_observations == 3
    assert report.unexplained_jump_count == 0
    assert report.is_consistent(min_alignment=0.9)


def test_빈_시퀀스_정합():
    report = evaluate_relationship_consistency([])
    assert report.alignment_rate == 1.0
    assert report.is_consistent(min_alignment=1.0)
