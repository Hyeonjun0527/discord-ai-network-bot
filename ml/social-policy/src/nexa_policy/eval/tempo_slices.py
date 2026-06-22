"""채널 tempo 조건부 timing 평가(NEXA-P12-T015).

timing 모델의 오차를 채널 tempo slice(조용함/보통/빠름)별로 쪼개 본다. 평균 한 줄로 보면 빠른 채널의
나쁜 timing 이 다수의 느린 채널 성능에 가려질 수 있다 — 이 모듈은 slice별 metric 과 함께 "평균이 worst
slice 를 숨기는가" 를 정량 판정한다.

**acceptance(T015) — 평균 성능이 빠른 채널 오류를 숨기지 않는다**:
- [evaluate_by_tempo]: 각 slice 의 survival metric(검열 지원, eval.survival 재사용)을 따로 계산한다.
- [TempoSliceReport.hides_fast_channel_error]: worst slice(보통 빠름)의 integrated Brier 가 전체 평균보다
  유의하게 나쁜데도 평균만 보면 합격선 안이면 True — 즉 "평균이 숨긴다" 를 명시적으로 드러낸다.

torch 비의존 — numpy. eval.survival(T004) 와 결합. 운영 데이터 금지(slice 라벨은 호출자가 tempo feature 로
구간 분류해 넘긴다 — 이 모듈은 분류 규칙이 아니라 slice별 집계·은닉 판정만 한다).
"""

from __future__ import annotations

from dataclasses import dataclass
from enum import StrEnum
from typing import TYPE_CHECKING

from nexa_policy.eval.survival import SurvivalMetrics, evaluate_survival

if TYPE_CHECKING:
    import numpy as np


class TempoSlice(StrEnum):
    """채널 대화 속도 slice. tempo feature(분당 메시지 등)로 호출자가 분류한다."""

    QUIET = "quiet"
    NORMAL = "normal"
    FAST = "fast"


@dataclass(frozen=True)
class TempoSliceReport:
    """slice별 timing metric + 평균이 worst slice 를 숨기는지의 판정.

    [per_slice]: slice → SurvivalMetrics(검열 지원). [overall]: 전체(slice 합산) metric.
    [hides_fast_channel_error]: 평균만 보면 합격선 안이지만 worst slice 가 임계 이상 더 나쁘면 True.
    """

    per_slice: dict[TempoSlice, SurvivalMetrics]
    overall: SurvivalMetrics
    hides_fast_channel_error: bool

    def to_dict(self) -> dict[str, object]:
        return {
            "per_slice": {s.value: m.to_dict() for s, m in self.per_slice.items()},
            "overall": self.overall.to_dict(),
            "hides_fast_channel_error": self.hides_fast_channel_error,
        }


def evaluate_by_tempo(
    hazard: np.ndarray,
    event_bin: np.ndarray,
    event_observed: np.ndarray,
    tempo_slice: np.ndarray,
    *,
    overall_pass_brier: float = 0.25,
    hide_gap: float = 0.05,
) -> TempoSliceReport:
    """tempo slice별 timing 오차를 비교하고 평균이 worst slice 를 숨기는지 판정한다.

    [tempo_slice]: 각 표본의 slice 라벨(TempoSlice.value 문자열 배열). slice별로 마스킹해 survival metric 을
    따로 계산한다. "은닉" 판정: 전체 integrated Brier 가 [overall_pass_brier] 이하(=합격선 안)인데 worst
    slice 의 integrated Brier 가 전체보다 [hide_gap] 이상 더 나쁘면 True — 평균이 빠른 채널 오류를 숨긴다.
    """
    overall = evaluate_survival(hazard, event_bin, event_observed)
    per_slice: dict[TempoSlice, SurvivalMetrics] = {}
    for s in TempoSlice:
        mask = tempo_slice == s.value
        if not mask.any():
            continue
        per_slice[s] = evaluate_survival(
            hazard[mask], event_bin[mask], event_observed[mask]
        )

    worst_brier = (
        max(m.integrated_brier for m in per_slice.values()) if per_slice else overall.integrated_brier
    )
    hides = bool(
        overall.integrated_brier <= overall_pass_brier
        and worst_brier - overall.integrated_brier >= hide_gap
    )
    return TempoSliceReport(
        per_slice=per_slice, overall=overall, hides_fast_channel_error=hides
    )
