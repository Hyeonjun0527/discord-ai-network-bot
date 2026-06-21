"""class imbalance 처리 실험(NEXA-P11-T014).

다수 클래스(IGNORE) 쏠림을 막는 세 전략을 비교한다: class weight, focal loss, under-sampling.
모두 결정론(seed). action MLP 를 각 전략으로 학습해 balanced accuracy 와 "모두 IGNORE" 퇴화 여부를
가린다.

**acceptance(T014) — accuracy 만 높고 모두 IGNORE 하는 모델을 탈락시킨다**:
- [degenerate_all_ignore] 는 예측이 거의 전부 IGNORE 인지(소수 클래스 recall ≈ 0) 판정한다.
- [compare_imbalance_strategies] 는 각 전략의 balanced accuracy·SPEAK recall·퇴화 여부를 보고하고,
  퇴화 모델을 [ImbalanceComparison.disqualified] 로 표시한다(높은 raw accuracy 라도 탈락).
"""

from __future__ import annotations

from dataclasses import dataclass, field
from typing import TYPE_CHECKING

from nexa_policy.datasets import ACTION_HEAD_CLASSES, PolicyDataset
from nexa_policy.metrics import balanced_accuracy
from nexa_policy.models.mlp import MlpActionModel
from nexa_policy.models.nn import (
    relu,
    relu_backward,
    softmax,
    softmax_cross_entropy_grad,
)

if TYPE_CHECKING:
    import numpy as np

N_ACTIONS = len(ACTION_HEAD_CLASSES)
_IGNORE = ACTION_HEAD_CLASSES.index("ignore")
_SPEAK = ACTION_HEAD_CLASSES.index("speak")


def _design(ds: PolicyDataset, idx: np.ndarray) -> np.ndarray:
    import numpy as np

    return np.concatenate([ds.features[idx], ds.missing_mask[idx]], axis=1).astype(np.float64)


def class_weights_inverse(y: np.ndarray) -> np.ndarray:
    """inverse-frequency class weight(평균 1 정규화)."""
    import numpy as np

    counts = np.bincount(y, minlength=N_ACTIONS).astype(np.float64)
    with np.errstate(divide="ignore"):
        inv = np.where(counts > 0, 1.0 / counts, 0.0)
    w = inv[y]
    return w / w.mean() if w.mean() > 0 else w


def undersample_indices(y: np.ndarray, *, seed: int) -> np.ndarray:
    """다수 클래스를 소수 클래스 최대 수에 맞춰 결정론적으로 언더샘플한 index."""
    import numpy as np

    gen = np.random.default_rng(seed)
    present = [c for c in range(N_ACTIONS) if (y == c).sum() > 0]
    if not present:
        return np.arange(y.shape[0])
    target = min(int((y == c).sum()) for c in present)
    keep: list[int] = []
    for c in present:
        idx_c = np.where(y == c)[0]
        chosen = gen.permutation(idx_c)[:target]
        keep.extend(int(i) for i in chosen)
    return np.array(sorted(keep), dtype=np.int64)


def _train_action_mlp(
    X: np.ndarray,
    y: np.ndarray,
    *,
    sample_weight: np.ndarray | None,
    focal_gamma: float | None,
    epochs: int,
    lr: float,
    in_dim: int,
    hidden_dim: int,
    seed: int,
) -> MlpActionModel:
    import numpy as np

    model = MlpActionModel.build(in_dim=in_dim, hidden_dim=hidden_dim, seed=seed)
    w = np.ones(y.shape[0]) if sample_weight is None else sample_weight
    for _ in range(epochs):
        model.fc1.zero_grad()
        model.fc2.zero_grad()
        h_pre = model.fc1.forward(X)
        h = relu(h_pre)
        probs = softmax(model.fc2.forward(h))
        eff_w = w.copy()
        if focal_gamma is not None:
            # focal: 잘 맞춘 샘플 down-weight. p_t = 정답 확률.
            p_t = probs[np.arange(y.shape[0]), y]
            eff_w = eff_w * (1.0 - p_t) ** focal_gamma
        _, grad = softmax_cross_entropy_grad(probs, y, eff_w)
        grad_h = model.fc2.backward(grad)
        model.fc1.backward(relu_backward(grad_h, h_pre))
        model.fc1.step(lr)
        model.fc2.step(lr)
    return model


