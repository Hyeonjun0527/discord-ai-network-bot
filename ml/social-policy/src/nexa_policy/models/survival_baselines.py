"""해석 가능한 survival baseline(NEXA-P12-T005) — Cox-PH 근사·parametric exponential.

discrete hazard(T003)와 비교할 **단순·해석 가능** 기준선이다. 복잡한 모델이 이들을 못 넘으면 채택하지
않는다(acceptance T005).

baseline 둘:
- [ExponentialSurvival]: parametric. 상수 hazard `λ`(MLE: 사건수/총 노출시간). survival `S(t)=exp(-λt)`.
  검열 표본은 노출시간만 기여(사건수 X) — 검열을 never 로 강제하지 않는다.
- [CoxLinearHazard]: Cox 비례위험의 선형 로그-위험 근사. 공변량 `x` 에 선형계수 `β`(결정론 경사하강)로
  로그-부분위험 `β·x`. baseline hazard 비모수는 생략하고 **부분위험 순서**(C-index)만 본다 — 해석 가능한
  위험 순위 baseline.

torch 비의존 — numpy MLE/경사하강. sklearn 불필요(작은 닫힌형/선형).
"""

from __future__ import annotations

from dataclasses import dataclass
from typing import TYPE_CHECKING

if TYPE_CHECKING:
    import numpy as np


@dataclass(frozen=True)
class ExponentialSurvival:
    """상수 hazard parametric 모델. `λ` = 사건수 / 총 노출시간(검열 포함 노출)."""

    rate: float  # λ > 0.

    @classmethod
    def fit(cls, duration: np.ndarray, event_observed: np.ndarray) -> ExponentialSurvival:
        """MLE: `λ = (관찰 사건 수) / (모든 표본의 노출시간 합)`. 검열 표본도 노출시간은 기여한다."""
        import numpy as np

        total_time = float(np.clip(duration, 1e-9, None).sum())
        n_events = float(event_observed.sum())
        rate = (n_events / total_time) if total_time > 0 and n_events > 0 else 1e-6
        return cls(rate=max(rate, 1e-9))

    def survival(self, t: np.ndarray) -> np.ndarray:
        """`S(t) = exp(-λ t)` — 단조 감소."""
        import numpy as np

        return np.exp(-self.rate * np.clip(t, 0.0, None))

    def hazard_at(self, t: np.ndarray) -> np.ndarray:
        """상수 hazard `λ`(시간 무관)."""
        import numpy as np

        return np.full_like(np.asarray(t, dtype=np.float64), self.rate)


@dataclass
class CoxLinearHazard:
    """Cox 부분위험의 선형 로그-위험 근사. `risk(x) = exp(β·x)`. 위험 **순서**만 본다(baseline hazard 생략).

    β 는 Breslow 근사 partial likelihood 의 경사상승으로 결정론 학습한다(작은 fixture·소수 step).
    """

    beta: np.ndarray

    @classmethod
    def fit(
        cls,
        features: np.ndarray,
        duration: np.ndarray,
        event_observed: np.ndarray,
        *,
        lr: float = 0.1,
        steps: int = 200,
    ) -> CoxLinearHazard:
        """Cox partial likelihood 경사상승(결정론). 사건 표본의 risk 를 risk set 대비 올린다."""
        import numpy as np

        n, dim = features.shape
        beta = np.zeros(dim, dtype=np.float64)
        order = np.argsort(duration)  # 시간 오름차순(risk set = 자신 이후).
        x = features[order]
        ev = event_observed[order].astype(bool)
        for _ in range(steps):
            scores = x @ beta
            exp_s = np.exp(scores - scores.max())
            grad = np.zeros(dim, dtype=np.float64)
            for i in range(n):
                if not ev[i]:
                    continue
                risk_set = slice(i, n)  # 시간 >= t_i.
                w = exp_s[risk_set]
                denom = float(w.sum())
                if denom <= 0:
                    continue
                weighted_mean = (w[:, None] * x[risk_set]).sum(axis=0) / denom
                grad += x[i] - weighted_mean
            beta = beta + lr * grad / max(int(ev.sum()), 1)
        return cls(beta=beta)

    def log_partial_hazard(self, features: np.ndarray) -> np.ndarray:
        """`β·x` — 로그 부분위험(클수록 일찍 사건). C-index 의 risk_score 로 쓴다."""
        return features @ self.beta
