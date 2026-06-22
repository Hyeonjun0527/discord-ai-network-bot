"""discrete-time hazard baseline(NEXA-P12-T003).

연속시간 생존을 시간 bin 으로 이산화해, bin `k` 별 **조건부 hazard** `h_k = P(사건이 bin k | bin k 까지 생존)`
을 예측한다. survival 은 `S_k = prod_{j<=k} (1 - h_j)` 로 단조 감소하고, bin 별 사건 확률
`f_k = h_k * S_{k-1}` 의 합 + 최종 생존확률 = 1 로 수학적으로 유효하다.

**acceptance(T003) — survival probability 가 단조 감소하고 합계가 수학적으로 유효하다**:
- [survival_from_hazard]: `S` 는 단조 비증가(`h_k ∈ [0,1]` 이므로). [event_pmf]: `sum f_k + S_last = 1`.
- right-censored 표본(P12-T002)은 [discrete_nll] 에서 "마지막 관찰 bin 까지 생존" 항만 더한다(사건 항 없음)
  — 검열을 never 로 강제하지 않는다.

torch 비의존 — numpy [Linear] + sigmoid hazard. 결정론 init(seed). 경량 forward/backward.
"""

from __future__ import annotations

from dataclasses import dataclass
from typing import TYPE_CHECKING

from nexa_policy.models.nn import Linear, relu, sigmoid

if TYPE_CHECKING:
    import numpy as np


def time_bin_index(duration_s: float, bin_edges_s: tuple[float, ...]) -> int:
    """초 단위 시간을 discrete bin index 로. `bin_edges_s` 는 상한 경계(오름차순), 마지막 bin 은 그 이상.

    예: edges=(2,10,60) → bins [0,2)=0, [2,10)=1, [10,60)=2, [60,inf)=3.
    """
    for k, edge in enumerate(bin_edges_s):
        if duration_s < edge:
            return k
    return len(bin_edges_s)


@dataclass
class DiscreteHazardModel:
    """feature → bin 별 조건부 hazard logit(sigmoid). bin 수 = len(bin_edges)+1.

    각 bin 의 hazard 는 독립 sigmoid(softmax 아님) — 조건부 hazard 는 bin 마다 0~1 확률이다.
    """

    in_dim: int
    n_bins: int
    trunk: Linear
    hazard_head: Linear

    @classmethod
    def build(
        cls, *, in_dim: int, n_bins: int, hidden_dim: int = 16, seed: int = 20260622
    ) -> DiscreteHazardModel:
        trunk = Linear(in_dim, hidden_dim)
        hazard_head = Linear(hidden_dim, n_bins)
        for i, layer in enumerate((trunk, hazard_head)):
            layer.init(seed + i)
        return cls(in_dim=in_dim, n_bins=n_bins, trunk=trunk, hazard_head=hazard_head)

    def hazard(self, x: np.ndarray) -> np.ndarray:
        """bin 별 조건부 hazard `h_k ∈ (0,1)`, shape (n, n_bins)."""
        h = relu(self.trunk.forward(x))
        return sigmoid(self.hazard_head.forward(h))


def survival_from_hazard(hazard: np.ndarray) -> np.ndarray:
    """조건부 hazard → survival `S_k = prod_{j<=k}(1 - h_j)`, shape (n, n_bins).

    `h_k ∈ [0,1]` 이므로 `S` 는 **단조 비증가**(acceptance T003). `S_k` = bin k 끝까지 생존 확률.
    """
    import numpy as np

    return np.cumprod(1.0 - hazard, axis=-1)


def event_pmf(hazard: np.ndarray) -> np.ndarray:
    """bin 별 사건 발생 확률 `f_k = h_k * S_{k-1}`, shape (n, n_bins). `sum_k f_k = 1 - S_last`.

    `sum_k f_k + S_last = 1` 이 수학적으로 성립한다(acceptance T003) — [pmf_plus_survival_sum] 으로 검사.
    """
    import numpy as np

    surv = survival_from_hazard(hazard)
    # S_{k-1}: S 를 한 칸 밀고 맨 앞을 1(아직 아무 bin 도 안 지남)로.
    surv_prev = np.concatenate([np.ones_like(surv[..., :1]), surv[..., :-1]], axis=-1)
    return hazard * surv_prev


def pmf_plus_survival_sum(hazard: np.ndarray) -> np.ndarray:
    """`sum_k f_k + S_last`, shape (n,). 수학적으로 1 이어야 한다(유효성 검사)."""

    pmf = event_pmf(hazard)
    surv_last = survival_from_hazard(hazard)[..., -1]
    return pmf.sum(axis=-1) + surv_last


def discrete_nll(
    hazard: np.ndarray, event_bin: np.ndarray, event_observed: np.ndarray
) -> float:
    """discrete-time survival NLL(검열 지원).

    - 사건 관찰(event_observed=1): bin `e` 에서 사건 → `-log h_e - sum_{j<e} log(1-h_j)`.
    - 검열(event_observed=0): bin `e` 까지 생존만 관찰 → `-sum_{j<=e} log(1-h_j)`(사건 항 없음).
      검열을 never 로 강제하지 않는다(acceptance T002/T003).
    """
    import numpy as np

    n = hazard.shape[0]
    eps = 1e-12
    h = np.clip(hazard, eps, 1.0 - eps)
    total = 0.0
    for i in range(n):
        e = int(event_bin[i])
        # bin j<e 까지 생존(사건/검열 공통).
        surv_term = float(np.log(1.0 - h[i, :e]).sum()) if e > 0 else 0.0
        if event_observed[i]:
            total += -(float(np.log(h[i, e])) + surv_term)
        else:
            # 검열: e 까지 생존(e 포함), 사건 항 없음.
            total += -(surv_term + float(np.log(1.0 - h[i, e])))
    return total / n if n else 0.0
