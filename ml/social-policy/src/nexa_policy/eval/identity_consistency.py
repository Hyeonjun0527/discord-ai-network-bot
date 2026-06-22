"""장기 identity consistency metric(NEXA-P19-T002). 운영 데이터 미접근 — 합성 fixture·결정론.

NEXA 의 정체성(가치·취향·말투·금지사항)이 **시간에 따라 안정**적인지, 급변/붕괴/반복이 없는지 측정한다.
30/90일 코호트(cohort-design.md)에서 같은 metric 으로 본다. torch 미사용(numpy).

핵심 구분(acceptance T002 — 단순 문장 유사도와 사실 모순을 분리한다):
- **표현 안정성**(representation drift): 정체성 임베딩(값/취향/말투의 벡터)이 인접 시점 사이 얼마나 변했는가.
  급변(jump)·붕괴(collapse)를 cosine drift 로 본다. 이건 "문장 유사도" 축이다.
- **사실 모순**(factual contradiction): 같은 슬롯(예: 좋아하는 색)의 **단정 값**이 시간에 따라 모순되게
  바뀌는가. 이건 임베딩 유사도와 **다른 축**이다 — 말투는 그대로여도 사실이 뒤집힐 수 있고, 그 반대도 가능하다.
- **금지사항 위반**(prohibition violation)·**반복**(repetition): 정체성 가드레일을 깬 시점 수, 같은 발화의
  과도한 반복 비율.

각 축을 분리해 보고하므로 "말투는 비슷한데 사실이 뒤집힌" 사례를 유사도 평균이 가리지 않는다.
"""

from __future__ import annotations

from dataclasses import dataclass
from typing import TYPE_CHECKING

if TYPE_CHECKING:
    import numpy as np


@dataclass(frozen=True)
class IdentitySnapshot:
    """한 시점의 NEXA 정체성 관찰. 가치/취향/말투는 임베딩, 사실 슬롯은 닫힌 값.

    - [embedding]: 가치·취향·말투를 합친 정체성 표현 벡터(정규화 전·임의 차원).
    - [fact_slots]: 단정 가능한 사실 슬롯의 현재 값(예: {"favorite_color": "blue"}). 모순 탐지의 축.
    - [prohibition_violations]: 이 시점에서 깨진 정체성 가드레일 수(0 이면 위반 없음).
    - [utterance_signature]: 발화 내용의 안정 해시/서명(반복 탐지용). None 이면 발화 없음(침묵).
    """

    embedding: np.ndarray
    fact_slots: dict[str, str]
    prohibition_violations: int = 0
    utterance_signature: str | None = None

    def __post_init__(self) -> None:
        if self.prohibition_violations < 0:
            raise ValueError("prohibition_violations 는 음수일 수 없다.")


def _cosine(a: np.ndarray, b: np.ndarray) -> float:
    import numpy as np

    na = float(np.linalg.norm(a))
    nb = float(np.linalg.norm(b))
    if na == 0.0 or nb == 0.0:
        return 0.0
    return float(np.dot(a, b) / (na * nb))


def representation_drifts(snapshots: list[IdentitySnapshot]) -> list[float]:
    """인접 시점 사이 정체성 표현의 drift(1 - cosine) 목록. 길이 = len-1, 클수록 급변.

    이 축은 **말투/취향 표현의 변화량**이다(문장 유사도 성격). 사실 모순과 분리해 본다.
    """
    drifts: list[float] = []
    for prev, cur in zip(snapshots, snapshots[1:], strict=False):
        drifts.append(1.0 - _cosine(prev.embedding, cur.embedding))
    return drifts


def max_representation_jump(snapshots: list[IdentitySnapshot]) -> float:
    """인접 drift 의 최댓값(급변 탐지). 시점이 1개 이하면 0.0(변화 없음)."""
    drifts = representation_drifts(snapshots)
    return max(drifts) if drifts else 0.0


def factual_contradiction_rate(snapshots: list[IdentitySnapshot]) -> float:
    """사실 슬롯이 시간에 따라 모순되게 바뀐 **전이** 비율(표현 유사도와 독립 축).

    각 슬롯에 대해 인접 시점에서 둘 다 값이 있는데 값이 달라지면 모순 1건. 분모는 그런 비교 가능 전이 수.
    같은 슬롯이 단조 갱신(예: 새 사실 추가)이 아니라 **단정 값의 뒤집힘**을 모순으로 센다.
    비교 가능한 전이가 없으면 0.0(모순 정의 불가 → 과신 금지).
    """
    contradictions = 0
    comparable = 0
    for prev, cur in zip(snapshots, snapshots[1:], strict=False):
        for slot, prev_val in prev.fact_slots.items():
            cur_val = cur.fact_slots.get(slot)
            if cur_val is None:
                continue
            comparable += 1
            if cur_val != prev_val:
                contradictions += 1
    return contradictions / comparable if comparable else 0.0


def repetition_rate(snapshots: list[IdentitySnapshot]) -> float:
    """발화 서명이 직전 발화와 같은(과도 반복) 비율. 침묵(None)은 분모에서 제외.

    분모는 발화가 있는 시점 중 직전에도 발화가 있던 비교 가능 전이 수. 비교 불가면 0.0.
    """
    prev_sig: str | None = None
    repeats = 0
    comparable = 0
    for snap in snapshots:
        sig = snap.utterance_signature
        if sig is not None and prev_sig is not None:
            comparable += 1
            if sig == prev_sig:
                repeats += 1
        if sig is not None:
            prev_sig = sig
    return repeats / comparable if comparable else 0.0


@dataclass(frozen=True)
class IdentityConsistencyReport:
    """장기 identity consistency 요약. 각 축이 분리돼 보고된다(평균이 한 축을 가리지 않음)."""

    n_snapshots: int
    mean_representation_drift: float
    max_representation_jump: float
    factual_contradiction_rate: float
    prohibition_violation_count: int
    repetition_rate: float

    def is_stable(self, *, jump_ceiling: float, contradiction_ceiling: float) -> bool:
        """표현 급변·사실 모순·금지 위반이 모두 임계 이하인지(둘 다 통과해야 안정).

        표현이 안정적이어도 사실이 뒤집히면 불안정으로 본다(두 축 AND).
        """
        return (
            self.max_representation_jump <= jump_ceiling
            and self.factual_contradiction_rate <= contradiction_ceiling
            and self.prohibition_violation_count == 0
        )

    def to_dict(self) -> dict[str, object]:
        return {
            "n_snapshots": self.n_snapshots,
            "mean_representation_drift": self.mean_representation_drift,
            "max_representation_jump": self.max_representation_jump,
            "factual_contradiction_rate": self.factual_contradiction_rate,
            "prohibition_violation_count": self.prohibition_violation_count,
            "repetition_rate": self.repetition_rate,
        }


def evaluate_identity_consistency(
    snapshots: list[IdentitySnapshot],
) -> IdentityConsistencyReport:
    """정체성 스냅샷 시퀀스에서 일관성 리포트를 만든다(결정론). 빈/단일 시퀀스는 안정으로 본다."""
    import numpy as np

    drifts = representation_drifts(snapshots)
    return IdentityConsistencyReport(
        n_snapshots=len(snapshots),
        mean_representation_drift=float(np.mean(drifts)) if drifts else 0.0,
        max_representation_jump=max(drifts) if drifts else 0.0,
        factual_contradiction_rate=factual_contradiction_rate(snapshots),
        prohibition_violation_count=sum(s.prohibition_violations for s in snapshots),
        repetition_rate=repetition_rate(snapshots),
    )
