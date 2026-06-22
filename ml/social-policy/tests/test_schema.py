"""T003 스키마 conformance·원문 미포함·version 고정 테스트."""

from __future__ import annotations

import pytest

from nexa_policy.data.schema import (
    SCHEMA_VERSION,
    EventRecord,
    SchemaError,
    conform,
    load_schema,
    parquet_available,
    write_parquet,
)
from tests.conftest import make_event


def test_schema_declares_no_raw_content_and_fixed_version() -> None:
    schema = load_schema()
    assert schema["contains_raw_content"] is False
    assert schema["schema_version"] == SCHEMA_VERSION


def test_conform_accepts_valid_row() -> None:
    row = make_event(event_id="e1", time_ms=1, actor="actor-1").to_row()
    assert conform(row)["event_id"] == "e1"


def test_conform_rejects_unknown_column_blocks_raw_content_leak() -> None:
    row = make_event(event_id="e1", time_ms=1, actor="actor-1").to_row()
    row["message_content"] = "비밀 원문"
    with pytest.raises(SchemaError):
        conform(row)


def test_conform_rejects_forbidden_id_column() -> None:
    row = make_event(event_id="e1", time_ms=1, actor="actor-1").to_row()
    row["user_id"] = "123456789"
    with pytest.raises(SchemaError, match="금지 컬럼"):
        conform(row)


def test_conform_rejects_forbidden_nested_feature_key() -> None:
    row = make_event(
        event_id="e1", time_ms=1, actor="actor-1", features={"raw_text": "hi"}
    ).to_row()
    with pytest.raises(SchemaError, match="금지 컬럼"):
        conform(row)


def test_conform_rejects_version_mismatch() -> None:
    row = make_event(event_id="e1", time_ms=1, actor="actor-1").to_row()
    row["schema_version"] = 999
    with pytest.raises(SchemaError):
        conform(row)


def test_event_record_has_no_raw_content_columns() -> None:
    row = make_event(event_id="e1", time_ms=1, actor="actor-1").to_row()
    joined = " ".join(row.keys()).lower()
    for forbidden in ("content", "raw", "user_id", "username", "snowflake"):
        assert forbidden not in joined


def test_write_parquet_without_pyarrow_fails_closed() -> None:
    if parquet_available():
        pytest.skip("pyarrow 설치됨 — fail-closed 경로는 미설치 환경에서만 검증")
    records = [make_event(event_id="e1", time_ms=1, actor="actor-1")]
    with pytest.raises(SchemaError, match="pyarrow"):
        write_parquet(records, __import__("pathlib").Path("/tmp/x.parquet"))


def test_write_parquet_roundtrip_if_available(tmp_path: object) -> None:
    if not parquet_available():
        pytest.skip("pyarrow 미설치 — Parquet round-trip 생략(in-memory 경로만 사용)")
    from pathlib import Path

    import pyarrow.parquet as pq

    records: list[EventRecord] = [
        make_event(event_id="e1", time_ms=1, actor="actor-1"),
        make_event(event_id="e2", time_ms=2, actor="actor-2"),
    ]
    out = write_parquet(records, Path(str(tmp_path)) / "events.parquet")
    table = pq.read_table(out)
    assert table.num_rows == 2
    assert "features_json" in table.column_names
