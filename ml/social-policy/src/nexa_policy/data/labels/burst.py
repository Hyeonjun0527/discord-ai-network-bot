"""burst shape label 생성기(NEXA-P10-T008).

응답 메시지 수·길이·간격·reaction 여부를 label 로 만든다.

**acceptance(T008) — 한 인간의 연속 메시지가 P04 버스트 기준으로 묶인다**:
- 같은 작성자의 연속 메시지를 gap 임계값([burst_gap_ms], 기본 7s — EXP-burst-baseline 의 fixed_gap 기준)과
  화자 변경으로 한 burst 로 묶는다(다른 작성자가 끼면 burst 종료).
- 결과는 묶인 burst 의 shape: 메시지 수·총/평균 길이 버킷·메시지 간 간격·reaction 포함 여부.
"""

from __future__ import annotations

from dataclasses import dataclass

from nexa_policy.data.schema import EventRecord

DEFAULT_BURST_GAP_MS = 7_000


@dataclass(frozen=True)
class BurstShapeLabel:
    """한 응답 burst 의 형태."""

    message_count: int
    total_char_len_bucket: int
    gaps_ms: tuple[int, ...]
    has_reaction: bool

    def to_dict(self) -> dict[str, object]:
        return {
            "message_count": self.message_count,
            "total_char_len_bucket": self.total_char_len_bucket,
            "gaps_ms": list(self.gaps_ms),
            "has_reaction": self.has_reaction,
        }


def label_burst_shape(
    *,
    masked_actor: str,
    cut_time_ms: int,
    events: list[EventRecord],
    burst_gap_ms: int = DEFAULT_BURST_GAP_MS,
) -> BurstShapeLabel | None:
    """cut 이후 masked_actor 의 첫 응답 burst 를 P04 기준으로 묶어 shape 를 만든다.

    화자 변경(다른 작성자 메시지)이 끼거나 gap 이 임계값을 넘으면 burst 가 종료된다.
    응답 메시지가 하나도 없으면 None.
    """
    ordered = sorted(
        (e for e in events if e.event_time_ms > cut_time_ms),
        key=lambda e: (e.event_time_ms, e.event_id),
    )

    burst: list[EventRecord] = []
    has_reaction = False
    last_msg_time: int | None = None
    for ev in ordered:
        is_actor = ev.actor_pseudonym == masked_actor
        if not burst:
            if is_actor and ev.event_kind in ("message", "reply", "mention"):
                burst.append(ev)
                last_msg_time = ev.event_time_ms
            elif is_actor and ev.event_kind == "reaction":
                # 발화 없는 단독 리액션도 첫 신호로 본다(shape: 메시지 0, reaction True).
                has_reaction = True
                break
            continue

        # burst 진행 중.
        if not is_actor and ev.event_kind in ("message", "reply", "mention"):
            break  # 화자 변경 → burst 종료.
        if is_actor and ev.event_kind == "reaction":
            has_reaction = True
            continue
        if is_actor and ev.event_kind in ("message", "reply", "mention"):
            assert last_msg_time is not None
            if ev.event_time_ms - last_msg_time > burst_gap_ms:
                break  # gap 초과 → burst 종료.
            burst.append(ev)
            last_msg_time = ev.event_time_ms

    if not burst:
        if has_reaction:
            return BurstShapeLabel(
                message_count=0, total_char_len_bucket=0, gaps_ms=(), has_reaction=True
            )
        return None

    total_len = 0
    for e in burst:
        bucket = (e.features or {}).get("char_len_bucket")
        if isinstance(bucket, int):
            total_len += bucket
    gaps = tuple(
        burst[i].event_time_ms - burst[i - 1].event_time_ms for i in range(1, len(burst))
    )
    return BurstShapeLabel(
        message_count=len(burst),
        total_char_len_bucket=total_len,
        gaps_ms=gaps,
        has_reaction=has_reaction,
    )
