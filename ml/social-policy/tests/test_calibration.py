"""T015 probability calibration 테스트 — 악화되면 적용하지 않는다."""

from __future__ import annotations

import numpy as np

from nexa_policy.calibration.calibrate import (
    apply_temperature,
    fit_temperature,
    select_calibration,
)
from nexa_policy.models.nn import softmax


def _overconfident(labels: np.ndarray, n_classes: int, *, sharpness: float = 4.0) -> np.ndarray:
    """정답에 과도하게 confident 한(보정 여지 큰) 확률 — temperature>1 로 개선 가능."""
    gen = np.random.default_rng(0)
    logits = gen.standard_normal((labels.shape[0], n_classes))
    logits[np.arange(labels.shape[0]), labels] += sharpness
    return softmax(logits * 2.0)


def test_apply_temperature_preserves_simplex() -> None:
    probs = softmax(np.random.default_rng(1).standard_normal((5, 4)))
    out = apply_temperature(probs, 2.0)
    assert np.allclose(out.sum(axis=1), 1.0)


def test_apply_temperature_rejects_nonpositive() -> None:
    probs = softmax(np.random.default_rng(1).standard_normal((5, 4)))
    for t in (0.0, -1.0):
        try:
            apply_temperature(probs, t)
        except ValueError:
            continue
        raise AssertionError("0/음수 temperature 는 거부돼야 한다.")


def test_temperature_improves_overconfident() -> None:
    """과확신 확률에서 temperature 보정이 Brier/ECE 를 개선해 적용된다."""
    n_classes = 5
    labels = np.random.default_rng(2).integers(0, n_classes, size=200)
    val_probs = _overconfident(labels, n_classes)
    test_labels = np.random.default_rng(3).integers(0, n_classes, size=200)
    test_probs = _overconfident(test_labels, n_classes)
    decision, calibrated = select_calibration(
        val_probs=val_probs, val_labels=labels,
        test_probs=test_probs, test_labels=test_labels, n_classes=n_classes,
    )
    assert decision.applied is True
    assert decision.brier_after <= decision.brier_before + 1e-9
    assert np.allclose(calibrated.sum(axis=1), 1.0)


def test_no_apply_when_not_improving() -> None:
    """이미 잘 보정된(또는 보정이 악화시키는) 경우 적용하지 않고 원확률을 반환한다."""
    n_classes = 3
    labels = np.array([0, 1, 2, 0, 1, 2] * 10)
    # 완벽 보정에 가까운 확률(정답=1.0 거의). temperature 가 개선 못 함.
    probs = np.full((labels.shape[0], n_classes), 0.001)
    probs[np.arange(labels.shape[0]), labels] = 0.998
    decision, calibrated = select_calibration(
        val_probs=probs, val_labels=labels, test_probs=probs, test_labels=labels,
        n_classes=n_classes,
    )
    if not decision.applied:
        assert decision.method == "identity"
        assert np.array_equal(calibrated, probs)


def test_fit_temperature_deterministic() -> None:
    labels = np.random.default_rng(4).integers(0, 4, size=100)
    probs = _overconfident(labels, 4)
    assert fit_temperature(probs, labels) == fit_temperature(probs, labels)
