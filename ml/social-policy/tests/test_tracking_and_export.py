"""T016 실험 추적·T017 ONNX export 테스트 — 재구성 가능성·파이썬/ONNX parity."""

from __future__ import annotations

import json
from pathlib import Path

import numpy as np
import pytest

from nexa_policy.datasets import make_synthetic_dataset
from nexa_policy.experiments.tracking import (
    ExperimentConfig,
    ExperimentError,
    RunRecord,
    artifact_hash,
    load_run_record,
    reconstruct_command,
    require_dataset_binding,
)
from nexa_policy.export.onnx import (
    HEAD_OUTPUTS,
    ONNX_OPSET,
    export_policy_onnx,
    run_onnx,
    verify_parity,
)
from nexa_policy.reproducibility import capture_environment
from nexa_policy.training.splitting import make_split_indices
from nexa_policy.training.trainer import design_matrix, train_multihead

_DS = make_synthetic_dataset(seed=7, n_guilds=12)
_SP = make_split_indices(_DS, seed=0)


def _config() -> ExperimentConfig:
    return ExperimentConfig(
        experiment="policy-multihead",
        dataset_id="nexa-ds-abc123",
        seed=3,
        epochs=40,
        learning_rate=0.05,
        hidden_dim=16,
        task_weights={"action": 1.0, "target": 0.5},
    )


# ---- T016 tracking ----
def test_config_digest_deterministic() -> None:
    assert _config().config_digest() == _config().config_digest()


def test_dataset_binding_required() -> None:
    with pytest.raises(ExperimentError):
        require_dataset_binding(_config().__class__(
            experiment="x", dataset_id="  ", seed=1, epochs=1, learning_rate=0.1, hidden_dim=4
        ))
    assert require_dataset_binding(_config()) == "nexa-ds-abc123"


def test_run_record_reconstructable_from_file(tmp_path: Path) -> None:
    """결과 파일만으로 학습 명령을 재구성할 수 있다(acceptance T016)."""
    config = _config()
    record = RunRecord(
        config=config,
        metrics={"test_balanced_accuracy": 0.5},
        artifact_hashes={"model": artifact_hash({"w": [1, 2, 3]})},
        environment=capture_environment(seed=config.seed),
        code_commit="deadbeef",
    )
    out = record.write(tmp_path / "run.json")
    loaded = load_run_record(out)
    assert loaded["config"]["dataset_id"] == "nexa-ds-abc123"
    assert loaded["reconstruct_command"] == reconstruct_command(config)
    # 명령에 재현 필수 인자가 모두 들어있다.
    cmd = loaded["reconstruct_command"]
    for token in ("--dataset-id nexa-ds-abc123", "--seed 3", "--epochs 40", "--lr 0.05"):
        assert token in cmd
    # 환경·artifact hash 가 봉인됐다.
    assert loaded["environment"]["libraries"]["numpy"] is not None
    assert loaded["artifact_hashes"]["model"]


def test_artifact_hash_changes_with_payload() -> None:
    assert artifact_hash({"a": 1}) != artifact_hash({"a": 2})


# ---- T017 ONNX export ----
def _trained():  # type: ignore[no-untyped-def]
    return train_multihead(_DS, train_idx=_SP.train, val_idx=_SP.validation, epochs=40, seed=3)


def test_export_creates_onnx_with_fixed_opset(tmp_path: Path) -> None:
    res = _trained()
    out = export_policy_onnx(res.model, tmp_path / "policy.onnx")
    assert out.opset == ONNX_OPSET
    assert out.path.exists()
    assert out.output_names == HEAD_OUTPUTS


def test_onnx_parity_within_tolerance(tmp_path: Path) -> None:
    """export 전후 fixture 출력 차이가 허용오차 안이다(acceptance T017)."""
    res = _trained()
    path = tmp_path / "policy.onnx"
    export_policy_onnx(res.model, path)
    x = design_matrix(_DS, _SP.test).astype(np.float32)
    max_abs = verify_parity(res.model, path, x, tol=1e-5)
    assert all(v <= 1e-5 for v in max_abs.values())


def test_onnx_outputs_are_probabilities(tmp_path: Path) -> None:
    res = _trained()
    path = tmp_path / "policy.onnx"
    export_policy_onnx(res.model, path)
    x = design_matrix(_DS, _SP.test[:5]).astype(np.float32)
    outs = run_onnx(path, x)
    for name in HEAD_OUTPUTS:
        assert np.allclose(outs[name].sum(axis=1), 1.0, atol=1e-5)


def test_onnx_dynamic_batch(tmp_path: Path) -> None:
    """batch 축이 동적이라 다른 batch 크기로도 추론된다."""
    res = _trained()
    path = tmp_path / "policy.onnx"
    export_policy_onnx(res.model, path)
    for n in (1, 3, 7):
        x = design_matrix(_DS, _SP.test[:n]).astype(np.float32)
        outs = run_onnx(path, x)
        assert outs["action"].shape[0] == n


def test_onnx_model_validates(tmp_path: Path) -> None:
    """export 된 모델이 onnx.checker 를 통과한다(잘 형성된 그래프)."""
    import onnx

    res = _trained()
    path = tmp_path / "policy.onnx"
    export_policy_onnx(res.model, path)
    model = onnx.load(str(path))
    onnx.checker.check_model(model)
    saved = json.loads(json.dumps({"opset": ONNX_OPSET}))  # 직렬화 가능성 sanity.
    assert saved["opset"] == 17
