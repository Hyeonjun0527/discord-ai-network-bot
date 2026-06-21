"""정책 모델 ONNX export(NEXA-P11-T017).

학습된 [MultiHeadPolicyModel] 의 numpy 가중치를 ONNX 그래프로 내보낸다. opset 과 dynamic axes
(batch 만 동적, feature/클래스 차원 고정)를 고정해 다음 단계(T018 JVM parity)의 기반을 만든다.

**acceptance(T017) — export 전후 fixture 출력 차이가 허용오차 안이다**:
- [export_policy_onnx] 는 trunk(MatMul→Add→Relu ×2)와 5개 head(MatMul→Add→Softmax)를 그대로
  그래프화한다(파이썬 forward 와 같은 연산·가중치).
- [verify_parity] 는 같은 입력에서 파이썬 [MultiHeadPolicyModel] 출력과 onnxruntime 출력을 비교해
  최대 절대 오차가 tol 이하임을 단언한다(없으면 fail-closed).

opset 17 고정, 입력 'features' (batch, in_dim) — batch 만 동적.
"""

from __future__ import annotations

from dataclasses import dataclass
from pathlib import Path
from typing import TYPE_CHECKING, Any

from nexa_policy.models.heads import MultiHeadPolicyModel

if TYPE_CHECKING:
    import numpy as np

ONNX_OPSET = 17
INPUT_NAME = "features"
HEAD_OUTPUTS = ("action", "target", "delay", "burst", "act")


@dataclass(frozen=True)
class ExportResult:
    path: Path
    opset: int
    input_name: str
    input_dim: int
    output_names: tuple[str, ...]

    def to_dict(self) -> dict[str, object]:
        return {
            "path": str(self.path),
            "opset": self.opset,
            "input_name": self.input_name,
            "input_dim": self.input_dim,
            "output_names": list(self.output_names),
        }


def _linear_nodes(
    prefix: str, in_name: str, W: np.ndarray, b: np.ndarray
) -> tuple[list[Any], list[Any], str]:
    """MatMul + Add 노드와 initializer(W,b)를 만들고 출력 이름을 반환한다."""
    from onnx import helper as h
    from onnx import numpy_helper

    w_name, b_name = f"{prefix}_W", f"{prefix}_b"
    mm_out, add_out = f"{prefix}_mm", f"{prefix}_out"
    inits = [
        numpy_helper.from_array(W.astype("float32"), name=w_name),
        numpy_helper.from_array(b.astype("float32"), name=b_name),
    ]
    nodes = [
        h.make_node("MatMul", [in_name, w_name], [mm_out], name=f"{prefix}_matmul"),
        h.make_node("Add", [mm_out, b_name], [add_out], name=f"{prefix}_add"),
    ]
    return nodes, inits, add_out


def export_policy_onnx(model: MultiHeadPolicyModel, path: Path) -> ExportResult:
    """멀티헤드 모델을 ONNX 로 내보낸다(opset 고정, batch 동적)."""
    import onnx
    from onnx import TensorProto
    from onnx import helper as h

    nodes: list[Any] = []
    inits: list[Any] = []

    # trunk: Linear -> Relu -> Linear -> Relu.
    t1_nodes, t1_inits, t1_out = _linear_nodes("trunk1", INPUT_NAME, model.trunk.W, model.trunk.b)
    nodes += t1_nodes
    inits += t1_inits
    relu1 = "trunk1_relu"
    nodes.append(h.make_node("Relu", [t1_out], [relu1], name="trunk1_relu_op"))

    t2_nodes, t2_inits, t2_out = _linear_nodes("trunk2", relu1, model.trunk2.W, model.trunk2.b)
    nodes += t2_nodes
    inits += t2_inits
    relu2 = "trunk2_relu"
    nodes.append(h.make_node("Relu", [t2_out], [relu2], name="trunk2_relu_op"))

    # heads: Linear -> Softmax(axis=-1).
    head_layers = {
        "action": model.action_head,
        "target": model.target_head,
        "delay": model.delay_head,
        "burst": model.burst_head,
        "act": model.act_head,
    }
    outputs: list[Any] = []
    for name in HEAD_OUTPUTS:
        layer = head_layers[name]
        hn, hi, h_out = _linear_nodes(f"head_{name}", relu2, layer.W, layer.b)
        nodes += hn
        inits += hi
        nodes.append(h.make_node("Softmax", [h_out], [name], axis=-1, name=f"head_{name}_softmax"))
        outputs.append(
            h.make_tensor_value_info(name, TensorProto.FLOAT, ["batch", layer.out_dim])
        )

    input_vi = h.make_tensor_value_info(
        INPUT_NAME, TensorProto.FLOAT, ["batch", model.in_dim]
    )
    graph = h.make_graph(nodes, "nexa_policy_multihead", [input_vi], outputs, initializer=inits)
    opset = h.make_opsetid("", ONNX_OPSET)
    onnx_model = h.make_model(graph, opset_imports=[opset], ir_version=9)
    onnx.checker.check_model(onnx_model)

    path.parent.mkdir(parents=True, exist_ok=True)
    onnx.save(onnx_model, str(path))
    return ExportResult(
        path=path,
        opset=ONNX_OPSET,
        input_name=INPUT_NAME,
        input_dim=model.in_dim,
        output_names=HEAD_OUTPUTS,
    )


def run_onnx(path: Path, features: np.ndarray) -> dict[str, np.ndarray]:
    """onnxruntime 으로 ONNX 모델을 추론한다. head 이름 → 확률 행렬."""
    import numpy as np
    import onnxruntime as ort

    sess = ort.InferenceSession(str(path), providers=["CPUExecutionProvider"])
    outs = sess.run(list(HEAD_OUTPUTS), {INPUT_NAME: features.astype(np.float32)})
    return dict(zip(HEAD_OUTPUTS, outs, strict=True))


def python_reference(model: MultiHeadPolicyModel, features: np.ndarray) -> dict[str, np.ndarray]:
    """파이썬 forward 의 head 확률(softmax) — ONNX 비교 기준."""
    return {
        "action": model.action_proba(features),
        "target": _softmax_scores(model, features),
        "delay": model.delay_proba(features),
        "burst": model.burst_proba(features),
        "act": model.act_proba(features),
    }


def _softmax_scores(model: MultiHeadPolicyModel, features: np.ndarray) -> np.ndarray:
    """target head 는 candidate mask 없이 raw softmax(ONNX 그래프와 동일 — mask 는 후처리)."""
    from nexa_policy.models.nn import softmax

    _, _, h2 = model.forward_trunk(features)
    return softmax(model.target_head.forward(h2))


def verify_parity(
    model: MultiHeadPolicyModel, path: Path, features: np.ndarray, *, tol: float = 1e-5
) -> dict[str, float]:
    """파이썬 추론과 ONNX 추론의 head 별 최대 절대 오차를 잰다. tol 초과면 fail-closed."""
    import numpy as np

    ref = python_reference(model, features)
    got = run_onnx(path, features)
    max_abs: dict[str, float] = {}
    for name in HEAD_OUTPUTS:
        diff = float(np.max(np.abs(ref[name] - got[name])))
        max_abs[name] = diff
        if diff > tol:
            raise AssertionError(
                f"ONNX parity 실패: head {name!r} 최대오차 {diff} > tol {tol}."
            )
    return max_abs
