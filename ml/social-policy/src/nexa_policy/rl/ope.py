"""offline policy evaluation(OPE) baseline(NEXA-P19-T013). 운영 데이터 미접근 — 합성 fixture·결정론.

배포 없이(offline) 정책 성능을 로그 데이터로 추정한다: importance weighting(IPS)·doubly robust(DR).
trajectory(T010)의 (상태,행동,보상)과 behavior policy(로그된 정책)의 action 확률을 입력으로 받는다.
torch 미사용(numpy).

acceptance(T013) — support 부족과 높은 분산을 숨기지 않고 confidence interval 을 보고한다:
- [estimate_ips]/[estimate_dr] 는 점추정과 **표준오차·95% CI** 를 함께 낸다(점추정만 보고 금지).
- [SupportDiagnostics]: importance weight 의 effective sample size(ESS)·최대 weight·clip 비율을 보고해
  support 부족(소수 표본이 추정을 지배)을 드러낸다. 분산이 크면 CI 가 넓게 나와 신뢰 낮음이 보인다.
"""

from __future__ import annotations

from dataclasses import dataclass
from typing import TYPE_CHECKING

if TYPE_CHECKING:
    import numpy as np

# 95% 정규 근사 z.
_Z_95 = 1.959963984540054


@dataclass(frozen=True)
class SupportDiagnostics:
    """importance weighting 의 support 진단(숨기지 않고 보고)."""

    n: int
    effective_sample_size: float
    max_weight: float
    clipped_fraction: float

    @property
    def ess_fraction(self) -> float:
        """ESS / n. 1 에 가까울수록 건강, 작을수록 소수 표본이 추정을 지배(support 부족)."""
        return self.effective_sample_size / self.n if self.n else 0.0

    def to_dict(self) -> dict[str, object]:
        return {
            "n": self.n,
            "effective_sample_size": self.effective_sample_size,
            "max_weight": self.max_weight,
            "clipped_fraction": self.clipped_fraction,
            "ess_fraction": self.ess_fraction,
        }


@dataclass(frozen=True)
class OPEEstimate:
    """OPE 점추정 + 불확실성(CI). support 진단 동반."""

    method: str
    value: float
    std_error: float
    ci_low: float
    ci_high: float
    support: SupportDiagnostics

    @property
    def ci_width(self) -> float:
        return self.ci_high - self.ci_low

    def to_dict(self) -> dict[str, object]:
        return {
            "method": self.method,
            "value": self.value,
            "std_error": self.std_error,
            "ci_low": self.ci_low,
            "ci_high": self.ci_high,
            "ci_width": self.ci_width,
            "support": self.support.to_dict(),
        }


def _importance_weights(
    target_action_prob: np.ndarray,
    behavior_action_prob: np.ndarray,
    *,
    clip: float,
) -> tuple[np.ndarray, float]:
    """target/behavior 확률비 weight 와 clip 비율. behavior 0 은 eps 로 보호."""
    import numpy as np

    eps = 1e-12
    raw = target_action_prob / np.clip(behavior_action_prob, eps, None)
    clipped = np.clip(raw, 0.0, clip)
    clipped_fraction = float(np.mean(raw > clip))
    return clipped, clipped_fraction


def _diagnostics(weights: np.ndarray, clipped_fraction: float) -> SupportDiagnostics:
    n = int(weights.shape[0])
    s = float(weights.sum())
    ss = float((weights**2).sum())
    ess = (s * s / ss) if ss > 0 else 0.0
    return SupportDiagnostics(
        n=n,
        effective_sample_size=ess,
        max_weight=float(weights.max()) if n else 0.0,
        clipped_fraction=clipped_fraction,
    )


def estimate_ips(
    *,
    rewards: np.ndarray,
    target_action_prob: np.ndarray,
    behavior_action_prob: np.ndarray,
    clip: float = 10.0,
) -> OPEEstimate:
    """IPS(inverse propensity scoring) 추정 + 표준오차·95% CI + support 진단.

    V_IPS = mean(w * r), w = π_target(a|s)/π_behavior(a|s)(clip). CI 는 표본 표준오차 정규근사.
    """
    import numpy as np

    if not (rewards.shape == target_action_prob.shape == behavior_action_prob.shape):
        raise ValueError("rewards·target·behavior 확률은 같은 모양이어야 한다.")
    if rewards.size < 2:
        raise ValueError("OPE 추정에는 최소 2개 표본이 필요하다.")
    w, clipped_fraction = _importance_weights(target_action_prob, behavior_action_prob, clip=clip)
    per_sample = w * rewards
    value = float(per_sample.mean())
    se = float(per_sample.std(ddof=1) / np.sqrt(per_sample.shape[0]))
    return OPEEstimate(
        method="ips",
        value=value,
        std_error=se,
        ci_low=value - _Z_95 * se,
        ci_high=value + _Z_95 * se,
        support=_diagnostics(w, clipped_fraction),
    )


def estimate_dr(
    *,
    rewards: np.ndarray,
    target_action_prob: np.ndarray,
    behavior_action_prob: np.ndarray,
    q_estimate: np.ndarray,
    v_estimate: np.ndarray,
    clip: float = 10.0,
) -> OPEEstimate:
    """doubly robust 추정 + 표준오차·95% CI + support 진단.

    V_DR = mean( v_hat + w * (r - q_hat) ). q_hat=취한 action 의 모델 보상 추정, v_hat=상태 가치 추정.
    모델(q/v)이 좋거나 weight 가 좋으면 둘 중 하나만 맞아도 편향이 작다(double robustness).
    """
    import numpy as np

    shapes = {
        rewards.shape,
        target_action_prob.shape,
        behavior_action_prob.shape,
        q_estimate.shape,
        v_estimate.shape,
    }
    if len(shapes) != 1:
        raise ValueError("모든 입력 배열은 같은 모양이어야 한다.")
    if rewards.size < 2:
        raise ValueError("OPE 추정에는 최소 2개 표본이 필요하다.")
    w, clipped_fraction = _importance_weights(target_action_prob, behavior_action_prob, clip=clip)
    per_sample = v_estimate + w * (rewards - q_estimate)
    value = float(per_sample.mean())
    se = float(per_sample.std(ddof=1) / np.sqrt(per_sample.shape[0]))
    return OPEEstimate(
        method="dr",
        value=value,
        std_error=se,
        ci_low=value - _Z_95 * se,
        ci_high=value + _Z_95 * se,
        support=_diagnostics(w, clipped_fraction),
    )
