"""reaction/message 비율 적응(NEXA-P19-T007). 운영 데이터 미접근 — 합성 fixture·결정론. torch 미사용(numpy).

서버 문화(reaction 중심 vs 메시지 중심)에 맞춰 REACT 와 SPEAK mix 를 **제한 범위에서** 조정한다. 조정은
SPEAK 확률 질량 일부를 REACT 로 옮기는 bounded shift 이며, IGNORE(침묵)는 건드리지 않는다(말을 줄이는 게
침묵을 늘리는 것과 다르다).

acceptance(T007) — 말을 줄이고 reaction 을 늘린 것이 quality 개선인지 FIR/MIR 와 함께 본다:
- [apply_mix_shift]: SPEAK→REACT 로 shift_fraction 만큼만 질량 이동(범위 제한). IGNORE 불변 → 상호작용
  총량(SPEAK+REACT)은 보존되므로 MIR(상호작용을 IGNORE 로 놓침)은 정의상 악화되지 않는다.
- [MixAdaptationReport]: shift 전/후의 FIR 와 MIR 를 함께 보고한다. "reaction 으로 바꿔서 발화 기회를 놓친
  것처럼 보이는지(FIR↑)" 를 숨기지 않고 본다 — quality 개선 판정은 FIR/MIR 동반 확인 없이는 하지 않는다.
"""

from __future__ import annotations

from dataclasses import dataclass
from typing import TYPE_CHECKING

from nexa_policy.datasets import ACTION_HEAD_CLASSES
from nexa_policy.metrics import false_ignore_rate, missed_interaction_rate

if TYPE_CHECKING:
    import numpy as np

_IGNORE = ACTION_HEAD_CLASSES.index("ignore")
_REACT = ACTION_HEAD_CLASSES.index("react")
_SPEAK = ACTION_HEAD_CLASSES.index("speak")

# SPEAK→REACT 로 옮길 수 있는 최대 질량 비율(제한 범위). 과적응 방지.
MAX_SHIFT_FRACTION = 0.5


def apply_mix_shift(probs: np.ndarray, shift_fraction: float) -> np.ndarray:
    """각 행의 SPEAK 확률 질량 중 [shift_fraction] 을 REACT 로 옮긴다(IGNORE 불변, 행합 보존).

    shift_fraction ∈ [0, MAX_SHIFT_FRACTION]. 0=변화 없음. 상호작용 총량(SPEAK+REACT)은 보존된다.
    """
    import numpy as np

    if not 0.0 <= shift_fraction <= MAX_SHIFT_FRACTION:
        raise ValueError(f"shift_fraction 은 [0, {MAX_SHIFT_FRACTION}] 범위여야 한다: {shift_fraction}")
    out = np.array(probs, dtype=np.float64, copy=True)
    moved = out[:, _SPEAK] * shift_fraction
    out[:, _SPEAK] -= moved
    out[:, _REACT] += moved
    return out


@dataclass(frozen=True)
class MixAdaptationReport:
    """mix shift 전/후 FIR·MIR 동반 리포트(quality 개선 판정 근거)."""

    shift_fraction: float
    fir_before: float
    fir_after: float
    mir_before: float
    mir_after: float

    @property
    def fir_worsened(self) -> bool:
        """reaction 으로 옮기면서 발화 기회 놓침(FIR)이 늘었는가(허용오차)."""
        return self.fir_after > self.fir_before + 1e-12

    @property
    def mir_worsened(self) -> bool:
        """상호작용을 IGNORE 로 놓침(MIR)이 늘었는가 — shift 는 IGNORE 불변이라 정상 False."""
        return self.mir_after > self.mir_before + 1e-12

    def is_quality_improvement(self) -> bool:
        """FIR·MIR 어느 것도 악화하지 않을 때만 개선으로 본다(개선 주장은 둘 다 확인)."""
        return not self.fir_worsened and not self.mir_worsened

    def to_dict(self) -> dict[str, object]:
        return {
            "shift_fraction": self.shift_fraction,
            "fir_before": self.fir_before,
            "fir_after": self.fir_after,
            "mir_before": self.mir_before,
            "mir_after": self.mir_after,
        }


def evaluate_mix_shift(
    *,
    y_true: np.ndarray,
    probs: np.ndarray,
    shift_fraction: float,
) -> MixAdaptationReport:
    """mix shift 전/후 예측(argmax)으로 FIR·MIR 를 계산해 동반 리포트를 만든다.

    y_true 는 관측 라벨(UNKNOWN 제외 호출자 책임). 예측은 확률 argmax. shift 는 probs 에 적용 후 재argmax.
    """
    shifted = apply_mix_shift(probs, shift_fraction)
    pred_before = probs.argmax(axis=1)
    pred_after = shifted.argmax(axis=1)
    return MixAdaptationReport(
        shift_fraction=shift_fraction,
        fir_before=false_ignore_rate(y_true, pred_before, speak_class=_SPEAK, ignore_class=_IGNORE),
        fir_after=false_ignore_rate(y_true, pred_after, speak_class=_SPEAK, ignore_class=_IGNORE),
        mir_before=missed_interaction_rate(
            y_true, pred_before, interaction_classes=(_SPEAK, _REACT), ignore_class=_IGNORE
        ),
        mir_after=missed_interaction_rate(
            y_true, pred_after, interaction_classes=(_SPEAK, _REACT), ignore_class=_IGNORE
        ),
    )
