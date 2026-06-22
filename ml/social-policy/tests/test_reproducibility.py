"""T001 재현성·환경 캡처 테스트 — seed 결정론, metric 변동 허용 범위."""

from __future__ import annotations

import numpy as np

from nexa_policy.baselines.policy_baselines import (
    AlwaysSilentBaseline,
    evaluate_baseline,
)
from nexa_policy.datasets import make_synthetic_dataset
from nexa_policy.reproducibility import (
    DEFAULT_SEED,
    ReproducibilityError,
    capture_environment,
    rng,
    seed_everything,
)


def test_seed_everything_returns_seed() -> None:
    assert seed_everything(123) == 123


def test_negative_seed_rejected() -> None:
    try:
        seed_everything(-1)
    except ReproducibilityError:
        return
    raise AssertionError("음수 seed 는 거부돼야 한다.")


def test_rng_deterministic_same_seed() -> None:
    a = rng(7).random(10)
    b = rng(7).random(10)
    assert np.array_equal(a, b)


def test_rng_differs_on_different_seed() -> None:
    assert not np.array_equal(rng(1).random(10), rng(2).random(10))


def test_same_dataset_config_same_metric() -> None:
    """동일 dataset/config 에서 핵심 metric 변동이 0(완전 결정론, 허용 범위 안)."""
    ds_a = make_synthetic_dataset(seed=DEFAULT_SEED)
    ds_b = make_synthetic_dataset(seed=DEFAULT_SEED)
    rep_a = evaluate_baseline(
        AlwaysSilentBaseline(), action_labels=ds_a.action_labels,
        action_mask=ds_a.action_mask, seed=DEFAULT_SEED,
    )
    rep_b = evaluate_baseline(
        AlwaysSilentBaseline(), action_labels=ds_b.action_labels,
        action_mask=ds_b.action_mask, seed=DEFAULT_SEED,
    )
    assert rep_a.to_dict() == rep_b.to_dict()


def test_environment_capture_records_libraries() -> None:
    env = capture_environment(seed=5)
    assert env.seed == 5
    assert "numpy" in env.libraries
    # numpy 는 ML 의존이라 설치되어 버전이 있어야 한다.
    assert env.libraries["numpy"] is not None
