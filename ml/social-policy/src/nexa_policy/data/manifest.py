"""dataset manifest 생성기(NEXA-P10-T017).

row 수, 길드 수, 기간, class 분포, exclusions, hashes 를 기록한다.

**acceptance(T017) — 모델 run 이 manifest ID 없이 시작되지 않는다**:
- [DatasetManifest] 는 항상 dataset_id 를 담고, [require_manifest_id] 가 학습 진입 가드를 제공한다.
- manifest 는 입력 레코드에서 자동 집계되며(수동 수치 드리프트 금지), [content_hash] 로 무결성을 봉인한다.
"""

from __future__ import annotations

from collections import Counter
from collections.abc import Iterable
from dataclasses import dataclass, field
from typing import Any

from nexa_policy.data.labels.action import ActionClass
from nexa_policy.data.schema import EventRecord
from nexa_policy.data.versioning import DatasetVersionInputs, compute_dataset_id, stable_digest


class ManifestError(ValueError):
    """manifest 불변식 위반(fail-closed)."""


@dataclass(frozen=True)
class DatasetManifest:
    """데이터셋 manifest. 자동 집계 수치 + immutable dataset_id + content hash."""

    dataset_id: str
    schema_version: int
    source_watermark: str
    code_commit: str
    consent_snapshot_id: str
    row_count: int
    guild_count: int
    period_start_ms: int | None
    period_end_ms: int | None
    class_distribution: dict[str, int]
    exclusions: dict[str, int]
    content_hash: str
    version_inputs: dict[str, Any] = field(default_factory=dict)

    def to_dict(self) -> dict[str, Any]:
        return {
            "dataset_id": self.dataset_id,
            "schema_version": self.schema_version,
            "source_watermark": self.source_watermark,
            "code_commit": self.code_commit,
            "consent_snapshot_id": self.consent_snapshot_id,
            "row_count": self.row_count,
            "guild_count": self.guild_count,
            "period_start_ms": self.period_start_ms,
            "period_end_ms": self.period_end_ms,
            "class_distribution": dict(self.class_distribution),
            "exclusions": dict(self.exclusions),
            "content_hash": self.content_hash,
            "version_inputs": dict(self.version_inputs),
        }


def _period(records: list[EventRecord]) -> tuple[int | None, int | None]:
    if not records:
        return None, None
    times = [r.event_time_ms for r in records]
    return min(times), max(times)


def build_manifest(
    *,
    records: Iterable[EventRecord],
    version_inputs: DatasetVersionInputs,
    class_labels: Iterable[str] | None = None,
    exclusions: dict[str, int] | None = None,
) -> DatasetManifest:
    """레코드와 version 구성요소로 manifest 를 자동 집계한다(수동 수치 드리프트 금지).

    - dataset_id 는 version_inputs 로 결정론 계산(T016 재사용).
    - class_distribution 은 명시 class_labels(예: action 라벨 분포) 가 있으면 그것을, 없으면 event_kind 를 센다.
    - content_hash 는 집계 수치 전체의 안정 해시 — manifest 변조/드리프트 감지.
    """
    rec_list = list(records)
    dataset_id = compute_dataset_id(version_inputs)
    guilds = {r.guild_pseudonym for r in rec_list}
    start_ms, end_ms = _period(rec_list)

    if class_labels is not None:
        dist = dict(Counter(class_labels))
    else:
        dist = dict(Counter(r.event_kind for r in rec_list))

    excl = dict(exclusions or {})

    body: dict[str, Any] = {
        "dataset_id": dataset_id,
        "schema_version": version_inputs.schema_version,
        "row_count": len(rec_list),
        "guild_count": len(guilds),
        "period_start_ms": start_ms,
        "period_end_ms": end_ms,
        "class_distribution": dist,
        "exclusions": excl,
    }
    content_hash = stable_digest(body, digest_size=20)

    return DatasetManifest(
        dataset_id=dataset_id,
        schema_version=version_inputs.schema_version,
        source_watermark=version_inputs.source_watermark,
        code_commit=version_inputs.code_commit,
        consent_snapshot_id=version_inputs.consent_snapshot_id,
        row_count=len(rec_list),
        guild_count=len(guilds),
        period_start_ms=start_ms,
        period_end_ms=end_ms,
        class_distribution=dist,
        exclusions=excl,
        content_hash=content_hash,
        version_inputs=version_inputs.to_canonical(),
    )


def require_manifest_id(manifest: DatasetManifest | None) -> str:
    """학습 진입 가드: manifest(dataset_id) 가 없으면 모델 run 을 시작할 수 없다(T017 acceptance)."""
    if manifest is None or not manifest.dataset_id.strip():
        raise ManifestError("dataset manifest ID 없이 모델 run 을 시작할 수 없다.")
    return manifest.dataset_id


# action 라벨 분포 집계에 쓸 안정 class 이름(드리프트 방지로 ActionClass 미러).
ACTION_CLASS_NAMES = tuple(c.value for c in ActionClass)
