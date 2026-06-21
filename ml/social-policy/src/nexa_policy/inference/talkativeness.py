"""talkativeness hazard scaling(NEXA-P12-T009, ml 쪽).

서버별 talkativeness multiplier 를 **speak/react hazard 또는 calibrated logit 에만** 적용한다(메시지 수
곱 아님 — P08-T017 경계와 일관). multiplier 는 hazard 의 logit(log-odds)에 `ln(multiplier)` 를 가산해
오즈 배율로 작용한다. 이 ml 구현은 central Kotlin [TalkativenessHazardScaling] 과 **같은 수식**을 쓴다
(학습·평가·runtime 정합 — time-origin.md SSOT 정신).

**acceptance(T009) — 0.5/1.0/1.5/2.0 에서 순서 보존과 cap 이 테스트된다**:
- [scale_hazard]: 같은 base hazard 에 대해 multiplier 가 클수록 보정 hazard 가 크다(순서 보존, 단조).
- cap: hazard 는 [0, HAZARD_CAP] 로 클램프해 1 을 넘거나 폭주하지 않는다(과도 끼어들기 방지 — T010 분석 대비).
- 적용 대상은 TIMED_ACTIONS(react/speak)만 — 다른 hazard 는 건드리지 않는다.

torch 비의존 — numpy. central 무변경(이 파일은 ml 신규).
"""

from __future__ import annotations

from typing import TYPE_CHECKING

from nexa_policy.models.neural_survival import TIMED_ACTIONS

if TYPE_CHECKING:
    import numpy as np

# hazard 상한(폭주·과도 끼어들기 방지). 1.0 미만으로 둬 확정 사건이 되지 않게 한다.
HAZARD_CAP = 0.999
# multiplier 허용 범위(central TalkativenessMultiplier [0, 2] 와 동일).
MULTIPLIER_MIN = 0.0
MULTIPLIER_MAX = 2.0
# multiplier 0 의 logit 가산 하한(강한 침묵 편향, -∞ 방지) — central MIN_LOGIT_ADJUSTMENT 와 동일.
MIN_LOGIT_ADJUSTMENT = -10.0


def _logit_adjustment(multiplier: float) -> float:
    """multiplier → logit 가산 보정량 `ln(multiplier)`(0 이면 강한 음수 클램프). central 과 동일 수식."""
    import numpy as np

    if multiplier < MULTIPLIER_MIN or multiplier > MULTIPLIER_MAX:
        raise ValueError(f"multiplier 는 [{MULTIPLIER_MIN}, {MULTIPLIER_MAX}] 범위여야 한다: {multiplier}")
    if multiplier <= 0.0:
        return MIN_LOGIT_ADJUSTMENT
    return float(max(np.log(multiplier), MIN_LOGIT_ADJUSTMENT))


def scale_hazard(hazard: np.ndarray, multiplier: float) -> np.ndarray:
    """hazard 의 logit 에 `ln(multiplier)` 를 가산해 보정한 뒤 [0, HAZARD_CAP] 로 클램프한다.

    multiplier 1.0 = 보정 없음(원 hazard, cap 만 적용). >1 = hazard 증가(더 말 많음), <1 = 감소.
    순서 보존: multiplier 가 클수록 보정 hazard 가 (cap 전까지) 단조 증가한다(acceptance T009).
    """
    import numpy as np

    adj = _logit_adjustment(multiplier)
    eps = 1e-12
    h = np.clip(hazard, eps, 1.0 - eps)
    logit = np.log(h / (1.0 - h)) + adj
    scaled = 1.0 / (1.0 + np.exp(-logit))
    capped: np.ndarray = np.clip(scaled, 0.0, HAZARD_CAP)
    return capped


def scale_action_hazards(
    time_hazards: dict[str, np.ndarray], multiplier: float
) -> dict[str, np.ndarray]:
    """TIMED_ACTIONS(react/speak)의 hazard 에만 multiplier 를 적용한다. 그 외 키는 그대로 통과."""
    out: dict[str, np.ndarray] = {}
    for action, hazard in time_hazards.items():
        out[action] = scale_hazard(hazard, multiplier) if action in TIMED_ACTIONS else hazard
    return out
