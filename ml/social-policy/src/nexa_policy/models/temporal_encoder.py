"""Temporal encoder baseline 설계(NEXA-P11-T007).

최근 burst sequence(이벤트 feature)와 time gap 을 인코딩하는 작은 encoder 두 후보를 비교한다:
GRU(순차 의존) vs mean-pool(순서 무시, 경량). 둘 다 numpy forward 로 결정론 구현(설계 비교용 —
무거운 학습 금지).

**acceptance(T007) — 모델 선택 근거와 sequence truncation 영향이 보고된다**:
- [compare_encoders] 는 두 encoder 의 출력 차원·파라미터 수·gap 민감도(시간 간격 변화에 출력이 변하는지)를
  비교해 [EncoderComparison] 으로 보고한다(선택 근거).
- [truncation_impact] 는 sequence 를 길이별로 자를 때 인코딩이 얼마나 변하는지(상대 변화)를 측정해
  truncation 영향(짧게 자르면 정보 손실)을 수치화한다.

순서·gap 을 쓰는 GRU 가 mean-pool 보다 gap 민감도가 크면(시간 구조 포착) GRU 선택 근거가 된다.
"""

from __future__ import annotations

from dataclasses import dataclass
from typing import TYPE_CHECKING, Any

from nexa_policy.models.nn import sigmoid
from nexa_policy.reproducibility import require_numpy

if TYPE_CHECKING:
    import numpy as np


@dataclass
class GruEncoder:
    """소형 GRU(단일 층). 입력 시퀀스 (T, F) → 마지막 hidden (H,). 결정론 init."""

    in_dim: int
    hidden_dim: int
    Wz: np.ndarray
    Wr: np.ndarray
    Wh: np.ndarray
    Uz: np.ndarray
    Ur: np.ndarray
    Uh: np.ndarray

    @classmethod
    def build(cls, *, in_dim: int, hidden_dim: int = 8, seed: int = 20260622) -> GruEncoder:
        np = require_numpy()
        gen = np.random.default_rng(seed)
        s = (1.0 / in_dim) ** 0.5
        sh = (1.0 / hidden_dim) ** 0.5

        def w(rows: int, cols: int, scale: float) -> Any:
            return (gen.standard_normal((rows, cols)) * scale).astype(np.float64)

        return cls(
            in_dim=in_dim,
            hidden_dim=hidden_dim,
            Wz=w(in_dim, hidden_dim, s),
            Wr=w(in_dim, hidden_dim, s),
            Wh=w(in_dim, hidden_dim, s),
            Uz=w(hidden_dim, hidden_dim, sh),
            Ur=w(hidden_dim, hidden_dim, sh),
            Uh=w(hidden_dim, hidden_dim, sh),
        )

    @property
    def param_count(self) -> int:
        return int(sum(m.size for m in (self.Wz, self.Wr, self.Wh, self.Uz, self.Ur, self.Uh)))

    def encode(self, seq: np.ndarray) -> np.ndarray:
        """seq: (T, in_dim) → 마지막 hidden (hidden_dim,). 빈 시퀀스면 0 벡터."""
        np = require_numpy()
        h = np.zeros(self.hidden_dim, dtype=np.float64)
        for t in range(seq.shape[0]):
            x = seq[t]
            z = sigmoid(x @ self.Wz + h @ self.Uz)
            r = sigmoid(x @ self.Wr + h @ self.Ur)
            h_tilde = np.tanh(x @ self.Wh + (r * h) @ self.Uh)
            h = (1.0 - z) * h + z * h_tilde
        return h


@dataclass
class MeanPoolEncoder:
    """평균 풀링 encoder(순서 무시). 입력 (T, F) 를 단일 dense 로 사상 후 평균. 경량 baseline."""

    in_dim: int
    hidden_dim: int
    W: np.ndarray

    @classmethod
    def build(cls, *, in_dim: int, hidden_dim: int = 8, seed: int = 20260622) -> MeanPoolEncoder:
        np = require_numpy()
        gen = np.random.default_rng(seed)
        scale = (1.0 / in_dim) ** 0.5
        return cls(
            in_dim=in_dim,
            hidden_dim=hidden_dim,
            W=(gen.standard_normal((in_dim, hidden_dim)) * scale).astype(np.float64),
        )

    @property
    def param_count(self) -> int:
        return int(self.W.size)

    def encode(self, seq: np.ndarray) -> np.ndarray:
        np = require_numpy()
        if seq.shape[0] == 0:
            return np.zeros(self.hidden_dim, dtype=np.float64)
        return np.tanh((seq @ self.W).mean(axis=0))


