"""right-censoring 데이터 처리(NEXA-P12-T002).

생존분석은 표본마다 (생존시간 `t`, 사건 관찰 여부 `event_observed`) 쌍이 필요하다. opportunity 이후
행동이 관찰되면 `event_observed=True`(시간=행동까지 delay), 관찰이 잘렸으면 `event_observed=False`
(검열 — `t > c` 만 안다)다. 검열을 "행동 안 함(never)"으로 잘못 학습하면 모델이 과소 발화한다.

**acceptance(T002) — 검열 샘플을 never 정답으로 잘못 학습하지 않는다**:
- 검열 사유 셋(세션 종료·관찰 창 종료·동의 철회)은 모두 `event_observed=False` 의 우중도절단이다.
  [SurvivalSample.is_event] 는 False, 생존시간은 "최소 이만큼은 안 했다"의 하한일 뿐이다.
- **진짜 never**(observed_full_window 로 끝까지 봤는데 행동 없음)도 사건 미발생이지만, 검열과 구분해
  [CensorReason.TRUE_NEVER] 로 표시한다 — 검열은 "더 못 봄", never 는 "충분히 봤는데 안 함".
- P10 delay 라벨([data.labels.delay.DelayLabel])과 일관: censored↔검열, is_never↔진짜 never.

torch 비의존 — 순수 파이썬/dataclass. numpy 배열 변환은 [to_survival_arrays] 가 담당(결정론).
"""

from __future__ import annotations

from dataclasses import dataclass
from enum import Enum
from typing import TYPE_CHECKING

from nexa_policy.data.labels.delay import DelayLabel
from nexa_policy.time.origin import MS_PER_SECOND

if TYPE_CHECKING:
    import numpy as np


class CensorReason(Enum):
    """사건 미발생/검열 사유(time-origin.md right-censoring 정의)."""

    # ── 검열(우중도절단): "더 못 봤다" — never 아님 ──
    SESSION_END = "session_end"  # 관찰 창이 세션 경계로 잘림.
    OBSERVATION_WINDOW_END = "observation_window_end"  # 고정 관찰 창 종료.
    CONSENT_WITHDRAWAL = "consent_withdrawal"  # 동의 철회 → 이후 관찰 불가.
    # ── 진짜 never: "충분히 봤는데 안 함" — 검열 아님 ──
    TRUE_NEVER = "true_never"


_CENSORED_REASONS = frozenset(
    {
        CensorReason.SESSION_END,
        CensorReason.OBSERVATION_WINDOW_END,
        CensorReason.CONSENT_WITHDRAWAL,
    }
)


@dataclass(frozen=True)
class SurvivalSample:
    """생존분석 한 표본: (시간 `t`초, 사건 관찰 여부).

    - [duration_s]: 사건 관찰 시 행동까지 시간(초), 검열/never 시 관찰 한계(`c`초, "최소 이만큼").
    - [event_observed]: True 면 그 시각에 행동 발생(uncensored). False 면 검열 또는 진짜 never.
    - [reason]: 사건 미발생일 때의 사유(검열 3종 vs 진짜 never). 사건 관찰 시 None.
    """

    duration_s: float
    event_observed: bool
    reason: CensorReason | None

    def __post_init__(self) -> None:
        if self.duration_s < 0:
            raise ValueError(f"duration 은 음수일 수 없다: {self.duration_s}")
        if self.event_observed and self.reason is not None:
            raise ValueError("사건이 관찰되면 검열/never 사유가 있을 수 없다.")
        if not self.event_observed and self.reason is None:
            raise ValueError("사건 미관찰이면 사유(검열 또는 never)가 있어야 한다.")

    @property
    def is_censored(self) -> bool:
        """우중도절단 여부 — 검열 3종만 True, 진짜 never 는 False(검열 아님)."""
        return self.reason in _CENSORED_REASONS

    @property
    def is_true_never(self) -> bool:
        """충분히 관찰했는데 행동 없음(진짜 never). 검열과 상호배타."""
        return self.reason is CensorReason.TRUE_NEVER


def from_delay_label(
    label: DelayLabel,
    *,
    observation_limit_s: float,
    censor_reason: CensorReason = CensorReason.SESSION_END,
) -> SurvivalSample:
    """P10 [DelayLabel] 을 생존 표본으로 변환한다(검열↔never 구분 보존).

    - delay 있으면 사건 관찰: `duration = delay_ms/1000`, event_observed=True.
    - censored 면 우중도절단: `duration = observation_limit_s`, reason=[censor_reason](검열 3종 중 하나).
    - is_never 면 진짜 never: `duration = observation_limit_s`, reason=TRUE_NEVER.
    """
    if label.delay_ms is not None:
        return SurvivalSample(
            duration_s=label.delay_ms / MS_PER_SECOND,
            event_observed=True,
            reason=None,
        )
    if label.is_never:
        return SurvivalSample(
            duration_s=observation_limit_s,
            event_observed=False,
            reason=CensorReason.TRUE_NEVER,
        )
    # censored: 우중도절단(세션 종료/관찰 창/동의 철회 중 하나). never 아님.
    if censor_reason not in _CENSORED_REASONS:
        raise ValueError(f"검열 사유는 우중도절단 3종이어야 한다: {censor_reason}")
    return SurvivalSample(
        duration_s=observation_limit_s,
        event_observed=False,
        reason=censor_reason,
    )


def to_survival_arrays(
    samples: list[SurvivalSample],
) -> tuple[np.ndarray, np.ndarray]:
    """표본 리스트 → (durations[n] float, events[n] {0,1}). 생존모델·metric 공통 입력.

    검열·진짜 never 모두 events=0(사건 미발생)이지만, 학습에서 never 를 정답으로 강제하지 않도록
    durations 는 관찰 한계(하한)로만 들어간다 — 모델은 "최소 이만큼 생존"만 학습한다(acceptance T002).
    """
    import numpy as np

    if not samples:
        return np.zeros(0, dtype=np.float64), np.zeros(0, dtype=np.int64)
    durations = np.array([s.duration_s for s in samples], dtype=np.float64)
    events = np.array([1 if s.event_observed else 0 for s in samples], dtype=np.int64)
    return durations, events
