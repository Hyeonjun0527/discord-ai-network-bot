"""장기 relationship consistency metric(NEXA-P19-T003). 운영 데이터 미접근 — 합성 fixture·결정론.

관계 상태(예: familiarity)의 시간 변화가 **실제 관찰된 상호작용과 일치**하고, 갑작스런 친밀도 점프가 없는지
측정한다. 30/90일 코호트(cohort-design.md)에서 identity consistency 와 짝을 이룬다. torch 미사용(numpy).

핵심 구분(acceptance T003 — 사용자 심리를 정답으로 요구하지 않는다):
- ground truth 는 **관찰된 상호작용 신호**(교환 burst·reaction·응답)뿐이다. "사용자가 NEXA 를 좋아한다/싫어한다"
  같은 심리 라벨을 정답으로 쓰지 않는다(observable-state-policy 정합 — central FamiliarityCalculator 와 같은 정신).
- **alignment**: 친밀도 증가 구간에서 실제 교환량도 증가했는가(단조 정합 — Spearman 부호 일치 비율).
- **jump**: 인접 시점 친밀도 변화가 그 구간 관찰 상호작용으로 **설명되지 않는** 급등이 있는가.
"""

from __future__ import annotations

from dataclasses import dataclass


@dataclass(frozen=True)
class RelationshipObservation:
    """한 시점의 관계 상태 관찰. 모두 관찰된 신호다(심리 라벨 아님).

    - [familiarity]: 관계 상태 지표 [0,1](central FamiliarityCalculator 의 출력 같은 값).
    - [exchanged_bursts_delta]: 직전 시점 이후 새로 교환한 burst 수(관찰된 상호작용 양, ≥0).
    - [observed_reactions_delta]: 직전 이후 새로 관찰된 reaction 수(≥0).
    """

    familiarity: float
    exchanged_bursts_delta: int = 0
    observed_reactions_delta: int = 0

    def __post_init__(self) -> None:
        if not 0.0 <= self.familiarity <= 1.0:
            raise ValueError("familiarity 는 [0,1] 범위여야 한다.")
        if self.exchanged_bursts_delta < 0 or self.observed_reactions_delta < 0:
            raise ValueError("관찰 delta 는 음수일 수 없다.")

    @property
    def interaction_volume(self) -> int:
        """이 구간의 관찰된 상호작용 총량(burst + reaction). alignment 의 관찰 축."""
        return self.exchanged_bursts_delta + self.observed_reactions_delta


def alignment_rate(observations: list[RelationshipObservation]) -> float:
    """친밀도 변화 방향이 관찰 상호작용 양과 정합하는 전이 비율 [0,1].

    각 인접 전이에서 familiarity 가 올랐으면(>0) 그 구간 interaction_volume>0 이어야 정합(친밀도 상승은
    실제 교환으로 설명돼야 함). familiarity 가 내렸거나 그대로면(감쇠/무상호작용) 정합으로 본다(관찰과 모순 아님).
    비교 가능한 전이가 없으면 1.0(불일치 없음).
    """
    aligned = 0
    total = 0
    for prev, cur in zip(observations, observations[1:], strict=False):
        total += 1
        rose = cur.familiarity > prev.familiarity + 1e-12
        if rose:
            # 상승은 그 구간 관찰 상호작용으로 뒷받침돼야 정합.
            if cur.interaction_volume > 0:
                aligned += 1
        else:
            # 하락/유지는 감쇠·무상호작용으로 설명 가능 → 정합.
            aligned += 1
    return aligned / total if total else 1.0


def unexplained_jumps(
    observations: list[RelationshipObservation], *, jump_threshold: float
) -> list[int]:
    """관찰 상호작용 없이 친밀도가 [jump_threshold] 넘게 급등한 전이 index 목록(갑작스런 친밀도 점프).

    interaction_volume==0 인데 familiarity 가 임계 이상 뛴 전이를 "설명되지 않는 점프"로 표시한다.
    index 는 후행 시점(cur)의 위치.
    """
    if jump_threshold <= 0:
        raise ValueError("jump_threshold 는 양수여야 한다.")
    flagged: list[int] = []
    for i, (prev, cur) in enumerate(zip(observations, observations[1:], strict=False), start=1):
        delta = cur.familiarity - prev.familiarity
        if delta > jump_threshold and cur.interaction_volume == 0:
            flagged.append(i)
    return flagged


@dataclass(frozen=True)
class RelationshipConsistencyReport:
    """장기 relationship consistency 요약. 관찰 신호만으로 계산(심리 정답 불요)."""

    n_observations: int
    alignment_rate: float
    max_familiarity_jump: float
    unexplained_jump_count: int

    def is_consistent(self, *, min_alignment: float) -> bool:
        """정합률이 충분하고 설명되지 않는 급등이 없는지(둘 다 통과)."""
        return self.alignment_rate >= min_alignment and self.unexplained_jump_count == 0

    def to_dict(self) -> dict[str, object]:
        return {
            "n_observations": self.n_observations,
            "alignment_rate": self.alignment_rate,
            "max_familiarity_jump": self.max_familiarity_jump,
            "unexplained_jump_count": self.unexplained_jump_count,
        }


def evaluate_relationship_consistency(
    observations: list[RelationshipObservation], *, jump_threshold: float = 0.3
) -> RelationshipConsistencyReport:
    """관계 관찰 시퀀스에서 일관성 리포트를 만든다(결정론). 관찰된 신호만 입력으로 쓴다."""
    jumps = unexplained_jumps(observations, jump_threshold=jump_threshold)
    max_jump = 0.0
    for prev, cur in zip(observations, observations[1:], strict=False):
        max_jump = max(max_jump, abs(cur.familiarity - prev.familiarity))
    return RelationshipConsistencyReport(
        n_observations=len(observations),
        alignment_rate=alignment_rate(observations),
        max_familiarity_jump=max_jump,
        unexplained_jump_count=len(jumps),
    )
