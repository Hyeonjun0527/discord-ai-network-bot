"""학습 artifact 삭제 tombstone(NEXA-P17-T011, security/ml).

삭제 대상 source 가 포함된 dataset/model 을 식별하고, 재학습(retrain)·폐기(retire) 상태를 추적 가능한
tombstone 으로 관리한다. 삭제 전파(deletion-propagation.md)의 "dataset/model 단계" 를 ML 쪽에서 닫는다.

**핵심 한계(acceptance) — 모델 가중치에서 개별 샘플 제거 불가**:
- 신경망/트리 파라미터에는 학습 데이터가 분산 인코딩되므로 한 사용자의 행을 사후에 외과적으로 제거할 수
  없다. [DeletionResolution.can_resolve_in_model_weights] 는 항상 False 다.
- 모델 수준 삭제는 **재학습 또는 폐기**로만 충족된다. [resolve_tombstone] 이 트리거 종류·삭제 비율로
  RETRAINED/RETIRED/PENDING_RETRAIN 을 결정한다.

원문·snowflake 는 담지 않는다 — 삭제 증적은 가명 id + 시각만(deletion-propagation.md 불변식 2).
"""

from __future__ import annotations

from collections.abc import Iterable
from dataclasses import dataclass, field, replace
from enum import StrEnum


class DeletionError(ValueError):
    """tombstone 불변식 위반(fail-closed)."""


class DeletionTrigger(StrEnum):
    """삭제 트리거 종류(central DeletionTrigger 미러)."""

    MESSAGE_DELETE = "MESSAGE_DELETE"
    USER_REQUEST = "USER_REQUEST"
    GUILD_LEAVE = "GUILD_LEAVE"
    CONSENT_REVOKE = "CONSENT_REVOKE"


class TombstoneStatus(StrEnum):
    """tombstone 상태 — 재학습 전/후/폐기."""

    PENDING_RETRAIN = "PENDING_RETRAIN"
    RETRAINED = "RETRAINED"
    RETIRED = "RETIRED"


# 재학습 전 model 폐기를 강제하는 트리거(즉시 제거 의무 — training-deletion.md 재학습 기준 1).
_FORCE_RETIRE_TRIGGERS = frozenset({DeletionTrigger.CONSENT_REVOKE, DeletionTrigger.USER_REQUEST})


@dataclass(frozen=True)
class DeletionTombstone:
    """삭제 요청 1건이 학습 artifact 에 남긴 불변 증적(가명 id·시각만, 원문 비포함)."""

    deletion_request_id: str
    deleted_source_ids: frozenset[str]
    affected_dataset_ids: frozenset[str]
    affected_model_ids: frozenset[str]
    requested_at_ms: int
    status: TombstoneStatus = TombstoneStatus.PENDING_RETRAIN
    resolved_at_ms: int | None = None

    def __post_init__(self) -> None:
        if not self.deletion_request_id.strip():
            raise DeletionError("deletion_request_id 는 비어 있을 수 없다.")
        if not self.deleted_source_ids:
            raise DeletionError("삭제 대상 source 가 비어 있다(tombstone 무의미).")


@dataclass(frozen=True)
class DatasetIndex:
    """source id → 그 source 를 포함하는 dataset id 목록과 dataset → model 목록의 인덱스."""

    source_to_datasets: dict[str, frozenset[str]] = field(default_factory=dict)
    dataset_to_models: dict[str, frozenset[str]] = field(default_factory=dict)
    dataset_row_counts: dict[str, int] = field(default_factory=dict)

    def datasets_for(self, source_ids: Iterable[str]) -> frozenset[str]:
        out: set[str] = set()
        for sid in source_ids:
            out |= set(self.source_to_datasets.get(sid, frozenset()))
        return frozenset(out)

    def models_for(self, dataset_ids: Iterable[str]) -> frozenset[str]:
        out: set[str] = set()
        for did in dataset_ids:
            out |= set(self.dataset_to_models.get(did, frozenset()))
        return frozenset(out)


