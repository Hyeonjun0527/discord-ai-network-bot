"""T002 FeatureVector loader 테스트 — version·dtype·missing·range, unknown version 거부."""

from __future__ import annotations

import json
from pathlib import Path

import pytest

from nexa_policy.features.schema import (
    CATALOG_VERSION,
    FeatureSchemaError,
    FeatureVectorLoader,
    load_feature_catalog,
)

_REPO_SCHEMA = (
    Path(__file__).resolve().parents[3] / "contracts" / "policy" / "feature-vector.schema.json"
)
_VENDORED_SCHEMA = (
    Path(__file__).resolve().parents[1] / "contracts" / "policy" / "feature-vector.schema.json"
)


def _vec(**features: object) -> dict[str, object]:
    return {"version": CATALOG_VERSION, "features": features}


def test_catalog_loads_with_version_and_dim() -> None:
    cat = load_feature_catalog()
    assert cat.version == CATALOG_VERSION
    assert cat.dim == 32
    assert cat.feature_ids[0] == "burst.fragment_count"
    assert "thread.direct_address_pressure" in cat.feature_ids
    assert "tempo.anti_spam_pressure" in cat.feature_ids


def test_unknown_version_rejected_not_ignored() -> None:
    """알 수 없는 feature version 을 조용히 무시하지 않는다(acceptance)."""
    loader = FeatureVectorLoader()
    with pytest.raises(FeatureSchemaError):
        loader.load_one({"version": 999, "features": {}})


def test_missing_version_rejected() -> None:
    loader = FeatureVectorLoader()
    with pytest.raises(FeatureSchemaError):
        loader.load_one({"features": {}})


def test_unknown_feature_id_rejected() -> None:
    loader = FeatureVectorLoader()
    with pytest.raises(FeatureSchemaError):
        loader.load_one(_vec(**{"not.a.feature": {"value": 1.0, "missing": False}}))


def test_out_of_range_rejected() -> None:
    loader = FeatureVectorLoader()
    with pytest.raises(FeatureSchemaError):
        loader.load_one(_vec(**{"tempo.nexa_share": {"value": 5.0, "missing": False}}))
    with pytest.raises(FeatureSchemaError):
        loader.load_one(_vec(**{"burst.fragment_count": {"value": -1.0, "missing": False}}))


def test_boolean_must_be_zero_or_one() -> None:
    loader = FeatureVectorLoader()
    with pytest.raises(FeatureSchemaError):
        loader.load_one(_vec(**{"burst.is_question": {"value": 0.5, "missing": False}}))


def test_missing_preserved_as_mask_not_zero() -> None:
    """missing=true 면 value 0 + missing-mask 1(0 과 '모름' 구분)."""
    loader = FeatureVectorLoader()
    values, mask = loader.load_one(_vec(**{"tempo.nexa_share": {"value": 0.0, "missing": True}}))
    idx = loader.catalog.feature_ids.index("tempo.nexa_share")
    assert values[idx] == 0.0
    assert mask[idx] == 1.0


def test_unspecified_feature_marked_missing() -> None:
    loader = FeatureVectorLoader()
    _, mask = loader.load_one(_vec())  # 아무 feature 도 명시 안 함.
    assert all(m == 1.0 for m in mask)


def test_batch_shapes() -> None:
    loader = FeatureVectorLoader()
    loaded = loader.load_batch([_vec(), _vec()])
    assert loaded.values.shape == (2, loader.catalog.dim)
    assert loaded.missing_mask.shape == (2, loader.catalog.dim)


def test_vendored_schema_matches_repo_ssot() -> None:
    """벤더링된 스키마가 repo SSOT 와 동일(드리프트 가드)."""
    if not _REPO_SCHEMA.exists():
        pytest.skip("repo SSOT 스키마가 이 체크아웃에 없다.")
    repo = json.loads(_REPO_SCHEMA.read_text(encoding="utf-8"))
    vendored = json.loads(_VENDORED_SCHEMA.read_text(encoding="utf-8"))
    assert repo == vendored, "벤더링 feature-vector 스키마가 repo SSOT 와 드리프트했다."
