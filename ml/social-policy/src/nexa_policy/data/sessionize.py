"""대화 세션 경계 생성기(NEXA-P10-T011).

긴 inactivity, channel/thread 이동, consent change 로 session 을 나눈다.

**acceptance(T011) — split 이후 미래 세션 정보가 이전 샘플 feature 에 들어가지 않는다**:
- 세션은 시간 순으로만 자르고, 각 세션은 자기 이벤트만 본다([Session.events]).
- 세션 경계 결정은 **직전까지의 신호**(이전 이벤트와의 gap, channel/thread, consent)만 쓴다 — 미래 이벤트를
  참조하지 않는다(왼쪽에서 오른쪽 단일 패스).
"""

from __future__ import annotations

from dataclasses import dataclass

from nexa_policy.data.schema import EventRecord

DEFAULT_INACTIVITY_MS = 30 * 60 * 1_000  # 30분 침묵이면 새 세션.


@dataclass(frozen=True)
class Session:
    """한 대화 세션. 같은 channel/thread·consent 상태에서 연속된 이벤트."""

    session_id: str
    guild_pseudonym: str
    channel_pseudonym: str
    thread_pseudonym: str | None
    events: tuple[EventRecord, ...]

    @property
    def start_ms(self) -> int:
        return self.events[0].event_time_ms

    @property
    def end_ms(self) -> int:
        return self.events[-1].event_time_ms


def _consent_state(event: EventRecord) -> bool:
    return bool((event.masks or {}).get("consent_opt_in"))


def sessionize(
    events: list[EventRecord],
    *,
    inactivity_ms: int = DEFAULT_INACTIVITY_MS,
) -> list[Session]:
    """이벤트를 시간 순 단일 패스로 세션 경계를 나눈다(미래 미참조).

    새 세션 시작 조건(직전 이벤트 대비):
    - inactivity_ms 초과 gap, 또는
    - channel/thread 이동, 또는
    - consent_opt_in 상태 변경.
    """
    if not events:
        return []
    ordered = sorted(events, key=lambda e: (e.event_time_ms, e.event_id))

    sessions: list[Session] = []
    current: list[EventRecord] = [ordered[0]]
    seq = 0

    def flush(buf: list[EventRecord]) -> None:
        nonlocal seq
        head = buf[0]
        sessions.append(
            Session(
                session_id=f"{head.guild_pseudonym}:sess{seq}",
                guild_pseudonym=head.guild_pseudonym,
                channel_pseudonym=head.channel_pseudonym,
                thread_pseudonym=head.thread_pseudonym,
                events=tuple(buf),
            )
        )
        seq += 1

    for prev, cur in zip(ordered, ordered[1:], strict=False):
        boundary = (
            cur.event_time_ms - prev.event_time_ms > inactivity_ms
            or cur.channel_pseudonym != prev.channel_pseudonym
            or cur.thread_pseudonym != prev.thread_pseudonym
            or _consent_state(cur) != _consent_state(prev)
        )
        if boundary:
            flush(current)
            current = [cur]
        else:
            current.append(cur)
    flush(current)
    return sessions
