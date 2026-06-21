"""inter-annotator agreement 계산(NEXA-P10-T022).

action, target, delay bin 별 agreement 와 disagreement matrix 를 계산한다.

**acceptance(T022) — 낮은 합의 항목은 gold 에서 제외하거나 soft label 로 유지된다**:
- [cohen_kappa] 로 두 annotator 의 합의를 계산한다(완전 일치=1.0, 우연 수준=0.0).
- [item_agreement] 는 항목별 다수결 비율로 합의도를 내고, [resolve_gold] 가 임계값 미만 항목을
  gold 에서 제외(또는 soft label 유지)하도록 분류한다.
- stdlib 만으로 동작(외부 의존 없음).
"""

from __future__ import annotations

from collections import Counter
from collections.abc import Sequence
from dataclasses import dataclass
from enum import Enum


class AgreementError(ValueError):
    """agreement 입력 불변식 위반."""


def cohen_kappa(rater_a: Sequence[object], rater_b: Sequence[object]) -> float:
    """두 annotator 의 Cohen's kappa.

    kappa = (po - pe) / (1 - pe). po=관측 일치율, pe=우연 기대 일치율.
    완전 일치=1.0, 우연 수준=0.0, 우연보다 나쁨<0. 두 시퀀스 길이가 같아야 한다.
    """
    if len(rater_a) != len(rater_b):
        raise AgreementError("두 annotator 의 항목 수가 같아야 한다.")
    n = len(rater_a)
    if n == 0:
        raise AgreementError("빈 입력으로 kappa 를 계산할 수 없다.")

    agree = sum(1 for a, b in zip(rater_a, rater_b, strict=True) if a == b)
    po = agree / n

    count_a = Counter(rater_a)
    count_b = Counter(rater_b)
    labels = set(count_a) | set(count_b)
    pe = sum((count_a.get(label, 0) / n) * (count_b.get(label, 0) / n) for label in labels)

    if pe == 1.0:
        # 모든 라벨이 한 클래스 → 우연 일치 100%, kappa 정의 불가 → 완전 일치로 본다.
        return 1.0
    return (po - pe) / (1.0 - pe)


def disagreement_matrix(
    rater_a: Sequence[object], rater_b: Sequence[object]
) -> dict[tuple[object, object], int]:
    """(rater_a 라벨, rater_b 라벨) → 건수 confusion matrix. 대각선이 합의, 비대각선이 불일치."""
    if len(rater_a) != len(rater_b):
        raise AgreementError("두 annotator 의 항목 수가 같아야 한다.")
    matrix: Counter[tuple[object, object]] = Counter()
    for a, b in zip(rater_a, rater_b, strict=True):
        matrix[(a, b)] += 1
    return dict(matrix)


@dataclass(frozen=True)
class ItemAgreement:
    """한 항목(샘플)에 대한 복수 annotator 합의도."""

    item_id: str
    majority_label: object
    agreement_ratio: float  # 다수 라벨 비율(0~1).
    label_distribution: dict[object, int]


def item_agreement(item_id: str, labels: Sequence[object]) -> ItemAgreement:
    """한 항목의 annotator 라벨 분포로 다수결·합의 비율을 계산한다."""
    if not labels:
        raise AgreementError("빈 라벨로 항목 합의를 계산할 수 없다.")
    dist = Counter(labels)
    majority_label, majority_count = max(dist.items(), key=lambda kv: (kv[1], str(kv[0])))
    return ItemAgreement(
        item_id=item_id,
        majority_label=majority_label,
        agreement_ratio=majority_count / len(labels),
        label_distribution=dict(dist),
    )


class GoldDecision(Enum):
    GOLD = "gold"  # 충분히 합의 → gold 라벨 채택.
    SOFT = "soft"  # 합의 낮음 → soft label(분포) 로 유지.
    EXCLUDE = "exclude"  # 합의 매우 낮음 → gold 에서 제외.


@dataclass(frozen=True)
class GoldResolution:
    item_id: str
    decision: GoldDecision
    label: object | None  # GOLD 면 다수 라벨, 아니면 None.
    distribution: dict[object, int]


def resolve_gold(
    agreement: ItemAgreement,
    *,
    gold_threshold: float = 0.8,
    soft_threshold: float = 0.5,
) -> GoldResolution:
    """합의 비율로 항목을 gold/soft/exclude 로 분류한다(acceptance: 낮은 합의는 제외 또는 soft).

    - agreement_ratio >= gold_threshold → GOLD(다수 라벨 채택).
    - soft_threshold <= ratio < gold_threshold → SOFT(분포 유지).
    - ratio < soft_threshold → EXCLUDE(gold 에서 제외).
    """
    ratio = agreement.agreement_ratio
    if ratio >= gold_threshold:
        return GoldResolution(
            agreement.item_id, GoldDecision.GOLD, agreement.majority_label,
            agreement.label_distribution,
        )
    if ratio >= soft_threshold:
        return GoldResolution(
            agreement.item_id, GoldDecision.SOFT, None, agreement.label_distribution
        )
    return GoldResolution(
        agreement.item_id, GoldDecision.EXCLUDE, None, agreement.label_distribution
    )
