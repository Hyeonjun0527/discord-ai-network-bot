"""train/validation/test split(NEXA-P10-T012) + 시간 기반 holdout(NEXA-P10-T013).

**T012 acceptance — 동일 길드와 파생 샘플이 둘 이상의 split 에 나타나지 않는다**:
- 길드 가명을 stable hash(blake2b, seed salt)한 뒤 비율로 길드 자체를 train/val/test 에 배정한다.
- 같은 길드의 모든 샘플은 같은 split 으로 간다(길드 단위 완전 분리, 누출 방지). 결정론(seed).

**T013 acceptance — cutoff 이후 이벤트가 train feature/label 에 들어가지 않는다**:
- 길드 분리와 **별도로** 최신 기간 holdout 을 구성한다. event_time_ms 가 cutoff 이상이면 holdout,
  미만이면 train. 미래→과거 누출 없음(단일 cutoff, 경계 명확).
"""

from __future__ import annotations

import hashlib
from collections.abc import Callable
from dataclasses import dataclass
from enum import Enum
from typing import TypeVar

T = TypeVar("T")


class Split(Enum):
    TRAIN = "train"
    VALIDATION = "validation"
    TEST = "test"


@dataclass(frozen=True)
class SplitRatios:
    train: float = 0.7
    validation: float = 0.15
    test: float = 0.15

    def __post_init__(self) -> None:
        total = self.train + self.validation + self.test
        if abs(total - 1.0) > 1e-9:
            raise ValueError(f"split 비율 합은 1.0 이어야 한다: {total}")
        if min(self.train, self.validation, self.test) < 0:
            raise ValueError("split 비율은 음수일 수 없다.")


def _guild_bucket(guild_pseudonym: str, seed: int) -> float:
    """길드 가명을 [0,1) 로 결정론적 해시. 같은 길드·seed 면 항상 같은 값."""
    digest = hashlib.blake2b(
        f"{seed}:{guild_pseudonym}".encode(), digest_size=8
    ).digest()
    return int.from_bytes(digest, "big") / float(1 << 64)


def assign_guild_split(guild_pseudonym: str, *, ratios: SplitRatios, seed: int = 0) -> Split:
    """한 길드의 split 을 결정한다(길드 단위 완전 분리)."""
    b = _guild_bucket(guild_pseudonym, seed)
    if b < ratios.train:
        return Split.TRAIN
    if b < ratios.train + ratios.validation:
        return Split.VALIDATION
    return Split.TEST


def split_by_guild[T](
    samples: list[T],
    *,
    guild_of: Callable[[T], str],
    ratios: SplitRatios | None = None,
    seed: int = 0,
) -> dict[Split, list[T]]:
    """샘플을 길드 단위로 train/val/test 에 배정한다.

    같은 길드의 모든 파생 샘플은 같은 split 으로 간다(누출 방지, acceptance T012).
    """
    r = ratios or SplitRatios()
    out: dict[Split, list[T]] = {Split.TRAIN: [], Split.VALIDATION: [], Split.TEST: []}
    cache: dict[str, Split] = {}
    for sample in samples:
        guild = guild_of(sample)
        split = cache.get(guild)
        if split is None:
            split = assign_guild_split(guild, ratios=r, seed=seed)
            cache[guild] = split
        out[split].append(sample)
    return out


def assert_no_guild_leakage[T](
    split_map: dict[Split, list[T]], *, guild_of: Callable[[T], str]
) -> None:
    """어떤 길드도 둘 이상의 split 에 없음을 단언한다(누출 가드, fail-closed)."""
    seen: dict[str, Split] = {}
    for split, samples in split_map.items():
        for sample in samples:
            guild = guild_of(sample)
            prev = seen.get(guild)
            if prev is not None and prev != split:
                raise ValueError(
                    f"길드 누출: {guild!r} 가 {prev.value} 와 {split.value} 양쪽에 있다."
                )
            seen[guild] = split


class TimeSplit(Enum):
    TRAIN = "train"
    HOLDOUT = "holdout"


def split_by_time[T](
    samples: list[T],
    *,
    time_of: Callable[[T], int],
    cutoff_ms: int,
) -> dict[TimeSplit, list[T]]:
    """cutoff_ms 기준 시간 holdout 을 구성한다(T013).

    event_time_ms < cutoff → train, >= cutoff → holdout. 미래→과거 누출 없음(단일 경계).
    """
    out: dict[TimeSplit, list[T]] = {TimeSplit.TRAIN: [], TimeSplit.HOLDOUT: []}
    for sample in samples:
        if time_of(sample) < cutoff_ms:
            out[TimeSplit.TRAIN].append(sample)
        else:
            out[TimeSplit.HOLDOUT].append(sample)
    return out


def assert_no_time_leakage[T](
    split_map: dict[TimeSplit, list[T]], *, time_of: Callable[[T], int], cutoff_ms: int
) -> None:
    """train 의 어떤 샘플도 cutoff 이상 시각을 갖지 않음을 단언한다(미래 누출 가드)."""
    for sample in split_map.get(TimeSplit.TRAIN, []):
        if time_of(sample) >= cutoff_ms:
            raise ValueError(
                f"시간 누출: train 샘플 시각 {time_of(sample)} 가 cutoff {cutoff_ms} 이상이다."
            )
