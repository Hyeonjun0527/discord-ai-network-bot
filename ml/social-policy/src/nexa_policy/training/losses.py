"""멀티태스크 loss·mask + 학습 루프(NEXA-P11-T013).

action/target/delay/burst/act head 의 loss 를 configurable weight 로 결합한다. 각 head 는 per-sample
mask(라벨 유무·censored·낮은 confidence)를 받아, **라벨 없는 head 가 gradient 를 만들지 않는다**
(acceptance T013).

backward 는 numpy 로 직접: 각 head 의 softmax-CE grad 를 trunk 까지 chain rule 로 누적하되,
mask=0 샘플은 grad 0 이라 그 head 로부터 trunk 로 흐르는 신호가 없다(검증: mask 전부 0 인 head 는
파라미터 grad 가 정확히 0).
"""

from __future__ import annotations

from dataclasses import dataclass, field
from typing import TYPE_CHECKING

from nexa_policy.models.heads import MultiHeadPolicyModel
from nexa_policy.models.nn import (
    Linear,
    relu,
    relu_backward,
    softmax,
    softmax_cross_entropy_grad,
)

if TYPE_CHECKING:
    import numpy as np


@dataclass(frozen=True)
class TaskWeights:
    """head 별 loss weight(configurable). 0 이면 그 head 는 학습에서 제외."""

    action: float = 1.0
    target: float = 0.5
    delay: float = 0.5
    burst: float = 0.5
    act: float = 0.3

    def as_dict(self) -> dict[str, float]:
        return {
            "action": self.action,
            "target": self.target,
            "delay": self.delay,
            "burst": self.burst,
            "act": self.act,
        }


@dataclass
class MultiTaskBatch:
    """한 batch 의 입력·라벨·mask. mask 0 인 head 는 그 샘플에서 무시된다."""

    x: np.ndarray
    action_labels: np.ndarray
    action_weight: np.ndarray
    target_labels: np.ndarray
    target_candidate_mask: np.ndarray
    target_weight: np.ndarray
    delay_labels: np.ndarray
    delay_weight: np.ndarray
    burst_labels: np.ndarray
    burst_weight: np.ndarray
    act_labels: np.ndarray
    act_weight: np.ndarray


@dataclass
class MultiTaskLossBreakdown:
    """head 별 loss 와 가중 합."""

    total: float
    per_head: dict[str, float] = field(default_factory=dict)


def _head_grad(
    logits: np.ndarray, labels: np.ndarray, weight: np.ndarray
) -> tuple[float, np.ndarray]:
    """softmax-CE loss·grad. weight 0 샘플은 기여 0(mask)."""
    probs = softmax(logits)
    return softmax_cross_entropy_grad(probs, labels, weight)


def _target_grad(
    logits: np.ndarray, labels: np.ndarray, candidate_mask: np.ndarray, weight: np.ndarray
) -> tuple[float, np.ndarray]:
    """target ranking: 유효 후보만 softmax(padding/excluded 후보 -inf). labels<0(none) weight 0."""
    import numpy as np

    masked_logits = np.where(candidate_mask > 0, logits, -1e30)
    probs = softmax(masked_logits)
    loss, grad = softmax_cross_entropy_grad(probs, labels, weight)
    # -inf 위치는 prob≈0 이라 grad≈0; 안전하게 후보 mask 밖 grad 를 0 으로.
    grad = grad * (candidate_mask > 0).astype(np.float64)
    return loss, grad


def compute_multitask_loss(
    model: MultiHeadPolicyModel, batch: MultiTaskBatch, weights: TaskWeights
) -> MultiTaskLossBreakdown:
    """forward 만 — head 별 masked loss 와 가중 합(backward 없음, 평가/모니터용)."""
    logits = model.all_logits(batch.x)
    per_head: dict[str, float] = {}
    la, _ = _head_grad(logits["action"], batch.action_labels, batch.action_weight)
    lt, _ = _target_grad(
        logits["target"], batch.target_labels, batch.target_candidate_mask, batch.target_weight
    )
    ld, _ = _head_grad(logits["delay"], batch.delay_labels, batch.delay_weight)
    lb, _ = _head_grad(logits["burst"], batch.burst_labels, batch.burst_weight)
    lk, _ = _head_grad(logits["act"], batch.act_labels, batch.act_weight)
    per_head = {"action": la, "target": lt, "delay": ld, "burst": lb, "act": lk}
    total = (
        weights.action * la
        + weights.target * lt
        + weights.delay * ld
        + weights.burst * lb
        + weights.act * lk
    )
    return MultiTaskLossBreakdown(total=total, per_head=per_head)


def train_step(
    model: MultiHeadPolicyModel,
    batch: MultiTaskBatch,
    weights: TaskWeights,
    *,
    lr: float,
) -> MultiTaskLossBreakdown:
    """한 batch forward+backward+update. mask 0 head 는 trunk 로 grad 를 흘리지 않는다(T013)."""

    for layer in model.layers:
        layer.zero_grad()

    # forward(중간값 보존).
    h1_pre = model.trunk.forward(batch.x)
    h1 = relu(h1_pre)
    h2_pre = model.trunk2.forward(h1)
    h2 = relu(h2_pre)

    def head_forward(head: Linear) -> np.ndarray:
        return head.forward(h2)

    logits = {
        "action": head_forward(model.action_head),
        "target": head_forward(model.target_head),
        "delay": head_forward(model.delay_head),
        "burst": head_forward(model.burst_head),
        "act": head_forward(model.act_head),
    }

    la, ga = _head_grad(logits["action"], batch.action_labels, batch.action_weight)
    lt, gt = _target_grad(
        logits["target"], batch.target_labels, batch.target_candidate_mask, batch.target_weight
    )
    ld, gd = _head_grad(logits["delay"], batch.delay_labels, batch.delay_weight)
    lb, gb = _head_grad(logits["burst"], batch.burst_labels, batch.burst_weight)
    lk, gk = _head_grad(logits["act"], batch.act_labels, batch.act_weight)

    # 각 head 의 grad 에 task weight 를 곱해 backward(head 가 dL/dh2 를 반환). 합산해 trunk 로 chain.
    grad_h2 = (
        model.action_head.backward(ga * weights.action)
        + model.target_head.backward(gt * weights.target)
        + model.delay_head.backward(gd * weights.delay)
        + model.burst_head.backward(gb * weights.burst)
        + model.act_head.backward(gk * weights.act)
    )

    # trunk2 backward(relu).
    grad_h2_pre = relu_backward(grad_h2, h2_pre)
    grad_h1 = model.trunk2.backward(grad_h2_pre)
    # trunk backward(relu).
    grad_h1_pre = relu_backward(grad_h1, h1_pre)
    model.trunk.backward(grad_h1_pre)

    for layer in model.layers:
        layer.step(lr)

    per_head = {"action": la, "target": lt, "delay": ld, "burst": lb, "act": lk}
    total = (
        weights.action * la
        + weights.target * lt
        + weights.delay * ld
        + weights.burst * lb
        + weights.act * lk
    )
    return MultiTaskLossBreakdown(total=total, per_head=per_head)
