"""Masked Member 학습 예제 단위(NEXA-P10-T004 의 코드 표현).

특정 인간(actor)의 **다음 행동을 가리고**, 그 시점 이전 이벤트만 입력으로 제공하는 학습 예제를 정의한다.

**acceptance(T004) — 정답이 문장 하나가 아니라 action/target/time/burst 분포다**:
- [MaskedMemberExample.target] 는 단일 텍스트가 아니라 [LabelTargets] — action/target/delay/burst/social_act
  라벨 묶음이다(각 생성기가 채운다).
- 입력은 cut 시점 **이전** 이벤트만(prior_events). 미래 leakage 금지.
- 원문/실제 user id 미포함(가명·신호만, EventRecord 그대로).

이 단위는 라벨 생성기(labels/*)와 split(split.py)이 공유하는 샘플 경계다.
"""

from __future__ import annotations

from dataclasses import dataclass
from typing import Any

from nexa_policy.data.schema import EventRecord


@dataclass(frozen=True)
class LabelTargets:
    """한 마스킹 예제의 정답 묶음(분포). 각 필드는 해당 라벨 생성기가 채운다(없으면 None/UNKNOWN)."""

    action: dict[str, Any] | None = None
    target: dict[str, Any] | None = None
    delay: dict[str, Any] | None = None
    burst: dict[str, Any] | None = None
    social_act: dict[str, Any] | None = None


@dataclass(frozen=True)
class MaskedMemberExample:
    """마스킹된 멤버 학습 예제.

    - [masked_actor]: 다음 행동을 가린 대상 인간(가명).
    - [cut_time_ms]: 기준 시점. 이 시각까지를 입력으로 보고, 이후 행동이 라벨이 된다.
    - [prior_events]: cut 이전 이벤트만(입력 컨텍스트). 미래 leakage 금지(불변식).
    - [target]: 정답 분포(action/target/delay/burst/social_act).
    """

    guild_pseudonym: str
    session_id: str
    masked_actor: str
    cut_time_ms: int
    prior_events: tuple[EventRecord, ...]
    target: LabelTargets

    def __post_init__(self) -> None:
        for ev in self.prior_events:
            if ev.event_time_ms > self.cut_time_ms:
                raise ValueError(
                    "prior_events 는 cut_time_ms 이후 이벤트를 포함할 수 없다(미래 leakage 금지)."
                )


def build_masked_example(
    *,
    guild_pseudonym: str,
    session_id: str,
    masked_actor: str,
    cut_time_ms: int,
    all_events: list[EventRecord],
    target: LabelTargets,
) -> MaskedMemberExample:
    """cut_time_ms 이전 이벤트만 골라 마스킹 예제를 만든다.

    입력 컨텍스트는 결정론적으로 (event_time_ms, event_id) 순 정렬된다.
    """
    prior = sorted(
        (e for e in all_events if e.event_time_ms <= cut_time_ms),
        key=lambda e: (e.event_time_ms, e.event_id),
    )
    return MaskedMemberExample(
        guild_pseudonym=guild_pseudonym,
        session_id=session_id,
        masked_actor=masked_actor,
        cut_time_ms=cut_time_ms,
        prior_events=tuple(prior),
        target=target,
    )
