"""오프라인 trajectory dataset builder(NEXA-P19-T010). 운영 데이터 미접근 — 합성 fixture·결정론. torch 미사용.

오프라인 RL(P19-T013~T015)용 **구간 trajectory** 를 만든다: scene-state → action → delay → outcome 의
시퀀스. 각 step 은 consent 와 lineage(provenance)를 동반하며, **실제 생성 문구만으로 reward 를 계산하지 않고**
취소(CANCEL)·침묵(IGNORE)도 포함한다(acceptance T010). reward 자체의 정의는 reward-contract.md(T011).

acceptance(T010):
- **consent + lineage**: consent 없는 step 은 trajectory 에 포함하지 않는다([build_trajectory] 가 필터). 모든
  step 은 source event ID([lineage])를 가진다(provenance — 환원·삭제 가능).
- **취소/침묵 포함**: action 은 발화(SPEAK/REACT)뿐 아니라 IGNORE(침묵)·CANCEL(취소)도 포함한다. 생성 문구가
  없는 step([emitted_text]=None)도 정당한 trajectory step 이다(침묵/취소는 학습 신호다).
"""

from __future__ import annotations

from dataclasses import dataclass, field
from typing import TYPE_CHECKING

from nexa_policy.data.labels.action import ActionClass

if TYPE_CHECKING:
    import numpy as np

# trajectory 가 다루는 action 어휘(생성 문구 유무와 독립). CANCEL/IGNORE 포함이 핵심.
TRAJECTORY_ACTIONS: tuple[str, ...] = ("ignore", "wait", "react", "speak", "cancel")


@dataclass(frozen=True)
class TrajectoryStep:
    """한 step: scene-state → action → delay → outcome (+ consent·lineage).

    - [scene_state]: 행동 결정 시점의 관찰 상태 벡터(P08 feature 카탈로그 차원). 생성 문구 아님.
    - [action]: 취한 행동(IGNORE/WAIT/REACT/SPEAK/CANCEL). 침묵·취소 포함.
    - [delay_bin]: 행동까지의 지연 bin index(침묵이면 'never' 계열일 수 있음).
    - [outcome]: 관찰된 결과 코드(reward-contract 의 입력. 자유 텍스트 아님).
    - [emitted_text]: 실제 생성 문구가 있으면 서명/None. **reward 를 이것만으로 계산하지 않는다**(침묵/취소도 신호).
    - [consent_opt_in]: 이 step 이 학습에 쓰일 수 있는 동의 여부.
    - [lineage]: 이 step 을 뒷받침하는 source event ID 목록(provenance — 비어 있을 수 없음).
    """

    scene_state: np.ndarray
    action: str
    delay_bin: int
    outcome: str
    consent_opt_in: bool
    lineage: list[str]
    emitted_text: str | None = None

    def __post_init__(self) -> None:
        if self.action not in TRAJECTORY_ACTIONS:
            raise ValueError(f"알 수 없는 action: {self.action}")
        if not self.lineage:
            raise ValueError("trajectory step 은 적어도 하나의 source event ID(lineage)를 가져야 한다.")
        if any(not eid for eid in self.lineage):
            raise ValueError("lineage event ID 는 비어 있을 수 없다.")

    @property
    def is_silent_or_cancel(self) -> bool:
        """침묵(IGNORE)이거나 취소(CANCEL) — 생성 문구가 없을 수 있는 정당한 step."""
        return self.action in (ActionClass.IGNORE.value, "cancel")


@dataclass(frozen=True)
class Trajectory:
    """consent 통과 step 들의 시퀀스(한 대화 구간). lineage 로 환원·삭제 가능."""

    segment_id: str
    steps: list[TrajectoryStep] = field(default_factory=list)

    @property
    def length(self) -> int:
        return len(self.steps)

    @property
    def silent_or_cancel_count(self) -> int:
        """침묵/취소 step 수(생성 문구 없는 신호 — 포함 증거)."""
        return sum(1 for s in self.steps if s.is_silent_or_cancel)

    @property
    def all_have_lineage(self) -> bool:
        return all(s.lineage for s in self.steps)


def build_trajectory(segment_id: str, candidate_steps: list[TrajectoryStep]) -> Trajectory:
    """consent 없는 step 을 제외하고 trajectory 를 만든다(lineage 는 step 생성 시 이미 강제).

    consent_opt_in=False 인 step 은 학습 trajectory 에 들어가지 않는다(privacy — cohort-design 정합).
    """
    consented = [s for s in candidate_steps if s.consent_opt_in]
    return Trajectory(segment_id=segment_id, steps=consented)


def make_synthetic_trajectory(
    *,
    segment_id: str = "seg-1",
    n_steps: int = 8,
    dim: int = 6,
    seed: int = 20260622,
    consent_drop_frac: float = 0.1,
) -> tuple[Trajectory, list[TrajectoryStep]]:
    """결정론 합성 trajectory 와 (consent 필터 전) 후보 step 목록을 만든다.

    SPEAK/REACT 와 함께 IGNORE/CANCEL 도 섞고, 일부 step 은 consent 미동의로 둬 필터가 동작함을 보인다.
    반환: (consent 통과 trajectory, 전체 후보 step). 같은 seed → 같은 결과.
    """
    import numpy as np

    gen = np.random.default_rng(seed)
    candidates: list[TrajectoryStep] = []
    for i in range(n_steps):
        # action 을 순환시켜 침묵/취소를 반드시 포함한다.
        action = TRAJECTORY_ACTIONS[i % len(TRAJECTORY_ACTIONS)]
        emitted = None if action in ("ignore", "cancel", "wait") else f"sig-{i}"
        outcome = "continued" if action in ("speak", "react") else "ignored"
        consent = gen.random() >= consent_drop_frac
        candidates.append(
            TrajectoryStep(
                scene_state=gen.random(dim).astype(np.float64),
                action=action,
                delay_bin=int(gen.integers(0, 5)),
                outcome=outcome,
                consent_opt_in=consent,
                lineage=[f"{segment_id}-e{i}"],
                emitted_text=emitted,
            )
        )
    return build_trajectory(segment_id, candidates), candidates
