"""T004 logistic·T005 tree·T006 mlp·T007 temporal encoder 테스트."""

from __future__ import annotations

import numpy as np

from nexa_policy.datasets import make_synthetic_dataset
from nexa_policy.models.logistic import train_logistic
from nexa_policy.models.mlp import train_mlp_action
from nexa_policy.models.temporal_encoder import (
    GruEncoder,
    MeanPoolEncoder,
    compare_encoders,
    truncation_impact,
)
from nexa_policy.models.tree import train_tree
from nexa_policy.training.splitting import make_split_indices

_DS = make_synthetic_dataset(seed=7, n_guilds=12)
_SP = make_split_indices(_DS, seed=0)


# ---- T004 logistic ----
def test_logistic_saves_val_test_metrics() -> None:
    _, report = train_logistic(
        _DS, train_idx=_SP.train, val_idx=_SP.validation, test_idx=_SP.test, seed=3
    )
    d = report.to_dict()
    for split in ("validation", "test"):
        m = d[split]
        assert {"balanced_accuracy", "false_ignore_rate", "missed_interaction_rate",
                "brier_score"} <= set(m)
    assert report.coefficients  # 해석 가능한 계수.


def test_logistic_deterministic() -> None:
    m1, r1 = train_logistic(_DS, train_idx=_SP.train, val_idx=_SP.validation,
                            test_idx=_SP.test, seed=3)
    m2, r2 = train_logistic(_DS, train_idx=_SP.train, val_idx=_SP.validation,
                            test_idx=_SP.test, seed=3)
    assert r1.to_dict() == r2.to_dict()


# ---- T005 tree ----
def test_tree_reports_overfit_gap_and_importance() -> None:
    _, report = train_tree(_DS, train_idx=_SP.train, val_idx=_SP.validation,
                           test_idx=_SP.test, seed=3)
    d = report.to_dict()
    assert "overfit_gap" in d
    assert "train_balanced_accuracy" in d
    assert isinstance(d["feature_importance"], dict)
    # 과적합 gap = train - test(둘 다 보고됨).
    assert report.overfit_gap == report.train_balanced_accuracy - report.test.balanced_accuracy


# ---- T006 mlp ----
def test_mlp_reports_param_count_and_latency() -> None:
    model, report = train_mlp_action(
        _DS, train_idx=_SP.train, val_idx=_SP.validation, test_idx=_SP.test, epochs=60, seed=3
    )
    assert report.param_count == model.param_count
    assert report.param_count > 0
    assert report.inference_seconds_per_sample >= 0.0
    # baseline 과 같은 지표가 들어있다(비교 가능).
    d = report.to_dict()
    assert {"test_balanced_accuracy", "test_false_ignore_rate", "test_brier_score"} <= set(d)


def test_mlp_deterministic() -> None:
    _, r1 = train_mlp_action(_DS, train_idx=_SP.train, val_idx=_SP.validation,
                             test_idx=_SP.test, epochs=60, seed=3)
    _, r2 = train_mlp_action(_DS, train_idx=_SP.train, val_idx=_SP.validation,
                             test_idx=_SP.test, epochs=60, seed=3)
    # latency(wall-clock)는 비결정적이므로 제외하고 학습 결과(메트릭·파라미터)만 비교.
    d1, d2 = r1.to_dict(), r2.to_dict()
    d1.pop("inference_seconds_per_sample")
    d2.pop("inference_seconds_per_sample")
    assert d1 == d2


# ---- T007 temporal encoder ----
def _seq(n: int = 6, f: int = 4, seed: int = 1) -> np.ndarray:
    return np.random.default_rng(seed).random((n, f))


def test_encoder_comparison_reports_rationale() -> None:
    cmp = compare_encoders(_seq(), seed=3)
    d = cmp.to_dict()
    assert d["recommended"] in ("gru", "mean_pool")
    assert d["rationale"]
    assert d["gru_param_count"] > 0 and d["meanpool_param_count"] > 0


def test_gru_more_order_sensitive_than_meanpool() -> None:
    """GRU 는 순서 의존이라 시퀀스 뒤집기에 더 민감(mean-pool 은 평균이라 불변)."""
    cmp = compare_encoders(_seq(seed=9), seed=3)
    assert cmp.gru_gap_sensitivity >= cmp.meanpool_gap_sensitivity


def test_truncation_impact_reported() -> None:
    seq = _seq(n=8, seed=4)
    gru = GruEncoder.build(in_dim=seq.shape[1], seed=3)
    pool = MeanPoolEncoder.build(in_dim=seq.shape[1], seed=3)
    # 더 많이 자를수록(keep_last 작을수록) 변화가 0 이상.
    assert truncation_impact(gru, seq, keep_last=2) >= 0.0
    assert truncation_impact(pool, seq, keep_last=8) == 0.0  # 안 자르면 변화 0.


def test_encoder_deterministic() -> None:
    seq = _seq(seed=2)
    a = GruEncoder.build(in_dim=seq.shape[1], seed=3).encode(seq)
    b = GruEncoder.build(in_dim=seq.shape[1], seed=3).encode(seq)
    assert np.allclose(a, b)
