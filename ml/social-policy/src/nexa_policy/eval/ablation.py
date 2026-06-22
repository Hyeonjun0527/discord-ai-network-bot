"""feature ablation 분석(NEXA-P11-T023). 운영 데이터 미접근 — 합성 fixture 전용·결정론.

feature 군(burst/scene/relationship/memory/saturation)을 하나씩 **제거**(0 + missing 처리)하고 모델을 다시
학습·평가해 그 군이 성능에 기여하는 정도를 측정한다. 민감하거나 비용 높은 feature 가 실질 기여가 없으면
제거를 권고한다(불필요한 데이터 수집·privacy 노출 축소).

**acceptance(T023) — 민감/비용 높은 feature 가 실질 기여 없으면 제거한다**:
- [ablate_groups] 가 full 모델 대비 각 군 제거 시 balanced accuracy **하락폭(기여도)** 을 낸다.
- [recommend_removals] 가 기여도가 [min_contribution] 미만이면서 (민감/고비용) 표시된 군을 제거 후보로 낸다.

feature 군→카탈로그 feature ID prefix 매핑은 [FEATURE_GROUPS] 로 고정한다(features.md SSOT 미러).
"""

from __future__ import annotations

from dataclasses import dataclass
from typing import TYPE_CHECKING

from nexa_policy.datasets import ACTION_HEAD_CLASSES, PolicyDataset
from nexa_policy.metrics import balanced_accuracy
from nexa_policy.training.splitting import SplitIndices
from nexa_policy.training.trainer import design_matrix, train_multihead

if TYPE_CHECKING:
    import numpy as np

# feature 군 → 카탈로그 feature ID prefix(features.md 미러). "scene" 은 thread 군, "saturation" 은 agent 군.
FEATURE_GROUPS: dict[str, tuple[str, ...]] = {
    "burst": ("burst.",),
    "scene": ("thread.",),
    "relationship": ("relationship.",),
    "memory": ("memory.",),
    "saturation": ("agent.",),
}

# 군별 비용/민감도 플래그(features.md 의 privacy/수집비용 판단 미러). 기여 없으면 우선 제거 대상.
SENSITIVE_OR_COSTLY: dict[str, bool] = {
    "burst": False,
    "scene": False,
    "relationship": True,  # 관계 추정은 개인 상호작용 집계 → 민감.
    "memory": True,  # 기억 매칭은 과거 발화 의존 → 민감·비용.
    "saturation": False,
}


@dataclass(frozen=True)
class AblationResult:
    """한 feature 군 제거 결과."""

    group: str
    full_score: float
    ablated_score: float

    @property
    def contribution(self) -> float:
        """제거 시 balanced accuracy 하락폭(양수=그 군이 기여, ≤0=무기여/노이즈)."""
        return self.full_score - self.ablated_score


def _column_mask_for_groups(ds: PolicyDataset, groups: tuple[str, ...]) -> np.ndarray:
    """제거할 군의 feature 컬럼 인덱스(True=제거 대상). 카탈로그 feature 순서 기준."""
    import numpy as np

    prefixes: list[str] = []
    for g in groups:
        prefixes.extend(FEATURE_GROUPS[g])
    ids = ds.catalog.feature_ids
    return np.asarray([any(fid.startswith(p) for p in prefixes) for fid in ids], dtype=bool)


def _ablated_dataset(ds: PolicyDataset, groups: tuple[str, ...]) -> PolicyDataset:
    """지정 군 feature 를 0 + missing=1 로 만든 데이터셋 사본(다른 라벨/분할은 그대로)."""
    from dataclasses import replace

    col_mask = _column_mask_for_groups(ds, groups)
    feats = ds.features.copy()
    missing = ds.missing_mask.copy()
    feats[:, col_mask] = 0.0
    missing[:, col_mask] = 1.0  # 제거된 군은 '모름'으로(0 과 구분).
    return replace(ds, features=feats, missing_mask=missing)


def _score(ds: PolicyDataset, split: SplitIndices, *, epochs: int, seed: int) -> float:
    """모델을 학습하고 test split action head balanced accuracy 를 낸다(결정론)."""
    import numpy as np

    res = train_multihead(
        ds, train_idx=split.train, val_idx=split.validation, epochs=epochs, seed=seed
    )
    x = design_matrix(ds, split.test).astype(np.float32)
    y_true = ds.action_labels[split.test]
    valid = y_true >= 0
    if not bool(valid.any()):
        return 0.0
    probs = res.model.action_proba(x)
    y_pred = np.argmax(probs, axis=1)
    return balanced_accuracy(
        y_true[valid], y_pred[valid], n_classes=len(ACTION_HEAD_CLASSES)
    )


def ablate_groups(
    ds: PolicyDataset,
    split: SplitIndices,
    *,
    epochs: int = 30,
    seed: int = 3,
) -> list[AblationResult]:
    """full 모델 대비 각 feature 군 제거 시 기여도를 측정한다(군 이름 정렬, 결정론)."""
    full = _score(ds, split, epochs=epochs, seed=seed)
    results: list[AblationResult] = []
    for group in sorted(FEATURE_GROUPS):
        ablated = _score(_ablated_dataset(ds, (group,)), split, epochs=epochs, seed=seed)
        results.append(AblationResult(group=group, full_score=full, ablated_score=ablated))
    return results


def recommend_removals(
    results: list[AblationResult],
    *,
    min_contribution: float = 0.01,
) -> list[str]:
    """기여도가 [min_contribution] 미만이면서 민감/고비용인 군을 제거 후보로 낸다(이름 정렬).

    기여 없는데 민감/비싼 feature 를 수집할 이유가 없다(데이터 최소수집·privacy). 기여가 큰 군은 민감해도
    제거하지 않는다(여기선 추천만 — 실제 제거는 human gate).
    """
    return sorted(
        r.group
        for r in results
        if r.contribution < min_contribution and SENSITIVE_OR_COSTLY.get(r.group, False)
    )
