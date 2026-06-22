"""NEXA-P19-T006: delay personalization — 타이밍 배율만 조정하고 강제 응답률을 올리지 않는다."""

from __future__ import annotations

import pytest

from nexa_policy.adaptation.delay import (
    DELAY_SCALE_MAX,
    DELAY_SCALE_MIN,
    DelayCalibration,
    DelayObservation,
    adjust_delay_scale,
)


def test_acceptance_출력은_타이밍_배율뿐_응답률_필드_없음():
    cal = adjust_delay_scale(DelayObservation(10, 10, 5, 5))
    # DelayCalibration 에는 응답률/강제응답 필드가 없다(타이밍만).
    fields = set(DelayCalibration.__dataclass_fields__)
    assert "response_rate" not in fields and "forced_response" not in fields
    assert cal.combined_scale == pytest.approx(1.0)  # 중립 입력 → 보정 없음.


def test_빠른_서버와_사용자는_더_빠른_타이밍():
    # 빠른 길드(작은 gap), 빠른 사용자(작은 지연) → scale < 1.
    fast = adjust_delay_scale(DelayObservation(2, 10, 1, 5))
    assert fast.combined_scale < 1.0


def test_느린_서버와_사용자는_더_느린_타이밍():
    slow = adjust_delay_scale(DelayObservation(30, 10, 20, 5))
    assert slow.combined_scale > 1.0


def test_배율은_clamp_범위_안():
    extreme = adjust_delay_scale(DelayObservation(1000, 1, 1000, 1))
    assert DELAY_SCALE_MIN <= extreme.combined_scale <= DELAY_SCALE_MAX
    assert extreme.clamped


def test_user_weight_로_서버_개인_비중_조절():
    obs = DelayObservation(guild_median_gap_s=2, reference_gap_s=10, user_observed_delay_s=20, user_reference_delay_s=5)
    server_heavy = adjust_delay_scale(obs, user_weight=0.0)
    user_heavy = adjust_delay_scale(obs, user_weight=1.0)
    # 서버는 빠름(scale<1), 사용자는 느림(scale>1) → 가중에 따라 결과가 다르다.
    assert server_heavy.combined_scale < user_heavy.combined_scale


def test_양수_검증():
    with pytest.raises(ValueError):
        DelayObservation(0, 10, 5, 5)
