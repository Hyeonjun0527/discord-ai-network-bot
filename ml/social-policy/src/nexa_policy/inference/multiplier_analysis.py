"""talkativeness multiplier saturation·fairness 분석(NEXA-P12-T010).

서버별 talkativeness multiplier(특히 1.5x)가 채널 tempo·관계 상태별로 **실제 발화율**에 얼마나 영향을 주는지
시뮬레이션한다. multiplier 는 hazard logit 에 `ln(multiplier)` 가산이라 오즈 배율로 작용하므로, base hazard
가 이미 높은(=빠른 대화·가까운 관계) 구간에서는 cap 근처로 **포화**한다. 이 포화가 곧 "과도한 끼어들기 방어"
이기도 하지만, 빠른 대화의 누적 발화확률을 worst-case 로 정량 보고해야 한다(acceptance T010).

**acceptance(T010) — 빠른 대화에서 1.5x 가 과도한 끼어들기로 변하지 않는지 worst-case 를 보고한다**:
- [per_bin_speak_probability]: bin 별 hazard 에서 "관찰 창 내 발화" 누적확률(`1 - prod(1-h)`)을 base/scaled 로 계산.
- [analyze_multiplier]: tempo×관계 시나리오별로 base 대비 scaled 발화확률 **증가폭**과 cap 포화율을 낸다.
- [MultiplierFairnessReport.worst_case]: scaled 발화확률 절대값이 가장 높은 시나리오(=가장 끼어들 위험).

scale_hazard(T009)와 같은 수식을 쓴다(중복 구현 금지). torch 비의존 — numpy. 운영 데이터 금지(시나리오는
seed 결정론 합성 hazard).
"""

from __future__ import annotations

from dataclasses import dataclass
from typing import TYPE_CHECKING

from nexa_policy.inference.talkativeness import HAZARD_CAP, scale_hazard

if TYPE_CHECKING:
    import numpy as np


def per_window_speak_probability(hazard: np.ndarray) -> float:
    """bin 별 조건부 hazard 에서 "관찰 창 내 최소 1회 발화" 누적확률 `1 - prod(1-h_k)`."""
    import numpy as np

    return float(1.0 - np.prod(1.0 - hazard))


@dataclass(frozen=True)
class TempoRelationScenario:
    """시나리오: 채널 tempo·관계 상태가 만든 base hazard 곡선(bin 별).

    [base_hazard] 가 클수록 빠른 대화/가까운 관계(이미 자주 발화). multiplier 포화를 본다.
    """

    tempo: str  # "quiet" | "normal" | "fast".
    relationship: str  # "stranger" | "acquaintance" | "close".
    base_hazard: tuple[float, ...]


@dataclass(frozen=True)
class ScenarioOutcome:
    """한 시나리오·multiplier 의 발화확률 변화·포화 결과."""

    tempo: str
    relationship: str
    multiplier: float
    base_speak_prob: float
    scaled_speak_prob: float
    saturated_bin_fraction: float  # scaled hazard 가 cap 근처(>=0.99*cap)인 bin 비율.

    @property
    def delta_speak_prob(self) -> float:
        """multiplier 로 늘어난 발화확률(scaled - base). 끼어들기 증가량."""
        return self.scaled_speak_prob - self.base_speak_prob


@dataclass(frozen=True)
class MultiplierFairnessReport:
    """multiplier fairness 분석 묶음. worst-case(가장 끼어들 위험) 시나리오를 명시한다."""

    multiplier: float
    outcomes: tuple[ScenarioOutcome, ...]

    @property
    def worst_case(self) -> ScenarioOutcome:
        """scaled 발화확률이 가장 높은 시나리오 — 가장 과도하게 끼어들 위험이 큰 곳."""
        return max(self.outcomes, key=lambda o: o.scaled_speak_prob)

    @property
    def max_delta(self) -> float:
        """모든 시나리오 중 최대 발화확률 증가폭."""
        return max(o.delta_speak_prob for o in self.outcomes)

    def to_dict(self) -> dict[str, object]:
        return {
            "multiplier": self.multiplier,
            "worst_case": {
                "tempo": self.worst_case.tempo,
                "relationship": self.worst_case.relationship,
                "scaled_speak_prob": self.worst_case.scaled_speak_prob,
                "delta": self.worst_case.delta_speak_prob,
            },
            "max_delta": self.max_delta,
            "outcomes": [
                {
                    "tempo": o.tempo,
                    "relationship": o.relationship,
                    "base_speak_prob": o.base_speak_prob,
                    "scaled_speak_prob": o.scaled_speak_prob,
                    "delta": o.delta_speak_prob,
                    "saturated_bin_fraction": o.saturated_bin_fraction,
                }
                for o in self.outcomes
            ],
        }


def analyze_multiplier(
    scenarios: tuple[TempoRelationScenario, ...], multiplier: float
) -> MultiplierFairnessReport:
    """각 시나리오에 multiplier 를 적용해 발화확률 변화·cap 포화를 계산한다(scale_hazard 재사용)."""
    import numpy as np

    outcomes: list[ScenarioOutcome] = []
    for sc in scenarios:
        base = np.asarray(sc.base_hazard, dtype=np.float64)
        scaled = scale_hazard(base, multiplier)
        saturated = float(np.mean(scaled >= 0.99 * HAZARD_CAP))
        outcomes.append(
            ScenarioOutcome(
                tempo=sc.tempo,
                relationship=sc.relationship,
                multiplier=multiplier,
                base_speak_prob=per_window_speak_probability(base),
                scaled_speak_prob=per_window_speak_probability(scaled),
                saturated_bin_fraction=saturated,
            )
        )
    return MultiplierFairnessReport(multiplier=multiplier, outcomes=tuple(outcomes))


def default_scenarios() -> tuple[TempoRelationScenario, ...]:
    """tempo×관계 합성 시나리오(seed 결정론·운영 데이터 아님).

    fast/close 일수록 base hazard 가 높다(이미 자주 발화) — multiplier 포화·끼어들기 worst-case 후보.
    """
    return (
        TempoRelationScenario("quiet", "stranger", (0.02, 0.03, 0.04, 0.05)),
        TempoRelationScenario("normal", "acquaintance", (0.10, 0.12, 0.14, 0.16)),
        TempoRelationScenario("fast", "close", (0.45, 0.50, 0.55, 0.60)),
    )
