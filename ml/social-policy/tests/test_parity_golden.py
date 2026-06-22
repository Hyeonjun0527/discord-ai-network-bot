"""T019 Python-JVM golden parity — Python 측 검증.

central(JVM onnxruntime adapter, T018)이 같은 ONNX 모델·입력으로 추론해 비교할 golden 을, Python 측에서도
onnxruntime 으로 재현할 수 있음을 확인한다(같은 fixture·같은 head ordering·같은 허용오차). golden 이 결정론적으로
재생성되는지도 검증한다(드리프트 가드).
"""

from __future__ import annotations

import json
from pathlib import Path

import numpy as np

from nexa_policy.export.onnx import HEAD_OUTPUTS, run_onnx
from nexa_policy.export.parity import (
    HEAD_CATEGORY_ORDER,
    PARITY_TOLERANCE,
    build_fixture_inputs,
    golden_payload,
    write_fixture,
)

_FIXTURE_DIR = (
    Path(__file__).resolve().parents[3] / "contracts" / "policy" / "fixtures" / "parity"
)
_ONNX = _FIXTURE_DIR / "policy-v1-fixture.onnx"
_GOLDEN = _FIXTURE_DIR / "policy-v1-parity.golden.json"


def _load_golden() -> dict:
    return json.loads(_GOLDEN.read_text(encoding="utf-8"))


def test_committed_fixtures_exist() -> None:
    """central JVM parity 가 읽을 committed fixture 가 존재한다."""
    assert _ONNX.exists(), f"parity ONNX fixture 부재: {_ONNX}"
    assert _GOLDEN.exists(), f"parity golden 부재: {_GOLDEN}"


def test_onnxruntime_reproduces_golden_within_tolerance() -> None:
    """committed ONNX 를 committed 입력으로 추론하면 golden head 확률과 허용오차 내 일치한다."""
    golden = _load_golden()
    x = np.asarray(golden["inputs"], dtype=np.float32)
    outs = run_onnx(_ONNX, x)
    tol = float(golden["tolerance"])
    for name in HEAD_OUTPUTS:
        got = outs[name]
        expected = np.asarray(golden["heads"][name], dtype=np.float64)
        max_abs = float(np.max(np.abs(got - expected)))
        assert max_abs <= tol, f"head {name!r} golden 오차 {max_abs} > tol {tol}"


def test_golden_category_order_matches_ssot() -> None:
    """golden 의 head category 순서가 코드 SSOT(HEAD_CATEGORY_ORDER)와 일치한다(JVM 복원 순서 고정)."""
    golden = _load_golden()
    for name, order in HEAD_CATEGORY_ORDER.items():
        assert golden["categoryOrder"][name] == list(order)
        # head 출력 폭이 category 수와 같아야 분포 복원이 어긋나지 않는다.
        assert len(golden["heads"][name][0]) == len(order)


def test_golden_tolerance_documented() -> None:
    """허용오차가 golden 에 명시되고 코드 상수와 일치한다(acceptance T019)."""
    assert _load_golden()["tolerance"] == PARITY_TOLERANCE


def test_golden_regeneration_is_deterministic(tmp_path: Path) -> None:
    """같은 상수로 재생성한 golden 이 committed 와 동일하다(수동 드리프트 금지)."""
    regenerated = write_fixture(tmp_path)
    fresh = json.loads(regenerated.golden_path.read_text(encoding="utf-8"))
    committed = _load_golden()
    # 메타·순서 동일.
    assert fresh["categoryOrder"] == committed["categoryOrder"]
    assert fresh["modelVersion"] == committed["modelVersion"]
    assert fresh["inputDim"] == committed["inputDim"]
    # 수치는 결정론이라 허용오차 없이도 매우 근접(부동소수 직렬화 차이만 흡수).
    for name in HEAD_OUTPUTS:
        a = np.asarray(fresh["heads"][name], dtype=np.float64)
        b = np.asarray(committed["heads"][name], dtype=np.float64)
        assert np.max(np.abs(a - b)) <= 1e-9


def test_payload_heads_are_probability_distributions() -> None:
    """golden 의 각 head 행이 확률분포다(합≈1)."""
    model, x = build_fixture_inputs()
    payload = golden_payload(model, x)
    for name in HEAD_OUTPUTS:
        rows = np.asarray(payload["heads"][name], dtype=np.float64)
        assert np.allclose(rows.sum(axis=1), 1.0, atol=1e-5)
