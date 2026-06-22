#!/usr/bin/env python3
"""NEXA-P18-T018 actionruntime 부하 테스트 (합성 시뮬레이션).

actionruntime 스케줄러를 **합성**으로 부하한다 — 실제 central-server·실제 DB·실제 Discord 에 접근하지 않는다.
동시 guild/channel 과 예약 action 수를 증가시키며 throughput(처리량)과 DB contention(claim 경합 → 재시도)을
모델링해, **SLO 한계와 scale-out 기준을 수치화**한다(acceptance T018).

모델(단순·결정론적):
- 스케줄러 worker 가 due action 을 claim 한다. claim 은 행 잠금이라 동시 worker 가 같은 due 행을 노리면 경합한다.
- 경합 확률은 (동시 due action 밀도 / worker 수)에 비례한다고 보고, 경합 시 재시도 지연이 latency 에 더해진다.
- throughput = min(worker 처리능력, 도착률), DB contention 으로 유효 처리능력이 감소한다.

출력: 각 부하 수준의 throughput·p95 latency·contention 비율, SLO 위반 임계(scale-out 권고). 문서는
`docs/nexa/experiments/EXP-action-load.md`. 운영 부하 테스트(실 DB)는 별도 staging 에서만.
"""
from __future__ import annotations

import argparse
import json
import sys
from dataclasses import asdict, dataclass

# SLO 한계(slo.md 와 일치) — 합성 모델의 판정 기준.
SLO_P95_LATENCY_MS = 2000  # 예약 action claim→처리 시작 p95 상한.
SLO_MIN_THROUGHPUT_RATIO = 0.9  # 도착률 대비 처리율이 이 밑이면 backlog 누적(scale-out 필요).

# 단일 worker 의 기준 처리능력(actions/sec, 합성). claim+상태전이+audit 1건 비용 가정.
WORKER_BASE_CAPACITY = 50.0


@dataclass(frozen=True)
class LoadLevel:
    guilds: int
    channels_per_guild: int
    scheduled_per_channel: int
    workers: int

    @property
    def total_scheduled(self) -> int:
        return self.guilds * self.channels_per_guild * self.scheduled_per_channel


@dataclass(frozen=True)
class LoadResult:
    guilds: int
    channels: int
    total_scheduled: int
    workers: int
    arrival_rate: float
    contention_ratio: float
    effective_throughput: float
    throughput_ratio: float
    p95_latency_ms: float
    slo_ok: bool
    scale_out_recommended: bool


def simulate(level: LoadLevel) -> LoadResult:
    channels = level.guilds * level.channels_per_guild
    total = level.total_scheduled
    # 도착률: 예약 action 이 due 되는 합성 속도(채널 수에 비례, 채널당 분산 도착).
    arrival_rate = channels * 0.5  # actions/sec (합성).

    # DB contention: 동시 due 밀도가 worker 수 대비 높을수록 claim 경합이 커진다.
    due_density = arrival_rate / max(level.workers, 1)
    contention_ratio = min(0.95, due_density / WORKER_BASE_CAPACITY)

    # 유효 처리능력: worker 총능력에서 contention 으로 깎인다.
    raw_capacity = level.workers * WORKER_BASE_CAPACITY
    effective_throughput = raw_capacity * (1.0 - contention_ratio)
    throughput_ratio = (
        min(1.0, effective_throughput / arrival_rate) if arrival_rate > 0 else 1.0
    )

    # p95 latency: 기본 처리시간 + 경합 재시도 지연 + backlog 대기(도착>처리면 급증).
    base_ms = 20.0
    retry_ms = contention_ratio * 800.0
    backlog_ms = 0.0 if effective_throughput >= arrival_rate else (arrival_rate - effective_throughput) * 60.0
    p95 = base_ms + retry_ms + backlog_ms

    slo_ok = p95 <= SLO_P95_LATENCY_MS and throughput_ratio >= SLO_MIN_THROUGHPUT_RATIO
    scale_out = (not slo_ok) or contention_ratio >= 0.5

    return LoadResult(
        guilds=level.guilds,
        channels=channels,
        total_scheduled=total,
        workers=level.workers,
        arrival_rate=round(arrival_rate, 2),
        contention_ratio=round(contention_ratio, 3),
        effective_throughput=round(effective_throughput, 1),
        throughput_ratio=round(throughput_ratio, 3),
        p95_latency_ms=round(p95, 1),
        slo_ok=slo_ok,
        scale_out_recommended=scale_out,
    )


def default_sweep() -> list[LoadLevel]:
    """동시 guild/channel·예약 action·worker 를 증가시키는 부하 sweep."""
    levels = []
    for guilds in (1, 10, 50, 200, 1000):
        for workers in (1, 4, 16):
            levels.append(
                LoadLevel(
                    guilds=guilds,
                    channels_per_guild=5,
                    scheduled_per_channel=20,
                    workers=workers,
                )
            )
    return levels


def find_scale_out_threshold(results: list[LoadResult]) -> dict[str, object]:
    """worker 수별로 SLO 를 처음 위반하는 guild 규모(scale-out 임계)를 찾는다."""
    thresholds: dict[int, int | None] = {}
    for r in results:
        if not r.slo_ok and thresholds.get(r.workers) is None:
            thresholds[r.workers] = r.guilds
    return {f"workers_{w}": g for w, g in sorted(thresholds.items())}


def main() -> int:
    parser = argparse.ArgumentParser(description="NEXA actionruntime 합성 부하 시뮬레이션 (T018)")
    parser.add_argument("--json", action="store_true", help="결과를 JSON 으로 출력")
    args = parser.parse_args()

    results = [simulate(lvl) for lvl in default_sweep()]
    thresholds = find_scale_out_threshold(results)

    if args.json:
        print(json.dumps({"results": [asdict(r) for r in results], "scale_out_threshold": thresholds}, indent=2))
        return 0

    print("NEXA actionruntime 합성 부하 (T018) — 실제 DB/Discord 미접근")
    print(f"SLO: p95 ≤ {SLO_P95_LATENCY_MS}ms, throughput ratio ≥ {SLO_MIN_THROUGHPUT_RATIO}")
    print(f"{'guilds':>7} {'chans':>6} {'workers':>7} {'arr/s':>7} {'cont':>6} {'thru/s':>8} {'ratio':>6} {'p95ms':>8} {'SLO':>4} {'scale':>6}")
    for r in results:
        print(
            f"{r.guilds:>7} {r.channels:>6} {r.workers:>7} {r.arrival_rate:>7} "
            f"{r.contention_ratio:>6} {r.effective_throughput:>8} {r.throughput_ratio:>6} "
            f"{r.p95_latency_ms:>8} {'ok' if r.slo_ok else 'X':>4} {'yes' if r.scale_out_recommended else '-':>6}"
        )
    print("\nscale-out 임계(worker 수별 SLO 첫 위반 guild 규모):")
    print(json.dumps(thresholds, indent=2))
    return 0


if __name__ == "__main__":
    sys.exit(main())
