"""T024 Policy v1 Model Card 테스트 — manifest 자동 삽입·금지 추론·LIVE 전제·한계 명시(드리프트 가드)."""

from __future__ import annotations

import hashlib
from pathlib import Path

from nexa_policy.reporting.model_card import (
    PolicyModelManifest,
    manifest_from_fixture,
    render_model_card,
)

_FIXTURE_ONNX = (
    Path(__file__).resolve().parents[3]
    / "contracts"
    / "policy"
    / "fixtures"
    / "parity"
    / "policy-v1-fixture.onnx"
)
_CARD = (
    Path(__file__).resolve().parents[3] / "docs" / "nexa" / "models" / "social-policy-v1.md"
)


def _manifest() -> PolicyModelManifest:
    return manifest_from_fixture(
        _FIXTURE_ONNX,
        model_id="policy-v1-fixture",
        model_version="policy-v1-fixture",
        dataset_id="nexa-ds-fixture",
        feature_schema_version=2,
        calibration_version="cal-1",
        opset=17,
        metrics={"balanced_accuracy": 0.5, "false_ignore_rate": 0.2, "ece": 0.1},
    )


def test_manifest_hash_is_auto_computed() -> None:
    """artifact hash 가 fixture 바이트에서 자동 계산된다(수동 입력 아님)."""
    expected = hashlib.sha256(_FIXTURE_ONNX.read_bytes()).hexdigest()
    assert _manifest().artifact_sha256 == expected


def test_card_inserts_manifest_values() -> None:
    card = render_model_card(_manifest())
    m = _manifest()
    assert m.artifact_sha256 in card
    assert m.dataset_id in card
    assert "balanced_accuracy" in card
    assert "0.5000" in card  # metric 자동 포맷.


def test_card_documents_forbidden_inference() -> None:
    """금지 추론(observable-state-policy)이 구체적으로 명시된다(acceptance)."""
    card = render_model_card(_manifest())
    assert "observable-state-policy" in card
    assert "금지" in card
    assert "member ID" in card


def test_card_documents_live_prerequisites_and_limitations() -> None:
    """LIVE 승인 전제와 known limitations 가 구체적이다(acceptance T024)."""
    card = render_model_card(_manifest())
    assert "LIVE 승인 전제" in card
    assert "parity" in card.lower()
    assert "ShadowModelRegistry" in card  # 미승인 artifact LIVE 불가(T020) 참조.
    assert "Known Limitations" in card


def test_committed_card_matches_generator() -> None:
    """committed 카드가 생성기 출력과 정확히 일치한다(수동 드리프트 금지)."""
    expected = render_model_card(_manifest())
    assert _CARD.exists(), f"Model Card 부재: {_CARD}"
    assert _CARD.read_text(encoding="utf-8") == expected
