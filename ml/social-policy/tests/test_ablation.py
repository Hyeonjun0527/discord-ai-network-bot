"""T023 feature ablation 분석 테스트 — 군별 기여도·민감/무기여 군 제거 권고(결정론)."""

from __future__ import annotations

import pytest

from nexa_policy.datasets import make_synthetic_dataset
from nexa_policy.eval.ablation import (
    FEATURE_GROUPS,
    AblationResult,
    ablate_groups,
    recommend_removals,
)
from nexa_policy.training.splitting import make_split_indices

_DS = make_synthetic_dataset(seed=7, n_guilds=12)
_SP = make_split_indices(_DS, seed=0)


def test_ablation_covers_all_feature_groups() -> None:
    results = ablate_groups(_DS, _SP, epochs=20, seed=3)
    assert {r.group for r in results} == set(FEATURE_GROUPS)


def test_ablation_is_deterministic() -> None:
    a = ablate_groups(_DS, _SP, epochs=15, seed=3)
    b = ablate_groups(_DS, _SP, epochs=15, seed=3)
    assert [(r.group, r.contribution) for r in a] == [(r.group, r.contribution) for r in b]


def test_full_score_is_shared_baseline() -> None:
    """모든 군 결과가 같은 full 모델 점수를 공유한다(기여도는 그 baseline 대비 하락폭으로 정의)."""
    results = ablate_groups(_DS, _SP, epochs=20, seed=3)
    full_scores = {r.full_score for r in results}
    assert len(full_scores) == 1, "full 점수는 군마다 같아야 한다(같은 full 모델)."
    # 기여도 = full - ablated 가 정확히 계산된다.
    for r in results:
        assert r.contribution == pytest.approx(r.full_score - r.ablated_score)


def test_recommend_removals_targets_sensitive_no_contribution() -> None:
    """기여 없는데 민감한 군만 제거 후보(민감하지만 기여 큰 군·기여 없지만 비민감 군은 제외)."""
    results = [
        AblationResult("burst", full_score=0.7, ablated_score=0.4),  # 기여 큼·비민감.
        AblationResult("relationship", full_score=0.7, ablated_score=0.70),  # 무기여·민감 → 후보.
        AblationResult("memory", full_score=0.7, ablated_score=0.695),  # 무기여·민감 → 후보.
        AblationResult("scene", full_score=0.7, ablated_score=0.70),  # 무기여·비민감 → 제외.
        AblationResult("saturation", full_score=0.7, ablated_score=0.70),  # 무기여·비민감 → 제외.
    ]
    assert recommend_removals(results, min_contribution=0.01) == ["memory", "relationship"]


def test_recommend_keeps_contributing_sensitive_group() -> None:
    """민감해도 기여가 크면 제거하지 않는다."""
    results = [AblationResult("relationship", full_score=0.7, ablated_score=0.3)]
    assert recommend_removals(results, min_contribution=0.01) == []
