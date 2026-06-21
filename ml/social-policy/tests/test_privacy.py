"""T014 재가명화·T015 redaction 테스트 — 운영↔학습 가명 비연결·원문 미저장."""

from __future__ import annotations

import pytest

from nexa_policy.data.privacy import (
    PrivacyError,
    PseudonymPolicy,
    privatize_record,
    redact_record,
    repseudonymize_record,
)
from nexa_policy.data.schema import EventRecord, SchemaError, conform, load_schema
from tests.conftest import make_event


# ---- T014 ----
def test_repseudonym_is_deterministic_with_same_salt() -> None:
    policy = PseudonymPolicy(purpose_salt="salt-A")
    ev = make_event(event_id="e1", time_ms=1, actor="actor-1", guild="guild-1")
    a = repseudonymize_record(ev, policy)
    b = repseudonymize_record(ev, policy)
    assert a.actor_pseudonym == b.actor_pseudonym
    assert a.guild_pseudonym == b.guild_pseudonym


def test_different_salt_breaks_link_to_operational_pseudonym() -> None:
    ev = make_event(event_id="e1", time_ms=1, actor="actor-1", guild="guild-1")
    op_like = PseudonymPolicy(purpose_salt="operational-salt")
    training = PseudonymPolicy(purpose_salt="training-export-salt")
    # 운영 salt 와 학습 salt 가 다르면 같은 actor 도 다른 가명 → 직접 연결 불가(T014 acceptance).
    assert repseudonymize_record(ev, op_like).actor_pseudonym != repseudonymize_record(
        ev, training
    ).actor_pseudonym


def test_repseudonym_does_not_echo_original_pseudonym() -> None:
    policy = PseudonymPolicy(purpose_salt="salt-A")
    ev = make_event(event_id="e1", time_ms=1, actor="actor-secret", guild="guild-secret")
    out = repseudonymize_record(ev, policy)
    assert "actor-secret" not in out.actor_pseudonym
    assert "guild-secret" not in out.guild_pseudonym


def test_cross_guild_unlinkable() -> None:
    policy = PseudonymPolicy(purpose_salt="salt-A")
    e1 = make_event(event_id="e1", time_ms=1, actor="actor-1", guild="guild-1")
    e2 = make_event(event_id="e1", time_ms=1, actor="actor-1", guild="guild-2")
    # 같은 운영 actor 가명이라도 길드가 다르면 학습 가명이 다르다(cross-guild 연결 차단).
    assert (
        repseudonymize_record(e1, policy).actor_pseudonym
        != repseudonymize_record(e2, policy).actor_pseudonym
    )


def test_same_actor_same_guild_links_within_guild() -> None:
    policy = PseudonymPolicy(purpose_salt="salt-A")
    e1 = make_event(event_id="e1", time_ms=1, actor="actor-1", guild="guild-1")
    e2 = make_event(event_id="e2", time_ms=2, actor="actor-1", guild="guild-1")
    # 같은 길드·같은 actor 는 같은 새 가명(길드 내 관계 보존).
    assert (
        repseudonymize_record(e1, policy).actor_pseudonym
        == repseudonymize_record(e2, policy).actor_pseudonym
    )


def test_empty_salt_rejected() -> None:
    with pytest.raises(PrivacyError):
        PseudonymPolicy(purpose_salt="  ")


# ---- T015 ----
def test_redaction_strips_unknown_feature_keys() -> None:
    ev = EventRecord(
        guild_pseudonym="g", channel_pseudonym="c", thread_pseudonym=None,
        event_id="e1", event_time_ms=1, burst_id="b", scene_id="s",
        actor_pseudonym="a", event_kind="message",
        features={"char_len_bucket": 2, "message_text": "원문!!"},  # 원문 흔적.
        masks={"is_observable": True, "consent_opt_in": True},
        training_eligible=True,
    )
    redacted = redact_record(ev)
    assert "message_text" not in redacted.features
    assert redacted.features["char_len_bucket"] == 2


def test_redaction_rejects_snowflake_in_value() -> None:
    ev = make_event(
        event_id="e1", time_ms=1, actor="actor-1",
        features={"reaction_code": "123456789012345678"},  # snowflake 형태.
    )
    with pytest.raises(PrivacyError, match="snowflake"):
        redact_record(ev)


def test_redaction_rejects_url_in_value() -> None:
    ev = make_event(
        event_id="e1", time_ms=1, actor="actor-1",
        features={"reaction_code": "https://cdn.example/file.png"},
    )
    with pytest.raises(PrivacyError, match="URL"):
        redact_record(ev)


def test_privatized_row_conforms_no_raw_columns() -> None:
    policy = PseudonymPolicy(purpose_salt="salt-A")
    ev = make_event(
        event_id="e1", time_ms=1, actor="actor-1",
        features={"is_question": True, "char_len_bucket": 1},
    )
    out = privatize_record(ev, policy)
    # 결과 row 가 스키마 conformance(원문/식별자 금지)를 통과한다 — Parquet 에 원문 없음.
    conform(out.to_row(), load_schema())


def test_conform_rejects_forbidden_column_after_privatize() -> None:
    # 가드: privatize 결과에 user_id 가 끼면 conform 이 거부(원문/식별자 누출 차단).
    row = make_event(event_id="e1", time_ms=1, actor="a").to_row()
    row["user_id"] = "x"
    with pytest.raises(SchemaError):
        conform(row, load_schema())