@dataclass(frozen=True)
class DeletionResolution:
    """삭제 요청의 모델 수준 해소 판정(재학습/폐기 기준)."""

    status: TombstoneStatus
    # 모델 가중치에서 개별 샘플을 외과적으로 제거할 수 있는가 — 구조적으로 항상 False(핵심 한계).
    can_resolve_in_model_weights: bool = False


def identify_affected(
    *,
    deleted_source_ids: Iterable[str],
    index: DatasetIndex,
) -> tuple[frozenset[str], frozenset[str]]:
    """삭제 대상 source 를 포함하는 dataset/model 을 식별한다(acceptance — 영향 artifact 식별)."""
    sources = frozenset(deleted_source_ids)
    datasets = index.datasets_for(sources)
    models = index.models_for(datasets)
    return datasets, models


def build_tombstone(
    *,
    deletion_request_id: str,
    deleted_source_ids: Iterable[str],
    index: DatasetIndex,
    requested_at_ms: int,
) -> DeletionTombstone:
    """삭제 요청에서 영향 dataset/model 을 자동 식별해 PENDING_RETRAIN tombstone 을 만든다."""
    sources = frozenset(deleted_source_ids)
    datasets, models = identify_affected(deleted_source_ids=sources, index=index)
    return DeletionTombstone(
        deletion_request_id=deletion_request_id,
        deleted_source_ids=sources,
        affected_dataset_ids=datasets,
        affected_model_ids=models,
        requested_at_ms=requested_at_ms,
    )


def _deleted_ratio(
    *, deleted_source_ids: frozenset[str], dataset_ids: frozenset[str], index: DatasetIndex
) -> float:
    """영향 dataset 들의 행 대비 삭제 source 비율(재학습 vs 폐기 판정용). 행수 0 이면 0.0."""
    total = sum(index.dataset_row_counts.get(d, 0) for d in dataset_ids)
    if total <= 0:
        return 0.0
    return min(1.0, len(deleted_source_ids) / total)


def decide_resolution(
    *,
    tombstone: DeletionTombstone,
    trigger: DeletionTrigger,
    index: DatasetIndex,
    retrained: bool,
    retrain_threshold: float = 0.01,
) -> DeletionResolution:
    """삭제 트리거·삭제 비율·재학습 여부로 모델 수준 해소 상태를 결정한다(재학습 기준).

    - retrained=True: 삭제 행을 제외해 이미 재학습됨 → RETRAINED.
    - 즉시 제거 의무 트리거(동의 철회·사용자 요청) 또는 삭제 비율 ≥ 임계 → RETIRED(재학습 전 폐기).
    - 그 외 → PENDING_RETRAIN(다음 정기 재학습까지 대기).
    """
    if retrained:
        return DeletionResolution(status=TombstoneStatus.RETRAINED)
    if trigger in _FORCE_RETIRE_TRIGGERS:
        return DeletionResolution(status=TombstoneStatus.RETIRED)
    ratio = _deleted_ratio(
        deleted_source_ids=tombstone.deleted_source_ids,
        dataset_ids=tombstone.affected_dataset_ids,
        index=index,
    )
    if ratio >= retrain_threshold:
        return DeletionResolution(status=TombstoneStatus.RETIRED)
    return DeletionResolution(status=TombstoneStatus.PENDING_RETRAIN)


def resolve_tombstone(
    *,
    tombstone: DeletionTombstone,
    trigger: DeletionTrigger,
    index: DatasetIndex,
    retrained: bool,
    resolved_at_ms: int,
    retrain_threshold: float = 0.01,
) -> DeletionTombstone:
    """[decide_resolution] 으로 상태를 정해 종결된(혹은 대기) 새 tombstone 을 만든다(불변 — 새 인스턴스)."""
    resolution = decide_resolution(
        tombstone=tombstone,
        trigger=trigger,
        index=index,
        retrained=retrained,
        retrain_threshold=retrain_threshold,
    )
    terminal = resolution.status in (TombstoneStatus.RETRAINED, TombstoneStatus.RETIRED)
    return replace(
        tombstone,
        status=resolution.status,
        resolved_at_ms=resolved_at_ms if terminal else None,
    )
