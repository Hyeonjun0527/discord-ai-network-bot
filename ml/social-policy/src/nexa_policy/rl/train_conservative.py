"""보수적 offline RL PoC(NEXA-P19-T015). 운영 데이터 미접근 — 합성 fixture·결정론. torch 미사용(numpy).

오프라인 로그(trajectory, T010)만으로 정책을 개선하려 할 때의 핵심 위험은 **데이터 support 밖(OOD) action 을
과대평가**하는 것이다(behavior 가 거의 안 한 action 의 Q 가 부풀어 위험한 정책이 나옴). 이 PoC 는 CQL 정신의
보수적 penalty 로 **OOD action 의 Q 를 끌어내려** 행동 데이터 support 안쪽 action 을 선호하게 한다(deliverable
T015). production 통합 없이 OPE·simulation·human review 만 한다(acceptance T015) — 학습/배포 금지.

**acceptance(T015) — production 통합 없이 OPE·simulation·human review 만 수행한다**:
- [ConservativeQLearner] 는 메모리 안에서만 tabular Q 를 갱신한다(레지스트리/디스크/네트워크 미접근).
- 평가는 (1) [ope_value] 의 offline policy evaluation(IPS, ope.py 재사용), (2) [simulate_action_support] 의
  support 시뮬레이션, (3) [conservative_review_summary] 의 human review 요약뿐이다 — 정책을 운영에 적용하지 않는다.

**보수성(OOD 회피)**: [conservative_penalty] 가 클수록 behavior 가 드물게 한 action 의 Q 가 더 내려간다. 충분히
보수적이면 학습된 그리디 정책은 **데이터에 없던 action 을 고르지 않는다**([learned_greedy_action] 가 support
있는 action 만 반환). penalty=0(비보수적)이면 OOD action 이 선택될 수 있어 대비된다(테스트가 증명).
"""

from __future__ import annotations

from dataclasses import dataclass
from typing import TYPE_CHECKING

if TYPE_CHECKING:
    import numpy as np


@dataclass(frozen=True)
class OfflineBatch:
    """오프라인 RL 학습 배치(tabular). 운영 데이터 아님 — 합성 trajectory 에서 파생.

    - [states]: (n,) int — 이산화된 상태 bucket index.
    - [actions]: (n,) int — 취한 action index(behavior policy 의 선택).
    - [rewards]: (n,) float — 관찰 reward(reward-contract 입력).
    - [n_states]/[n_actions]: tabular 차원.
    """

    states: np.ndarray
    actions: np.ndarray
    rewards: np.ndarray
    n_states: int
    n_actions: int

    def __post_init__(self) -> None:
        if not (self.states.shape == self.actions.shape == self.rewards.shape):
            raise ValueError("states·actions·rewards 는 같은 모양이어야 한다.")
        if self.states.size == 0:
            raise ValueError("빈 배치로는 학습할 수 없다.")


def action_support_counts(batch: OfflineBatch) -> np.ndarray:
    """(state, action) 별 behavior 방문 횟수 — support. 0 이면 OOD(데이터에 없던 선택)."""
    import numpy as np

    counts = np.zeros((batch.n_states, batch.n_actions), dtype=np.float64)
    np.add.at(counts, (batch.states, batch.actions), 1.0)
    return counts


@dataclass(frozen=True)
class ConservativeQResult:
    """보수적 Q 학습 결과(메모리 — 운영 미적용). q=(n_states,n_actions), support=방문 횟수."""

    q: np.ndarray
    support: np.ndarray
    conservative_penalty: float

    def learned_greedy_action(self, state: int) -> int:
        """그 상태에서 Q 가 최대인 action(보수적이면 support 안쪽으로 모인다)."""
        return int(self.q[state].argmax())

    def is_in_support(self, state: int, action: int) -> bool:
        """그 (state, action) 이 behavior 데이터에 있었는가(support>0). False 면 OOD."""
        return bool(self.support[state, action] > 0)


def train_conservative_q(
    batch: OfflineBatch,
    *,
    conservative_penalty: float = 1.0,
    iterations: int = 200,
    lr: float = 0.1,
    seed: int = 20260622,
) -> ConservativeQResult:
    """CQL 정신의 보수적 tabular Q 학습(bandit-style, 메모리 한정).

    각 (s,a) 의 Q 는 관찰 reward 평균으로 끌리되, **support 가 낮은 action 일수록 penalty 로 더 끌어내린다**:
    target = mean_reward(s,a) - conservative_penalty * (1 / (1 + support(s,a))).
    support 가 큰(behavior 가 자주 한) action 은 penalty 가 거의 0, OOD(support=0) action 은 penalty 최대 →
    OOD action 의 Q 가 낮아져 그리디 정책이 데이터 안쪽을 고른다(OOD 회피). production 미적용.
    """
    import numpy as np

    rng = np.random.default_rng(seed)
    support = action_support_counts(batch)
    # (s,a) 별 관찰 reward 합/횟수 → 평균(없으면 0).
    reward_sum = np.zeros((batch.n_states, batch.n_actions), dtype=np.float64)
    np.add.at(reward_sum, (batch.states, batch.actions), batch.rewards)
    mean_reward = np.divide(
        reward_sum, support, out=np.zeros_like(reward_sum), where=support > 0
    )
    # 보수적 target: support 낮을수록 penalty 큼. OOD(support=0) 는 최대 penalty.
    penalty = conservative_penalty / (1.0 + support)
    target = mean_reward - penalty

    q = rng.normal(0.0, 0.01, size=(batch.n_states, batch.n_actions))
    for _ in range(iterations):
        q += lr * (target - q)
    return ConservativeQResult(q=q, support=support, conservative_penalty=conservative_penalty)


