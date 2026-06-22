"""정책 추론 latency benchmark(NEXA-P12-T017).

정책 판단(hazard/action forward)의 wall-clock latency 를 batch size 별로 측정해 p50/p95/p99 를 보고한다.
"GPU/Python" 과 "CPU ONNX" 는 같은 forward 콜러블을 다른 라벨로 측정하는 형태로 둔다(이 PoC 는 numpy
forward 만 — 실제 ONNX runtime 기동은 central 책임, torch 비의존).

**acceptance(T017) — 정책 판단이 GLM 호출보다 먼저 충분히 빠르게 완료되는 목표가 정해진다**:
- [LatencyBudget]: GLM 호출 예산(예: 800ms p95) 대비 정책이 가져도 되는 비율([policy_fraction])로 목표
  p95 를 정한다. [LatencyStats.within_budget] 가 측정 p95 가 목표 안인지 판정한다.
- [benchmark]: 콜러블을 [warmup]·[runs] 회 호출해 percentile 을 낸다(결정론 측정 — 시계만 비결정, 통계는 정렬).

torch 비의존. 운영 데이터 금지(합성 feature). benchmark 자체는 시간 측정이라 절댓값은 환경 의존 — 핵심은
percentile 산출과 budget gate 로직이 결정론으로 검증되는 점이다.
"""

from __future__ import annotations

import time
from collections.abc import Callable
from dataclasses import dataclass
from typing import TYPE_CHECKING

if TYPE_CHECKING:
    import numpy as np


@dataclass(frozen=True)
class LatencyBudget:
    """정책 latency 목표 — GLM 호출 예산에서 정책이 가져도 되는 몫.

    정책 판단은 GLM 텍스트 생성 호출보다 **먼저** 끝나야 한다(participation 이 "말할지" 를 GLM 호출 전에
    결정). 따라서 정책 p95 목표 = GLM p95 예산 × [policy_fraction](작게).
    """

    glm_call_p95_ms: float = 800.0
    policy_fraction: float = 0.1

    @property
    def policy_p95_target_ms(self) -> float:
        """정책 추론 p95 목표(ms). GLM 예산의 일부 — 이 안에서 끝나야 호출 전 결정이 끊김없다."""
        return self.glm_call_p95_ms * self.policy_fraction


@dataclass(frozen=True)
class LatencyStats:
    """단일 (라벨, batch_size) 측정 결과 percentile(ms)."""

    label: str
    batch_size: int
    runs: int
    p50_ms: float
    p95_ms: float
    p99_ms: float

    def within_budget(self, budget: LatencyBudget) -> bool:
        """측정 p95 가 정책 목표 안인가 — 정책이 GLM 호출보다 먼저 충분히 빠른가."""
        return self.p95_ms <= budget.policy_p95_target_ms

    def to_dict(self) -> dict[str, object]:
        return {
            "label": self.label,
            "batch_size": self.batch_size,
            "runs": self.runs,
            "p50_ms": self.p50_ms,
            "p95_ms": self.p95_ms,
            "p99_ms": self.p99_ms,
        }


def _percentile(sorted_ms: list[float], q: float) -> float:
    """이미 정렬된 ms 리스트의 분위(nearest-rank, 결정론)."""
    if not sorted_ms:
        return 0.0
    idx = min(len(sorted_ms) - 1, max(0, round(q * (len(sorted_ms) - 1))))
    return sorted_ms[idx]


def benchmark(
    forward: Callable[[np.ndarray], object],
    make_batch: Callable[[int], np.ndarray],
    *,
    label: str,
    batch_size: int,
    runs: int = 50,
    warmup: int = 5,
    clock: Callable[[], float] = time.perf_counter,
) -> LatencyStats:
    """[forward] 를 batch_size 입력으로 [runs] 회 호출해 latency percentile 을 측정한다.

    [warmup] 회는 통계에서 제외(JIT/캐시 워밍업). [clock] 은 주입 가능(테스트는 결정론 가짜 시계 주입).
    """
    batch = make_batch(batch_size)
    for _ in range(warmup):
        forward(batch)
    samples_ms: list[float] = []
    for _ in range(runs):
        start = clock()
        forward(batch)
        samples_ms.append((clock() - start) * 1000.0)
    samples_ms.sort()
    return LatencyStats(
        label=label,
        batch_size=batch_size,
        runs=runs,
        p50_ms=_percentile(samples_ms, 0.50),
        p95_ms=_percentile(samples_ms, 0.95),
        p99_ms=_percentile(samples_ms, 0.99),
    )


def benchmark_sweep(
    forward: Callable[[np.ndarray], object],
    make_batch: Callable[[int], np.ndarray],
    *,
    label: str,
    batch_sizes: tuple[int, ...] = (1, 8, 32),
    runs: int = 50,
    clock: Callable[[], float] = time.perf_counter,
) -> list[LatencyStats]:
    """여러 batch size 에서 latency 를 측정한다(batch 별 p50/p95/p99 sweep)."""
    return [
        benchmark(
            forward, make_batch, label=label, batch_size=bs, runs=runs, clock=clock
        )
        for bs in batch_sizes
    ]
