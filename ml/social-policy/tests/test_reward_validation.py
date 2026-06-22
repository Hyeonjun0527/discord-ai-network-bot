"""NEXA-P19-T012: reward proxy validation — 상관 낮은 proxy 는 RL 사용 불가로 판정."""

from __future__ import annotations

import numpy as np
import pytest

from nexa_policy.rl.reward_validation import (
    disagreement_rate,
    pearson_correlation,
    spearman_correlation,
    validate_proxy,
)


def test_acceptance_상관_높은_proxy는_usable():
    human = np.array([1.0, 2.0, 3.0, 4.0, 5.0, 6.0])
    proxy = human + np.array([0.1, -0.1, 0.0, 0.1, -0.1, 0.0])  # 거의 일치.
    result = validate_proxy(proxy_name="continuation", proxy_scores=proxy, human_scores=human)
    assert result.usable
    assert result.spearman > 0.9


def test_acceptance_상관_낮은_proxy는_사용_불가():
    rng = np.random.default_rng(0)
    human = rng.random(40)
    proxy = rng.random(40)  # 무관.
    result = validate_proxy(proxy_name="mention_count", proxy_scores=proxy, human_scores=human)
    assert not result.usable  # 상관 낮음 → RL 에 쓰지 않는다.


def test_역상관_proxy도_사용_불가():
    human = np.array([1.0, 2.0, 3.0, 4.0, 5.0])
    proxy = -human  # 완전 역상관.
    result = validate_proxy(proxy_name="bad", proxy_scores=proxy, human_scores=human)
    assert not result.usable


def test_spearman_pearson_계산():
    a = np.array([1.0, 2.0, 3.0, 4.0])
    b = np.array([1.0, 2.0, 3.0, 4.0])
    assert spearman_correlation(a, b) == pytest.approx(1.0)
    assert pearson_correlation(a, b) == pytest.approx(1.0)


def test_disagreement_rate():
    proxy = np.array([1.0, 2.0, 3.0, 4.0])
    human = np.array([4.0, 3.0, 2.0, 1.0])  # 완전 반대.
    assert disagreement_rate(proxy, human) == 1.0


def test_상수_proxy_상관_0():
    proxy = np.array([1.0, 1.0, 1.0, 1.0])
    human = np.array([1.0, 2.0, 3.0, 4.0])
    assert pearson_correlation(proxy, human) == 0.0
