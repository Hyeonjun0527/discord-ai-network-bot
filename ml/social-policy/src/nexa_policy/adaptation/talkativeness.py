"""adaptive talkativeness 보정(NEXA-P19-T005). 운영 데이터 미접근 — 합성 fixture·결정론. torch 미사용.

운영자가 정한 talkativeness multiplier **주변에서 calibration 만 천천히** 조정한다. 모델 weight/identity 를
학습하지 않는다(ADR 0014 — 실시간 가능한 것은 bounded calibration 뿐). central TalkativenessMultiplier 와
같은 [0,2] 범위·logit 가산 의미를 따른다(inference/talkativeness.py).

acceptance(T005) — 사용자 설정 범위를 넘지 않고 자동 변경 내역을 설명·rollback 할 수 있다:
- **clamp**: 보정된 multiplier 는 항상 운영자 설정 [lower, upper] 안이다([adjust] 가 clamp 보장).
- **slow**: 한 step 변화량이 [max_step] 으로 제한된다(폭주 금지 — 천천히만 움직인다).
- **explain**: 각 조정은 [TalkativenessAdjustment] 로 (이전·이후·관찰 신호·이유)를 남긴다(설명 가능).
- **rollback**: [rollback] 이 운영자 baseline multiplier 로 즉시 복귀한다(자동 변경 되돌리기).

조정 신호: 관찰된 over/under-participation(예: 끼어듦 불만↑ → 줄이고, 발화 기회 놓침 FIR↑ → 약간 늘림).
어떤 경우에도 직접 호출 강제 응답률을 올리는 방향이 아니다(T006 경계와 일관 — engagement 조작 금지).
"""

from __future__ import annotations

from dataclasses import dataclass

# central TalkativenessMultiplier 와 동일한 허용 범위.
MULTIPLIER_MIN = 0.0
MULTIPLIER_MAX = 2.0


@dataclass(frozen=True)
class TalkativenessCalibrationConfig:
    """운영자가 정한 baseline 과 자동 보정 허용 범위·속도.

    - [baseline]: 운영자가 설정한 기준 multiplier(rollback target).
    - [lower]/[upper]: 자동 보정이 넘을 수 없는 사용자 설정 범위(baseline 을 포함해야 함).
    - [max_step]: 한 step 최대 변화량(천천히).
    """

    baseline: float
    lower: float
    upper: float
    max_step: float = 0.05

    def __post_init__(self) -> None:
        for v in (self.baseline, self.lower, self.upper):
            if not MULTIPLIER_MIN <= v <= MULTIPLIER_MAX:
                raise ValueError(f"multiplier 는 [{MULTIPLIER_MIN}, {MULTIPLIER_MAX}] 범위여야 한다: {v}")
        if not self.lower <= self.baseline <= self.upper:
            raise ValueError("baseline 은 [lower, upper] 안이어야 한다.")
        if self.max_step <= 0:
            raise ValueError("max_step 은 양수여야 한다.")


@dataclass(frozen=True)
class TalkativenessAdjustment:
    """한 번의 자동 보정 기록(설명·감사·rollback 용)."""

    previous: float
    proposed: float
    applied: float
    reason: str
    clamped: bool

    def to_dict(self) -> dict[str, object]:
        return {
            "previous": self.previous,
            "proposed": self.proposed,
            "applied": self.applied,
            "reason": self.reason,
            "clamped": self.clamped,
        }


@dataclass(frozen=True)
class ParticipationSignals:
    """보정 방향을 정하는 관찰 신호(가명·집계). 모두 [0,1] 관찰 비율.

    - [intrusion_complaint_rate]: 끼어듦/과다 발화 불만 비율(높으면 줄임).
    - [missed_interaction_rate]: 발화 기회를 놓친 비율(FIR/MIR 계열, 높으면 약간 늘림).
    """

    intrusion_complaint_rate: float
    missed_interaction_rate: float

    def __post_init__(self) -> None:
        for v in (self.intrusion_complaint_rate, self.missed_interaction_rate):
            if not 0.0 <= v <= 1.0:
                raise ValueError("관찰 신호는 [0,1] 범위여야 한다.")

    def direction(self) -> float:
        """보정 방향 [-1,1]: 불만 우세면 음수(줄임), 놓침 우세면 양수(늘림). 둘 다 없으면 0."""
        return self.missed_interaction_rate - self.intrusion_complaint_rate


def adjust(
    current: float,
    signals: ParticipationSignals,
    config: TalkativenessCalibrationConfig,
) -> TalkativenessAdjustment:
    """관찰 신호로 multiplier 를 한 step 보정한다(천천히·범위 내·설명 가능).

    proposed = current + direction*max_step → [lower, upper] 로 clamp. clamp 되면 clamped=True.
    """
    direction = signals.direction()
    proposed = current + direction * config.max_step
    applied = min(max(proposed, config.lower), config.upper)
    clamped = abs(applied - proposed) > 1e-12
    reason = (
        f"missed={signals.missed_interaction_rate:.3f}, "
        f"intrusion={signals.intrusion_complaint_rate:.3f}, dir={direction:+.3f}"
    )
    return TalkativenessAdjustment(
        previous=current,
        proposed=proposed,
        applied=applied,
        reason=reason,
        clamped=clamped,
    )


def rollback(config: TalkativenessCalibrationConfig) -> float:
    """자동 보정을 운영자 baseline multiplier 로 즉시 되돌린다(rollback)."""
    return config.baseline
