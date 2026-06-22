"""NEXA-P19-T014: behavior cloning v2 — 장기 state+timing 추가, v1 대비 이득·비용 비교."""

from __future__ import annotations

import numpy as np

from nexa_policy.datasets import make_synthetic_dataset
from nexa_policy.models.mlp import _design
from nexa_policy.models.policy_v2 import (
    N_LONG_CHANNELS,
    BcComparison,
    augment_long_state,
    compare_v1_v2,
    train_bc_v2,
)
from nexa_policy.training.splitting import make_split_indices


def _ds_and_split():
    ds = make_synthetic_dataset(n_samples=300, n_guilds=10, seed=7)
    split = make_split_indices(ds, seed=0)
    return ds, split


def test_augment_adds_long_state_and_timing_channels():
    ds, split = _ds_and_split()
    base = _design(ds, split.train)
    aug = augment_long_state(ds, split.train)
    # v2 입력은 현재 feature + 장기 state/timing 채널이다.
    assert aug.shape[1] == base.shape[1] + N_LONG_CHANNELS
    assert aug.shape[0] == split.train.size


def test_augment_is_deterministic():
    ds, split = _ds_and_split()
    a = augment_long_state(ds, split.test)
    b = augment_long_state(ds, split.test)
    assert np.array_equal(a, b)


def test_timing_channel_in_unit_range():
    ds, split = _ds_and_split()
    aug = augment_long_state(ds, split.train)
    timing = aug[:, -1]  # since_last_action_bin.
    assert float(timing.min()) >= 0.0
    assert float(timing.max()) <= 1.0


def test_train_bc_v2_reports_gain_and_cost():
    ds, split = _ds_and_split()
    _, report = train_bc_v2(ds, train_idx=split.train, test_idx=split.test)
    d = report.to_dict()
    # 이득(지표)과 비용(param·latency)을 함께 보고한다.
    assert d["model"] == "bc_v2"
    assert 0.0 <= float(d["test_balanced_accuracy"]) <= 1.0
    assert int(d["param_count"]) > 0
    assert float(d["inference_seconds_per_sample"]) >= 0.0


def test_acceptance_v1_v2_holdout_comparison_has_gain_and_cost():
    ds, split = _ds_and_split()
    cmp = compare_v1_v2(
        ds, train_idx=split.train, val_idx=split.validation, test_idx=split.test
    )
    assert isinstance(cmp, BcComparison)
    d = cmp.to_dict()
    # acceptance: 같은 holdout 에서 v1·v2 의 이득과 비용이 한 표에 있다.
    assert "v1" in d and "v2" in d
    assert "balanced_accuracy_gain" in d
    # v2 는 장기 state+timing 으로 입력 차원이 커 param 비용이 v1 이상이다(비용 숨김 없음).
    assert cmp.param_cost_ratio >= 1.0
    # 두 모델 모두 같은 지표 어휘로 비교된다.
    assert set(["test_balanced_accuracy", "param_count"]).issubset(cmp.v1.keys())
    assert set(["test_balanced_accuracy", "param_count"]).issubset(cmp.v2.keys())
