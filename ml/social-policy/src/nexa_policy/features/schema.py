"""FeatureVector Python loader(NEXA-P11-T002).

P08 SSOT(`contracts/policy/feature-vector.schema.json` + `docs/nexa/policy/features.md`)를 읽어
feature 벡터의 dtype·missing·range 를 검증하고, 모델 입력용 결정론적 수치 행렬로 변환한다.

**acceptance(T002) — 알 수 없는 feature version 을 조용히 무시하지 않는다**:
- [load_feature_catalog] 는 스키마 `version` 을 읽고, [FeatureVectorLoader] 는 입력 벡터의 version 이
  카탈로그 version 과 다르면 [FeatureSchemaError] 로 거부한다(silent skip 금지).
- 각 feature 는 `{value, missing}` 다. missing=true 면 value 는 0 으로 정규화하되 별도 missing-mask 채널을
  둔다(0 과 "모름" 구분, features.md 불변식 2).
- 알 수 없는 feature ID(카탈로그 밖)도 거부한다(원문/파생 텍스트 몰래 끼우기 방지).
- 범위 위반(예: NORMALIZED [0,1] 밖, COUNT 음수)도 거부한다.

feature 키·순서는 features.md 표를 미러하는 안정 [CATALOG_FEATURES] 로 고정한다(코드·데이터셋 공유).
"""

from __future__ import annotations

import json
from dataclasses import dataclass
from pathlib import Path
from typing import TYPE_CHECKING, Any

from nexa_policy.reproducibility import require_numpy

if TYPE_CHECKING:
    import numpy as np

_CONTRACT_PATH = (
    Path(__file__).resolve().parents[3] / "contracts" / "policy" / "feature-vector.schema.json"
)

# features.md / FeatureCatalog.VERSION 미러(SSOT). feature 추가·의미 변경 시 함께 올린다.
CATALOG_VERSION = 1


class FeatureSchemaError(ValueError):
    """feature 벡터 schema/version/range 위반(fail-closed)."""


class FeatureType:
    """features.md 의 feature type SSOT 미러(x-feature-types)."""

    NORMALIZED = "NORMALIZED"
    COUNT = "COUNT"
    DURATION = "DURATION"
    RATE = "RATE"
    BOOLEAN = "BOOLEAN"
    CATEGORICAL = "CATEGORICAL"


@dataclass(frozen=True)
class FeatureSpec:
    """한 feature 의 ID·type·범위. features.md 표를 1:1 미러한다(드리프트 금지)."""

    feature_id: str
    feature_type: str
    minimum: float
    maximum: float | None  # None 이면 상한 없음(COUNT/DURATION/RATE).

    def validate_value(self, value: float) -> None:
        if value < self.minimum:
            raise FeatureSchemaError(
                f"feature {self.feature_id!r} 값 {value} 이 최소 {self.minimum} 미만이다."
            )
        if self.maximum is not None and value > self.maximum:
            raise FeatureSchemaError(
                f"feature {self.feature_id!r} 값 {value} 이 최대 {self.maximum} 초과다."
            )
        if self.feature_type == FeatureType.BOOLEAN and value not in (0.0, 1.0):
            raise FeatureSchemaError(
                f"BOOLEAN feature {self.feature_id!r} 는 0/1 이어야 한다: {value}"
            )


