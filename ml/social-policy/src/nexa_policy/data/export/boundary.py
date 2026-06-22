"""데이터 export 보안 경계(NEXA-P10-T002).

training eligibility 를 통과한 event/burst/scene/decision 만 export manifest 에 포함한다.

**acceptance(T002) — 운영 DB 직접 dump 가 아니라 승인된 projection 조회만 사용한다**:
- 이 경계는 **이미 가명화·승인된 projection 레코드([EventRecord])만** 입력으로 받는다. 운영 DB·게이트웨이에
  연결하는 코드가 없다(import 도 없음) — raw dump 진입점을 타입으로 차단한다.
- 입력은 `ApprovedProjection` 래퍼로만 들어온다. 임의 dict/dump 를 직접 export 할 수 없다(fail-closed).
- `training_eligible == true` 이고 opt-in·관찰가능 마스크를 만족하는 행만 manifest 에 들어간다.
- 원문/실제 식별자 흔적이 있는 컬럼은 스키마 conformance(schema.conform)가 거부한다.

**중요**: central(Kotlin) 측 projection 쿼리는 별도 작업(P10-T002 의 central 경로)에서 이 계약을 만족하도록
구현한다. 이 모듈은 그 projection 의 **소비 측 보안 게이트**이자 계약 단언점이다 — 운영 데이터에 직접 접근하지
않으며, 합성 fixture 로 경계 동작을 증명한다.
"""

from __future__ import annotations

from dataclasses import dataclass

from nexa_policy.data.schema import EventRecord, SchemaError, conform, load_schema


class ExportBoundaryError(SchemaError):
    """export 보안 경계 위반(fail-closed) — 부적격/원문/원본 id."""


@dataclass(frozen=True)
class ApprovedProjection:
    """승인된 projection 조회 결과 래퍼. **이 타입을 통해서만** export 경계에 들어올 수 있다.

    운영 DB dump(임의 dict/row)는 이 래퍼를 만들 수 없게 함으로써 raw-dump 경로를 구조적으로 차단한다.
    레코드는 이미 가명화·스키마 적합 [EventRecord] 여야 한다.
    """

    source: str  # 승인된 projection 이름(예: "participation_training_projection_v1").
    records: tuple[EventRecord, ...]

    def __post_init__(self) -> None:
        if not self.source.strip():
            raise ExportBoundaryError("승인된 projection source 이름이 필요하다.")
        # raw-dump 위장 차단: source 가 운영 dump/덤프/raw 면 거부한다.
        lowered = self.source.lower()
        if any(bad in lowered for bad in ("dump", "rawdb", "raw_db", "operational_db", "pg_dump")):
            raise ExportBoundaryError(f"운영 DB 직접 dump 는 export 할 수 없다: {self.source!r}")


@dataclass(frozen=True)
class ExportManifest:
    """export 매니페스트. eligibility 게이트를 통과한 행만 담는다."""

    source: str
    schema_version: int
    eligible: tuple[EventRecord, ...]
    excluded_count: int

    @property
    def included_count(self) -> int:
        return len(self.eligible)


def _is_eligible(record: EventRecord) -> bool:
    if not record.training_eligible:
        return False
    masks = record.masks or {}
    if masks.get("consent_opt_in") is not True:
        return False
    if masks.get("is_observable") is not True:
        return False
    return True


def build_export_manifest(projection: ApprovedProjection) -> ExportManifest:
    """승인된 projection 으로부터 export manifest 를 만든다(eligibility 필터 + conformance fail-closed).

    eligible 하지 않은 행은 조용히 제외(카운트만), 원문/원본 id 흔적이 있으면 즉시 거부한다.
    """
    schema = load_schema()
    eligible: list[EventRecord] = []
    excluded = 0
    for record in projection.records:
        # conformance(원문/원본 id/version) 는 모든 행에 대해 강제 — 위반 시 export 전체 실패(fail-closed).
        conform(record.to_row(), schema)
        if _is_eligible(record):
            eligible.append(record)
        else:
            excluded += 1
    return ExportManifest(
        source=projection.source,
        schema_version=schema["schema_version"],
        eligible=tuple(eligible),
        excluded_count=excluded,
    )