def degenerate_all_ignore(
    y_true: np.ndarray, y_pred: np.ndarray, *, minority_recall_floor: float = 0.05
) -> bool:
    """소수 클래스(SPEAK) recall 이 바닥(floor) 미만이면 '모두 IGNORE' 퇴화로 판정."""

    speak_mask = y_true == _SPEAK
    if speak_mask.sum() == 0:
        # SPEAK 가 평가셋에 없으면 IGNORE 비율로 판단.
        return bool((y_pred == _IGNORE).mean() > 0.99)
    speak_recall = float((y_pred[speak_mask] == _SPEAK).mean())
    return speak_recall < minority_recall_floor


@dataclass(frozen=True)
class StrategyResult:
    name: str
    balanced_accuracy: float
    speak_recall: float
    disqualified: bool

    def to_dict(self) -> dict[str, object]:
        return {
            "name": self.name,
            "balanced_accuracy": self.balanced_accuracy,
            "speak_recall": self.speak_recall,
            "disqualified": self.disqualified,
        }


@dataclass(frozen=True)
class ImbalanceComparison:
    strategies: list[StrategyResult] = field(default_factory=list)

    def best_qualified(self) -> StrategyResult | None:
        ok = [s for s in self.strategies if not s.disqualified]
        return max(ok, key=lambda s: s.balanced_accuracy) if ok else None

    def to_dict(self) -> dict[str, object]:
        return {"strategies": [s.to_dict() for s in self.strategies]}


def compare_imbalance_strategies(
    ds: PolicyDataset,
    *,
    train_idx: np.ndarray,
    test_idx: np.ndarray,
    epochs: int = 80,
    lr: float = 0.05,
    hidden_dim: int = 16,
    seed: int = 20260622,
) -> ImbalanceComparison:
    """none/class_weight/focal/undersample 전략을 비교하고 퇴화 모델을 탈락시킨다."""

    def labeled(idx: np.ndarray) -> np.ndarray:
        return idx[(ds.action_mask[idx] > 0) & (ds.action_labels[idx] >= 0)]

    tr, te = labeled(train_idx), labeled(test_idx)
    X_tr, y_tr = _design(ds, tr), ds.action_labels[tr]
    X_te, y_te = _design(ds, te), ds.action_labels[te]
    in_dim = X_tr.shape[1]

    def evaluate(name: str, model: MlpActionModel) -> StrategyResult:
        y_pred = model.proba(X_te).argmax(1)
        speak_mask = y_te == _SPEAK
        speak_recall = float((y_pred[speak_mask] == _SPEAK).mean()) if speak_mask.sum() else 0.0
        return StrategyResult(
            name=name,
            balanced_accuracy=balanced_accuracy(y_te, y_pred, n_classes=N_ACTIONS),
            speak_recall=speak_recall,
            disqualified=degenerate_all_ignore(y_te, y_pred),
        )

    results: list[StrategyResult] = []

    # 1) none(불균형 그대로 — 퇴화 후보).
    m_none = _train_action_mlp(
        X_tr, y_tr, sample_weight=None, focal_gamma=None,
        epochs=epochs, lr=lr, in_dim=in_dim, hidden_dim=hidden_dim, seed=seed,
    )
    results.append(evaluate("none", m_none))

    # 2) class weight.
    m_cw = _train_action_mlp(
        X_tr, y_tr, sample_weight=class_weights_inverse(y_tr), focal_gamma=None,
        epochs=epochs, lr=lr, in_dim=in_dim, hidden_dim=hidden_dim, seed=seed,
    )
    results.append(evaluate("class_weight", m_cw))

    # 3) focal loss.
    m_focal = _train_action_mlp(
        X_tr, y_tr, sample_weight=None, focal_gamma=2.0,
        epochs=epochs, lr=lr, in_dim=in_dim, hidden_dim=hidden_dim, seed=seed,
    )
    results.append(evaluate("focal", m_focal))

    # 4) under-sampling.
    keep = undersample_indices(y_tr, seed=seed)
    m_us = _train_action_mlp(
        X_tr[keep], y_tr[keep], sample_weight=None, focal_gamma=None,
        epochs=epochs, lr=lr, in_dim=in_dim, hidden_dim=hidden_dim, seed=seed,
    )
    results.append(evaluate("undersample", m_us))

    return ImbalanceComparison(strategies=results)
