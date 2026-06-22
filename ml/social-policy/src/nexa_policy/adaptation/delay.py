"""delay personalization(NEXA-P19-T006). 운영 데이터 미접근 — 합성 fixture·결정론. torch 미사용(numpy).

길드 tempo 와 사용자별 **관찰 지연**으로 응답 **타이밍 calibration** 만 조정한다(언제 답하느냐 — 시간 분포의
평탄/이동). 이것은 hazard 의 time temperature(calibration/time.py)와 같은 정신의 server/user-conditioned 보정이며,
모델 weight 학습이 아니다(ADR 0014).

acceptance(T006) — 직접 호출에 대한 강제 응답률을 올리는 방식이 아니다:
- 이 모듈은 **응답 여부(SPEAK 확률)를 바꾸지 않는다**. 오직 "응답한다면 얼마나 빨리/느리게"의 time scale 만
  조정한다. [DelayCalibration] 에 응답률·강제응답 필드가 없고, [adjust_delay_scale] 의 출력은 시간 배율뿐이다.
- 빠른 서버/빠른 사용자 → 더 빠른 타이밍(scale<1), 느린 서버/사용자 → 더 느린 타이밍(scale>1). bounded.
- 직접 호출(멘션) 처리 자체는 P09/P12 의 별도 경계이며 여기서 강제 응답률을 끌어올리지 않는다.
"""

from __future__ import annotations

from dataclasses import dataclass

# 타이밍 배율 허용 범위(폭주 금지). 1.0=보정 없음, <1 빠름, >1 느림.
DELAY_SCALE_MIN = 0.5
DELAY_SCALE_MAX = 2.0


@dataclass(frozen=True)
class DelayObservation:
    """타이밍 보정 입력. 모두 관찰된 지연 통계(초). 응답 여부/강제율 필드 없음.

    - [guild_median_gap_s]: 길드 메시지 사이 중앙 간격(tempo — 빠른 서버는 작다).
    - [reference_gap_s]: 기준 tempo(코호트 평균 등). guild/reference 비가 서버 배율을 만든다.
    - [user_observed_delay_s]: 이 사용자가 보통 응답을 받기까지 관찰된 지연(개인 적응).
    - [user_reference_delay_s]: 기준 사용자 지연. user/reference 비가 사용자 배율을 만든다.
    """

    guild_median_gap_s: float
    reference_gap_s: float
    user_observed_delay_s: float
    user_reference_delay_s: float

    def __post_init__(self) -> None:
        for name, v in (
            ("guild_median_gap_s", self.guild_median_gap_s),
            ("reference_gap_s", self.reference_gap_s),
            ("user_observed_delay_s", self.user_observed_delay_s),
            ("user_reference_delay_s", self.user_reference_delay_s),
        ):
            if v <= 0:
                raise ValueError(f"{name} 은 양수여야 한다: {v}")


@dataclass(frozen=True)
class DelayCalibration:
    """결정된 타이밍 배율. 응답 여부와 무관 — '언제'만 조정한다.

    - [guild_scale]/[user_scale]: 각 축의 raw 배율(보정 전).
    - [combined_scale]: clamp 된 최종 시간 배율(<1 빠름, >1 느림).
    - [clamped]: 범위 clamp 가 걸렸는가.
    """

    guild_scale: float
    user_scale: float
    combined_scale: float
    clamped: bool

    def to_dict(self) -> dict[str, object]:
        return {
            "guild_scale": self.guild_scale,
            "user_scale": self.user_scale,
            "combined_scale": self.combined_scale,
            "clamped": self.clamped,
        }


def _blend(guild_scale: float, user_scale: float, *, user_weight: float) -> float:
    """길드·사용자 배율을 log 공간 가중 기하평균으로 결합(중립 1.0 보존)."""
    import numpy as np

    g = np.log(guild_scale)
    u = np.log(user_scale)
    return float(np.exp((1.0 - user_weight) * g + user_weight * u))


def adjust_delay_scale(
    obs: DelayObservation, *, user_weight: float = 0.5
) -> DelayCalibration:
    """길드 tempo·사용자 관찰 지연으로 타이밍 배율을 정한다([DELAY_SCALE_MIN, MAX] clamp).

    guild_scale = guild_gap/reference_gap(빠른 서버<1), user_scale = user_delay/reference_delay.
    combined = 가중 기하평균 후 clamp. 응답률은 건드리지 않는다(acceptance T006).
    """
    if not 0.0 <= user_weight <= 1.0:
        raise ValueError("user_weight 는 [0,1] 범위여야 한다.")
    guild_scale = obs.guild_median_gap_s / obs.reference_gap_s
    user_scale = obs.user_observed_delay_s / obs.user_reference_delay_s
    raw = _blend(guild_scale, user_scale, user_weight=user_weight)
    combined = min(max(raw, DELAY_SCALE_MIN), DELAY_SCALE_MAX)
    clamped = abs(combined - raw) > 1e-12
    return DelayCalibration(
        guild_scale=guild_scale,
        user_scale=user_scale,
        combined_scale=combined,
        clamped=clamped,
    )
