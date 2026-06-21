"""neural survival model(NEXA-P12-T006) — temporal encoder 상태에서 action-specific hazard.

temporal encoder(T007/P11)의 hidden 상태를 받아 **action 별**(REACT/SPEAK) 시간 bin hazard 를 예측한다.
action head(어떤 행동을 할까)와 time head(언제 할까)가 한 trunk 를 공유하되 따로 나오므로, 둘의 **불일치**
(예: action head 는 SPEAK 확률 0 인데 time head 는 빠른 SPEAK hazard)가 생길 수 있다 — 그 불일치를 정량화한다.

**acceptance(T006) — action head 와 time head 의 불일치가 정량화된다**:
- [ActionTimeMismatch]: action head 의 행동확률과 time head 의 누적 사건확률(hazard→pmf 합)이 얼마나
  어긋나는지(상관·평균 절대차)를 보고한다. T008 sampler 가 이 불일치를 joint 로 푼다.

torch 비의존 — GruEncoder(numpy) + 작은 [Linear] head. 결정론.
"""

from __future__ import annotations

from dataclasses import dataclass
from typing import TYPE_CHECKING

from nexa_policy.models.discrete_hazard import event_pmf
from nexa_policy.models.nn import Linear, sigmoid, softmax
from nexa_policy.models.temporal_encoder import GruEncoder

if TYPE_CHECKING:
    import numpy as np

# 시간 hazard 를 따로 두는 action(IGNORE/WAIT 은 시간축 사건 아님 — REACT/SPEAK 만).
TIMED_ACTIONS: tuple[str, ...] = ("react", "speak")


@dataclass
class NeuralSurvivalModel:
    """GRU encoder 상태 → action head(softmax) + action 별 time hazard head.

    - action_head: 전체 action 분포(softmax).
    - time_heads: TIMED_ACTIONS 별 시간 bin hazard(독립 sigmoid).
    """

    encoder: GruEncoder
    action_head: Linear
    time_heads: dict[str, Linear]
    n_actions: int
    n_bins: int

    @classmethod
    def build(
        cls,
        *,
        in_dim: int,
        n_actions: int,
        n_bins: int,
        hidden_dim: int = 8,
        seed: int = 20260622,
    ) -> NeuralSurvivalModel:
        encoder = GruEncoder.build(in_dim=in_dim, hidden_dim=hidden_dim, seed=seed)
        action_head = Linear(hidden_dim, n_actions)
        action_head.init(seed + 1)
        time_heads: dict[str, Linear] = {}
        for i, act in enumerate(TIMED_ACTIONS):
            head = Linear(hidden_dim, n_bins)
            head.init(seed + 2 + i)
            time_heads[act] = head
        return cls(
            encoder=encoder,
            action_head=action_head,
            time_heads=time_heads,
            n_actions=n_actions,
            n_bins=n_bins,
        )

    def encode_batch(self, sequences: list[np.ndarray]) -> np.ndarray:
        """시퀀스 리스트 → encoder hidden 행렬 (n, hidden_dim)."""
        import numpy as np

        return np.stack([self.encoder.encode(seq) for seq in sequences], axis=0)

    def action_proba(self, hidden: np.ndarray) -> np.ndarray:
        """action 분포(softmax), shape (n, n_actions)."""
        return softmax(self.action_head.forward(hidden))

    def time_hazard(self, hidden: np.ndarray, action: str) -> np.ndarray:
        """action 별 시간 bin hazard, shape (n, n_bins). TIMED_ACTIONS 만 유효."""
        if action not in self.time_heads:
            raise ValueError(f"시간 hazard 는 {TIMED_ACTIONS} 만 지원: {action}")
        return sigmoid(self.time_heads[action].forward(hidden))


@dataclass(frozen=True)
class ActionTimeMismatch:
    """action head 와 time head 의 불일치(acceptance T006). 클수록 joint 정합이 필요."""

    action: str
    # action head 의 그 행동 확률 vs time head 누적 사건확률의 평균 절대차.
    mean_abs_gap: float
    # 둘의 상관(높을수록 일관 — 행동확률↑ 일 때 빠른 사건 hazard↑).
    correlation: float

    def to_dict(self) -> dict[str, object]:
        return {
            "action": self.action,
            "mean_abs_gap": self.mean_abs_gap,
            "correlation": self.correlation,
        }


def quantify_mismatch(
    model: NeuralSurvivalModel,
    hidden: np.ndarray,
    *,
    action_index: dict[str, int],
) -> list[ActionTimeMismatch]:
    """TIMED_ACTIONS 마다 action head 확률과 time head 누적 사건확률의 불일치를 정량화한다."""
    import numpy as np

    proba = model.action_proba(hidden)
    out: list[ActionTimeMismatch] = []
    for act in TIMED_ACTIONS:
        idx = action_index[act]
        p_action = proba[:, idx]
        cum_event = event_pmf(model.time_hazard(hidden, act)).sum(axis=-1)
        gap = float(np.mean(np.abs(p_action - cum_event)))
        if p_action.std() < 1e-9 or cum_event.std() < 1e-9:
            corr = 0.0
        else:
            corr = float(np.corrcoef(p_action, cum_event)[0, 1])
        out.append(ActionTimeMismatch(action=act, mean_abs_gap=gap, correlation=corr))
    return out