# features.md 표의 안정 순서·type·범위(SSOT 미러). 순서가 곧 모델 입력 차원 순서다(결정론).
CATALOG_FEATURES: tuple[FeatureSpec, ...] = (
    FeatureSpec("burst.fragment_count", FeatureType.COUNT, 0.0, None),
    FeatureSpec("burst.total_length", FeatureType.COUNT, 0.0, None),
    FeatureSpec("burst.gap_seconds", FeatureType.DURATION, 0.0, None),
    FeatureSpec("burst.is_question", FeatureType.BOOLEAN, 0.0, 1.0),
    FeatureSpec("burst.has_mention", FeatureType.BOOLEAN, 0.0, 1.0),
    FeatureSpec("burst.is_reply", FeatureType.BOOLEAN, 0.0, 1.0),
    FeatureSpec("burst.source_type", FeatureType.CATEGORICAL, 0.0, 2.0),
    FeatureSpec("thread.focus_present", FeatureType.BOOLEAN, 0.0, 1.0),
    FeatureSpec("thread.target_entropy", FeatureType.NORMALIZED, 0.0, 1.0),
    FeatureSpec("thread.active_speakers", FeatureType.COUNT, 0.0, None),
    FeatureSpec("thread.topic_age_seconds", FeatureType.DURATION, 0.0, None),
    FeatureSpec("tempo.human_burst_rate", FeatureType.RATE, 0.0, None),
    FeatureSpec("tempo.median_gap_seconds", FeatureType.DURATION, 0.0, None),
    FeatureSpec("tempo.overlap_ratio", FeatureType.NORMALIZED, 0.0, 1.0),
    FeatureSpec("tempo.nexa_share", FeatureType.NORMALIZED, 0.0, 1.0),
    FeatureSpec("relationship.familiarity", FeatureType.NORMALIZED, 0.0, 1.0),
    FeatureSpec("relationship.reciprocity", FeatureType.NORMALIZED, 0.0, 1.0),
    FeatureSpec("relationship.banter_acceptance", FeatureType.NORMALIZED, 0.0, 1.0),
    FeatureSpec("relationship.sample_confidence", FeatureType.NORMALIZED, 0.0, 1.0),
    FeatureSpec("memory.relevant_present", FeatureType.BOOLEAN, 0.0, 1.0),
    FeatureSpec("memory.relevant_confidence", FeatureType.NORMALIZED, 0.0, 1.0),
    FeatureSpec("memory.relevant_age_seconds", FeatureType.DURATION, 0.0, None),
    FeatureSpec("memory.pending_intent_active", FeatureType.BOOLEAN, 0.0, 1.0),
    FeatureSpec("agent.recent_burst_count", FeatureType.COUNT, 0.0, None),
    FeatureSpec("agent.share", FeatureType.NORMALIZED, 0.0, 1.0),
    FeatureSpec("agent.last_spoke_age_seconds", FeatureType.DURATION, 0.0, None),
    FeatureSpec("agent.pending_action_count", FeatureType.COUNT, 0.0, None),
)


@dataclass(frozen=True)
class FeatureCatalog:
    """feature 카탈로그(version + 안정 순서 spec). 모델 입력 차원의 SSOT."""

    version: int
    features: tuple[FeatureSpec, ...]

    @property
    def feature_ids(self) -> tuple[str, ...]:
        return tuple(f.feature_id for f in self.features)

    @property
    def dim(self) -> int:
        return len(self.features)


def load_feature_catalog(path: Path | None = None) -> FeatureCatalog:
    """P08 스키마가 instance `version`(정수 ≥1)을 required 로 강제하는지 확인하고 카탈로그를 만든다.

    스키마 파일은 JSON-Schema 라 구체 version 값을 담지 않는다 — 카탈로그 version 의 SSOT 는
    [CATALOG_VERSION](features.md / FeatureCatalog.VERSION 미러)다. 스키마가 instance version 을
    required·정수·minimum 1 로 강제하지 못하면 거부한다(version 을 조용히 흘리지 않는 계약 보증, T002).
    feature 순서·type 은 [CATALOG_FEATURES](features.md 미러)로 고정한다.
    """
    schema_path = path or _CONTRACT_PATH
    schema: dict[str, Any] = json.loads(schema_path.read_text(encoding="utf-8"))
    required = schema.get("required", [])
    version_prop = schema.get("properties", {}).get("version", {})
    if "version" not in required:
        raise FeatureSchemaError("feature 스키마가 instance version 을 required 로 강제하지 않는다.")
    if version_prop.get("type") != "integer" or version_prop.get("minimum", 0) < 1:
        raise FeatureSchemaError("feature 스키마 version 제약(integer, minimum≥1)이 누락됐다.")
    return FeatureCatalog(version=CATALOG_VERSION, features=CATALOG_FEATURES)


