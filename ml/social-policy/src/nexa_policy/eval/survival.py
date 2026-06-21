"""생존 분석 metric(NEXA-P12-T004) — 검열 지원·결정론·numpy 전용.

연속/이산 시간 생존 예측을 검열을 고려해 평가한다. 단순 delay accuracy 와 **함께** 보고해야 한다
(acceptance: censoring 지원 + delay accuracy 병행).

metric:
- [survival_nll]: discrete-time NLL(discrete_hazard 와 동일 정의의 평균 음의 로그우도).
- [concordance_index]: Harrell's C-index — 더 일찍 사건이 난 쌍을 모델이 더 높은 위험으로 순서지었는가.
  검열 쌍은 비교 가능할 때만(검열 시점 전에 사건이 난 쪽이 더 위험) 센다.
- [integrated_brier_score]: 시간 격자에서 survival 예측의 Brier 를 적분(검열 가중 단순화 버전).
- [time_calibration_error]: 예측 사건확률 분위와 실제 사건 비율의 차(시간 보정 sanity).
- [delay_bin_accuracy]: 사건 관찰 표본의 예측 bin 일치율(단순 delay accuracy, 병행 보고용).
"""

from __future__ import annotations

from dataclasses import dataclass
from typing import TYPE_CHECKING

from nexa_policy.models.discrete_hazard import discrete_nll, event_pmf, survival_from_hazard

if TYPE_CHECKING:
    import numpy as np


def survival_nll(
    hazard: np.ndarray, event_bin: np.ndarray, event_observed: np.ndarray
) -> float:
    """discrete-time survival NLL(검열 지원). 낮을수록 좋다."""
    return discrete_nll(hazard, event_bin, event_observed)


def concordance_index(
    risk_score: np.ndarray, duration: np.ndarray, event_observed: np.ndarray
) -> float:
    """Harrell's C-index. risk_score 가 클수록 더 일찍 사건이 나야 한다(위험↑ → 생존↓).

    비교 가능 쌍: 둘 중 더 짧은 쪽이 사건 관찰(검열 아님)인 쌍만. concordant = 더 짧은 쪽 risk 가 더 큼.
    동점 risk 는 0.5 로 센다. 비교 가능 쌍이 없으면 0.5(무정보).
    """
    import numpy as np

    n = risk_score.shape[0]
    concordant = 0.0
    comparable = 0
    for i in range(n):
        for j in range(i + 1, n):
            # 더 짧은 쪽이 사건 관찰이어야 순서 비교 가능.
            if duration[i] < duration[j] and event_observed[i]:
                shorter, longer = i, j
            elif duration[j] < duration[i] and event_observed[j]:
                shorter, longer = j, i
            else:
                continue
            comparable += 1
            if risk_score[shorter] > risk_score[longer]:
                concordant += 1.0
            elif np.isclose(risk_score[shorter], risk_score[longer]):
                concordant += 0.5
    return concordant / comparable if comparable else 0.5


def integrated_brier_score(
    hazard: np.ndarray, event_bin: np.ndarray, event_observed: np.ndarray
) -> float:
    """시간 bin 격자에서 survival 예측 Brier 의 평균(낮을수록 좋다).

    각 bin k 에서 실제 "k 까지 생존" 지시자 vs 예측 `S_k`. 검열 표본은 검열 시점 이후 bin 을 제외
    (관찰 불가)해 검열 편향을 줄이는 단순화 버전이다.
    """

    surv = survival_from_hazard(hazard)
    n, n_bins = surv.shape
    total = 0.0
    count = 0
    for i in range(n):
        e = int(event_bin[i])
        for k in range(n_bins):
            if not event_observed[i] and k > e:
                continue  # 검열 시점 이후는 관찰 불가.
            # 실제 "bin k 끝까지 생존" = 사건 bin 이 k 보다 큼(또는 검열이 k 이상).
            alive = 1.0 if e > k else 0.0
            total += (surv[i, k] - alive) ** 2
            count += 1
    return total / count if count else 0.0


def time_calibration_error(
    hazard: np.ndarray, event_bin: np.ndarray, event_observed: np.ndarray, *, n_bins: int = 5
) -> float:
    """시간 보정 오차: 예측 사건확률을 구간으로 묶어 실제 사건 비율과의 차를 가중 평균한다.

    사건 관찰 표본만으로 "예측 누적 사건확률"의 보정을 본다(검열은 분모 제외, sanity 수준).
    """
    import numpy as np

    pmf = event_pmf(hazard)
    pred = pmf.sum(axis=-1)  # 관찰 창 내 사건 발생 예측확률.
    mask = event_observed.astype(bool)
    obs = event_observed.astype(np.float64)
    if mask.sum() == 0:
        return 0.0
    edges = np.linspace(0.0, 1.0, n_bins + 1)
    err = 0.0
    total = int(mask.sum())
    for b in range(n_bins):
        lo, hi = edges[b], edges[b + 1]
        in_bin = (pred > lo) & (pred <= hi) if b > 0 else (pred <= hi)
        in_bin = in_bin & mask
        cnt = int(in_bin.sum())
        if cnt == 0:
            continue
        err += (cnt / total) * abs(float(obs[in_bin].mean()) - float(pred[in_bin].mean()))
    return err


def delay_bin_accuracy(
    hazard: np.ndarray, event_bin: np.ndarray, event_observed: np.ndarray
) -> float:
    """사건 관찰 표본의 예측 bin(argmax pmf) 일치율 — 단순 delay accuracy(병행 보고용)."""
    import numpy as np

    mask = event_observed.astype(bool)
    if mask.sum() == 0:
        return 0.0
    pred_bin = event_pmf(hazard).argmax(axis=-1)
    correct = (pred_bin[mask] == event_bin[mask]).astype(np.float64)
    return float(correct.mean())


@dataclass(frozen=True)
class SurvivalMetrics:
    """생존 평가 묶음. 검열 지원 metric 과 단순 delay accuracy 를 **함께** 담는다(acceptance T004)."""

    nll: float
    concordance: float
    integrated_brier: float
    time_calibration: float
    delay_accuracy: float

    def to_dict(self) -> dict[str, float]:
        return {
            "nll": self.nll,
            "concordance": self.concordance,
            "integrated_brier": self.integrated_brier,
            "time_calibration": self.time_calibration,
            "delay_accuracy": self.delay_accuracy,
        }


def evaluate_survival(
    hazard: np.ndarray, event_bin: np.ndarray, event_observed: np.ndarray
) -> SurvivalMetrics:
    """전체 생존 metric 을 한 번에 계산한다. risk_score 는 관찰 창 내 사건확률(=일찍 발화할 위험)."""
    pmf = event_pmf(hazard)
    risk = pmf.sum(axis=-1)
    return SurvivalMetrics(
        nll=survival_nll(hazard, event_bin, event_observed),
        concordance=concordance_index(risk, event_bin.astype(float), event_observed),
        integrated_brier=integrated_brier_score(hazard, event_bin, event_observed),
        time_calibration=time_calibration_error(hazard, event_bin, event_observed),
        delay_accuracy=delay_bin_accuracy(hazard, event_bin, event_observed),
    )
