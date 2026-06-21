"""PolicyDataset → train/val/test index 배열(P11). 길드 단위 누출 방지(P10 split 재사용).

같은 길드의 모든 샘플이 한 split 으로 가도록 P10 [assign_guild_split] 을 재사용한다(누출 방지).
모든 모델(logistic/tree/mlp/multi-head)이 같은 split 을 쓰게 해 baseline 비교가 공정하다(T003 일관).
"""

from __future__ import annotations

from dataclasses import dataclass
from typing import TYPE_CHECKING

from nexa_policy.data.split import Split, SplitRatios, assign_guild_split
from nexa_policy.datasets import PolicyDataset

if TYPE_CHECKING:
    import numpy as np


@dataclass(frozen=True)
class SplitIndices:
    """train/val/test 샘플 index 배열(길드 단위 분리)."""

    train: np.ndarray
    validation: np.ndarray
    test: np.ndarray


def make_split_indices(
    ds: PolicyDataset, *, ratios: SplitRatios | None = None, seed: int = 0
) -> SplitIndices:
    """길드 가명으로 각 샘플을 train/val/test 에 배정한 index 배열을 만든다(결정론)."""
    import numpy as np

    r = ratios or SplitRatios()
    buckets: dict[Split, list[int]] = {Split.TRAIN: [], Split.VALIDATION: [], Split.TEST: []}
    cache: dict[str, Split] = {}
    for i, guild in enumerate(ds.guild_ids):
        split = cache.get(guild)
        if split is None:
            split = assign_guild_split(guild, ratios=r, seed=seed)
            cache[guild] = split
        buckets[split].append(i)
    return SplitIndices(
        train=np.asarray(buckets[Split.TRAIN], dtype=np.int64),
        validation=np.asarray(buckets[Split.VALIDATION], dtype=np.int64),
        test=np.asarray(buckets[Split.TEST], dtype=np.int64),
    )