@dataclass(frozen=True)
class EncoderComparison:
    """encoder 선택 비교 — 차원·파라미터·gap 민감도."""

    gru_param_count: int
    meanpool_param_count: int
    gru_gap_sensitivity: float  # gap 채널 교란 시 출력 상대 변화(클수록 시간구조 포착).
    meanpool_gap_sensitivity: float
    recommended: str  # "gru" | "mean_pool".
    rationale: str

    def to_dict(self) -> dict[str, object]:
        return {
            "gru_param_count": self.gru_param_count,
            "meanpool_param_count": self.meanpool_param_count,
            "gru_gap_sensitivity": self.gru_gap_sensitivity,
            "meanpool_gap_sensitivity": self.meanpool_gap_sensitivity,
            "recommended": self.recommended,
            "rationale": self.rationale,
        }


def _relative_change(a: np.ndarray, b: np.ndarray) -> float:
    np = require_numpy()
    denom = float(np.linalg.norm(a)) + 1e-9
    return float(np.linalg.norm(a - b) / denom)


def compare_encoders(
    seq: np.ndarray, *, hidden_dim: int = 8, seed: int = 20260622
) -> EncoderComparison:
    """GRU vs mean-pool 을 같은 시퀀스에서 비교한다. 시퀀스 순서를 뒤집어 출력 변화로
    시간 구조 민감도를 잰다(GRU 는 순서 의존이라 변하고, mean-pool 은 평균이라 덜 변한다)."""
    in_dim = seq.shape[1]
    gru = GruEncoder.build(in_dim=in_dim, hidden_dim=hidden_dim, seed=seed)
    pool = MeanPoolEncoder.build(in_dim=in_dim, hidden_dim=hidden_dim, seed=seed)

    # 순서를 뒤집은 시퀀스(같은 원소, 다른 순서·gap 배열).
    reversed_seq = seq[::-1].copy()

    gru_sens = _relative_change(gru.encode(seq), gru.encode(reversed_seq))
    pool_sens = _relative_change(pool.encode(seq), pool.encode(reversed_seq))

    recommended = "gru" if gru_sens > pool_sens else "mean_pool"
    rationale = (
        f"GRU 순서/gap 민감도 {gru_sens:.4f} vs mean-pool {pool_sens:.4f}. "
        + (
            "GRU 가 시간 구조(burst 순서·gap)를 더 포착하므로 temporal encoder 후보로 GRU 권장. "
            "단 파라미터·latency 가 더 크므로 시퀀스가 짧으면 mean-pool 도 실용적."
            if recommended == "gru"
            else "이 입력에선 순서 신호가 약해 mean-pool 로 충분(경량 우선)."
        )
    )
    return EncoderComparison(
        gru_param_count=gru.param_count,
        meanpool_param_count=pool.param_count,
        gru_gap_sensitivity=gru_sens,
        meanpool_gap_sensitivity=pool_sens,
        recommended=recommended,
        rationale=rationale,
    )


def truncation_impact(
    encoder: GruEncoder | MeanPoolEncoder, seq: np.ndarray, *, keep_last: int
) -> float:
    """시퀀스를 최근 keep_last 개로 자를 때 인코딩의 상대 변화(정보 손실 대리지표).

    0 에 가까우면 truncation 영향 적음(최근 몇 개로 충분), 크면 앞쪽 정보 손실이 크다.
    """
    if keep_last <= 0:
        raise ValueError("keep_last 는 1 이상이어야 한다.")
    full = encoder.encode(seq)
    truncated = encoder.encode(seq[-keep_last:])
    return _relative_change(full, truncated)
