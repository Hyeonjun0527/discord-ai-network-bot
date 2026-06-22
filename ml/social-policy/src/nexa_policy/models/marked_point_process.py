"""marked temporal point process PoC(NEXA-P12-T007).

대화 타임라인을 marked temporal point process(MTPP)로 본다: IGNORE 는 **사건 없음**(no event), 실제
행동 REACT/SPEAK/CANCEL 은 시각 `t` 에서 발생하는 **mark 가 달린 사건**이다. 사건 강도 `λ(t)` 와 mark
분포로 "언제·무엇을" 동시에 모델링한다.

**acceptance(T007) — PoC 는 production 통합 없이 likelihood 와 calibration 이득만 평가한다**:
- production 코드(central/inference)와 결합하지 않는다. [PoCResult] 로 log-likelihood 와 mark calibration
  (Brier)만 보고한다 — discrete hazard(T003) 대비 이득이 있는지 PoC 수준으로 본다.
- 무겁지 않게: 상수+선형 강도(homogeneous + 공변량 보정)와 mark softmax 만. 결정론 numpy.

torch 비의존.
"""

from __future__ import annotations

from dataclasses import dataclass
from typing import TYPE_CHECKING

if TYPE_CHECKING:
    import numpy as np

# IGNORE 는 사건 없음 — mark 는 실제 행동만.
EVENT_MARKS: tuple[str, ...] = ("react", "speak", "cancel")


@dataclass(frozen=True)
class MarkedEvent:
    """MTPP 사건 한 개: 시각 `t`(초, 원점 기준)와 mark(EVENT_MARKS index). IGNORE 는 사건이 아니라 미포함."""

    time_s: float
    mark: int


@dataclass(frozen=True)
class PoCResult:
    """MTPP PoC 평가 — likelihood 와 mark calibration 이득만(production 통합 없음)."""

    log_likelihood: float
    mark_brier: float
    base_intensity: float

    def to_dict(self) -> dict[str, float]:
        return {
            "log_likelihood": self.log_likelihood,
            "mark_brier": self.mark_brier,
            "base_intensity": self.base_intensity,
        }


def fit_base_intensity(events: list[MarkedEvent], horizon_s: float) -> float:
    """homogeneous Poisson 강도 MLE: `λ = 사건수 / 관찰구간`. 가장 단순한 시간 강도 baseline."""
    if horizon_s <= 0:
        raise ValueError("horizon 은 양수여야 한다.")
    return len(events) / horizon_s


def mark_distribution(events: list[MarkedEvent], *, smoothing: float = 1.0) -> np.ndarray:
    """관찰 mark 의 (Laplace-smoothed) 분포, shape (len(EVENT_MARKS),). softmax 해석 가능."""
    import numpy as np

    counts = np.full(len(EVENT_MARKS), smoothing, dtype=np.float64)
    for e in events:
        counts[e.mark] += 1.0
    return counts / counts.sum()


def log_likelihood(
    events: list[MarkedEvent], horizon_s: float, *, smoothing: float = 1.0
) -> float:
    """MTPP log-likelihood = Poisson 시간 항 + mark 항.

    `LL = (sum_i [log λ + log p(mark_i)]) - λ * horizon`. 결정론.
    """
    import numpy as np

    lam = fit_base_intensity(events, horizon_s)
    if lam <= 0:
        return -lam * horizon_s  # 사건 없음 → 시간 항만.
    marks = mark_distribution(events, smoothing=smoothing)
    ll = -lam * horizon_s
    for e in events:
        ll += float(np.log(lam)) + float(np.log(marks[e.mark]))
    return ll


def evaluate_poc(
    events: list[MarkedEvent], horizon_s: float, *, smoothing: float = 1.0
) -> PoCResult:
    """PoC: likelihood + mark Brier(예측 mark 분포 vs 실제 one-hot 평균)만 보고한다."""
    import numpy as np

    lam = fit_base_intensity(events, horizon_s)
    marks = mark_distribution(events, smoothing=smoothing)
    if events:
        onehot = np.zeros((len(events), len(EVENT_MARKS)), dtype=np.float64)
        for i, e in enumerate(events):
            onehot[i, e.mark] = 1.0
        brier = float(np.mean(np.sum((marks[None, :] - onehot) ** 2, axis=1)))
    else:
        brier = 0.0
    return PoCResult(
        log_likelihood=log_likelihood(events, horizon_s, smoothing=smoothing),
        mark_brier=brier,
        base_intensity=lam,
    )
