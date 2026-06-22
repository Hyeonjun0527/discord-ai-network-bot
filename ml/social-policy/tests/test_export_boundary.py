"""T002 export 보안 경계 테스트 — eligibility·운영 dump 금지·원문/원본 id fail-closed."""

from __future__ import annotations

import dataclasses

import pytest

from nexa_policy.data.export.boundary import (
    ApprovedProjection,
    ExportBoundaryError,
    build_export_manifest,
)
from nexa_policy.data.schema import SchemaError
from tests.conftest import make_event


def test_only_eligible_records_in_manifest() -> None:
    records = (
        make_event(event_id="e1", time_ms=1, actor="a1", eligible=True, opt_in=True, observable=True),
        make_event(event_id="e2", time_ms=2, actor="a2", eligible=False),
        make_event(event_id="e3", time_ms=3, actor="a3", eligible=True, opt_in=False),
        make_event(event_id="e4", time_ms=4, actor="a4", eligible=True, observable=False),
    )
    proj = ApprovedProjection(source="participation_training_projection_v1", records=records)
    manifest = build_export_manifest(proj)
    assert manifest.included_count == 1
    assert manifest.excluded_count == 3
    assert manifest.eligible[0].event_id == "e1"


def test_rejects_operational_dump_source() -> None:
    with pytest.raises(ExportBoundaryError, match="dump"):
        ApprovedProjection(source="operational_db_dump", records=())


def test_blank_source_rejected() -> None:
    with pytest.raises(ExportBoundaryError):
        ApprovedProjection(source="   ", records=())


def test_export_fails_closed_on_raw_content_in_record() -> None:
    bad = make_event(event_id="e1", time_ms=1, actor="a1")
    # 원문 흔적을 features 에 강제로 끼워넣은 부적합 레코드.
    bad = dataclasses.replace(bad, features={"raw_content": "비밀"})
    proj = ApprovedProjection(source="proj_v1", records=(bad,))
    with pytest.raises(SchemaError, match="금지 컬럼"):
        build_export_manifest(proj)


def test_manifest_carries_no_real_user_ids() -> None:
    records = (make_event(event_id="e1", time_ms=1, actor="actor-pseudo-xyz"),)
    proj = ApprovedProjection(source="proj_v1", records=records)
    manifest = build_export_manifest(proj)
    actor = manifest.eligible[0].actor_pseudonym
    # 가명만 — 순수 숫자 snowflake 가 아님.
    assert not actor.isdigit()
    assert actor == "actor-pseudo-xyz"
