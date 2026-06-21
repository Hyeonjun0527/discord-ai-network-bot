"""T022 서버 간 일반화 분석 테스트 — 부분군 성능·최악군·평균↑/붕괴 식별(결정론)."""

from __future__ import annotations

import numpy as np
import pytest

from nexa_policy.datasets import ACTION_HEAD_CLASSES, make_synthetic_dataset
from nexa_policy.eval.generalization import (
    SubgroupPerformance,
    derive_subgroups,
    detect_collapse,
    evaluate_subgroups,
    worst_subgroup,
)
from nexa_policy.training.splitting import make_split_indices
from nexa_policy.training.trainer import design_matrix, train_multihead

_IGNORE = ACTION_HEAD_CLASSES.index("ignore")
_SPEAK = ACTION_HEAD_CLASSES.index("speak")


def _trained_predictions():  # type: ignore[no-untyped-def]
    ds = make_synthetic_dataset(seed=7, n_guilds=12)
    sp = make_split_indices(ds, seed=0)
    res = train_multihead(ds, train_idx=sp.train, val_idx=sp.validation, epochs=40, seed=3)
    x = design_matrix(ds, sp.test).astype(np.float32)
    y_true = ds.action_labels[sp.test]
    y_pred = np.argmax(res.model.action_proba(x), axis=1)
    subgroups = derive_subgroups([ds.guild_ids[i] for i in sp.test], axis="size")
    return subgroups, y_true, y_pred


def test_derive_subgroups_is_deterministic() -> None:
    ids = ["guild-0", "guild-7", "guild-2", "guild-7"]
    a = derive_subgroups(ids)
    b = derive_subgroups(ids)
    assert a == b
    # 같은 길드는 같은 그룹.
    assert a[1] == a[3]


def test_evaluate_subgroups_covers_all_groups() -> None:
    subgroups, y_true, y_pred = _trained_predictions()
    perfs = evaluate_subgroups(subgroups=subgroups, y_true=y_true, y_pred=y_pred)
    assert {p.subgroup for p in perfs} == set(subgroups)
    for p in perfs:
        assert 0.0 <= p.balanced_accuracy <= 1.0
        assert 0.0 <= p.false_ignore_rate <= 1.0


def test_evaluate_subgroups_length_guard() -> None:
    with pytest.raises(ValueError):
        evaluate_subgroups(
            subgroups=["a", "b"], y_true=np.array([0]), y_pred=np.array([0])
        )


def test_worst_subgroup_picks_lowest_accuracy() -> None:
    perfs = [
        SubgroupPerformance("size-0", 10, 0.9, 0.1),
        SubgroupPerformance("size-1", 10, 0.3, 0.5),
        SubgroupPerformance("size-2", 10, 0.6, 0.2),
    ]
    assert worst_subgroup(perfs).subgroup == "size-1"


def test_detect_collapse_flags_deceptive_model() -> None:
    """평균은 baseline 이상이나 한 부분군이 floor 미만 → is_deceptive True."""
    perfs = [
        SubgroupPerformance("size-0", 50, 0.85, 0.1),
        SubgroupPerformance("size-1", 50, 0.20, 0.7),  # 붕괴.
    ]
    verdict = detect_collapse(perfs, baseline_mean=0.5, collapse_floor=0.4)
    assert verdict.improves_on_average is True
    assert verdict.collapses_on_subgroup is True
    assert verdict.is_deceptive is True


def test_detect_collapse_healthy_model_not_deceptive() -> None:
    perfs = [
        SubgroupPerformance("size-0", 50, 0.7, 0.1),
        SubgroupPerformance("size-1", 50, 0.65, 0.15),
    ]
    verdict = detect_collapse(perfs, baseline_mean=0.5, collapse_floor=0.4)
    assert verdict.is_deceptive is False


def test_detect_collapse_empty_rejected() -> None:
    with pytest.raises(ValueError):
        detect_collapse([], baseline_mean=0.5, collapse_floor=0.4)
