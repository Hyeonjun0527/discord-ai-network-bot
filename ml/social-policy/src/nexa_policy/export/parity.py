"""Python-JVM golden parity fixture 생성기(NEXA-P11-T019).

같은 ONNX 모델·고정 입력에 대한 **각 head 출력**을 작은 golden JSON 으로 봉인한다. central(JVM
onnxruntime adapter, T018)이 같은 ONNX 모델을 같은 입력으로 추론해 이 golden 과 허용오차 내 일치하는지
검증한다 — 즉 Python 추론과 JVM 추론이 같은 결정을 낸다(언어 경계 parity).

**acceptance(T019) — 수치 허용오차와 category ordering 이 명시된다**:
- [PARITY_TOLERANCE] 가 허용오차(절대오차)를 명시한다.
- [HEAD_CATEGORY_ORDER] 가 각 head 의 category 순서(인덱스→안정 코드)를 명시한다 — JVM 이 같은 순서로
  분포를 복원하도록 SSOT 를 고정한다(순서가 어긋나면 분포가 뒤섞인다).

산출물(작은 텍스트/소형 바이너리만 — 운영 모델 artifact 아님):
- `<fixtures>/parity/policy-v1-fixture.onnx`(학습된 소형 모델, ~8KB)
- `<fixtures>/parity/policy-v1-parity.golden.json`(입력 행렬 + head 별 golden 확률 + 메타)
"""

from __future__ import annotations

import json
from dataclasses import dataclass
from pathlib import Path
from typing import Any

from nexa_policy.datasets import (
    ACTION_HEAD_CLASSES,
    BURST_COUNT_BUCKETS,
    DELAY_BINS,
    N_TARGET_CANDIDATES,
    SOCIAL_ACT_CLASSES,
    make_synthetic_dataset,
)
from nexa_policy.export.onnx import (
    HEAD_OUTPUTS,
    ONNX_OPSET,
    export_policy_onnx,
    python_reference,
)
from nexa_policy.training.splitting import make_split_indices
from nexa_policy.training.trainer import design_matrix, train_multihead

# parity fixture 의 결정론 구성(같은 값이면 같은 모델·같은 golden). central 과 공유하는 고정 상수.
FIXTURE_MODEL_VERSION = "policy-v1-fixture"
DATASET_SEED = 7
DATASET_GUILDS = 12
SPLIT_SEED = 0
TRAIN_SEED = 3
TRAIN_EPOCHS = 40
# golden 에 봉인할 입력 행 수(test split 앞에서). 작게 둔다(파일 크기·검증 충분).
GOLDEN_ROWS = 4

# JVM 이 같은 절대오차로 비교하도록 명시(acceptance T019). ONNX export parity(1e-5)보다 느슨하게 잡아
# 플랫폼별 부동소수 미세 차이를 흡수하되, 결정이 뒤바뀌지 않을 만큼 타이트하다.
PARITY_TOLERANCE = 1e-4

# 각 head 의 category 순서(인덱스 i → 안정 코드). JVM 이 같은 순서로 분포를 복원하게 하는 SSOT(acceptance T019).
HEAD_CATEGORY_ORDER: dict[str, tuple[str, ...]] = {
    "action": ACTION_HEAD_CLASSES,
    "target": tuple(f"candidate-{i}" for i in range(N_TARGET_CANDIDATES)),
    "delay": DELAY_BINS,
    "burst": BURST_COUNT_BUCKETS,
    "act": SOCIAL_ACT_CLASSES,
}


@dataclass(frozen=True)
class ParityFixture:
    """parity golden artifact 경로 묶음."""

    onnx_path: Path
    golden_path: Path

    def to_dict(self) -> dict[str, str]:
        return {"onnx_path": str(self.onnx_path), "golden_path": str(self.golden_path)}


def build_fixture_inputs() -> tuple[Any, Any]:
    """결정론 학습 모델과 golden 입력 행렬(float32)을 만든다. 같은 상수 → 같은 모델·입력."""
    ds = make_synthetic_dataset(seed=DATASET_SEED, n_guilds=DATASET_GUILDS)
    sp = make_split_indices(ds, seed=SPLIT_SEED)
    result = train_multihead(
        ds,
        train_idx=sp.train,
        val_idx=sp.validation,
        epochs=TRAIN_EPOCHS,
        seed=TRAIN_SEED,
    )
    import numpy as np

    x = design_matrix(ds, sp.test[:GOLDEN_ROWS]).astype(np.float32)
    return result.model, x


def golden_payload(model: Any, x: Any) -> dict[str, Any]:
    """입력 + head 별 golden 확률 + 메타를 직렬화 가능한 dict 로 만든다."""
    ref = python_reference(model, x)
    return {
        "modelVersion": FIXTURE_MODEL_VERSION,
        "opset": ONNX_OPSET,
        "inputName": "features",
        "inputDim": int(model.in_dim),
        "tolerance": PARITY_TOLERANCE,
        "categoryOrder": {k: list(v) for k, v in HEAD_CATEGORY_ORDER.items()},
        "inputs": [[float(v) for v in row] for row in x.tolist()],
        "heads": {name: [[float(p) for p in row] for row in ref[name].tolist()] for name in HEAD_OUTPUTS},
    }


def write_fixture(fixtures_dir: Path) -> ParityFixture:
    """parity 디렉터리에 ONNX 모델과 golden JSON 을 쓴다(결정론). 반환은 두 경로."""
    parity_dir = fixtures_dir / "parity"
    parity_dir.mkdir(parents=True, exist_ok=True)
    onnx_path = parity_dir / f"{FIXTURE_MODEL_VERSION}.onnx"
    golden_path = parity_dir / "policy-v1-parity.golden.json"

    model, x = build_fixture_inputs()
    export_policy_onnx(model, onnx_path)
    payload = golden_payload(model, x)
    golden_path.write_text(
        json.dumps(payload, indent=2, ensure_ascii=False, sort_keys=True) + "\n",
        encoding="utf-8",
    )
    return ParityFixture(onnx_path=onnx_path, golden_path=golden_path)
