"""T003 baseline 재현 테스트 — always-silent·fixed-probability 결정론 점수."""

from __future__ import annotations

import pytest

from nexa_policy.baselines.policy_baselines import (
    AlwaysSilentBaseline,
    FixedProbabilityBaseline,
    evaluate_baseline,
)
from nexa_policy.datasets import make_synthetic_dataset


def test_always_silent_catches_all_speak() -> None:
    """항상 IGNORE → 모든 SPEAK 를 놓친다(FIR=1, MIR=1). 바닥선 정의."""
    ds = make_synthetic_dataset(seed=11)
    rep = evaluate_baseline(
        AlwaysSilentBaseline(), action_labels=ds.action_labels,
        action_mask=ds.action_mask, seed=11,
    )
    assert rep.false_ignore_rate == pytest.approx(1.0)
    assert rep.missed_interaction_rate == pytest.approx(1.0)


def test_baseline_reproducible_same_split() -> None:
    ds = make_synthetic_dataset(seed=11)
    a = evaluate_baseline(AlwaysSilentBaseline(), action_labels=ds.action_labels,
                          action_mask=ds.action_mask, seed=11)
    b = evaluate_baseline(AlwaysSilentBaseline(), action_labels=ds.action_labels,
                          action_mask=ds.action_mask, seed=11)
    assert a.to_dict() == b.to_dict()


def test_unknown_masked_excluded() -> None:
    """UNKNOWN(mask=0)은 평가에서 제외(강제 IGNORE 금지)."""
    ds = make_synthetic_dataset(seed=11, unknown_action_frac=0.3)
    rep = evaluate_baseline(AlwaysSilentBaseline(), action_labels=ds.action_labels,
                            action_mask=ds.action_mask, seed=11)
    n_observable = int((ds.action_mask > 0).sum())
    assert rep.n_evaluated == n_observable
    assert rep.n_evaluated < ds.n


def test_fixed_probability_from_marginal_sums_to_one() -> None:
    ds = make_synthetic_dataset(seed=11)
    base = FixedProbabilityBaseline.from_marginal(ds.action_labels, ds.action_mask)
    assert sum(base.distribution) == pytest.approx(1.0)


def test_fixed_probability_invalid_distribution_rejected() -> None:
    with pytest.raises(ValueError):
        FixedProbabilityBaseline(distribution=(0.5, 0.5, 0.0, 0.0))  # 길이 불일치.
    with pytest.raises(ValueError):
        FixedProbabilityBaseline(distribution=(0.5, 0.5, 0.5, 0.5, 0.5))  # 합 != 1.


def test_baseline_notes_for_shadow_diff() -> None:
    """shadow 차이를 notes 로 명시할 수 있다(조용한 불일치 금지)."""
    ds = make_synthetic_dataset(seed=11)
    rep = evaluate_baseline(
        AlwaysSilentBaseline(), action_labels=ds.action_labels, action_mask=ds.action_mask,
        seed=11, notes=["Kotlin shadow 와 동일: 둘 다 학습 없는 정의 기반 baseline."],
    )
    assert rep.notes
