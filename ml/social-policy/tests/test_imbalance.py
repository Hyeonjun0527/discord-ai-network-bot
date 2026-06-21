"""T014 class imbalance 실험 테스트 — '모두 IGNORE' 퇴화 모델 탈락."""

from __future__ import annotations

import numpy as np

from nexa_policy.datasets import ACTION_HEAD_CLASSES, make_synthetic_dataset
from nexa_policy.training.imbalance import (
    class_weights_inverse,
    compare_imbalance_strategies,
    degenerate_all_ignore,
    undersample_indices,
)
from nexa_policy.training.splitting import make_split_indices

_IGNORE = ACTION_HEAD_CLASSES.index("ignore")
_SPEAK = ACTION_HEAD_CLASSES.index("speak")
_DS = make_synthetic_dataset(seed=7, n_guilds=12)
_SP = make_split_indices(_DS, seed=0)


def test_degenerate_all_ignore_detected() -> None:
    """모두 IGNORE 예측은 퇴화로 판정된다."""
    y_true = np.array([_IGNORE, _SPEAK, _SPEAK, _IGNORE])
    y_pred = np.array([_IGNORE, _IGNORE, _IGNORE, _IGNORE])  # 전부 IGNORE.
    assert degenerate_all_ignore(y_true, y_pred) is True


def test_non_degenerate_not_flagged() -> None:
    y_true = np.array([_IGNORE, _SPEAK, _SPEAK, _IGNORE])
    y_pred = np.array([_IGNORE, _SPEAK, _SPEAK, _IGNORE])  # SPEAK 맞춤.
    assert degenerate_all_ignore(y_true, y_pred) is False


def test_class_weights_inverse_upweights_minority() -> None:
    y = np.array([_IGNORE] * 90 + [_SPEAK] * 10)
    w = class_weights_inverse(y)
    assert w[y == _SPEAK].mean() > w[y == _IGNORE].mean()


def test_undersample_balances_classes() -> None:
    y = np.array([_IGNORE] * 90 + [_SPEAK] * 10)
    keep = undersample_indices(y, seed=1)
    yk = y[keep]
    assert int((yk == _IGNORE).sum()) == int((yk == _SPEAK).sum())


def test_undersample_deterministic() -> None:
    y = np.array([_IGNORE] * 50 + [_SPEAK] * 8)
    assert np.array_equal(undersample_indices(y, seed=2), undersample_indices(y, seed=2))


def test_comparison_disqualifies_degenerate_models() -> None:
    """accuracy 만 높고 모두 IGNORE 하는 모델을 탈락시킨다(acceptance T014)."""
    cmp = compare_imbalance_strategies(
        _DS, train_idx=_SP.train, test_idx=_SP.test, epochs=60, seed=3
    )
    assert cmp.strategies  # 모든 전략 보고됨.
    # 적어도 하나의 전략은 퇴화로 탈락(불균형 그대로 'none' 가 강한 후보).
    assert any(s.disqualified for s in cmp.strategies)
    # 자격 있는 최선 전략은 퇴화가 아니다.
    best = cmp.best_qualified()
    if best is not None:
        assert not best.disqualified
