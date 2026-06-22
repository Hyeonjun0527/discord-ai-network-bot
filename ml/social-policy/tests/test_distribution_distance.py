"""T021 행동 분포 distance 테스트 — 평균이 아닌 분포(KS/EMD/quantile) 비교."""

from __future__ import annotations

import pytest

from nexa_policy.eval.distribution_distance import (
    DistanceError,
    behavior_distance_report,
    earth_movers_distance,
    ks_statistic,
    mention_non_response_rate,
    quantile,
    quantile_gaps,
    reaction_ratio,
)


def test_ks_identical_distributions_zero() -> None:
    assert ks_statistic([1, 2, 3, 4], [1, 2, 3, 4]) == pytest.approx(0.0)


def test_ks_same_mean_different_shape_nonzero() -> None:
    # 평균은 같지만(둘 다 평균 0) 분포 모양이 다르다 → KS > 0.
    concentrated = [0, 0, 0, 0]
    spread = [-3, -1, 1, 3]
    assert sum(concentrated) / 4 == sum(spread) / 4
    assert ks_statistic(concentrated, spread) > 0.0


def test_ks_disjoint_distributions_one() -> None:
    assert ks_statistic([0, 0, 0], [10, 10, 10]) == pytest.approx(1.0)


def test_emd_identical_zero() -> None:
    assert earth_movers_distance([1, 2, 3], [1, 2, 3]) == pytest.approx(0.0)


def test_emd_shift_equals_distance() -> None:
    # 전체를 +5 평행이동하면 EMD ≈ 5(Wasserstein-1 의 직관).
    base = [0, 1, 2, 3, 4]
    shifted = [5, 6, 7, 8, 9]
    assert earth_movers_distance(base, shifted) == pytest.approx(5.0, abs=1.0)


def test_emd_sensitive_to_tail() -> None:
    # 평균은 비슷해도 한쪽에 무거운 꼬리가 있으면 EMD 가 커진다.
    light = [1, 1, 1, 1]
    heavy_tail = [0, 0, 0, 4]
    assert earth_movers_distance(light, heavy_tail) > 0.0


def test_quantile_linear_interpolation() -> None:
    assert quantile([0, 10], 0.5) == pytest.approx(5.0)
    assert quantile([0, 1, 2, 3, 4], 0.0) == pytest.approx(0.0)
    assert quantile([0, 1, 2, 3, 4], 1.0) == pytest.approx(4.0)


def test_quantile_out_of_range_rejected() -> None:
    with pytest.raises(DistanceError):
        quantile([1, 2, 3], 1.5)


def test_quantile_gaps_exposes_tail_difference() -> None:
    # 중앙값은 같고 99분위 꼬리만 다른 두 분포 → p99 gap 이 p50 gap 보다 크다.
    human = [10] * 50 + [11] * 49 + [12]
    model = [10] * 50 + [11] * 49 + [120]
    gaps = quantile_gaps(human, model)
    assert gaps[0.5] == pytest.approx(0.0, abs=1.0)
    assert gaps[0.99] > gaps[0.5]


def test_reaction_ratio() -> None:
    assert reaction_ratio(10, 3) == pytest.approx(0.3)
    assert reaction_ratio(0, 0) == 0.0


def test_mention_non_response_rate() -> None:
    assert mention_non_response_rate(10, 7) == pytest.approx(0.3)
    assert mention_non_response_rate(0, 0) == 0.0
    # 과응답(responses>mentions)이어도 무응답률은 0 으로 클램프.
    assert mention_non_response_rate(5, 8) == 0.0


def test_empty_sample_rejected() -> None:
    with pytest.raises(DistanceError):
        ks_statistic([], [1, 2])


def test_behavior_distance_report_keys() -> None:
    report = behavior_distance_report(
        human_burst_counts=[1, 1, 2, 1, 3],
        model_burst_counts=[1, 1, 1, 1, 1],
        human_delays_ms=[500, 800, 1200, 2000],
        model_delays_ms=[100, 150, 200, 250],
    )
    for key in ("burst_ks", "delay_emd", "delay_p50_gap", "delay_p90_gap", "delay_p99_gap"):
        assert key in report
    # 모델이 사람보다 훨씬 빨리(짧은 delay) 답하면 delay distance 가 크다.
    assert report["delay_emd"] > 0.0
    # 모델 burst 가 항상 1 인데 사람은 가변 → burst 분포 KS > 0.
    assert report["burst_ks"] > 0.0
