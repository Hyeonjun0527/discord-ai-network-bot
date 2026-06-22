# EXP — actionruntime 부하 테스트 (NEXA-P18-T018)

- 작업: NEXA-P18-T018 (`kind: experiment`, `risk: medium`) · 상위: [actionruntime-context](../architecture/actionruntime-context.md)
- 스크립트: [`load-test-nexa-actions.py`](../../../scripts/load-test-nexa-actions.py)
- 관련 SLO: [slo.md](../operations/slo.md)

## 목적

actionruntime 스케줄러를 **동시 guild/channel·예약 action** 으로 부하해 throughput(처리량)과 DB contention(claim
경합)을 측정하고, **SLO 한계와 scale-out 기준을 수치화**한다(acceptance T018).

**합성 모델**이다 — 실제 central-server·실제 DB·실제 Discord 에 접근하지 않는다(`load-test-nexa-actions.py` 의
결정론적 시뮬레이션). 운영 부하 테스트(실 DB)는 별도 staging 에서만, 이 작업 범위 밖.

## 모델

- 스케줄러 worker 가 due action 을 claim 한다(행 잠금). 동시 worker 가 같은 due 밀도를 노리면 경합한다.
- contention_ratio = min(0.95, (due 밀도 / worker 수) / WORKER_BASE_CAPACITY). 경합이 클수록 유효 처리능력↓·재시도
  지연↑.
- throughput_ratio = 유효 처리율 / 도착률. 1 미만이면 backlog 누적 → p95 latency 급증.

## SLO 한계 (slo.md 와 일치)

| 지표 | 한계 |
| --- | --- |
| 예약 action claim→처리 시작 p95 | ≤ 2000ms |
| 도착률 대비 처리율(throughput ratio) | ≥ 0.9 |

## 결과 — scale-out 기준 (수치화)

`python3 scripts/load-test-nexa-actions.py` 로 재현. 주요 관측:

| guilds | channels | workers | 도착/s | contention | p95(ms) | SLO | scale-out |
| --- | --- | --- | --- | --- | --- | --- | --- |
| 10 | 50 | 1 | 25 | 0.50 | 420 | ok | 권고(contention≥0.5) |
| 50 | 250 | 1 | 125 | 0.95 | 8130 | **위반** | 필요 |
| 50 | 250 | 16 | 125 | 0.16 | 145 | ok | - |
| 200 | 1000 | 16 | 500 | 0.63 | 12520 | **위반** | 필요 |

**scale-out 임계**(worker 수별 SLO 첫 위반 guild 규모):

| worker 수 | SLO 첫 위반 guild 규모 |
| --- | --- |
| 1 | 50 |
| 4 | 50 |
| 16 | 200 |

해석: 단일 worker 는 ~10 guild(50 channel)부터 contention 이 커진다. 16 worker 로 50 guild 까지 SLO 를 지키며,
200 guild 부터는 추가 scale-out(worker 증설 또는 샤딩)이 필요하다. 운영 진입은 canary(1 guild)부터이므로
([canary-plan.md](../operations/canary-plan.md)) 초기 부하는 단일 worker 로도 충분하다.

## 운영 경계

실제 전송은 shadow 단계에서 hard block 되며, 이 실험은 claim/상태전이/audit 의 처리량만 본다. 운영 배포·실 DB
부하는 staging 한정.
