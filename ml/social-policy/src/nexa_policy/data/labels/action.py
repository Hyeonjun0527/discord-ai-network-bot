"""행동(action) label 생성기(NEXA-P10-T005).

관찰 창(observation window)에서 대상 인간이 실제로 한 행동을 IGNORE/WAIT/REACT/SPEAK 후보로 만든다.
코드는 central SocialActionKind 와 동일한 안정 wire 값(ignore/wait/react/speak)을 쓴다.

**acceptance(T005) — 관찰 불확실 샘플을 강제로 한 클래스에 넣지 않고 UNKNOWN mask 를 둔다**:
- 관찰 불가(`is_observable` false)면 [ActionClass.UNKNOWN] 으로 두고 `is_masked=True` 를 표시한다.
- 강제로 IGNORE 로 채우지 않는다(침묵≠관찰됨).
"""

from __future__ import annotations

from dataclasses import dataclass
from enum import Enum

from nexa_policy.data.schema import EventRecord


class ActionClass(Enum):
    """행동 라벨 클래스. wireName 은 central SocialActionKind 와 일치(드리프트 금지)."""

    IGNORE = "ignore"
    WAIT = "wait"
    REACT = "react"
    SPEAK = "speak"
    UNKNOWN = "unknown"


@dataclass(frozen=True)
class ActionLabel:
    """행동 라벨. UNKNOWN 이면 is_masked=True(학습 손실에서 제외 신호)."""

    action: ActionClass
    is_masked: bool

    def to_dict(self) -> dict[str, object]:
        return {"action": self.action.value, "is_masked": self.is_masked}


def label_action(
    *,
    masked_actor: str,
    cut_time_ms: int,
    window_ms: int,
    events: list[EventRecord],
    is_observable: bool,
) -> ActionLabel:
    """cut 이후 window 안에서 masked_actor 의 첫 행동으로 action 라벨을 만든다.

    - 관찰 불가면 UNKNOWN(masked) — 강제 IGNORE 금지(acceptance).
    - SPEAK: message/reply 발화. REACT: reaction. 발화도 리액션도 없으면 IGNORE.
    - WAIT 은 "지금은 안 했지만 같은 window 후반에 행동" 같은 지연 신호용이지만, 관찰만으로 WAIT 을 단정하기
      어려우므로 기본은 IGNORE/REACT/SPEAK 만 양성으로 보고, 지연 자체는 delay 라벨(T007)이 담당한다.
    """
    if not is_observable:
        return ActionLabel(action=ActionClass.UNKNOWN, is_masked=True)

    deadline = cut_time_ms + window_ms
    actor_events = sorted(
        (
            e
            for e in events
            if e.actor_pseudonym == masked_actor
            and cut_time_ms < e.event_time_ms <= deadline
        ),
        key=lambda e: (e.event_time_ms, e.event_id),
    )
    for ev in actor_events:
        if ev.event_kind in ("message", "reply", "mention"):
            return ActionLabel(action=ActionClass.SPEAK, is_masked=False)
        if ev.event_kind == "reaction":
            return ActionLabel(action=ActionClass.REACT, is_masked=False)
    return ActionLabel(action=ActionClass.IGNORE, is_masked=False)
