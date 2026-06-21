"""target label 생성기(NEXA-P10-T006).

reply/mention/adjacency 신호와 실제 행동으로 message/member/thread target label 을 만든다.

**acceptance(T006) — 복수 타당 target 과 none 을 표현한다**:
- 결과는 단일 target 이 아니라 [TargetLabel.targets] 리스트다. 신호가 여럿이면 복수 후보를 담고,
  아무 신호도 없으면 빈 리스트(none)로 둔다.
- target 은 가명 식별자만(원본 message/user/thread id 금지).
"""

from __future__ import annotations

from dataclasses import dataclass
from enum import Enum

from nexa_policy.data.schema import EventRecord


class TargetKind(Enum):
    MESSAGE = "message"
    MEMBER = "member"
    THREAD = "thread"


@dataclass(frozen=True)
class TargetCandidate:
    kind: TargetKind
    ref_pseudonym: str  # 가명 식별자(원본 id 금지).
    signal: str  # 무슨 신호에서 왔는지(reply/mention/adjacency).

    def to_dict(self) -> dict[str, str]:
        return {"kind": self.kind.value, "ref": self.ref_pseudonym, "signal": self.signal}


@dataclass(frozen=True)
class TargetLabel:
    """복수 후보 target. 비어 있으면 none(특정 대상 없음)."""

    targets: tuple[TargetCandidate, ...]

    @property
    def is_none(self) -> bool:
        return len(self.targets) == 0

    def to_dict(self) -> dict[str, object]:
        return {"targets": [t.to_dict() for t in self.targets], "is_none": self.is_none}


def label_target(
    *,
    action_event: EventRecord | None,
    prior_events: list[EventRecord],
    adjacency_ms: int = 10_000,
) -> TargetLabel:
    """행동 이벤트의 reply/mention 및 직전 인접 발화로 target 후보를 만든다.

    - reply_to_event_id 가 있으면 그 message 를 target 으로(reply 신호).
    - mention 대상이 있으면 member target(mention 신호) — features.mention_target_pseudonym.
    - 위 신호가 없으면 adjacency: cut 직전 adjacency_ms 안의 다른 작성자 메시지를 member target 으로.
    - 아무것도 없으면 none(빈 리스트).
    """
    if action_event is None:
        return TargetLabel(targets=())

    candidates: list[TargetCandidate] = []
    features = action_event.features or {}

    reply_to = features.get("reply_to_event_id")
    if isinstance(reply_to, str) and reply_to:
        candidates.append(TargetCandidate(TargetKind.MESSAGE, reply_to, "reply"))

    mention_target = features.get("mention_target_pseudonym")
    if isinstance(mention_target, str) and mention_target:
        candidates.append(TargetCandidate(TargetKind.MEMBER, mention_target, "mention"))

    if action_event.thread_pseudonym:
        candidates.append(
            TargetCandidate(TargetKind.THREAD, action_event.thread_pseudonym, "thread")
        )

    if not any(c.signal in ("reply", "mention") for c in candidates):
        lower = action_event.event_time_ms - adjacency_ms
        recent_others = sorted(
            (
                e
                for e in prior_events
                if e.actor_pseudonym != action_event.actor_pseudonym
                and lower <= e.event_time_ms <= action_event.event_time_ms
                and e.event_kind in ("message", "reply")
            ),
            key=lambda e: (e.event_time_ms, e.event_id),
        )
        if recent_others:
            last = recent_others[-1]
            candidates.append(
                TargetCandidate(TargetKind.MEMBER, last.actor_pseudonym, "adjacency")
            )

    return TargetLabel(targets=tuple(candidates))
