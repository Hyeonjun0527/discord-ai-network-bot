"""Gradient-boosted tree baseline(NEXA-P11-T005).

비선형 tabular baseline(sklearn HistGradientBoostingClassifier)을 SPEAK/SILENT 에 학습하고
feature importance(permutation)를 분석한다. 결정론(random_state 고정).

**acceptance(T005) — 길드 holdout 성능과 과적합 차이가 보고된다**:
- [train_tree] 는 train balanced accuracy 와 길드 holdout(test) balanced accuracy 를 모두 담아
  과적합 gap(train - test)을 [TreeReport.overfit_gap] 으로 보고한다.
- permutation importance 로 어떤 feature 가 예측에 기여하는지 [TreeReport.feature_importance] 에 담는다.
"""

from __future__ import annotations

from dataclasses import dataclass
from typing import TYPE_CHECKING, Any

from nexa_policy.datasets import PolicyDataset, action_is_speak
from nexa_policy.metrics import balanced_accuracy, brier_score, false_ignore_rate, one_hot
from nexa_policy.models.logistic import BinaryReport, _design_matrix

if TYPE_CHECKING:
    import numpy as np


@dataclass(frozen=True)
class TreeReport:
    """GBT baseline 결과 — val/test 지표 + 과적합 gap + feature importance."""

    validation: BinaryReport
    test: BinaryReport
    train_balanced_accuracy: float
    overfit_gap: float  # train - test balanced accuracy(클수록 과적합).
    feature_importance: dict[str, float]

    def to_dict(self) -> dict[str, object]:
        return {
            "model": "gradient_boosted_tree",
            "validation": self.validation.to_dict(),
            "test": self.test.to_dict(),
            "train_balanced_accuracy": self.train_balanced_accuracy,
            "overfit_gap": self.overfit_gap,
            "feature_importance": dict(self.feature_importance),
        }


@dataclass
class TreeModel:
    estimator: Any
    feature_ids: tuple[str, ...]

    def predict_proba_speak(self, X: np.ndarray) -> np.ndarray:
        import numpy as np

        proba: np.ndarray = self.estimator.predict_proba(X.astype(np.float64))
        classes = list(self.estimator.classes_)
        if 1 in classes:
            return proba[:, classes.index(1)]
        return np.zeros(X.shape[0])


def _report(split: str, y_true: np.ndarray, proba_speak: np.ndarray) -> BinaryReport:
    import numpy as np

    y_pred = (proba_speak >= 0.5).astype(np.int64)
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


def train_tree(
    ds: PolicyDataset,
    *,
    train_idx: np.ndarray,
    val_idx: np.ndarray,
    test_idx: np.ndarray,
    seed: int = 20260622,
) -> tuple[TreeModel, TreeReport]:
    """GBT 를 SPEAK/SILENT 에 학습하고 과적합 gap·permutation importance 를 보고한다."""
    import numpy as np
    from sklearn.ensemble import HistGradientBoostingClassifier
    from sklearn.inspection import permutation_importance

    def labeled(idx: np.ndarray) -> np.ndarray:
        return idx[(ds.action_mask[idx] > 0) & (ds.action_labels[idx] >= 0)]

    tr, va, te = labeled(train_idx), labeled(val_idx), labeled(test_idx)
    X_tr, y_tr = _design_matrix(ds, tr), action_is_speak(ds.action_labels[tr])

    clf = HistGradientBoostingClassifier(
        max_depth=3, max_iter=60, learning_rate=0.1, random_state=seed, l2_regularization=1.0
    )
    clf.fit(X_tr, y_tr)
    model = TreeModel(estimator=clf, feature_ids=ds.catalog.feature_ids)

    val_report = _report("validation", action_is_speak(ds.action_labels[va]), model.predict_proba_speak(_design_matrix(ds, va)))
    test_report = _report("test", action_is_speak(ds.action_labels[te]), model.predict_proba_speak(_design_matrix(ds, te)))

    y_pred_tr = (model.predict_proba_speak(X_tr) >= 0.5).astype(np.int64)
    train_bacc = balanced_accuracy(y_tr, y_pred_tr, n_classes=2)
    overfit_gap = train_bacc - test_report.balanced_accuracy

    feat_names = list(ds.catalog.feature_ids) + [f"{f}__missing" for f in ds.catalog.feature_ids]
    importance: dict[str, float] = {}
    if te.shape[0] >= 2 and len(set(action_is_speak(ds.action_labels[te]).tolist())) > 1:
        perm = permutation_importance(
            clf, _design_matrix(ds, te), action_is_speak(ds.action_labels[te]),
            n_repeats=5, random_state=seed, scoring="balanced_accuracy",
        )
        importance = {n: float(v) for n, v in zip(feat_names, perm.importances_mean, strict=True)}

    return model, TreeReport(
        validation=val_report,
        test=test_report,
        train_balanced_accuracy=train_bacc,
        overfit_gap=overfit_gap,
        feature_importance=importance,
    )