@dataclass(frozen=True)
class LoadedFeatures:
    """검증·정규화된 feature 행렬.

    - [values]: (n_samples, dim) float32 — missing 은 0 으로 채움.
    - [missing_mask]: (n_samples, dim) float32 — missing=1.0, 관측=0.0(0 과 '모름' 구분).
    - [feature_ids]: 컬럼 순서(카탈로그와 동일).
    """

    values: np.ndarray
    missing_mask: np.ndarray
    feature_ids: tuple[str, ...]


class FeatureVectorLoader:
    """feature 벡터 dict 를 카탈로그로 검증해 모델 입력 행렬로 만든다."""

    def __init__(self, catalog: FeatureCatalog | None = None) -> None:
        self._catalog = catalog or load_feature_catalog()
        self._index = {spec.feature_id: i for i, spec in enumerate(self._catalog.features)}

    @property
    def catalog(self) -> FeatureCatalog:
        return self._catalog

    def _check_version(self, vector: dict[str, Any]) -> None:
        if "version" not in vector:
            raise FeatureSchemaError("feature 벡터에 version 이 없다(알 수 없는 version 조용한 무시 금지).")
        version = vector["version"]
        if version != self._catalog.version:
            raise FeatureSchemaError(
                f"알 수 없는 feature version: {version!r} != 카탈로그 {self._catalog.version}. "
                "조용히 무시하지 않고 거부한다(T002 acceptance)."
            )

    def load_one(self, vector: dict[str, Any]) -> tuple[list[float], list[float]]:
        """단일 feature 벡터를 (values, missing_mask) 리스트로 검증·정규화한다."""
        self._check_version(vector)
        features = vector.get("features", {})
        if not isinstance(features, dict):
            raise FeatureSchemaError("features 는 object 여야 한다.")

        unknown = [fid for fid in features if fid not in self._index]
        if unknown:
            raise FeatureSchemaError(f"카탈로그에 없는 feature ID(거부): {sorted(unknown)}")

        values = [0.0] * self._catalog.dim
        missing = [0.0] * self._catalog.dim
        for spec in self._catalog.features:
            cell = features.get(spec.feature_id)
            if cell is None:
                # 명시되지 않은 feature 는 '모름'으로 둔다(0 으로 뭉개지 않음).
                missing[self._index[spec.feature_id]] = 1.0
                continue
            if not isinstance(cell, dict) or "value" not in cell or "missing" not in cell:
                raise FeatureSchemaError(
                    f"feature {spec.feature_id!r} 는 {{value, missing}} 형태여야 한다: {cell!r}"
                )
            idx = self._index[spec.feature_id]
            if cell["missing"] is True:
                missing[idx] = 1.0
                values[idx] = 0.0
                continue
            raw = cell["value"]
            if isinstance(raw, bool) or not isinstance(raw, (int, float)):
                raise FeatureSchemaError(
                    f"feature {spec.feature_id!r} value 는 수치여야 한다: {raw!r}"
                )
            val = float(raw)
            spec.validate_value(val)
            values[idx] = val
        return values, missing

    def load_batch(self, vectors: list[dict[str, Any]]) -> LoadedFeatures:
        """feature 벡터 리스트를 (values, missing_mask) 행렬로 변환한다."""
        np = require_numpy()
        if not vectors:
            empty = np.zeros((0, self._catalog.dim), dtype=np.float32)
            return LoadedFeatures(empty, empty.copy(), self._catalog.feature_ids)
        rows_v: list[list[float]] = []
        rows_m: list[list[float]] = []
        for vec in vectors:
            v, m = self.load_one(vec)
            rows_v.append(v)
            rows_m.append(m)
        return LoadedFeatures(
            values=np.asarray(rows_v, dtype=np.float32),
            missing_mask=np.asarray(rows_m, dtype=np.float32),
            feature_ids=self._catalog.feature_ids,
        )
