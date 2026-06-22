"""NEXA-P17-T011: 학습 artifact 삭제 tombstone — 영향 식별·재학습 기준·모델 가중치 한계."""

from __future__ import annotations

import pytest

from nexa_policy.data.deletion import (
    DatasetIndex,
    DeletionError,
    DeletionTrigger,
    TombstoneStatus,
    build_tombstone,
    decide_resolution,
    identify_affected,
    resolve_tombstone,
)


def _index() -> DatasetIndex:
    return DatasetIndex(
        source_to_datasets={
            "src-1": frozenset({"ds-A"}),
            "src-2": frozenset({"ds-A", "ds-B"}),
        },
        dataset_to_models={
            "ds-A": frozenset({"model-1"}),
            "ds-B": frozenset({"model-2"}),
        },
        dataset_row_counts={"ds-A": 100, "ds-B": 100},
    )


def test_identify_affected_dataset_and_models() -> None:
    datasets, models = identify_affected(deleted_source_ids=["src-2"], index=_index())
    assert datasets == frozenset({"ds-A", "ds-B"})
    assert models == frozenset({"model-1", "model-2"})


def test_build_tombstone_starts_pending() -> None:
    ts = build_tombstone(
        deletion_request_id="req-1",
        deleted_source_ids=["src-1"],
        index=_index(),
        requested_at_ms=10,
    )
    assert ts.status is TombstoneStatus.PENDING_RETRAIN
    assert ts.affected_dataset_ids == frozenset({"ds-A"})
    assert ts.affected_model_ids == frozenset({"model-1"})
    assert ts.resolved_at_ms is None


def test_empty_sources_rejected() -> None:
    with pytest.raises(DeletionError):
        build_tombstone(
            deletion_request_id="req-1",
            deleted_source_ids=[],
            index=_index(),
            requested_at_ms=1,
        )


def test_model_weights_cannot_resolve_individual_sample() -> None:
    # acceptance: 모델 가중치에서 개별 샘플 제거 불가는 구조적으로 항상 False.
    ts = build_tombstone(
        deletion_request_id="req-1", deleted_source_ids=["src-1"], index=_index(), requested_at_ms=1
    )
    res = decide_resolution(
        tombstone=ts, trigger=DeletionTrigger.MESSAGE_DELETE, index=_index(), retrained=False
    )
    assert res.can_resolve_in_model_weights is False


def test_consent_revoke_forces_retire_before_retrain() -> None:
    ts = build_tombstone(
        deletion_request_id="req-1", deleted_source_ids=["src-1"], index=_index(), requested_at_ms=1
    )
    out = resolve_tombstone(
        tombstone=ts,
        trigger=DeletionTrigger.CONSENT_REVOKE,
        index=_index(),
        retrained=False,
        resolved_at_ms=20,
    )
    assert out.status is TombstoneStatus.RETIRED
    assert out.resolved_at_ms == 20


def test_message_delete_small_ratio_stays_pending() -> None:
    ts = build_tombstone(
        deletion_request_id="req-1", deleted_source_ids=["src-1"], index=_index(), requested_at_ms=1
    )
    # 1 삭제 / 100 행 = 1% — 임계(1%) 미만이 아니라 동일하므로 retire 가 되지 않도록 임계를 올려 검증.
    out = resolve_tombstone(
        tombstone=ts,
        trigger=DeletionTrigger.MESSAGE_DELETE,
        index=_index(),
        retrained=False,
        resolved_at_ms=20,
        retrain_threshold=0.5,
    )
    assert out.status is TombstoneStatus.PENDING_RETRAIN
    assert out.resolved_at_ms is None


def test_retrained_resolves_to_retrained() -> None:
    ts = build_tombstone(
        deletion_request_id="req-1", deleted_source_ids=["src-1"], index=_index(), requested_at_ms=1
    )
    out = resolve_tombstone(
        tombstone=ts,
        trigger=DeletionTrigger.MESSAGE_DELETE,
        index=_index(),
        retrained=True,
        resolved_at_ms=30,
    )
    assert out.status is TombstoneStatus.RETRAINED
    assert out.resolved_at_ms == 30


def test_tombstone_holds_no_raw_content() -> None:
    # 가명 source id·시각만 — 원문/snowflake 없음.
    ts = build_tombstone(
        deletion_request_id="req-1", deleted_source_ids=["src-1"], index=_index(), requested_at_ms=1
    )
    assert all(not s.isdigit() or len(s) < 17 for s in ts.deleted_source_ids)
