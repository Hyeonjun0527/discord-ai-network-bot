#!/usr/bin/env python3
"""NEXA-P18-T019 Discord rate-limit 부하 테스트 (합성 시뮬레이션).

Discord 전송 executor 를 **합성**으로 부하한다 — 실제 JDA·실제 Discord API 를 호출하지 않는다. fake JDA rate
limit 과 `retry-after` 를 주입해(deliverable T019), rate limit 이 걸렸을 때 큐가 **오래된 메시지를 폭발적으로
전송하지 않는지**(acceptance T019)를 검증한다.

위협: rate limit 으로 전송이 막히면 큐에 메시지가 쌓인다. limit 이 풀리는 순간 쌓인 걸 **한꺼번에 쏟으면**
(burst flush) NEXA 가 갑자기 도배한다 — 게다가 오래된(stale) 메시지는 맥락이 지나 부적절하다. 안전 규칙:
  1) retry-after 를 존중해 그 전에는 보내지 않는다(rate limit 준수).
  2) limit 해제 후에도 **token-bucket** 으로 초당 전송을 평탄화한다(burst flush 금지).
  3) **stale drop**: due 후 staleness 한도(초)를 넘긴 메시지는 보내지 않고 폐기한다(맥락 지난 발화 금지).

출력: 주입 시나리오별 보낸/지연/폐기 수와 최대 순간 전송률(burst 검출). 문서는
`docs/nexa/experiments/EXP-discord-rate-limit.md`.
"""
from __future__ import annotations

import argparse
import json
import sys
from dataclasses import asdict, dataclass

# 안전 파라미터(합성). 운영 값은 slo.md/canary-plan.md 와 맞춘다.
SEND_RATE_PER_SEC = 5.0  # token-bucket 평탄화: 초당 최대 전송(burst flush 방지).
# bucket 용량은 1 token 으로 둔다 — 누적 token 으로 rate limit 해제 순간 묵힌 토큰을 한꺼번에 쏟지(burst flush)
# 않게 한다. 용량을 SEND_RATE 만큼 두면 해제 직후 그만큼 즉시 방출되어 burst 가 된다(안전 설계: 진짜 평탄화).
BUCKET_CAPACITY = 1.0
STALE_LIMIT_SEC = 30.0  # due 후 이 시간을 넘기면 stale 로 폐기(맥락 지난 발화 금지).
MAX_BURST_PER_SEC = 6  # 이보다 큰 순간 전송률이 관측되면 burst flush 위반.


@dataclass(frozen=True)
class QueuedMessage:
    due_at: float  # 메시지가 보내질 due 시각(초).


@dataclass(frozen=True)
class RateLimitWindow:
    start: float  # rate limit 시작 시각(초).
    retry_after: float  # 이 시간(초) 동안 전송 금지.


@dataclass
class ExecutorResult:
    scenario: str
    enqueued: int
    sent: int
    dropped_stale: int
    max_per_sec: int
    burst_violation: bool
    respected_retry_after: bool


