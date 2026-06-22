"""behavior cloning v2 정책(NEXA-P19-T014). 운영 데이터 미접근 — 합성 fixture·결정론. torch 미사용(numpy).

v1(`models.mlp.MlpActionModel`)은 **현재 시점 feature** 만으로 action 을 모방한다. v2 는 같은 supervised
behavior cloning 이되 **장기 state(누적 상호작용 통계)와 timing(직전 행동 이후 경과 bin)** 을 추가 입력으로 받아
사람의 행동을 더 잘 모방한다(deliverable T014). 같은 split 에서 v1·v2 를 학습하고 **장기 cohort holdout** 의
이득(balanced accuracy·FIR/MIR)과 비용(param 수·추론 latency)을 비교한다(acceptance T014).

**acceptance(T014) — v1 대비 장기 cohort holdout 이득과 비용을 비교한다**:
- [augment_long_state] 가 현재 feature 에 장기 state·timing 채널을 덧붙여 v2 입력을 만든다.
- [compare_v1_v2] 가 같은 holdout 에서 두 모델의 지표(이득)와 param 수·latency(비용)를 한 표([BcComparison])로
  돌려준다 — 이득만 보고 비용을 숨기지 않는다(MLP 카드와 같은 지표 어휘).

장기 state/timing 은 P10 export 경계(관찰/집계 신호)만 쓴다 — 원문·식별자·내면 상태 추론 없음.
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
from nexa_policy.models.mlp import MlpActionModel, _design, train_mlp_action
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
_REACT = ACTION_HEAD_CLASSES.index("react")

# v2 가 덧붙이는 장기 state·timing 채널 수(현재 feature 외 추가 차원). 관찰/집계 신호만.
LONG_STATE_CHANNELS: tuple[str, ...] = (
    "cumulative_speak_rate",   # 이 cohort 에서 사람의 누적 SPEAK 비율(장기 talkativeness).
    "cumulative_react_rate",   # 누적 REACT 비율.
    "recent_activity_density",  # 최근 창의 활동 밀도(장기 tempo).
    "since_last_action_bin",   # 직전 행동 이후 경과 시간 bin(정규화) — timing.
)
N_LONG_CHANNELS = len(LONG_STATE_CHANNELS)


def augment_long_state(
    ds: PolicyDataset,
    idx: np.ndarray,
    *,
    decay: float = 0.9,
) -> np.ndarray:
    """현재 feature 설계행렬에 장기 state·timing 채널을 덧붙인다(v2 입력).

    장기 state 는 시간순 누적 통계로 결정론적으로 파생한다(같은 ds → 같은 값). timing 은 직전 샘플과의 시간
    간격을 bin 으로 정규화한다. 모두 관찰/집계 신호다 — 원문·식별자 미사용.
    """
    import numpy as np

    base = _design(ds, idx)
    order = np.argsort(ds.event_time_ms[idx], kind="mergesort")
    times = ds.event_time_ms[idx][order].astype(np.float64)
    actions = ds.action_labels[idx][order]
    masks = ds.action_mask[idx][order]

    long_state = np.zeros((idx.size, N_LONG_CHANNELS), dtype=np.float64)
    speak_ewma = 0.0
    react_ewma = 0.0
    density_ewma = 0.0
    prev_time: float | None = None
    span = float(times.max() - times.min()) if idx.size > 1 else 1.0
    span = span if span > 0 else 1.0
    for pos in range(idx.size):
        gap = 0.0 if prev_time is None else (times[pos] - prev_time)
        # 직전 행동 이후 경과를 [0,1] 로 정규화(전체 span 기준). timing 신호.
        since_last = min(1.0, gap / span)
        long_state[order[pos]] = (
            speak_ewma,
            react_ewma,
            density_ewma,
            since_last,
        )
        labeled = masks[pos] > 0
        is_speak = 1.0 if labeled and actions[pos] == _SPEAK else 0.0
        is_react = 1.0 if labeled and actions[pos] == _REACT else 0.0
        speak_ewma = decay * speak_ewma + (1.0 - decay) * is_speak
        react_ewma = decay * react_ewma + (1.0 - decay) * is_react
        # 활동 밀도: 짧은 gap 일수록 높음(가까운 행동 = 붐빔).
        density = 1.0 - since_last
        density_ewma = decay * density_ewma + (1.0 - decay) * density
        prev_time = times[pos]

    return np.concatenate([base, long_state], axis=1).astype(np.float64)


@dataclass(frozen=True)
class BcV2Report:
    """BC v2 결과 — v1 과 같은 지표 어휘 + 비용(param·latency). 이득과 비용을 함께 본다."""

    n_test: int
    test_balanced_accuracy: float
    test_false_ignore_rate: float
    test_missed_interaction_rate: float
    test_brier_score: float
    param_count: int
    inference_seconds_per_sample: float

    def to_dict(self) -> dict[str, object]:
        return {
            "model": "bc_v2",
            "n_test": self.n_test,
            "test_balanced_accuracy": self.test_balanced_accuracy,
            "test_false_ignore_rate": self.test_false_ignore_rate,
            "test_missed_interaction_rate": self.test_missed_interaction_rate,
            "test_brier_score": self.test_brier_score,
            "param_count": self.param_count,
            "inference_seconds_per_sample": self.inference_seconds_per_sample,
        }


def train_bc_v2(
    ds: PolicyDataset,
    *,
    train_idx: np.ndarray,
    test_idx: np.ndarray,
    epochs: int = 80,
    lr: float = 0.05,
    hidden_dim: int = 16,
    seed: int = 20260622,
) -> tuple[MlpActionModel, BcV2Report]:
    """장기 state·timing 을 덧붙인 BC v2 action 모델을 학습하고 v1 과 같은 지표로 보고한다."""
    import numpy as np

    def labeled(idx: np.ndarray) -> np.ndarray:
        return idx[(ds.action_mask[idx] > 0) & (ds.action_labels[idx] >= 0)]

    tr, te = labeled(train_idx), labeled(test_idx)
    X_tr = augment_long_state(ds, tr)
    in_dim = X_tr.shape[1]
    model = MlpActionModel.build(in_dim=in_dim, hidden_dim=hidden_dim, seed=seed)

    y_tr = ds.action_labels[tr]
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

    X_te = augment_long_state(ds, te)
    start = time.perf_counter()
    p_te = model.proba(X_te)
    elapsed = time.perf_counter() - start
    per_sample = elapsed / max(1, te.size)
    y_te = ds.action_labels[te]
    y_pred = p_te.argmax(1)

    report = BcV2Report(
        n_test=int(te.size),
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


def _num(value: object) -> float:
    """dict[str, object] 값에서 수치를 안전하게 꺼낸다(보고 dict 는 숫자만 담는다)."""
    if isinstance(value, (int, float)):
        return float(value)
    raise TypeError(f"수치가 아닌 값: {value!r}")


@dataclass(frozen=True)
class BcComparison:
    """v1 vs v2 한 holdout 비교(이득과 비용을 한 표로). gain=지표 개선, cost=param/latency 증가."""

    v1: dict[str, object]
    v2: dict[str, object]

    @property
    def balanced_accuracy_gain(self) -> float:
        """v2 - v1 의 test balanced accuracy(양수면 v2 가 더 사람을 잘 모방)."""
        return _num(self.v2["test_balanced_accuracy"]) - _num(self.v1["test_balanced_accuracy"])

    @property
    def param_cost_ratio(self) -> float:
        """v2 param / v1 param(>1 이면 v2 가 더 비쌈)."""
        v1p = _num(self.v1["param_count"])
        return _num(self.v2["param_count"]) / v1p if v1p > 0 else 0.0

    def to_dict(self) -> dict[str, object]:
        return {
            "v1": dict(self.v1),
            "v2": dict(self.v2),
            "balanced_accuracy_gain": self.balanced_accuracy_gain,
            "param_cost_ratio": self.param_cost_ratio,
        }


def compare_v1_v2(
    ds: PolicyDataset,
    *,
    train_idx: np.ndarray,
    val_idx: np.ndarray,
    test_idx: np.ndarray,
    seed: int = 20260622,
) -> BcComparison:
    """같은 split 에서 v1(현재 feature)·v2(장기 state+timing)를 학습해 이득·비용을 비교한다(acceptance T014)."""
    _, v1_report = train_mlp_action(
        ds, train_idx=train_idx, val_idx=val_idx, test_idx=test_idx, seed=seed
    )
    _, v2_report = train_bc_v2(ds, train_idx=train_idx, test_idx=test_idx, seed=seed)
    return BcComparison(v1=v1_report.to_dict(), v2=v2_report.to_dict())
