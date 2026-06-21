"""Logistic SPEAK/SILENT baseline(NEXA-P11-T004).

해석 가능한 선형 모델로 SPEAK vs SILENT(=not SPEAK) 이진 분류를 학습한다. class weight 로
불균형(다수 SILENT)을 보정한다. sklearn LogisticRegression — 결정론(seed 고정, lbfgs).

**acceptance(T004) — validation/test balanced accuracy, FIR, MIR, Brier 가 저장된다**:
- [train_logistic] 은 train 으로 학습하고 [LogisticReport] 에 val/test 지표를 모두 담는다.
- class_weight="balanced" 로 "모두 SILENT" 퇴화를 막는다(소수 SPEAK 의 recall 반영 → FIR/MIR).
- 가중치(coef_)는 feature 별 해석 가능(어떤 feature 가 SPEAK 를 끌어올리는지).
"""

from __future__ import annotations

from dataclasses import dataclass
from typing import TYPE_CHECKING, Any

from nexa_policy.datasets import PolicyDataset, action_is_speak
from nexa_policy.metrics import brier_score, one_hot

if TYPE_CHECKING:
    import numpy as np


@dataclass(frozen=True)
class BinaryReport:
    """SPEAK/SILENT 이진 분류 지표 한 split."""

    split: str
    n: int
    balanced_accuracy: float
    false_ignore_rate: float  # 실제 SPEAK 인데 SILENT 예측 비율.
    missed_interaction_rate: float  # 여기선 FIR 과 동일(이진) — 일관 보고용.
    brier_score: float

    def to_dict(self) -> dict[str, object]:
        return {
            "split": self.split,
            "n": self.n,
            "balanced_accuracy": self.balanced_accuracy,
            "false_ignore_rate": self.false_ignore_rate,
            "missed_interaction_rate": self.missed_interaction_rate,
            "brier_score": self.brier_score,
        }


@dataclass(frozen=True)
class LogisticReport:
    """logistic baseline 결과 — val/test 지표 + 해석용 계수."""

    validation: BinaryReport
    test: BinaryReport
    coefficients: dict[str, float]
    intercept: float

    def to_dict(self) -> dict[str, object]:
        return {
            "model": "logistic",
            "validation": self.validation.to_dict(),
            "test": self.test.to_dict(),
            "coefficients": dict(self.coefficients),
            "intercept": self.intercept,
        }


def _design_matrix(ds: PolicyDataset, idx: np.ndarray) -> np.ndarray:
    """feature 값 + missing mask 를 결합한 설계행렬(0 과 '모름' 구분 학습)."""
    import numpy as np

    return np.concatenate([ds.features[idx], ds.missing_mask[idx]], axis=1).astype(np.float64)


def _binary_report(
    split: str, y_true: np.ndarray, proba_speak: np.ndarray
) -> BinaryReport:
    import numpy as np

    from nexa_policy.metrics import balanced_accuracy, false_ignore_rate

    y_pred = (proba_speak >= 0.5).astype(np.int64)
    # SPEAK=1, SILENT=0. FIR: 실제 1 인데 0 예측.
    fir = false_ignore_rate(y_true, y_pred, speak_class=1, ignore_class=0)
    probs2 = np.stack([1.0 - proba_speak, proba_speak], axis=1)
    return BinaryReport(
        split=split,
        n=int(y_true.shape[0]),
        balanced_accuracy=balanced_accuracy(y_true, y_pred, n_classes=2),
        false_ignore_rate=fir,
        missed_interaction_rate=fir,
        brier_score=brier_score(one_hot(y_true, 2), probs2),
    )


@dataclass
class LogisticModel:
    """학습된 logistic 모델 wrapper(추론용)."""

    estimator: Any
    feature_ids: tuple[str, ...]

    def predict_proba_speak(self, X: np.ndarray) -> np.ndarray:
        import numpy as np

        proba: np.ndarray = self.estimator.predict_proba(X.astype(np.float64))
        # SPEAK(=1) 클래스 열. 단일 클래스 학습 방어.
        classes = list(self.estimator.classes_)
        if 1 in classes:
            return proba[:, classes.index(1)]
        return np.zeros(X.shape[0])


def train_logistic(
    ds: PolicyDataset,
    *,
    train_idx: np.ndarray,
    val_idx: np.ndarray,
    test_idx: np.ndarray,
    seed: int = 20260622,
) -> tuple[LogisticModel, LogisticReport]:
    """SPEAK/SILENT logistic 을 학습하고 val/test 지표를 낸다. UNKNOWN(mask=0)은 학습/평가 제외."""
    from sklearn.linear_model import LogisticRegression

    def labeled(idx: np.ndarray) -> np.ndarray:
        return idx[(ds.action_mask[idx] > 0) & (ds.action_labels[idx] >= 0)]

    tr, va, te = labeled(train_idx), labeled(val_idx), labeled(test_idx)
    X_tr = _design_matrix(ds, tr)
    y_tr = action_is_speak(ds.action_labels[tr])

    clf = LogisticRegression(
        class_weight="balanced", max_iter=2000, random_state=seed, solver="lbfgs"
    )
    clf.fit(X_tr, y_tr)
    model = LogisticModel(estimator=clf, feature_ids=ds.catalog.feature_ids)

    val_report = _binary_report(
        "validation", action_is_speak(ds.action_labels[va]), model.predict_proba_speak(_design_matrix(ds, va))
    )
    test_report = _binary_report(
        "test", action_is_speak(ds.action_labels[te]), model.predict_proba_speak(_design_matrix(ds, te))
    )

    feat_names = list(ds.catalog.feature_ids) + [f"{f}__missing" for f in ds.catalog.feature_ids]
    coefs = {name: float(c) for name, c in zip(feat_names, clf.coef_[0], strict=True)}
    report = LogisticReport(
        validation=val_report,
        test=test_report,
        coefficients=coefs,
        intercept=float(clf.intercept_[0]),
    )
    return model, report
