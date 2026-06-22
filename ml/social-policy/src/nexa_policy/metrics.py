"""정책 평가 지표(P11). baseline·모델 공통 — 결정론·numpy 전용.

지표:
- [balanced_accuracy]: 클래스별 recall 평균(불균형에서 다수 클래스 쏠림 패널티).
- [false_ignore_rate](FIR): 실제 SPEAK 인데 IGNORE 로 예측한 비율(발화 기회 놓침).
- [missed_interaction_rate](MIR): 실제 상호작용(SPEAK/REACT)인데 IGNORE 예측 비율.
- [brier_score]: 확률 예측의 제곱오차(calibration 품질).
- [expected_calibration_error](ECE): 신뢰도 구간별 |정확도-신뢰도| 가중 평균.

P09 baseline calibration(EXP-talkativeness 의 logit 보정)과 일관되게 SPEAK 확률을 다룬다.
"""

from __future__ import annotations

from typing import TYPE_CHECKING

if TYPE_CHECKING:
    import numpy as np


def balanced_accuracy(y_true: np.ndarray, y_pred: np.ndarray, *, n_classes: int) -> float:
    """클래스별 recall 의 평균. 라벨 없는 클래스는 평균에서 제외(존재 클래스만)."""
    import numpy as np

    if y_true.size == 0:
        return 0.0
    recalls: list[float] = []
    for c in range(n_classes):
        mask = y_true == c
        total = int(mask.sum())
        if total == 0:
            continue
        correct = int((y_pred[mask] == c).sum())
        recalls.append(correct / total)
    return float(np.mean(recalls)) if recalls else 0.0


def false_ignore_rate(
    y_true: np.ndarray, y_pred: np.ndarray, *, speak_class: int, ignore_class: int
) -> float:
    """실제 SPEAK 샘플 중 IGNORE 로 예측한 비율(FIR). SPEAK 가 없으면 0."""
    speak_mask = y_true == speak_class
    total = int(speak_mask.sum())
    if total == 0:
        return 0.0
    ignored = int((y_pred[speak_mask] == ignore_class).sum())
    return ignored / total


def missed_interaction_rate(
    y_true: np.ndarray,
    y_pred: np.ndarray,
    *,
    interaction_classes: tuple[int, ...],
    ignore_class: int,
) -> float:
    """실제 상호작용(SPEAK/REACT) 중 IGNORE 예측 비율(MIR)."""
    import numpy as np

    inter_mask = np.isin(y_true, list(interaction_classes))
    total = int(inter_mask.sum())
    if total == 0:
        return 0.0
    missed = int((y_pred[inter_mask] == ignore_class).sum())
    return missed / total


def brier_score(y_true_onehot: np.ndarray, probs: np.ndarray) -> float:
    """다중클래스 Brier score = mean over samples of sum_c (p_c - y_c)^2."""
    import numpy as np

    if probs.size == 0:
        return 0.0
    return float(np.mean(np.sum((probs - y_true_onehot) ** 2, axis=1)))


def expected_calibration_error(
    confidences: np.ndarray, correct: np.ndarray, *, n_bins: int = 10
) -> float:
    """ECE: 신뢰도(예측 최대확률) 구간별 |정확도-평균신뢰도| 의 표본 가중 평균."""
    import numpy as np

    if confidences.size == 0:
        return 0.0
    bins = np.linspace(0.0, 1.0, n_bins + 1)
    ece = 0.0
    n = confidences.shape[0]
    for b in range(n_bins):
        lo, hi = bins[b], bins[b + 1]
        in_bin = (confidences > lo) & (confidences <= hi) if b > 0 else (confidences <= hi)
        count = int(in_bin.sum())
        if count == 0:
            continue
        acc = float(correct[in_bin].mean())
        conf = float(confidences[in_bin].mean())
        ece += (count / n) * abs(acc - conf)
    return ece


def one_hot(labels: np.ndarray, n_classes: int) -> np.ndarray:
    """라벨 → one-hot float32. 음수(-1) 라벨은 모두 0 행(masked 용)."""
    import numpy as np

    out = np.zeros((labels.shape[0], n_classes), dtype=np.float32)
    valid = labels >= 0
    out[np.arange(labels.shape[0])[valid], labels[valid]] = 1.0
    return out
