"""T023 데이터 누출 자동 검사 테스트 — 의도적 leakage fixture 가 실패한다."""

from __future__ import annotations

from dataclasses import dataclass

import pytest

from nexa_policy.data.leakage import (
    LabelFeaturePair,
    LeakageError,
    assert_no_leakage,
    check_group_leakage,
    check_label_feature_leakage,
    check_temporal_leakage,
)


@dataclass(frozen=True)
class Row:
    guild: str
    time_ms: int
    feature: str
    label: str


def test_clean_splits_pass() -> None:
    splits = {
        "train": [Row("g1", 1, "x", "A"), Row("g2", 2, "y", "B")],
        "test": [Row("g3", 3, "x", "A")],
    }
    report = check_group_leakage(splits, key_of=lambda r: r.guild, group_name="guild")
    assert report.ok
    assert_no_leakage(report)  # 예외 없음.


def test_group_leakage_detected() -> None:
    # 의도적 leakage: guild-X 가 train·test 양쪽에 — CI 가 실패해야 한다(acceptance).
    splits = {
        "train": [Row("guild-X", 1, "x", "A")],
        "test": [Row("guild-X", 2, "y", "B")],
    }
    report = check_group_leakage(splits, key_of=lambda r: r.guild, group_name="guild")
    assert not report.ok
    with pytest.raises(LeakageError, match="누출"):
        assert_no_leakage(report)


def test_temporal_leakage_detected() -> None:
    train = [Row("g1", 10, "x", "A"), Row("g1", 99, "y", "B")]  # 99 >= cutoff 50.
    report = check_temporal_leakage(train, time_of=lambda r: r.time_ms, cutoff_ms=50)
    assert not report.ok
    with pytest.raises(LeakageError):
        assert_no_leakage(report)


def test_temporal_clean_passes() -> None:
    train = [Row("g1", 10, "x", "A"), Row("g1", 20, "y", "B")]
    report = check_temporal_leakage(train, time_of=lambda r: r.time_ms, cutoff_ms=50)
    assert report.ok


def test_label_feature_leakage_detected() -> None:
    # feature 가 label 과 1:1 결정적 일치(label 을 그대로 베낌) → 누설.
    samples = [Row("g", i, f"f{i % 2}", "A" if i % 2 == 0 else "B") for i in range(8)]
    pairs = [LabelFeaturePair(feature_of=lambda r: r.feature, label_of=lambda r: r.label,
                              name="feature")]
    report = check_label_feature_leakage(samples, pairs=pairs)
    assert not report.ok
    assert any(v.kind == "label_feature" for v in report.violations)


def test_label_feature_correlation_not_flagged() -> None:
    # 상관은 있지만 1:1 가역이 아니면(feature 값이 여러 label 로 매핑) 누설 아님.
    samples = [
        Row("g", 0, "f0", "A"), Row("g", 1, "f0", "B"),
        Row("g", 2, "f1", "A"), Row("g", 3, "f1", "B"),
        Row("g", 4, "f2", "A"),
    ]
    pairs = [LabelFeaturePair(feature_of=lambda r: r.feature, label_of=lambda r: r.label,
                              name="feature")]
    report = check_label_feature_leakage(samples, pairs=pairs)
    assert report.ok
