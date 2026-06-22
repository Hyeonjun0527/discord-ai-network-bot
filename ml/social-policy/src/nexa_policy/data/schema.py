"""Parquet 이벤트 시퀀스 schema(NEXA-P10-T003).

`contracts/event_sequence.schema.json` 을 SSOT 로 로드하고, 한 정규화 이벤트를 표현하는
[EventRecord] 와 columnar 변환·conformance 검사를 제공한다.

**acceptance(T003) — 원문 포함 여부 명시·schema version 고정**:
- 스키마 최상위 `contains_raw_content` 가 false 임을 [load_schema] 가 단언한다.
- `schema_version` 이 고정([SCHEMA_VERSION])이며 [conform] 이 row 의 version 불일치를 거부한다.
- conformance 검사는 알 수 없는(추가) 컬럼을 거부한다 — 원문/파생 텍스트 컬럼이 몰래 끼는 것을 막는다.

Parquet 직렬화는 선택(pyarrow). 없으면 in-memory dict 행으로 동작하고 Parquet 쓰기만 거부한다.
"""

from __future__ import annotations

import json
from dataclasses import dataclass, field
from pathlib import Path
from typing import Any

SCHEMA_VERSION = 1

_CONTRACT_PATH = Path(__file__).resolve().parents[3] / "contracts" / "event_sequence.schema.json"

# 절대 컬럼명에 들어와선 안 되는 원문/식별자 흔적(fail-closed 가드).
_FORBIDDEN_COLUMN_SUBSTRINGS = (
    "content",
    "text",
    "raw",
    "body",
    "message_text",
    "username",
    "user_id",
    "userid",
    "snowflake",
    "discord_id",
    "email",
)


class SchemaError(ValueError):
    """스키마 conformance 위반(fail-closed)."""


def load_schema(path: Path | None = None) -> dict[str, Any]:
    """이벤트 시퀀스 JSON 스키마를 로드하고 불변식을 단언한다.

    - `contains_raw_content` 가 명시되고 false 여야 한다(T003 acceptance).
    - `schema_version` 가 [SCHEMA_VERSION] 으로 고정되어야 한다.
    """
    schema_path = path or _CONTRACT_PATH
    schema: dict[str, Any] = json.loads(schema_path.read_text(encoding="utf-8"))
    if "contains_raw_content" not in schema:
        raise SchemaError("스키마에 contains_raw_content 명시가 없다(원문 포함 여부 미선언).")
    if schema["contains_raw_content"] is not False:
        raise SchemaError("스키마는 원문을 포함하지 않아야 한다(contains_raw_content == false).")
    if schema.get("schema_version") != SCHEMA_VERSION:
        raise SchemaError(f"schema_version 고정 위반: {schema.get('schema_version')!r} != {SCHEMA_VERSION}")
    return schema


@dataclass(frozen=True)
class EventRecord:
    """한 정규화 이벤트(columnar row). 원문/실제 id 미포함 — 가명·신호만.

    schema.json 의 required 컬럼을 1:1 미러한다.
    """

    guild_pseudonym: str
    channel_pseudonym: str
    event_id: str
    event_time_ms: int
    burst_id: str
    scene_id: str
    actor_pseudonym: str
    event_kind: str
    features: dict[str, Any] = field(default_factory=dict)
    masks: dict[str, Any] = field(default_factory=dict)
    training_eligible: bool = False
    thread_pseudonym: str | None = None
    schema_version: int = SCHEMA_VERSION

    def to_row(self) -> dict[str, Any]:
        """columnar row(dict) 로 변환. Parquet writer 와 conformance 검사의 단위."""
        return {
            "schema_version": self.schema_version,
            "guild_pseudonym": self.guild_pseudonym,
            "channel_pseudonym": self.channel_pseudonym,
            "thread_pseudonym": self.thread_pseudonym,
            "event_id": self.event_id,
            "event_time_ms": self.event_time_ms,
            "burst_id": self.burst_id,
            "scene_id": self.scene_id,
            "actor_pseudonym": self.actor_pseudonym,
            "event_kind": self.event_kind,
            "features": dict(self.features),
            "masks": dict(self.masks),
            "training_eligible": self.training_eligible,
        }


def _assert_no_forbidden_columns(keys: Any) -> None:
    for key in keys:
        lowered = str(key).lower()
        for forbidden in _FORBIDDEN_COLUMN_SUBSTRINGS:
            if forbidden in lowered:
                raise SchemaError(
                    f"금지 컬럼 감지: {key!r} (원문/실제 식별자 흔적 '{forbidden}'). 원문 미포함 불변식 위반."
                )


def conform(row: dict[str, Any], schema: dict[str, Any] | None = None) -> dict[str, Any]:
    """row 가 스키마에 부합하는지 검사한다(fail-closed). 부합하면 그대로 반환.

    required 누락, 알 수 없는 컬럼(원문 몰래 끼우기 방지), version 불일치, 금지 컬럼명을 거부한다.
    무거운 jsonschema 의존 없이 stdlib 만으로 핵심 불변식을 강제한다.
    """
    sch = schema or load_schema()
    required = sch.get("required", [])
    allowed = set(sch.get("properties", {}).keys())

    _assert_no_forbidden_columns(row.keys())

    missing = [c for c in required if c not in row]
    if missing:
        raise SchemaError(f"required 컬럼 누락: {missing}")

    unknown = [c for c in row if c not in allowed]
    if unknown:
        raise SchemaError(f"알 수 없는 컬럼(원문/파생 텍스트 차단): {unknown}")

    if row.get("schema_version") != SCHEMA_VERSION:
        raise SchemaError(f"row schema_version 불일치: {row.get('schema_version')!r}")

    # 중첩 features/masks 키도 금지 컬럼 가드를 통과해야 한다.
    _assert_no_forbidden_columns(row.get("features", {}).keys())
    _assert_no_forbidden_columns(row.get("masks", {}).keys())
    return row


def parquet_available() -> bool:
    """pyarrow 가 설치되어 Parquet 직렬화가 가능한지."""
    try:
        import pyarrow  # noqa: F401
    except ImportError:
        return False
    return True


def write_parquet(records: list[EventRecord], path: Path) -> Path:
    """이벤트 레코드를 Parquet 로 쓴다(선택 어댑터). 각 row 는 먼저 conformance 검사를 통과해야 한다.

    pyarrow 가 없으면 명시적 [SchemaError] 로 거부한다(조용히 원문을 다른 포맷으로 흘리지 않는다).
    """
    if not parquet_available():
        raise SchemaError(
            "Parquet 직렬화는 pyarrow 가 필요하다(`pip install -e .[parquet]`). "
            "선택 의존이 없으면 in-memory 레코드/JSON 을 사용하라."
        )
    import pyarrow as pa
    import pyarrow.parquet as pq

    schema = load_schema()
    rows = [conform(r.to_row(), schema) for r in records]
    # features/masks 는 JSON 문자열 컬럼으로 평탄화(원문 아님 — 신호 구조).
    flat = [
        {
            **{k: v for k, v in row.items() if k not in ("features", "masks")},
            "features_json": json.dumps(row["features"], ensure_ascii=False, sort_keys=True),
            "masks_json": json.dumps(row["masks"], ensure_ascii=False, sort_keys=True),
        }
        for row in rows
    ]
    table = pa.Table.from_pylist(flat)
    path.parent.mkdir(parents=True, exist_ok=True)
    pq.write_table(table, path)
    return path
