"""소형 MLP action model(NEXA-P11-T006).

feature 벡터에서 action 분포(IGNORE/WAIT/REACT/SPEAK/CANCEL)를 예측하는 단일 태스크 MLP.
멀티헤드(T008~)와 달리 action 만 학습해 baseline 과 직접 비교한다(parameter 수·latency·성능).

**acceptance(T006) — parameter 수·latency·성능이 baseline 과 비교된다**:
- [MlpActionModel.param_count] 로 파라미터 수를 보고한다.
- [train_mlp_action] 은 학습 후 [MlpActionReport] 에 val/test balanced accuracy·FIR·MIR·Brier 와
  파라미터 수·추론 latency(초/샘플)를 담는다 → baseline(policy_baselines)과 같은 지표로 비교.
"""

from __future__ import annotations

import time
from dataclasses import dataclass
from typing import TYPE_CHECKING

from nexa_policy.datasets import ACTION_HEAD_CLASSES, PolicyDataset
from nexa_policy.metrics import (
    balanced_accuracy,
    brier_score,
    false_ignore_rate,
    missed_interaction_rate,
    one_hot,
)
from nexa_policy.models.nn import (
    Linear,
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
_REACT = ACTION_HEAD_CLASSES.index("react")


@dataclass
class MlpActionModel:
    """단일 hidden layer MLP action classifier(numpy)."""

    fc1: Linear
    fc2: Linear

    @classmethod
    def build(cls, *, in_dim: int, hidden_dim: int = 16, seed: int = 20260622) -> MlpActionModel:
        fc1 = Linear(in_dim, hidden_dim)
        fc2 = Linear(hidden_dim, N_ACTIONS)
        fc1.init(seed)
        fc2.init(seed + 1)
        return cls(fc1=fc1, fc2=fc2)

    @property
    def param_count(self) -> int:
        return int(self.fc1.W.size + self.fc1.b.size + self.fc2.W.size + self.fc2.b.size)

    def proba(self, x: np.ndarray) -> np.ndarray:
        h = relu(self.fc1.forward(x))
        return softmax(self.fc2.forward(h))


@dataclass(frozen=True)
class MlpActionReport:
    """MLP action 결과 — baseline 비교용 지표 + 파라미터·latency."""

    n_val: int
    n_test: int
    val_balanced_accuracy: float
    test_balanced_accuracy: float
    test_false_ignore_rate: float
    test_missed_interaction_rate: float
    test_brier_score: float
    param_count: int
    inference_seconds_per_sample: float

    def to_dict(self) -> dict[str, object]:
        return {
            "model": "mlp_action",
            "n_val": self.n_val,
            "n_test": self.n_test,
            "val_balanced_accuracy": self.val_balanced_accuracy,
            "test_balanced_accuracy": self.test_balanced_accuracy,
            "test_false_ignore_rate": self.test_false_ignore_rate,
            "test_missed_interaction_rate": self.test_missed_interaction_rate,
            "test_brier_score": self.test_brier_score,
            "param_count": self.param_count,
            "inference_seconds_per_sample": self.inference_seconds_per_sample,
        }


def _design(ds: PolicyDataset, idx: np.ndarray) -> np.ndarray:
    import numpy as np

    return np.concatenate([ds.features[idx], ds.missing_mask[idx]], axis=1).astype(np.float64)


def train_mlp_action(
    ds: PolicyDataset,
    *,
    train_idx: np.ndarray,
    val_idx: np.ndarray,
    test_idx: np.ndarray,
    epochs: int = 80,
    lr: float = 0.05,
    hidden_dim: int = 16,
    seed: int = 20260622,
) -> tuple[MlpActionModel, MlpActionReport]:
    """action MLP 를 학습하고 baseline 과 같은 지표로 보고한다(UNKNOWN 제외)."""
    import numpy as np

    def labeled(idx: np.ndarray) -> np.ndarray:
        return idx[(ds.action_mask[idx] > 0) & (ds.action_labels[idx] >= 0)]

    tr, va, te = labeled(train_idx), labeled(val_idx), labeled(test_idx)
    in_dim = _design(ds, tr[:1]).shape[1] if tr.size else (2 * ds.dim)
    model = MlpActionModel.build(in_dim=in_dim, hidden_dim=hidden_dim, seed=seed)

    X_tr = _design(ds, tr)
    y_tr = ds.action_labels[tr]
    # class weight(불균형 보정): inverse frequency.
    counts = np.bincount(y_tr, minlength=N_ACTIONS).astype(np.float64)
    inv = np.where(counts > 0, 1.0 / counts, 0.0)
    sample_w = inv[y_tr]
    sample_w = sample_w / sample_w.mean() if sample_w.mean() > 0 else sample_w

    for _ in range(epochs):
        model.fc1.zero_grad()
        model.fc2.zero_grad()
        h_pre = model.fc1.forward(X_tr)
        h = relu(h_pre)
        logits = model.fc2.forward(h)
        probs = softmax(logits)
        _, grad = softmax_cross_entropy_grad(probs, y_tr, sample_w)
        grad_h = model.fc2.backward(grad)
        grad_h_pre = relu_backward(grad_h, h_pre)
        model.fc1.backward(grad_h_pre)
        model.fc1.step(lr)
        model.fc2.step(lr)

    def report_split(idx: np.ndarray) -> tuple[int, float]:
        if idx.size == 0:
            return 0, 0.0
        p = model.proba(_design(ds, idx))
        y = ds.action_labels[idx]
        return int(idx.size), balanced_accuracy(y, p.argmax(1), n_classes=N_ACTIONS)

    n_val, val_bacc = report_split(va)

    # test 지표 + latency.
    X_te = _design(ds, te)
    start = time.perf_counter()
    p_te = model.proba(X_te)
    elapsed = time.perf_counter() - start
    per_sample = elapsed / max(1, te.size)
    y_te = ds.action_labels[te]
    y_pred = p_te.argmax(1)

    report = MlpActionReport(
        n_val=n_val,
        n_test=int(te.size),
        val_balanced_accuracy=val_bacc,
        test_balanced_accuracy=balanced_accuracy(y_te, y_pred, n_classes=N_ACTIONS),
        test_false_ignore_rate=false_ignore_rate(
            y_te, y_pred, speak_class=_SPEAK, ignore_class=_IGNORE
        ),
        test_missed_interaction_rate=missed_interaction_rate(
            y_te, y_pred, interaction_classes=(_SPEAK, _REACT), ignore_class=_IGNORE
        ),
        test_brier_score=brier_score(one_hot(y_te, N_ACTIONS), p_te),
        param_count=model.param_count,
        inference_seconds_per_sample=per_sample,
    )
    return model, report
