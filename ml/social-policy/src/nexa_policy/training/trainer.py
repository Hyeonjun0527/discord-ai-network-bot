"""멀티헤드 정책 모델 학습 오케스트레이션(P11-T008~T013 묶음).

PolicyDataset + split index → [MultiTaskBatch] 구성(각 head mask 포함) → 소수 epoch full-batch
경사하강. 초 단위 완료(작은 fixture). 결정론(seed). UNKNOWN/censored/낮은 confidence 는 mask 로 제외.
"""

from __future__ import annotations

from dataclasses import dataclass
from typing import TYPE_CHECKING

from nexa_policy.datasets import ACTION_HEAD_CLASSES, PolicyDataset
from nexa_policy.models.heads import MultiHeadPolicyModel
from nexa_policy.training.losses import (
    MultiTaskBatch,
    TaskWeights,
    compute_multitask_loss,
    train_step,
)

if TYPE_CHECKING:
    import numpy as np

_SPEAK = ACTION_HEAD_CLASSES.index("speak")
_REACT = ACTION_HEAD_CLASSES.index("react")


def design_matrix(ds: PolicyDataset, idx: np.ndarray) -> np.ndarray:
    """feature + missing mask 결합(0 과 '모름' 구분)."""
    import numpy as np

    return np.concatenate([ds.features[idx], ds.missing_mask[idx]], axis=1).astype(np.float64)


def make_batch(ds: PolicyDataset, idx: np.ndarray) -> MultiTaskBatch:
    """split index 로 MultiTaskBatch 를 만든다. 각 head 의 mask·weight 를 라벨 유무에서 도출."""
    import numpy as np

    x = design_matrix(ds, idx)
    # action: UNKNOWN(mask 0) 제외.
    action_w = ds.action_mask[idx].astype(np.float64)
    # target: SPEAK/REACT 이고 label≥0 인 샘플만.
    has_target = (ds.target_labels[idx] >= 0).astype(np.float64)
    # delay: delay_mask(censored 제외).
    delay_w = ds.delay_mask[idx].astype(np.float64)
    # burst: burst_mask.
    burst_w = ds.burst_mask[idx].astype(np.float64)
    # act: weak confidence weight(0 이면 제외).
    act_w = ds.act_weight[idx].astype(np.float64)
    return MultiTaskBatch(
        x=x,
        action_labels=ds.action_labels[idx].copy(),
        action_weight=action_w,
        target_labels=ds.target_labels[idx].copy(),
        target_candidate_mask=ds.target_candidate_mask[idx].astype(np.float64),
        target_weight=has_target,
        delay_labels=ds.delay_labels[idx].copy(),
        delay_weight=delay_w,
        burst_labels=ds.burst_labels[idx].copy(),
        burst_weight=burst_w,
        act_labels=ds.act_labels[idx].copy(),
        act_weight=act_w,
    )


@dataclass
class TrainingResult:
    """학습 결과 — 모델 + epoch 별 train/val total loss 궤적."""

    model: MultiHeadPolicyModel
    train_losses: list[float]
    val_losses: list[float]
    weights: TaskWeights


def train_multihead(
    ds: PolicyDataset,
    *,
    train_idx: np.ndarray,
    val_idx: np.ndarray,
    epochs: int = 60,
    lr: float = 0.05,
    hidden_dim: int = 16,
    weights: TaskWeights | None = None,
    seed: int = 20260622,
) -> TrainingResult:
    """멀티헤드 모델을 소수 epoch full-batch 로 학습한다(결정론)."""
    w = weights or TaskWeights()
    in_dim = design_matrix(ds, train_idx[:1]).shape[1]
    model = MultiHeadPolicyModel.build(in_dim=in_dim, hidden_dim=hidden_dim, seed=seed)
    train_batch = make_batch(ds, train_idx)
    val_batch = make_batch(ds, val_idx)

    train_losses: list[float] = []
    val_losses: list[float] = []
    for _ in range(epochs):
        result = train_step(model, train_batch, w, lr=lr)
        train_losses.append(result.total)
        val_losses.append(compute_multitask_loss(model, val_batch, w).total)
    return TrainingResult(model=model, train_losses=train_losses, val_losses=val_losses, weights=w)
