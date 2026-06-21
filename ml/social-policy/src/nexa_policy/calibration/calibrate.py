"""probability calibration(NEXA-P11-T015).

validation set 에서 temperature scaling(다중클래스 logits) 또는 isotonic(이진 SPEAK 확률)을 학습한다.
P09 calibration(EXP-talkativeness 의 SPEAK logit 보정)과 일관되게 logit 공간에서 보정한다.

**acceptance(T015) — test Brier/ECE 가 악화되면 적용하지 않는다**:
- [fit_temperature] 는 validation NLL 을 최소화하는 단일 T(>0)를 결정론 1D 탐색으로 찾는다.
- [select_calibration] 은 보정 전/후 test Brier·ECE 를 비교해 **개선될 때만** 적용한다(악화 시 identity).
  결과 [CalibrationDecision.applied] 가 False 면 원확률을 그대로 쓴다.
"""

from __future__ import annotations

from dataclasses import dataclass
from typing import TYPE_CHECKING

from nexa_policy.metrics import brier_score, expected_calibration_error, one_hot
from nexa_policy.models.nn import softmax

if TYPE_CHECKING:
    import numpy as np


def _logits_from_proba(probs: np.ndarray) -> np.ndarray:
    """확률 → logits(log). softmax 역은 상수 자유도가 있으나 temperature 적용엔 무방."""
    import numpy as np

    return np.log(np.clip(probs, 1e-12, 1.0))


def apply_temperature(probs: np.ndarray, temperature: float) -> np.ndarray:
    """확률에 temperature scaling 적용: softmax(logits / T)."""
    if temperature <= 0:
        raise ValueError("temperature 는 양수여야 한다.")
    return softmax(_logits_from_proba(probs) / temperature)


def _nll(probs: np.ndarray, labels: np.ndarray) -> float:
    import numpy as np

    n = probs.shape[0]
    p_true = probs[np.arange(n), labels]
    return float(-np.log(np.clip(p_true, 1e-12, 1.0)).mean())


def fit_temperature(
    val_probs: np.ndarray, val_labels: np.ndarray, *, grid: tuple[float, ...] | None = None
) -> float:
    """validation NLL 을 최소화하는 temperature 를 grid 탐색으로 찾는다(결정론)."""
    candidates = grid or tuple(round(0.25 + 0.05 * i, 4) for i in range(0, 96))  # 0.25..5.0.
    best_t = 1.0
    best_nll = float("inf")
    for t in candidates:
        nll = _nll(apply_temperature(val_probs, t), val_labels)
        if nll < best_nll - 1e-12:
            best_nll = nll
            best_t = t
    return best_t


@dataclass(frozen=True)
class CalibrationDecision:
    """보정 적용 여부와 전/후 지표."""

    method: str  # "temperature" | "identity".
    temperature: float
    applied: bool
    brier_before: float
    brier_after: float
    ece_before: float
    ece_after: float

    def to_dict(self) -> dict[str, object]:
        return {
            "method": self.method,
            "temperature": self.temperature,
            "applied": self.applied,
            "brier_before": self.brier_before,
            "brier_after": self.brier_after,
            "ece_before": self.ece_before,
            "ece_after": self.ece_after,
        }


def _metrics(probs: np.ndarray, labels: np.ndarray, n_classes: int) -> tuple[float, float]:
    import numpy as np

    brier = brier_score(one_hot(labels, n_classes), probs)
    conf = probs.max(axis=1)
    correct = (probs.argmax(axis=1) == labels).astype(np.float64)
    ece = expected_calibration_error(conf, correct)
    return brier, ece


def select_calibration(
    *,
    val_probs: np.ndarray,
    val_labels: np.ndarray,
    test_probs: np.ndarray,
    test_labels: np.ndarray,
    n_classes: int,
) -> tuple[CalibrationDecision, np.ndarray]:
    """val 에서 temperature 를 학습하고, test Brier·ECE 가 개선될 때만 적용한다(악화 시 identity).

    반환: (결정, 적용된 test 확률). applied=False 면 원 test_probs 를 그대로 반환.
    """
    brier_before, ece_before = _metrics(test_probs, test_labels, n_classes)
    temperature = fit_temperature(val_probs, val_labels)
    calibrated = apply_temperature(test_probs, temperature)
    brier_after, ece_after = _metrics(calibrated, test_labels, n_classes)

    # 둘 다 악화되지 않고(허용오차) 적어도 하나가 개선되면 적용.
    tol = 1e-9
    not_worse = (brier_after <= brier_before + tol) and (ece_after <= ece_before + tol)
    improves = (brier_after < brier_before - tol) or (ece_after < ece_before - tol)
    apply = bool(not_worse and improves)

    if apply:
        return (
            CalibrationDecision(
                method="temperature",
                temperature=temperature,
                applied=True,
                brier_before=brier_before,
                brier_after=brier_after,
                ece_before=ece_before,
                ece_after=ece_after,
            ),
            calibrated,
        )
    return (
        CalibrationDecision(
            method="identity",
            temperature=1.0,
            applied=False,
            brier_before=brier_before,
            brier_after=brier_before,
            ece_before=ece_before,
            ece_after=ece_before,
        ),
        test_probs,
    )
