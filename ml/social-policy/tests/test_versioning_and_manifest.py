"""T016 version hash·T017 manifest 테스트 — 재현 ID·자동 집계·진입 가드."""

from __future__ import annotations

import pytest

from nexa_policy.data.manifest import (
    ManifestError,
    build_manifest,
    require_manifest_id,
)
from nexa_policy.data.versioning import (
    DatasetVersionInputs,
    VersioningError,
    compute_dataset_id,
)
from tests.conftest import make_event


def _inputs(**over: object) -> DatasetVersionInputs:
    base: dict[str, object] = {
        "source_watermark": "proj_v1@2026-06-01",
        "schema_version": 1,
        "code_commit": "abc123",
        "consent_snapshot_id": "snap-1",
        "config_digest": "cfg-1",
    }
    base.update(over)
    return DatasetVersionInputs(**base)  # type: ignore[arg-type]


# ---- T016 ----
def test_same_inputs_same_id() -> None:
    assert compute_dataset_id(_inputs()) == compute_dataset_id(_inputs())


def test_changed_input_changes_id() -> None:
    base = compute_dataset_id(_inputs())
    assert compute_dataset_id(_inputs(consent_snapshot_id="snap-2")) != base
    assert compute_dataset_id(_inputs(code_commit="def456")) != base
    assert compute_dataset_id(_inputs(source_watermark="proj_v2@x")) != base


def test_empty_component_rejected() -> None:
    with pytest.raises(VersioningError):
        _inputs(code_commit="  ")


def test_wrong_schema_version_rejected() -> None:
    with pytest.raises(VersioningError):
        _inputs(schema_version=999)


# ---- T017 ----
def test_manifest_auto_aggregates_counts() -> None:
    records = [
        make_event(event_id="e1", time_ms=10, actor="a1", guild="g1"),
        make_event(event_id="e2", time_ms=30, actor="a2", guild="g1"),
        make_event(event_id="e3", time_ms=20, actor="a3", guild="g2", kind="reaction"),
    ]
    manifest = build_manifest(records=records, version_inputs=_inputs())
    assert manifest.row_count == 3
    assert manifest.guild_count == 2
    assert manifest.period_start_ms == 10
    assert manifest.period_end_ms == 30
    assert manifest.class_distribution == {"message": 2, "reaction": 1}


def test_manifest_id_required_to_start_run() -> None:
    manifest = build_manifest(records=[make_event(event_id="e1", time_ms=1, actor="a")],
                              version_inputs=_inputs())
    assert require_manifest_id(manifest) == manifest.dataset_id
    with pytest.raises(ManifestError):
        require_manifest_id(None)


def test_manifest_content_hash_stable() -> None:
    records = [make_event(event_id="e1", time_ms=1, actor="a", guild="g1")]
    a = build_manifest(records=records, version_inputs=_inputs())
    b = build_manifest(records=records, version_inputs=_inputs())
    assert a.content_hash == b.content_hash
    assert a.dataset_id == b.dataset_id


def test_manifest_explicit_class_labels_and_exclusions() -> None:
    records = [make_event(event_id="e1", time_ms=1, actor="a", guild="g1")]
    manifest = build_manifest(
        records=records,
        version_inputs=_inputs(),
        class_labels=["speak", "ignore", "speak"],
        exclusions={"non_consent": 4},
    )
    assert manifest.class_distribution == {"speak": 2, "ignore": 1}
    assert manifest.exclusions == {"non_consent": 4}
