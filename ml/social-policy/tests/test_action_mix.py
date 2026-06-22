"""NEXA-P19-T007: reaction/message 비율 적응 — 제한 범위 shift, FIR/MIR 동반 확인."""

from __future__ import annotations

import numpy as np
import pytest

from nexa_policy.adaptation.action_mix import (
    MAX_SHIFT_FRACTION,
    apply_mix_shift,
    evaluate_mix_shift,
)
from nexa_policy.datasets import ACTION_HEAD_CLASSES

_IGNORE = ACTION_HEAD_CLASSES.index("ignore")
_REACT = ACTION_HEAD_CLASSES.index("react")
_SPEAK = ACTION_HEAD_CLASSES.index("speak")


def _probs(rows):
    return np.array(rows, dtype=np.float64)


def test_shift_는_SPEAK에서_REACT로_질량_이동_행합_보존():
    p = _probs([[0.1, 0.0, 0.2, 0.7, 0.0]])  # ignore,wait,react,speak,cancel
    out = apply_mix_shift(p, 0.5)
    assert out[0, _SPEAK] == pytest.approx(0.35)
    assert out[0, _REACT] == pytest.approx(0.2 + 0.35)
    assert out[0, _IGNORE] == pytest.approx(0.1)  # IGNORE 불변.
    assert out.sum(axis=1)[0] == pytest.approx(1.0)


def test_제한_범위_초과_거부():
    with pytest.raises(ValueError):
        apply_mix_shift(_probs([[0.2, 0, 0.2, 0.6, 0]]), MAX_SHIFT_FRACTION + 0.1)


def test_acceptance_FIR_MIR_동반_보고_MIR은_악화되지_않음():
    # 일부는 실제 SPEAK. shift 후에도 IGNORE 로 가지 않으므로 MIR 악화 없음.
    y_true = np.array([_SPEAK, _SPEAK, _REACT, _IGNORE])
    probs = _probs(
        [
            [0.1, 0.0, 0.2, 0.7, 0.0],
            [0.1, 0.0, 0.3, 0.6, 0.0],
            [0.2, 0.0, 0.7, 0.1, 0.0],
            [0.8, 0.0, 0.1, 0.1, 0.0],
        ]
    )
    report = evaluate_mix_shift(y_true=y_true, probs=probs, shift_fraction=0.5)
    assert not report.mir_worsened  # IGNORE 불변 → 상호작용 놓침 증가 없음.
    assert report.to_dict()["shift_fraction"] == 0.5


def test_quality_improvement_판정은_FIR_MIR_둘다_확인():
    # SPEAK 가 shift 로 REACT 예측이 되어도 FIR(=IGNORE 예측)은 증가하지 않는다.
    y_true = np.array([_SPEAK, _SPEAK])
    probs = _probs([[0.1, 0, 0.2, 0.7, 0], [0.1, 0, 0.3, 0.6, 0]])
    report = evaluate_mix_shift(y_true=y_true, probs=probs, shift_fraction=0.5)
    assert report.is_quality_improvement() == (not report.fir_worsened and not report.mir_worsened)


def test_shift_0_은_변화_없음():
    p = _probs([[0.1, 0.0, 0.2, 0.7, 0.0]])
    assert np.allclose(apply_mix_shift(p, 0.0), p)
