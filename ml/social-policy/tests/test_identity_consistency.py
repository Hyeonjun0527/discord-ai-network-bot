"""NEXA-P19-T002: 장기 identity consistency — 표현 안정성과 사실 모순을 분리한다."""

from __future__ import annotations

import numpy as np

from nexa_policy.eval.identity_consistency import (
    IdentitySnapshot,
    evaluate_identity_consistency,
    factual_contradiction_rate,
    max_representation_jump,
    repetition_rate,
)


def _snap(vec, facts, *, violations=0, sig=None):
    return IdentitySnapshot(
        embedding=np.array(vec, dtype=np.float64),
        fact_slots=facts,
        prohibition_violations=violations,
        utterance_signature=sig,
    )


def test_acceptance_표현유사도와_사실모순은_다른_축이다():
    # 말투/취향 표현은 거의 동일(임베딩 안정)인데 사실 슬롯이 뒤집힘.
    snaps = [
        _snap([1.0, 0.0, 0.0], {"favorite_color": "blue"}),
        _snap([0.99, 0.01, 0.0], {"favorite_color": "red"}),  # 표현 유사·사실 모순.
    ]
    # 표현 jump 는 작지만 사실 모순률은 높다 → 평균 유사도가 모순을 가리지 않는다.
    assert max_representation_jump(snaps) < 0.05
    assert factual_contradiction_rate(snaps) == 1.0


def test_사실모순_없으면_0():
    snaps = [
        _snap([1.0, 0.0], {"favorite_color": "blue"}),
        _snap([0.5, 0.5], {"favorite_color": "blue"}),  # 표현은 바뀌어도 사실 일관.
    ]
    assert factual_contradiction_rate(snaps) == 0.0


def test_표현_급변_탐지():
    stable = [_snap([1.0, 0.0], {}), _snap([0.98, 0.02], {})]
    jumpy = [_snap([1.0, 0.0], {}), _snap([0.0, 1.0], {})]
    assert max_representation_jump(jumpy) > max_representation_jump(stable)


def test_금지위반_누적_카운트():
    snaps = [_snap([1.0], {}, violations=0), _snap([1.0], {}, violations=2)]
    report = evaluate_identity_consistency(snaps)
    assert report.prohibition_violation_count == 2
    assert not report.is_stable(jump_ceiling=0.5, contradiction_ceiling=0.5)


def test_반복_탐지():
    snaps = [_snap([1.0], {}, sig="hi"), _snap([1.0], {}, sig="hi"), _snap([1.0], {}, sig="bye")]
    assert repetition_rate(snaps) == 0.5


def test_안정_시퀀스는_stable():
    snaps = [
        _snap([1.0, 0.0], {"c": "blue"}, sig="a"),
        _snap([0.99, 0.01], {"c": "blue"}, sig="b"),
    ]
    report = evaluate_identity_consistency(snaps)
    assert report.is_stable(jump_ceiling=0.1, contradiction_ceiling=0.0)


def test_빈_시퀀스_안정():
    report = evaluate_identity_consistency([])
    assert report.is_stable(jump_ceiling=0.0, contradiction_ceiling=0.0)
    assert report.n_snapshots == 0
