"""action-time joint sampler(NEXA-P12-T008).

action mark 와 delay(시간 bin)를 **일관된 joint distribution** 에서 샘플링한다. action 을 먼저 뽑고, 뽑힌
action 이 시간축 사건(REACT/SPEAK)일 때만 그 action 의 time hazard 로 delay bin 을 뽑는다 — action 과 time
이 같은 결정에서 나오므로 불일치가 구조적으로 불가능하다.

**acceptance(T008) — SPEAK 확률 0 인데 SPEAK delay 가 선택되는 불일치가 없다**:
- delay 는 **뽑힌 action 의** time head 에서만 나온다. SPEAK 가 안 뽑히면 SPEAK delay 는 절대 나오지 않는다.
- IGNORE/WAIT/CANCEL 처럼 시간축 사건이 아닌 action 은 delay=None(샘플하지 않는다).
- 결정론: 같은 seed → 같은 (action, delay) joint sample.

torch 비의존 — numpy Generator. neural_survival(T006) 모델 또는 임의 hazard 와 함께 쓴다.
"""

from __future__ import annotations

from dataclasses import dataclass
from typing import TYPE_CHECKING

from nexa_policy.models.discrete_hazard import event_pmf
from nexa_policy.models.neural_survival import TIMED_ACTIONS
from nexa_policy.reproducibility import rng

if TYPE_CHECKING:
    import numpy as np


@dataclass(frozen=True)
class JointSample:
    """joint sample: 뽑힌 action 과 (시간축 사건이면) delay bin. 비-사건 action 은 delay_bin=None."""

    action: str
    delay_bin: int | None

    def __post_init__(self) -> None:
        # 불일치 가드: 시간축 사건이 아닌 action 은 delay 를 가질 수 없다(acceptance T008).
        if self.delay_bin is not None and self.action not in TIMED_ACTIONS:
            raise ValueError(
                f"{self.action} 은 시간축 사건이 아니므로 delay_bin 을 가질 수 없다(joint 불일치)."
            )


def sample_joint(
    *,
    action_proba: np.ndarray,
    action_classes: tuple[str, ...],
    time_hazards: dict[str, np.ndarray],
    seed: int = 20260622,
) -> list[JointSample]:
    """배치별 (action, delay) joint sample.

    - action_proba: (n, n_actions) — 행별 action 분포(softmax, 합 1).
    - action_classes: action index → 이름.
    - time_hazards: TIMED_ACTIONS → (n, n_bins) hazard. 뽑힌 action 의 hazard 로만 delay 를 뽑는다.

    뽑힌 action 이 SPEAK 인데 그 행의 SPEAK 확률이 사실상 0 이면 애초에 그 action 이 안 뽑히므로,
    SPEAK delay 가 나오는 일은 없다(acceptance T008).
    """

    gen = rng(seed)
    n = action_proba.shape[0]
    out: list[JointSample] = []
    for i in range(n):
        p = action_proba[i]
        a_idx = int(gen.choice(len(action_classes), p=p / p.sum()))
        action = action_classes[a_idx]
        if action in TIMED_ACTIONS:
            pmf = event_pmf(time_hazards[action][i : i + 1])[0]
            total = float(pmf.sum())
            if total <= 0:
                # 사건 확률이 사실상 0 → 관찰 창 내 발화 없음(검열 쪽). delay 미선택.
                out.append(JointSample(action=action, delay_bin=None))
                continue
            delay_bin = int(gen.choice(len(pmf), p=pmf / total))
            out.append(JointSample(action=action, delay_bin=delay_bin))
        else:
            out.append(JointSample(action=action, delay_bin=None))
    return out


def has_delay_action_mismatch(samples: list[JointSample]) -> bool:
    """불일치 검출 헬퍼: delay 가 달렸는데 그 action 이 시간축 사건(REACT/SPEAK)이 아닌 sample 이 있는가.

    특히 "SPEAK 확률 0 인데 SPEAK delay" 같은 불일치를 잡는다. sample_joint 의 구조상 항상 False 여야
    한다(acceptance T008 가드 — delay 는 뽑힌 시간축 action 에서만 나온다).
    """
    return any(s.delay_bin is not None and s.action not in TIMED_ACTIONS for s in samples)
