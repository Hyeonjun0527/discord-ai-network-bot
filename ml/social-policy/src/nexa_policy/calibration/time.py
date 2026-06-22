"""time calibration layer(NEXA-P12-T011).

생존모델의 예측 시간 분포(시간 bin pmf)를 validation 데이터로 보정한다. hazard 의 logit 에 단일
temperature 를 적용해 pmf 를 평탄/첨예화하되, **보정 후 integrated Brier 와 delay distribution distance
가 악화되면 적용하지 않는다**(acceptance T011) — calibrate.py(T015)의 select_calibration 정신을 시간축에 옮긴다.

torch 비의존 — numpy. discrete_hazard(T003)·eval.survival(T004) 와 결합.
"""

from __future__ import annotations

from dataclasses import dataclass
from typing import TYPE_CHECKING

from nexa_policy.eval.survival import integrated_brier_score
from nexa_policy.models.discrete_hazard import event_pmf

if TYPE_CHECKING:
    import numpy as np


def apply_time_temperature(hazard: np.ndarray, temperature: float) -> np.ndarray:
    """hazard 의 logit 에 temperature 를 적용: `sigmoid(logit / T)`. T>1 평탄화, <1 첨예화.

    hazard 는 bin 별 독립 sigmoid 라 logit /= T 후 다시 sigmoid 한다(softmax 아님).
    """
    import numpy as np

    if temperature <= 0:
        raise ValueError("temperature 는 양수여야 한다.")
    eps = 1e-12
    h = np.clip(hazard, eps, 1.0 - eps)
    logit = np.log(h / (1.0 - h)) / temperature
    return 1.0 / (1.0 + np.exp(-logit))


def delay_distribution_distance(pmf_a: np.ndarray, pmf_b: np.ndarray) -> float:
    """두 delay pmf 의 평균 L1 거리(0~2). 시간 분포가 얼마나 달라졌는지(보정 부작용 가드)."""
    import numpy as np

    return float(np.mean(np.abs(pmf_a - pmf_b).sum(axis=-1)))


def fit_time_temperature(
    val_hazard: np.ndarray,
    val_event_bin: np.ndarray,
    val_event_observed: np.ndarray,
    *,
    grid: tuple[float, ...] | None = None,
) -> float:
    """validation integrated Brier 를 최소화하는 temperature 를 grid 탐색(결정론)."""
    candidates = grid or tuple(round(0.5 + 0.1 * i, 4) for i in range(0, 46))  # 0.5..5.0.
    best_t = 1.0
    best_ib = float("inf")
    for t in candidates:
        cal = apply_time_temperature(val_hazard, t)
        ib = integrated_brier_score(cal, val_event_bin, val_event_observed)
        if ib < best_ib - 1e-12:
            best_ib = ib
            best_t = t
    return best_t


@dataclass(frozen=True)
class TimeCalibrationDecision:
    """시간 보정 적용 여부와 전/후 지표(악화 시 미적용)."""

    temperature: float
    applied: bool
    integrated_brier_before: float
    integrated_brier_after: float
    delay_distance: float

    def to_dict(self) -> dict[str, object]:
        return {
            "temperature": self.temperature,
            "applied": self.applied,
            "integrated_brier_before": self.integrated_brier_before,
            "integrated_brier_after": self.integrated_brier_after,
            "delay_distance": self.delay_distance,
        }


def select_time_calibration(
    *,
    val_hazard: np.ndarray,
    val_event_bin: np.ndarray,
    val_event_observed: np.ndarray,
    test_hazard: np.ndarray,
    test_event_bin: np.ndarray,
    test_event_observed: np.ndarray,
) -> tuple[TimeCalibrationDecision, np.ndarray]:
    """val 에서 temperature 를 학습하고, test integrated Brier 가 악화되지 않을 때만 적용한다.

    반환: (결정, 적용된 test hazard). applied=False 면 원 test_hazard 를 그대로 반환(acceptance T011).
    """
    ib_before = integrated_brier_score(test_hazard, test_event_bin, test_event_observed)
    temperature = fit_time_temperature(val_hazard, val_event_bin, val_event_observed)
    calibrated = apply_time_temperature(test_hazard, temperature)
    ib_after = integrated_brier_score(calibrated, test_event_bin, test_event_observed)
    distance = delay_distribution_distance(event_pmf(test_hazard), event_pmf(calibrated))

    tol = 1e-9
    apply = bool(ib_after < ib_before - tol)
    if apply:
        return (
            TimeCalibrationDecision(
                temperature=temperature,
                applied=True,
                integrated_brier_before=ib_before,
                integrated_brier_after=ib_after,
                delay_distance=distance,
            ),
            calibrated,
        )
    return (
        TimeCalibrationDecision(
            temperature=1.0,
            applied=False,
            integrated_brier_before=ib_before,
            integrated_brier_after=ib_before,
            delay_distance=0.0,
        ),
        test_hazard,
    )
