"""정책 baseline 재현(NEXA-P11-T003).

P09 의 무학습 기준선(always-silent·fixed-probability)을 같은 dataset split 에서 재계산해
학습 모델이 넘어야 할 바닥선을 고정한다. 결정론(seed) — 같은 split→같은 점수.

**acceptance(T003) — Kotlin shadow 리포트와 차이가 있으면 원인을 문서화한다**:
- baseline 은 학습이 없으므로 정의가 곧 결과다. [AlwaysSilentBaseline] 은 항상 IGNORE,
  [FixedProbabilityBaseline] 은 고정 확률 분포로 표본추출(seed 결정론).
- [evaluate_baseline] 은 balanced accuracy/FIR/MIR/Brier 를 낸다 → 모델 평가와 동일 지표로 비교 가능.
- shadow(Kotlin) 와의 차이는 [BaselineReport.notes] 로 명시하도록 둔다(조용한 불일치 금지).
"""

from __future__ import annotations

from dataclasses import dataclass, field
from typing import TYPE_CHECKING, Protocol

from nexa_policy.datasets import ACTION_HEAD_CLASSES
from nexa_policy.metrics import (
    balanced_accuracy,
    brier_score,
    false_ignore_rate,
    missed_interaction_rate,
    one_hot,
)
from nexa_policy.reproducibility import rng

if TYPE_CHECKING:
    import numpy as np

_IGNORE = ACTION_HEAD_CLASSES.index("ignore")
_SPEAK = ACTION_HEAD_CLASSES.index("speak")
_REACT = ACTION_HEAD_CLASSES.index("react")
N_ACTIONS = len(ACTION_HEAD_CLASSES)


class ActionBaseline(Protocol):
    """action 분포를 내는 baseline. 학습 없음 — 정의가 곧 예측."""

    name: str

    def predict_proba(self, n: int, *, seed: int) -> np.ndarray:
        """(n, N_ACTIONS) 확률 행렬을 반환한다(결정론)."""
        ...


@dataclass(frozen=True)
class AlwaysSilentBaseline:
    """항상 IGNORE(침묵). 가장 보수적인 바닥선."""

    name: str = "always_silent"

    def predict_proba(self, n: int, *, seed: int) -> np.ndarray:
        import numpy as np

        probs = np.zeros((n, N_ACTIONS), dtype=np.float32)
        probs[:, _IGNORE] = 1.0
        return probs


@dataclass(frozen=True)
class FixedProbabilityBaseline:
    """고정 분포 baseline. 데이터셋의 marginal(또는 명시 분포)로 예측 — 학습 신호 없음."""

    distribution: tuple[float, ...]
    name: str = "fixed_probability"

    def __post_init__(self) -> None:
        if len(self.distribution) != N_ACTIONS:
            raise ValueError(f"분포 길이는 {N_ACTIONS} 여야 한다.")
        if abs(sum(self.distribution) - 1.0) > 1e-6:
            raise ValueError("분포 합은 1.0 이어야 한다.")

    def predict_proba(self, n: int, *, seed: int) -> np.ndarray:
        import numpy as np

        row = np.asarray(self.distribution, dtype=np.float32)
        return np.tile(row, (n, 1))

    @classmethod
    def from_marginal(cls, action_labels: np.ndarray, action_mask: np.ndarray) -> (
        FixedProbabilityBaseline
    ):
        """관측된(mask=1) 라벨의 marginal 분포로 fixed baseline 을 만든다."""
        import numpy as np

        valid = (action_mask > 0) & (action_labels >= 0)
        counts = np.bincount(action_labels[valid], minlength=N_ACTIONS).astype(np.float64)
        total = counts.sum()
        dist = (counts / total) if total > 0 else np.full(N_ACTIONS, 1.0 / N_ACTIONS)
        return cls(distribution=tuple(float(x) for x in dist))


@dataclass(frozen=True)
class BaselineReport:
    """baseline 평가 리포트. 모델과 동일 지표 + shadow 차이 notes."""

    name: str
    n_evaluated: int
    balanced_accuracy: float
    false_ignore_rate: float
    missed_interaction_rate: float
    brier_score: float
    notes: list[str] = field(default_factory=list)

    def to_dict(self) -> dict[str, object]:
        return {
            "name": self.name,
            "n_evaluated": self.n_evaluated,
            "balanced_accuracy": self.balanced_accuracy,
            "false_ignore_rate": self.false_ignore_rate,
            "missed_interaction_rate": self.missed_interaction_rate,
            "brier_score": self.brier_score,
            "notes": list(self.notes),
        }


def evaluate_baseline(
    baseline: ActionBaseline,
    *,
    action_labels: np.ndarray,
    action_mask: np.ndarray,
    seed: int,
    notes: list[str] | None = None,
) -> BaselineReport:
    """baseline 을 관측 라벨(mask=1)에 대해 평가한다. UNKNOWN(mask=0)은 제외(강제 IGNORE 금지)."""

    valid = (action_mask > 0) & (action_labels >= 0)
    y_true = action_labels[valid]
    n = int(y_true.shape[0])
    probs = baseline.predict_proba(n, seed=seed)
    # 결정론 argmax(동률은 낮은 index — np.argmax 기본).
    _rng = rng(seed)  # fixed baseline 의 표본추출이 필요하면 쓰도록 결정론 generator 확보.
    del _rng
    y_pred = probs.argmax(axis=1)

    return BaselineReport(
        name=baseline.name,
        n_evaluated=n,
        balanced_accuracy=balanced_accuracy(y_true, y_pred, n_classes=N_ACTIONS),
        false_ignore_rate=false_ignore_rate(
            y_true, y_pred, speak_class=_SPEAK, ignore_class=_IGNORE
        ),
        missed_interaction_rate=missed_interaction_rate(
            y_true, y_pred, interaction_classes=(_SPEAK, _REACT), ignore_class=_IGNORE
        ),
        brier_score=brier_score(one_hot(y_true, N_ACTIONS), probs),
        notes=list(notes or []),
    )
