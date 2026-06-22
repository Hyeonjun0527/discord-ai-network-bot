"""NEXA-P19-T005: adaptive talkativeness — 범위 초과 없이 천천히 보정하고 설명·rollback 가능."""

from __future__ import annotations

import pytest

from nexa_policy.adaptation.talkativeness import (
    ParticipationSignals,
    TalkativenessCalibrationConfig,
    adjust,
    rollback,
)


def _config(baseline=1.0, lower=0.8, upper=1.2, max_step=0.05):
    return TalkativenessCalibrationConfig(baseline=baseline, lower=lower, upper=upper, max_step=max_step)


def test_acceptance_범위를_넘지_않는다():
    cfg = _config()
    # 강한 놓침 신호를 여러 번 줘도 upper 를 넘지 못한다.
    cur = 1.0
    for _ in range(100):
        cur = adjust(cur, ParticipationSignals(0.0, 1.0), cfg).applied
    assert cur <= cfg.upper + 1e-12
    # 강한 불만 신호도 lower 아래로 못 간다.
    cur = 1.0
    for _ in range(100):
        cur = adjust(cur, ParticipationSignals(1.0, 0.0), cfg).applied
    assert cur >= cfg.lower - 1e-12


def test_한_step_변화량은_max_step_이하():
    cfg = _config(max_step=0.05)
    a = adjust(1.0, ParticipationSignals(0.0, 1.0), cfg)
    assert abs(a.applied - 1.0) <= cfg.max_step + 1e-12


def test_방향_불만은_줄이고_놓침은_늘린다():
    cfg = _config()
    down = adjust(1.0, ParticipationSignals(intrusion_complaint_rate=0.8, missed_interaction_rate=0.1), cfg)
    up = adjust(1.0, ParticipationSignals(intrusion_complaint_rate=0.1, missed_interaction_rate=0.8), cfg)
    assert down.applied < 1.0
    assert up.applied > 1.0


def test_조정은_설명_가능():
    a = adjust(1.0, ParticipationSignals(0.2, 0.6), _config())
    assert "missed" in a.reason and "intrusion" in a.reason
    assert a.previous == 1.0


def test_rollback_은_baseline():
    cfg = _config(baseline=1.0)
    assert rollback(cfg) == 1.0


def test_clamp_플래그():
    cfg = _config(lower=0.99, upper=1.01, max_step=0.05)
    a = adjust(1.01, ParticipationSignals(0.0, 1.0), cfg)
    assert a.clamped


def test_baseline_범위밖이면_거부():
    with pytest.raises(ValueError):
        TalkativenessCalibrationConfig(baseline=1.5, lower=0.8, upper=1.2)
