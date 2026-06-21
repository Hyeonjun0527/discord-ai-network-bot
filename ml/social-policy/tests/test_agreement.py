"""T022 inter-annotator agreement 테스트 — kappa·disagreement matrix·gold 분류."""

from __future__ import annotations

import pytest

from nexa_policy.eval.agreement import (
    AgreementError,
    GoldDecision,
    cohen_kappa,
    disagreement_matrix,
    item_agreement,
    resolve_gold,
)


def test_kappa_perfect_agreement() -> None:
    assert cohen_kappa(["a", "b", "a"], ["a", "b", "a"]) == pytest.approx(1.0)


def test_kappa_chance_level_near_zero() -> None:
    # 두 annotator 가 독립적으로 50:50 → kappa ~ 0.
    a = ["x", "x", "y", "y"]
    b = ["x", "y", "x", "y"]
    assert cohen_kappa(a, b) == pytest.approx(0.0, abs=1e-9)


def test_kappa_single_class_treated_as_perfect() -> None:
    assert cohen_kappa(["a", "a"], ["a", "a"]) == 1.0


def test_kappa_length_mismatch_rejected() -> None:
    with pytest.raises(AgreementError):
        cohen_kappa(["a"], ["a", "b"])


def test_disagreement_matrix() -> None:
    m = disagreement_matrix(["a", "a", "b"], ["a", "b", "b"])
    assert m[("a", "a")] == 1
    assert m[("a", "b")] == 1
    assert m[("b", "b")] == 1


def test_item_agreement_majority() -> None:
    ia = item_agreement("item-1", ["speak", "speak", "ignore"])
    assert ia.majority_label == "speak"
    assert ia.agreement_ratio == pytest.approx(2 / 3)


def test_resolve_gold_high_agreement_is_gold() -> None:
    ia = item_agreement("i", ["speak", "speak", "speak", "speak", "ignore"])
    res = resolve_gold(ia, gold_threshold=0.8, soft_threshold=0.5)
    assert res.decision is GoldDecision.GOLD
    assert res.label == "speak"


def test_resolve_gold_low_agreement_excluded() -> None:
    # 3-way 분산(합의 1/3 < 0.5) → gold 에서 제외(acceptance).
    ia = item_agreement("i", ["speak", "ignore", "react"])
    res = resolve_gold(ia)
    assert res.decision is GoldDecision.EXCLUDE
    assert res.label is None


def test_resolve_gold_mid_agreement_soft() -> None:
    # 합의 0.6(0.5~0.8) → soft label 로 분포 유지.
    ia = item_agreement("i", ["speak", "speak", "speak", "ignore", "react"])
    res = resolve_gold(ia, gold_threshold=0.8, soft_threshold=0.5)
    assert res.decision is GoldDecision.SOFT
    assert res.distribution == {"speak": 3, "ignore": 1, "react": 1}
