"""라벨 검수 export/import 도구(NEXA-P10-T021).

가명화된 context packet 을 export 하고, annotation 을 schema validation 후 import 한다.

**acceptance(T021) — annotator 가 불필요한 사용자 식별자나 전체 서버 로그를 보지 않는다**:
- [build_context_packet] 는 한 마스킹 예제의 **cut 직전 좁은 컨텍스트**(최근 N 이벤트)만 담고,
  실제 user id/원문은 없다(가명·신호만). 전체 서버 로그를 노출하지 않는다.
- packet 의 actor 가명은 packet-local alias(A1/A2…)로 다시 가린다 — guild-scope 가명조차 annotator 에게
  넘기지 않아 cross-packet 연결을 막는다.
- [parse_annotation] 은 import 시 허용 라벨/필수 필드를 schema validation 한다(fail-closed).
"""

from __future__ import annotations

from dataclasses import dataclass, field
from typing import Any

from nexa_policy.data.labels.action import ActionClass
from nexa_policy.data.labels.social_act import SocialActCode
from nexa_policy.data.masking import MaskedMemberExample
from nexa_policy.data.schema import EventRecord

# annotator 에게 보여줄 좁은 신호 키(원문/식별자 금지). schema feature allow-list 의 부분집합.
_PACKET_FEATURE_KEYS = ("char_len_bucket", "is_question", "mentions_nexa", "reaction_code")
_DEFAULT_CONTEXT_EVENTS = 8

_VALID_ACTIONS = frozenset(c.value for c in ActionClass)
_VALID_SOCIAL_ACTS = frozenset(c.value for c in SocialActCode)
_DELAY_BINS = frozenset({"IMMEDIATE", "SHORT", "MEDIUM", "LONG", "NEVER"})


class AnnotationError(ValueError):
    """annotation packet/import 불변식 위반(fail-closed)."""


@dataclass(frozen=True)
class ContextPacket:
    """annotator 에게 export 되는 가명화·최소화된 컨텍스트.

    - [packet_id]: gold/IAA 매칭용 안정 id(원본 키 아님).
    - [masked_alias]: 평가 대상 actor 의 packet-local alias.
    - [events]: cut 직전 좁은 컨텍스트 이벤트의 신호 dict 목록(원문/실제 id 없음).
    - 실제 guild/user 가명은 담지 않는다.
    """

    packet_id: str
    masked_alias: str
    events: tuple[dict[str, Any], ...]

    def to_dict(self) -> dict[str, Any]:
        return {
            "packet_id": self.packet_id,
            "masked_alias": self.masked_alias,
            "events": [dict(e) for e in self.events],
        }


def _minimal_event_view(
    event: EventRecord, alias_of: dict[str, str]
) -> dict[str, Any]:
    """이벤트를 annotator 용 최소 신호 dict 로 변환한다(원문/실제 id 제거, actor→alias)."""
    features = event.features or {}
    signals = {k: features[k] for k in _PACKET_FEATURE_KEYS if k in features}
    return {
        "actor_alias": alias_of[event.actor_pseudonym],
        "event_kind": event.event_kind,
        "rel_time_ms": event.event_time_ms,
        "signals": signals,
    }


def build_context_packet(
    example: MaskedMemberExample,
    *,
    packet_id: str,
    context_events: int = _DEFAULT_CONTEXT_EVENTS,
) -> ContextPacket:
    """마스킹 예제에서 좁은(최근 context_events 개) 가명화 context packet 을 만든다.

    actor 가명은 packet-local alias(A1, A2…)로 다시 가려 guild-scope 가명도 노출하지 않는다.
    rel_time 은 cut 기준 상대 시각으로 정규화한다(절대 timestamp 노출 최소화).
    """
    if not packet_id.strip():
        raise AnnotationError("packet_id 가 필요하다.")
    recent = list(example.prior_events)[-context_events:]

    # alias 배정: masked actor 가 항상 A1, 나머지는 등장 순.
    alias_of: dict[str, str] = {example.masked_actor: "A1"}
    next_idx = 2
    for ev in recent:
        if ev.actor_pseudonym not in alias_of:
            alias_of[ev.actor_pseudonym] = f"A{next_idx}"
            next_idx += 1

    views: list[dict[str, Any]] = []
    for ev in recent:
        view = _minimal_event_view(ev, alias_of)
        view["rel_time_ms"] = ev.event_time_ms - example.cut_time_ms
        views.append(view)

    return ContextPacket(
        packet_id=packet_id,
        masked_alias="A1",
        events=tuple(views),
    )


@dataclass(frozen=True)
class Annotation:
    """import 된 annotation(한 annotator 의 한 packet 판단). schema validation 통과본."""

    packet_id: str
    annotator_id: str
    action: str
    target_alias: str | None
    delay_bin: str
    social_act: str
    ambiguity: bool
    notes_omitted: bool = field(default=True)  # 원문 메모는 import 하지 않는다(최소화).


def parse_annotation(payload: dict[str, Any]) -> Annotation:
    """annotation import: 필수 필드·허용 라벨을 schema validation 한다(fail-closed).

    - action ∈ ActionClass, social_act ∈ SocialActCode, delay_bin ∈ delay bins.
    - target_alias 는 packet-local alias(예: A2) 또는 None(none target).
    - 자유 텍스트 메모는 받지 않는다(원문 유입 차단).
    """
    required = ("packet_id", "annotator_id", "action", "delay_bin", "social_act", "ambiguity")
    missing = [k for k in required if k not in payload]
    if missing:
        raise AnnotationError(f"annotation 필수 필드 누락: {missing}")

    action = str(payload["action"])
    if action not in _VALID_ACTIONS:
        raise AnnotationError(f"허용되지 않은 action 라벨: {action!r}")
    social_act = str(payload["social_act"])
    if social_act not in _VALID_SOCIAL_ACTS:
        raise AnnotationError(f"허용되지 않은 social_act 라벨: {social_act!r}")
    delay_bin = str(payload["delay_bin"])
    if delay_bin not in _DELAY_BINS:
        raise AnnotationError(f"허용되지 않은 delay_bin: {delay_bin!r}")

    target = payload.get("target_alias")
    if target is not None and not str(target).strip():
        raise AnnotationError("target_alias 는 None 이거나 비어있지 않은 alias 여야 한다.")

    return Annotation(
        packet_id=str(payload["packet_id"]),
        annotator_id=str(payload["annotator_id"]),
        action=action,
        target_alias=str(target) if target is not None else None,
        delay_bin=delay_bin,
        social_act=social_act,
        ambiguity=bool(payload["ambiguity"]),
    )
