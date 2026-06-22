"""NEXA-P19-T010: 오프라인 trajectory builder — consent·lineage 동반, 취소/침묵 포함."""

from __future__ import annotations

import numpy as np
import pytest

from nexa_policy.rl.trajectory import (
    TrajectoryStep,
    build_trajectory,
    make_synthetic_trajectory,
)


def _step(action="speak", consent=True, lineage=None, emitted="sig"):
    return TrajectoryStep(
        scene_state=np.zeros(3),
        action=action,
        delay_bin=0,
        outcome="continued",
        consent_opt_in=consent,
        lineage=lineage if lineage is not None else ["e1"],
        emitted_text=emitted,
    )


def test_acceptance_consent_없는_step은_제외():
    cands = [_step(consent=True), _step(consent=False), _step(consent=True)]
    traj = build_trajectory("seg", cands)
    assert traj.length == 2  # 미동의 step 제외.


def test_acceptance_모든_step은_lineage를_가진다():
    with pytest.raises(ValueError):
        _step(lineage=[])  # lineage 없으면 생성 거부.
    traj = build_trajectory("seg", [_step(lineage=["a", "b"])])
    assert traj.all_have_lineage


def test_acceptance_취소와_침묵_포함_생성문구_없어도_정당():
    silent = _step(action="ignore", emitted=None)
    cancel = _step(action="cancel", emitted=None)
    assert silent.is_silent_or_cancel and cancel.is_silent_or_cancel
    traj = build_trajectory("seg", [silent, cancel, _step(action="speak")])
    assert traj.silent_or_cancel_count == 2


def test_합성_trajectory_결정론과_침묵포함():
    traj1, cands1 = make_synthetic_trajectory(seed=42)
    traj2, cands2 = make_synthetic_trajectory(seed=42)
    assert traj1.length == traj2.length
    assert [s.action for s in traj1.steps] == [s.action for s in traj2.steps]
    # 침묵/취소가 포함된다(생성 문구만으로 reward 계산하지 않는 증거).
    assert traj1.silent_or_cancel_count > 0


def test_알수없는_action_거부():
    with pytest.raises(ValueError):
        _step(action="bogus")
