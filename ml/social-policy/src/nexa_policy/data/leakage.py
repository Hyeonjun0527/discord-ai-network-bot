"""데이터 누출 자동 검사(NEXA-P10-T023).

split 간 group/temporal 중복과 feature 가 label 을 누설하는지 자동 탐지한다(P10-T011~T013·P09-T023 일관).

**acceptance(T023) — 의도적 leakage fixture 가 CI 에서 실패한다**:
- [check_group_leakage]: 같은 guild/actor/event/source 키가 둘 이상 split 에 나타나면 위반.
- [check_temporal_leakage]: train 샘플이 holdout cutoff 이상 시각을 가지면 위반(미래→과거 누출).
- [check_label_feature_leakage]: feature 값이 label 과 1:1 로 결정적 일치(label 을 그대로 베낀 feature)면 위반.
- 위반은 [LeakageReport.ok=False] + 상세로 보고되고, [assert_no_leakage] 가 fail-closed 로 예외를 던진다.
"""

from __future__ import annotations

from collections.abc import Callable, Iterable, Sequence
from dataclasses import dataclass, field
from typing import TypeVar

T = TypeVar("T")


@dataclass(frozen=True)
class LeakageViolation:
    kind: str  # "group" | "temporal" | "label_feature" | "feature_cutoff".
    detail: str


@dataclass
class LeakageReport:
    violations: list[LeakageViolation] = field(default_factory=list)

    @property
    def ok(self) -> bool:
        return not self.violations

    def add(self, kind: str, detail: str) -> None:
        self.violations.append(LeakageViolation(kind=kind, detail=detail))

    def merge(self, other: LeakageReport) -> None:
        self.violations.extend(other.violations)


class LeakageError(ValueError):
    """데이터 누출 감지(fail-closed) — CI 가 실패해야 한다."""


def check_group_leakage[T](
    splits: dict[str, Sequence[T]],
    *,
    key_of: Callable[[T], str],
    group_name: str = "group",
) -> LeakageReport:
    """같은 group 키(guild/actor/event/source)가 둘 이상 split 에 나타나는지 검사한다."""
    report = LeakageReport()
    seen: dict[str, str] = {}
    for split_name, samples in splits.items():
        for sample in samples:
            key = key_of(sample)
            prev = seen.get(key)
            if prev is not None and prev != split_name:
                report.add(
                    "group",
                    f"{group_name} {key!r} 가 split {prev!r} 와 {split_name!r} 양쪽에 있다.",
                )
            else:
                seen[key] = split_name
    return report


def check_temporal_leakage[T](
    train: Iterable[T],
    *,
    time_of: Callable[[T], int],
    cutoff_ms: int,
) -> LeakageReport:
    """train 샘플이 holdout cutoff 이상 시각을 갖는지 검사한다(미래→과거 누출)."""
    report = LeakageReport()
    for sample in train:
        t = time_of(sample)
        if t >= cutoff_ms:
            report.add("temporal", f"train 샘플 시각 {t} 가 cutoff {cutoff_ms} 이상이다.")
    return report


@dataclass(frozen=True)
class LabelFeaturePair[T]:
    """label-feature 누설 검사 입력: 각 샘플에서 (feature 값, label 값) 추출기."""

    feature_of: Callable[[T], object]
    label_of: Callable[[T], object]
    name: str


def check_label_feature_leakage[T](
    samples: Sequence[T],
    *,
    pairs: Sequence[LabelFeaturePair[T]],
    min_samples: int = 5,
) -> LeakageReport:
    """feature 가 label 을 결정적으로 누설하는지 검사한다.

    한 feature 값이 항상 같은 label 로만 매핑되고(역도 성립), 클래스가 2개 이상이며,
    충분한 표본(min_samples)이 있으면 'feature 가 label 을 그대로 베꼈다'고 본다 → 누설.
    (완전 1:1 결정적 일치만 잡는 보수적 규칙 — 정상적 상관은 통과.)
    """
    report = LeakageReport()
    n = len(samples)
    if n < min_samples:
        return report
    for pair in pairs:
        f_to_l: dict[object, set[object]] = {}
        l_to_f: dict[object, set[object]] = {}
        for s in samples:
            f = pair.feature_of(s)
            ell = pair.label_of(s)
            f_to_l.setdefault(f, set()).add(ell)
            l_to_f.setdefault(ell, set()).add(f)
        distinct_labels = len(l_to_f)
        # 각 feature 값이 정확히 한 label, 각 label 이 정확히 한 feature → 완전 가역 1:1 매핑.
        bijective = all(len(v) == 1 for v in f_to_l.values()) and all(
            len(v) == 1 for v in l_to_f.values()
        )
        if distinct_labels >= 2 and bijective:
            report.add(
                "label_feature",
                f"feature {pair.name!r} 가 label 과 1:1 결정적으로 일치한다(label 누설).",
            )
    return report


@dataclass(frozen=True)
class FeatureTimestamp[T]:
    """feature-cutoff 누출 검사 입력: 각 표본에서 feature 가 계산된 시각(ms) 추출기.

    [computed_at_of] 는 이 feature 가 **실제로 관측·계산된** 시각을, [name] 은 feature 이름을 준다.
    reply 가 온 뒤 계산되는 tempo·finalize reason 같은 feature 가 의심 대상이다(P12-T016 deliverable).
    """

    computed_at_of: Callable[[T], int]
    name: str


def check_feature_cutoff_leakage[T](
    samples: Sequence[T],
    *,
    cutoff_of: Callable[[T], int],
    features: Sequence[FeatureTimestamp[T]],
) -> LeakageReport:
    """각 tensor row 의 feature 가 그 row 의 **예측 시점(feature cutoff)** 이후에 계산됐는지 검사한다.

    예측 시점 feature 는 cutoff(결정이 내려진 시각) **이전** 정보만 써야 한다. reply 도착 후 계산된
    tempo 나 finalize reason 이 feature 로 들어가면 미래 누출이다(P09-T023·P10 leakage 와 일관).

    각 표본(=tensor row)마다 [cutoff_of] 로 feature cutoff timestamp 를 얻고, 각 feature 의
    [FeatureTimestamp.computed_at_of] 가 cutoff 이상(>=)이면 위반으로 보고한다 — acceptance(T016):
    "각 tensor row 에 feature cutoff timestamp 가 검증된다".
    """
    report = LeakageReport()
    for idx, sample in enumerate(samples):
        cutoff = cutoff_of(sample)
        for feat in features:
            computed_at = feat.computed_at_of(sample)
            if computed_at >= cutoff:
                report.add(
                    "feature_cutoff",
                    f"row {idx}: feature {feat.name!r} 계산 시각 {computed_at} 가 "
                    f"feature cutoff {cutoff} 이상이다(미래 누출).",
                )
    return report


def assert_no_leakage(report: LeakageReport) -> None:
    """누출이 있으면 fail-closed 로 예외를 던진다(CI 실패 트리거)."""
    if not report.ok:
        details = "; ".join(f"[{v.kind}] {v.detail}" for v in report.violations)
        raise LeakageError(f"데이터 누출 감지: {details}")