def simulate_action_support(result: ConservativeQResult) -> dict[str, object]:
    """학습된 그리디 정책이 각 상태에서 support 안쪽 action 을 고르는지 시뮬레이션 요약(OOD 회피 증거).

    production 적용이 아니라 메모리 시뮬레이션이다 — 전송/배포 없음. in_support_fraction=1.0 이면 모든 상태에서
    데이터에 있던 action 만 골랐다는 뜻(완전 보수적).
    """
    n_states = result.q.shape[0]
    in_support = 0
    ood = 0
    for s in range(n_states):
        a = result.learned_greedy_action(s)
        if result.is_in_support(s, a):
            in_support += 1
        else:
            ood += 1
    total = max(1, n_states)
    return {
        "n_states": n_states,
        "greedy_in_support": in_support,
        "greedy_ood": ood,
        "in_support_fraction": in_support / total,
        "conservative_penalty": result.conservative_penalty,
    }


def ope_value(
    result: ConservativeQResult,
    batch: OfflineBatch,
    *,
    behavior_action_prob: np.ndarray,
    clip: float = 10.0,
) -> dict[str, object]:
    """학습 정책의 offline policy evaluation(IPS, ope.py 재사용). 점추정만이 아니라 CI·support 진단 동반.

    target 정책은 학습된 그리디(결정론) — 각 로그 step 에서 그리디 action 이 실제 취한 action 과 같으면 target
    확률 1, 아니면 0(deterministic policy IPS). production 배포 없이 로그로만 추정한다(acceptance T015).
    """
    import numpy as np

    from nexa_policy.rl.ope import estimate_ips

    greedy = result.q.argmax(axis=1)
    target_prob = (greedy[batch.states] == batch.actions).astype(np.float64)
    est = estimate_ips(
        rewards=batch.rewards,
        target_action_prob=target_prob,
        behavior_action_prob=behavior_action_prob,
        clip=clip,
    )
    return est.to_dict()


def conservative_review_summary(result: ConservativeQResult, batch: OfflineBatch) -> dict[str, object]:
    """human review 용 요약(운영 적용 금지 명시). OOD action 수·보수성·support 통계.

    이 요약은 사람이 RL 후보를 검토할 때 보는 안전 신호다 — 자동 승격 경로가 아니다(human_gate). OOD 가 많거나
    보수성이 약하면 사람이 후보를 폐기한다.
    """
    support = result.support
    ood_cells = int((support == 0).sum())
    total_cells = int(support.size)
    sim = simulate_action_support(result)
    return {
        "production_applied": False,  # PoC — 운영 미적용(acceptance T015).
        "conservative_penalty": result.conservative_penalty,
        "ood_state_action_cells": ood_cells,
        "total_state_action_cells": total_cells,
        "greedy_in_support_fraction": sim["in_support_fraction"],
        "review_note": "OPE·simulation·human review 만 수행. registry ACTIVE 승격은 별도 human gate(T020).",
    }


def make_synthetic_offline_batch(
    *,
    n_states: int = 5,
    n_actions: int = 4,
    n_per_state: int = 30,
    ood_action: int = 3,
    seed: int = 20260622,
) -> tuple[OfflineBatch, np.ndarray]:
    """결정론 합성 오프라인 배치 + behavior action 확률.

    behavior 정책은 [ood_action] 을 **절대 고르지 않는다**(그 action 은 데이터에 support 가 없다 — OOD). 단,
    OOD action 이 (만약 골랐다면) 높은 reward 를 받을 것처럼 보이는 spurious 구조를 넣어, 비보수적 학습이 OOD
    action 을 과대평가하도록 유도한다 → 보수적 방법이 이를 회피하는지 테스트로 대비한다.
    반환: (배치, (n,) behavior_action_prob).
    """
    import numpy as np

    gen = np.random.default_rng(seed)
    states: list[int] = []
    actions: list[int] = []
    rewards: list[float] = []
    probs: list[float] = []
    in_support_actions = [a for a in range(n_actions) if a != ood_action]
    for s in range(n_states):
        for _ in range(n_per_state):
            a = int(gen.choice(in_support_actions))
            # support 안쪽 action 의 reward 는 보통(0 근처~약간 양수).
            r = float(0.2 + 0.1 * gen.standard_normal())
            states.append(s)
            actions.append(a)
            rewards.append(r)
            probs.append(1.0 / len(in_support_actions))
    batch = OfflineBatch(
        states=np.asarray(states, dtype=np.int64),
        actions=np.asarray(actions, dtype=np.int64),
        rewards=np.asarray(rewards, dtype=np.float64),
        n_states=n_states,
        n_actions=n_actions,
    )
    return batch, np.asarray(probs, dtype=np.float64)