def run_scenario(
    name: str,
    messages: list[QueuedMessage],
    limits: list[RateLimitWindow],
    horizon: float = 120.0,
    tick: float = 0.2,
) -> ExecutorResult:
    """token-bucket + retry-after + stale-drop 으로 전송을 합성한다."""
    pending = sorted(messages, key=lambda m: m.due_at)
    idx = 0
    tokens = 0.0
    sent = 0
    dropped = 0
    per_sec_counts: dict[int, int] = {}
    respected = True

    t = 0.0
    while t <= horizon and idx < len(pending):
        # token 충전(평탄화). bucket 용량을 작게 둬 해제 순간 burst flush 를 막는다.
        tokens = min(BUCKET_CAPACITY, tokens + SEND_RATE_PER_SEC * tick)

        # 현재 rate limit 이 걸려 있나?
        limited = any(w.start <= t < w.start + w.retry_after for w in limits)

        # due 도래한 메시지를 본다.
        while idx < len(pending) and pending[idx].due_at <= t:
            msg = pending[idx]
            age = t - msg.due_at
            if age > STALE_LIMIT_SEC:
                # stale: 맥락 지남 → 폐기(절대 안 보냄).
                dropped += 1
                idx += 1
                continue
            if limited:
                # rate limit 중: 보내지 않고 다음 tick 으로(retry-after 존중). break 로 대기.
                break
            if tokens < 1.0:
                # token 부족: 평탄화 대기.
                break
            # 전송.
            tokens -= 1.0
            sent += 1
            sec = int(t)
            per_sec_counts[sec] = per_sec_counts.get(sec, 0) + 1
            idx += 1

        t += tick

    # horizon 안에 못 보낸 due 메시지는 stale 검사 후 폐기로 간주(폭발 flush 안 함).
    while idx < len(pending):
        dropped += 1
        idx += 1

    max_per_sec = max(per_sec_counts.values(), default=0)
    burst_violation = max_per_sec > MAX_BURST_PER_SEC
    # rate limit 창 동안 보낸 게 있으면 retry-after 위반(평탄화 모델이라 발생하면 안 됨).
    for sec, cnt in per_sec_counts.items():
        if any(w.start <= sec < w.start + w.retry_after for w in limits) and cnt > 0:
            respected = False

    return ExecutorResult(
        scenario=name,
        enqueued=len(messages),
        sent=sent,
        dropped_stale=dropped,
        max_per_sec=max_per_sec,
        burst_violation=burst_violation,
        respected_retry_after=respected,
    )


def scenarios() -> list[ExecutorResult]:
    results = []

    # 1) rate limit 없음·도착 분산 → 모두 평탄 전송.
    results.append(
        run_scenario(
            "no-rate-limit-spread",
            [QueuedMessage(due_at=float(i)) for i in range(20)],
            limits=[],
        )
    )

    # 2) 강한 burst 도착(동시 due 50건) → token-bucket 이 평탄화(순간 전송률 한도 이하).
    results.append(
        run_scenario(
            "burst-arrival-50-at-once",
            [QueuedMessage(due_at=0.0) for _ in range(50)],
            limits=[],
        )
    )

    # 3) rate limit 창 + retry-after 후 해제 → 해제 순간 burst flush 안 함, retry-after 존중.
    results.append(
        run_scenario(
            "rate-limit-then-release",
            [QueuedMessage(due_at=0.0) for _ in range(40)],
            limits=[RateLimitWindow(start=0.0, retry_after=10.0)],
        )
    )

    # 4) 긴 정체 → 오래된 메시지는 stale 로 폐기(맥락 지난 발화 금지).
    results.append(
        run_scenario(
            "long-stall-stale-drop",
            [QueuedMessage(due_at=0.0) for _ in range(30)],
            limits=[RateLimitWindow(start=0.0, retry_after=60.0)],
        )
    )

    return results


def main() -> int:
    parser = argparse.ArgumentParser(description="NEXA Discord rate-limit 합성 부하 (T019)")
    parser.add_argument("--json", action="store_true")
    args = parser.parse_args()

    results = scenarios()
    any_burst = any(r.burst_violation for r in results)
    any_retry_violation = any(not r.respected_retry_after for r in results)

    if args.json:
        print(json.dumps({"results": [asdict(r) for r in results]}, indent=2))
    else:
        print("NEXA Discord rate-limit 합성 부하 (T019) — 실제 JDA/Discord 미접근")
        print(f"안전: send rate ≤ {SEND_RATE_PER_SEC}/s, stale drop > {STALE_LIMIT_SEC}s, burst 한도 {MAX_BURST_PER_SEC}/s")
        print(f"{'scenario':>28} {'enq':>4} {'sent':>5} {'drop':>5} {'max/s':>6} {'burst!':>7} {'retryOK':>8}")
        for r in results:
            print(
                f"{r.scenario:>28} {r.enqueued:>4} {r.sent:>5} {r.dropped_stale:>5} {r.max_per_sec:>6} "
                f"{'X' if r.burst_violation else '-':>7} {'ok' if r.respected_retry_after else 'X':>8}"
            )

    # acceptance: 어떤 시나리오도 burst flush 하지 않고 retry-after 를 존중해야 한다.
    if any_burst or any_retry_violation:
        print("FAIL: burst flush 또는 retry-after 위반 검출", file=sys.stderr)
        return 1
    return 0


if __name__ == "__main__":
    sys.exit(main())
