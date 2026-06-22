"""버스트 내부 메시지 간격 모델(NEXA-P12-T012).

한 SPEAK 행동이 1~N 개의 버블(메시지 조각)로 나뉠 때, 버블 사이 inter-message delay 분포를 학습한다.
출력은 **scheduler 계획**(각 버블의 상대 발화 시각 리스트)이지, 실시간 랜덤 sleep 이 아니다.

**acceptance(T012) — 실제 타이핑 흉내를 위해 긴 랜덤 sleep 을 사용하지 않고 scheduler 계획으로 출력한다**:
- [plan_burst]: bubble 수와 학습된 간격(또는 결정론 샘플)에서 **상대 schedule(초)** 를 미리 계산해 돌려준다.
  호출자(actionruntime)가 이 계획대로 예약 전송한다 — blocking sleep 없음.
- 간격은 [MAX_INTER_BUBBLE_S] 로 cap 해 "긴 랜덤 sleep" 을 구조적으로 막는다.

torch 비의존 — numpy. 간격 분포는 작은 fixture 에서 평균/분산만 MLE(경량).
"""

from __future__ import annotations

from dataclasses import dataclass
from typing import TYPE_CHECKING

from nexa_policy.reproducibility import rng

if TYPE_CHECKING:
    import numpy as np

# 버블 간 최대 간격(초). 긴 랜덤 sleep 방지(acceptance T012) — 사람 타이핑 리듬 상한.
MAX_INTER_BUBBLE_S = 4.0
# 최소 간격(연속 버블이 동시 도착하지 않게).
MIN_INTER_BUBBLE_S = 0.3


@dataclass(frozen=True)
class InterMessageModel:
    """버블 간 간격 분포(log-normal 근사: 로그 평균·표준편차). 작은 fixture MLE."""

    log_mean: float
    log_std: float

    @classmethod
    def fit(cls, inter_delays_s: np.ndarray) -> InterMessageModel:
        """관찰된 양의 간격(초)에서 로그 평균/표준편차를 MLE. 빈/비양수면 기본 리듬."""
        import numpy as np

        positive = inter_delays_s[inter_delays_s > 0]
        if positive.size == 0:
            return cls(log_mean=float(np.log(0.8)), log_std=0.4)
        logs = np.log(np.clip(positive, 1e-3, None))
        std = float(logs.std())
        return cls(log_mean=float(logs.mean()), log_std=std if std > 1e-6 else 0.1)

    def expected_interval_s(self) -> float:
        """분포의 중앙값 간격(초, cap 적용). 결정론 계획에 쓴다."""
        import numpy as np

        median = float(np.exp(self.log_mean))
        return float(np.clip(median, MIN_INTER_BUBBLE_S, MAX_INTER_BUBBLE_S))


@dataclass(frozen=True)
class BurstPlan:
    """버스트 발화 계획: 각 버블의 **원점 기준 상대 발화 시각(초)**. blocking sleep 아님."""

    offsets_s: tuple[float, ...]

    def __post_init__(self) -> None:
        if not self.offsets_s:
            raise ValueError("burst plan 은 최소 1개 버블을 가져야 한다.")
        if list(self.offsets_s) != sorted(self.offsets_s):
            raise ValueError("offsets 는 비감소(시간 순)여야 한다.")
        if self.offsets_s[0] < 0:
            raise ValueError("첫 offset 은 음수일 수 없다.")

    @property
    def n_bubbles(self) -> int:
        return len(self.offsets_s)


def plan_burst(
    model: InterMessageModel,
    *,
    n_bubbles: int,
    start_offset_s: float = 0.0,
    jitter: bool = False,
    seed: int = 20260622,
) -> BurstPlan:
    """n_bubbles 개 버블의 상대 schedule 을 미리 계산한다(scheduler 계획, 랜덤 sleep 아님).

    기본은 결정론(모델 중앙값 간격 누적). jitter=True 면 seed 고정 log-normal 샘플로 약한 변동을 주되,
    각 간격은 [MIN_INTER_BUBBLE_S, MAX_INTER_BUBBLE_S] 로 cap 한다(긴 sleep 방지).
    """
    import numpy as np

    if n_bubbles < 1:
        raise ValueError("n_bubbles 는 1 이상이어야 한다.")
    offsets = [float(start_offset_s)]
    gen = rng(seed) if jitter else None
    for _ in range(n_bubbles - 1):
        if gen is not None:
            sample = float(np.exp(gen.normal(model.log_mean, model.log_std)))
            interval = float(np.clip(sample, MIN_INTER_BUBBLE_S, MAX_INTER_BUBBLE_S))
        else:
            interval = model.expected_interval_s()
        offsets.append(offsets[-1] + interval)
    return BurstPlan(offsets_s=tuple(offsets))
