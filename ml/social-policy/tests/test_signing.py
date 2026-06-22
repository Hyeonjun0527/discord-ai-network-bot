"""NEXA-P17-T020: 모델 artifact 서명·hash 검증 — 변조 artifact 가 ACTIVE 가 되지 않는다."""

from __future__ import annotations

from pathlib import Path

import pytest

from nexa_policy.export.signing import (
    ArtifactIntegrityError,
    build_manifest,
    sign_artifact,
    verify_artifact,
    verify_signature,
)

KEY = b"nexa-test-signing-key-0123456789"
OTHER_KEY = b"some-other-key-9876543210abcdef"


def _bundle(tmp_path: Path) -> dict[str, Path]:
    model = tmp_path / "policy.onnx"
    config = tmp_path / "feature-config.json"
    calibration = tmp_path / "calibration.json"
    model.write_bytes(b"\x00onnx-bytes\x01")
    config.write_text('{"schemaVersion": 3}', encoding="utf-8")
    calibration.write_text('{"version": "cal-1"}', encoding="utf-8")
    return {"model": model, "config": config, "calibration": calibration}


def test_signed_artifact_verifies(tmp_path: Path) -> None:
    bundle = _bundle(tmp_path)
    signed = sign_artifact("policy-v1", bundle, KEY)
    # 변조 없으면 조용히 통과(예외 없음).
    verify_artifact(signed, bundle, KEY)


def test_tampered_model_file_is_rejected(tmp_path: Path) -> None:
    bundle = _bundle(tmp_path)
    signed = sign_artifact("policy-v1", bundle, KEY)
    # 서명 후 모델 파일을 바꾼다(같은 키라도 hash 불일치여야 한다).
    bundle["model"].write_bytes(b"\x00malicious-swap\x01")
    with pytest.raises(ArtifactIntegrityError):
        verify_artifact(signed, bundle, KEY)


def test_wrong_signing_key_is_rejected(tmp_path: Path) -> None:
    bundle = _bundle(tmp_path)
    signed = sign_artifact("policy-v1", bundle, KEY)
    with pytest.raises(ArtifactIntegrityError):
        verify_artifact(signed, bundle, OTHER_KEY)


def test_forged_signature_is_rejected(tmp_path: Path) -> None:
    bundle = _bundle(tmp_path)
    signed = sign_artifact("policy-v1", bundle, KEY)
    forged = signed.__class__(manifest=signed.manifest, signature="deadbeef")
    with pytest.raises(ArtifactIntegrityError):
        verify_signature(forged, KEY)


def test_manifest_tamper_breaks_signature(tmp_path: Path) -> None:
    bundle = _bundle(tmp_path)
    signed = sign_artifact("policy-v1", bundle, KEY)
    # manifest 의 모델 버전을 바꾸면 정규형이 달라져 서명이 깨진다.
    bad_manifest = signed.manifest.__class__(
        model_version="attacker-v9",
        components=signed.manifest.components,
    )
    forged = signed.__class__(manifest=bad_manifest, signature=signed.signature)
    with pytest.raises(ArtifactIntegrityError):
        verify_signature(forged, KEY)


def test_missing_component_is_rejected(tmp_path: Path) -> None:
    bundle = _bundle(tmp_path)
    signed = sign_artifact("policy-v1", bundle, KEY)
    # 검증 시 구성요소 하나를 빼면(집합 불일치) 거부.
    partial = {"model": bundle["model"], "config": bundle["config"]}
    with pytest.raises(ArtifactIntegrityError):
        verify_artifact(signed, partial, KEY)


def test_empty_bundle_cannot_be_signed(tmp_path: Path) -> None:
    with pytest.raises(ArtifactIntegrityError):
        build_manifest("policy-v1", {})


def test_signature_is_deterministic(tmp_path: Path) -> None:
    bundle = _bundle(tmp_path)
    s1 = sign_artifact("policy-v1", bundle, KEY)
    s2 = sign_artifact("policy-v1", bundle, KEY)
    assert s1.signature == s2.signature


def test_canonical_bytes_independent_of_component_order(tmp_path: Path) -> None:
    bundle = _bundle(tmp_path)
    reordered = {"calibration": bundle["calibration"], "model": bundle["model"], "config": bundle["config"]}
    assert (
        sign_artifact("policy-v1", bundle, KEY).signature
        == sign_artifact("policy-v1", reordered, KEY).signature
    )
