"""delay label 생성기(NEXA-P10-T007).

기준 시점(cut)부터 대상 인간의 실제 행동까지 delay 와 right-censoring 을 계산한다.

**acceptance(T007) — 세션 종료와 진짜 never 를 동일시하지 않는다**:
- 행동이 있으면 delay_ms 와 censored=False.
- 행동이 없는데 관찰 창이 **세션 종료**로 잘렸으면 censored=True(우중도절단 — "최소 이만큼은 안 했다",
  진짜 never 아님).
- 관찰 창이 세션 종료가 아니라 충분히 길어 끝까지 봤는데도 행동이 없으면 censored=False 의 never 후보.
  단 이 빌더는 보수적으로, 명시적 `observed_full_window=True` 일 때만 진짜 never 로 본다.
"""

from __future__ import annotations

from dataclasses import dataclass

from nexa_policy.data.schema import EventRecord


@dataclass(frozen=True)
class DelayLabel:
    """delay 라벨.

    - [delay_ms]: 행동까지 지연(행동 없으면 None).
    - [censored]: right-censored 여부(세션 종료로 관찰이 잘림 → 진짜 never 아님).
    - [is_never]: 충분히 관찰했는데도 행동 없음(진짜 never). censored 와 상호배타.
    """

    delay_ms: int | None
    censored: bool
    is_never: bool

    def __post_init__(self) -> None:
        if self.delay_ms is not None and (self.censored or self.is_never):
            raise ValueError("delay 가 있으면 censored/never 일 수 없다.")
        if self.censored and self.is_never:
            raise ValueError("censored 와 is_never 는 동시에 참일 수 없다.")

    def to_dict(self) -> dict[str, object]:
        return {"delay_ms": self.delay_ms, "censored": self.censored, "is_never": self.is_never}


def label_delay(
    *,
    masked_actor: str,
    cut_time_ms: int,
    events: list[EventRecord],
    session_end_ms: int,
    observed_full_window: bool,
) -> DelayLabel:
    """cut 부터 masked_actor 의 첫 행동까지 delay 를 계산한다.

    - 첫 행동이 있으면 delay_ms.
    - 없고 session_end 가 관찰 끝이면 censored(세션이 끝나서 더 못 봄 — never 아님).
    - 없고 observed_full_window 면 진짜 never.
    """
    actor_actions = sorted(

            e.event_time_ms
            for e in events
            if e.actor_pseudonym == masked_actor and e.event_time_ms > cut_time_ms

    )
    if actor_actions:
        first = actor_actions[0]
        if first <= session_end_ms:
            return DelayLabel(delay_ms=first - cut_time_ms, censored=False, is_never=False)
    # 행동 없음(또는 세션 밖) → 세션 종료로 잘린 우중도절단 vs 진짜 never.
    if observed_full_window:
        return DelayLabel(delay_ms=None, censored=False, is_never=True)
    return DelayLabel(delay_ms=None, censored=True, is_never=False)
