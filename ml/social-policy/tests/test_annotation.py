"""T021 라벨 검수 export/import 테스트 — 가명 alias·최소 컨텍스트·schema validation."""

from __future__ import annotations

import pytest

from nexa_policy.annotation.packet import (
    AnnotationError,
    build_context_packet,
    parse_annotation,
)
from nexa_policy.data.masking import LabelTargets, build_masked_example
from tests.conftest import make_event


def _example(prior_count: int = 3) -> object:
    events = [
        make_event(event_id=f"e{i}", time_ms=i * 1000, actor=f"actor-{i % 2}", guild="guild-secret")
        for i in range(1, prior_count + 1)
    ]
    return build_masked_example(
        guild_pseudonym="guild-secret",
        session_id="sess-1",
        masked_actor="actor-1",
        cut_time_ms=prior_count * 1000,
        all_events=events,
        target=LabelTargets(),
    )


def test_packet_uses_local_aliases_not_real_pseudonyms() -> None:
    packet = build_context_packet(_example(), packet_id="p1")
    assert packet.masked_alias == "A1"
    # 실제 guild/actor 가명이 packet 에 노출되지 않는다(전체 서버 식별자 미노출).
    blob = str(packet.to_dict())
    assert "guild-secret" not in blob
    assert "actor-1" not in blob and "actor-0" not in blob
    for ev in packet.events:
        assert ev["actor_alias"].startswith("A")


def test_packet_limits_context_window() -> None:
    packet = build_context_packet(_example(prior_count=20), packet_id="p1", context_events=5)
    # 전체 로그가 아니라 좁은 최근 컨텍스트만(acceptance).
    assert len(packet.events) == 5


def test_packet_rel_time_is_relative() -> None:
    packet = build_context_packet(_example(prior_count=3), packet_id="p1")
    # 마지막 prior 이벤트는 cut 이전(<=0 상대 시각).
    assert all(ev["rel_time_ms"] <= 0 for ev in packet.events)


def test_packet_has_no_raw_text_keys() -> None:
    packet = build_context_packet(_example(), packet_id="p1")
    for ev in packet.events:
        for forbidden in ("content", "text", "raw", "user_id", "username"):
            assert forbidden not in str(ev).lower()


def test_parse_annotation_valid() -> None:
    ann = parse_annotation({
        "packet_id": "p1", "annotator_id": "ann-7", "action": "speak",
        "target_alias": "A2", "delay_bin": "SHORT", "social_act": "agree",
        "ambiguity": False,
    })
    assert ann.action == "speak"
    assert ann.target_alias == "A2"
    assert ann.notes_omitted is True  # 자유 텍스트 메모 미적재.


def test_parse_annotation_none_target() -> None:
    ann = parse_annotation({
        "packet_id": "p1", "annotator_id": "ann-7", "action": "ignore",
        "target_alias": None, "delay_bin": "NEVER", "social_act": "unknown",
        "ambiguity": True,
    })
    assert ann.target_alias is None


def test_parse_annotation_rejects_bad_action() -> None:
    with pytest.raises(AnnotationError, match="action"):
        parse_annotation({
            "packet_id": "p1", "annotator_id": "a", "action": "explode",
            "delay_bin": "SHORT", "social_act": "agree", "ambiguity": False,
        })


def test_parse_annotation_rejects_missing_fields() -> None:
    with pytest.raises(AnnotationError, match="필수 필드"):
        parse_annotation({"packet_id": "p1"})


def test_parse_annotation_rejects_bad_delay_and_act() -> None:
    with pytest.raises(AnnotationError):
        parse_annotation({
            "packet_id": "p1", "annotator_id": "a", "action": "speak",
            "delay_bin": "SOON", "social_act": "agree", "ambiguity": False,
        })
    with pytest.raises(AnnotationError):
        parse_annotation({
            "packet_id": "p1", "annotator_id": "a", "action": "speak",
            "delay_bin": "SHORT", "social_act": "vibe", "ambiguity": False,
        })
