"""numpy 신경망 프리미티브(P11). torch 비의존 — 결정론·경량·완전 타입.

소형 정책 모델(MLP·멀티헤드·temporal encoder)을 numpy 로 직접 구현한다. 결정론 init(rng seed),
수동 forward/backward, 초 단위 학습(작은 fixture·소수 epoch). 무거운 학습 금지.

핵심:
- [Linear]: dense layer(He init), forward + backward(grad 누적).
- [relu]/[softmax]/[sigmoid] 와 backward 보조.
- ONNX export(T017)는 [Linear] 의 W/b 를 그대로 그래프 가중치로 쓴다(파이썬 추론과 일치).
"""

from __future__ import annotations

from typing import TYPE_CHECKING

from nexa_policy.reproducibility import require_numpy

if TYPE_CHECKING:
    import numpy as np


def relu(x: np.ndarray) -> np.ndarray:
    import numpy as np

    return np.maximum(x, 0.0)


def softmax(x: np.ndarray) -> np.ndarray:
    import numpy as np

    z = x - x.max(axis=-1, keepdims=True)
    e = np.exp(z)
    return e / e.sum(axis=-1, keepdims=True)


def sigmoid(x: np.ndarray) -> np.ndarray:
    import numpy as np

    return 1.0 / (1.0 + np.exp(-x))


class Linear:
    """dense layer y = x @ W + b. He 초기화(결정론 rng), 수동 grad.

    plain 클래스(dataclass 아님) — W/b 는 [init] 에서 항상 채워지는 ndarray 라 Optional 회피.
    """

    def __init__(self, in_dim: int, out_dim: int) -> None:
        np = require_numpy()
        self.in_dim = in_dim
        self.out_dim = out_dim
        self.W = np.zeros((in_dim, out_dim), dtype=np.float64)
        self.b = np.zeros(out_dim, dtype=np.float64)
        self.gW = np.zeros_like(self.W)
        self.gb = np.zeros_like(self.b)
        self._x = np.zeros((0, in_dim), dtype=np.float64)

    def init(self, seed: int) -> None:
        np = require_numpy()
        gen = np.random.default_rng(seed)
        scale = (2.0 / self.in_dim) ** 0.5
        self.W = (gen.standard_normal((self.in_dim, self.out_dim)) * scale).astype(np.float64)
        self.b = np.zeros(self.out_dim, dtype=np.float64)
        self.zero_grad()

    def zero_grad(self) -> None:
        np = require_numpy()
        self.gW = np.zeros_like(self.W)
        self.gb = np.zeros_like(self.b)

    def forward(self, x: np.ndarray) -> np.ndarray:
        self._x = x
        return x @ self.W + self.b

    def backward(self, grad_out: np.ndarray) -> np.ndarray:
        """grad_out: dL/dy. grad 누적 후 dL/dx 반환."""
        self.gW += self._x.T @ grad_out
        self.gb += grad_out.sum(axis=0)
        return grad_out @ self.W.T

    def step(self, lr: float) -> None:
        self.W -= lr * self.gW
        self.b -= lr * self.gb


def relu_backward(grad_out: np.ndarray, pre_activation: np.ndarray) -> np.ndarray:
    import numpy as np

    return grad_out * (pre_activation > 0).astype(np.float64)


def softmax_cross_entropy_grad(
    probs: np.ndarray, labels: np.ndarray, sample_weight: np.ndarray
) -> tuple[float, np.ndarray]:
    """softmax 확률·정수 라벨에서 (가중 평균 loss, dL/dlogits).

    sample_weight 0 인 샘플은 loss·grad 에 기여하지 않는다(mask). labels<0(없음)도 weight 0 가정.
    """
    import numpy as np

    n = probs.shape[0]
    eps = 1e-12
    onehot = np.zeros_like(probs)
    valid = labels >= 0
    onehot[np.arange(n)[valid], labels[valid]] = 1.0
    w = sample_weight.reshape(-1, 1)
    total_w = float(sample_weight.sum())
    if total_w <= 0:
        return 0.0, np.zeros_like(probs)
    log_probs = np.log(np.clip(probs, eps, 1.0))
    loss = -float((onehot * log_probs * w).sum()) / total_w
    grad = (probs - onehot) * w / total_w
    return loss, grad
