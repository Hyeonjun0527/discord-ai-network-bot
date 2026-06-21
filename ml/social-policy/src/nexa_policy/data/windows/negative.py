"""negative opportunity window 생성기(NEXA-P10-T010).

누군가 말할 기회가 있었지만 대상 인간이 침묵한 구간을 sampling 한다.

**acceptance(T010) — 모든 millisecond 를 negative 로 만들어 class imbalance 를 폭발시키지 않는다**:
- 연속 시간을 무한 분할하지 않는다. **기회 신호가 있는 시점**(대상에게 향한 발화·질문·멘션, 또는 활발한
  대화 tempo)에서만 후보를 만들고, 그중 대상이 침묵한 것을 negative 로 둔다.
- 같은 침묵 구간에서 [max_per_silence] 개로 sampling 을 캡한다(결정론 seed).
- 양성(대상이 실제 행동)과 겹치는 시점은 제외한다.
"""

from __future__ import annotations

import random
from dataclasses import dataclass

from nexa_policy.data.schema import EventRecord


@dataclass(frozen=True)
class NegativeWindow:
    """대상이 말할 기회가 있었지만 침묵한 구간 샘플."""

    cut_time_ms: int
    opportunity_signal: str  # 왜 기회였는지(direct/mention/question/tempo).
    masked_actor: str

    def to_dict(self) -> dict[str, object]:
        return {
            "cut_time_ms": self.cut_time_ms,
            "opportunity_signal": self.opportunity_signal,
            "masked_actor": self.masked_actor,
        }


def _opportunity_signal(event: EventRecord, masked_actor: str) -> str | None:
    """이 이벤트가 masked_actor 에게 발화 기회를 준 신호인지(아니면 None)."""
    if event.actor_pseudonym == masked_actor:
        return None  # 본인 발화는 기회 신호 아님.
    features = event.features or {}
    if features.get("mention_target_pseudonym") == masked_actor:
        return "mention"
    if event.event_kind in ("message", "reply") and features.get("is_question") is True:
        return "question"
    if event.event_kind in ("message", "reply"):
        return "tempo"
    return None


def sample_negative_windows(
    *,
    masked_actor: str,
    events: list[EventRecord],
    response_window_ms: int,
    max_per_silence: int = 1,
    min_gap_ms: int = 5_000,
    seed: int = 0,
) -> list[NegativeWindow]:
    """기회 신호 시점들 중 대상이 침묵한 것을 negative 로 sampling 한다.

    - 각 기회 시점 이후 response_window_ms 안에 대상 행동이 있으면 그 시점은 negative 아님(양성/제외).
    - 인접한 기회들은 min_gap_ms 로 thin-out(같은 침묵에서 폭발 방지), 그 뒤 max_per_silence 캡.
    - 결정론: 같은 입력·seed 면 같은 출력.
    """
    ordered = sorted(events, key=lambda e: (e.event_time_ms, e.event_id))
    actor_action_times = sorted(
        e.event_time_ms for e in ordered if e.actor_pseudonym == masked_actor
    )

    def acted_within(t0: int) -> bool:
        deadline = t0 + response_window_ms
        return any(t0 < at <= deadline for at in actor_action_times)

    candidates: list[NegativeWindow] = []
    last_kept: int | None = None
    for ev in ordered:
        signal = _opportunity_signal(ev, masked_actor)
        if signal is None:
            continue
        if acted_within(ev.event_time_ms):
            continue  # 침묵 아님 → negative 아님.
        if last_kept is not None and ev.event_time_ms - last_kept < min_gap_ms:
            continue  # 같은 침묵 구간 폭발 방지.
        candidates.append(
            NegativeWindow(
                cut_time_ms=ev.event_time_ms,
                opportunity_signal=signal,
                masked_actor=masked_actor,
            )
        )
        last_kept = ev.event_time_ms

    if max_per_silence >= len(candidates) or max_per_silence <= 0:
        return candidates
    rng = random.Random(seed)
    chosen = sorted(
        rng.sample(range(len(candidates)), max_per_silence)
    )
    return [candidates[i] for i in chosen]
