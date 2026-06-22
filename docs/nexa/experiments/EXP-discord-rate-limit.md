# EXP — Discord rate-limit 부하 테스트 (NEXA-P18-T019)

- 작업: NEXA-P18-T019 (`kind: experiment`, `risk: medium`) · 상위: [actionruntime-context](../architecture/actionruntime-context.md)
- 스크립트: [`load-test-discord-executor.py`](../../../scripts/load-test-discord-executor.py)
- 관련 SLO: [slo.md](../operations/slo.md)

## 위협 모델

Discord 전송 executor 는 rate limit 에 걸린다. limit 으로 막히면 큐에 메시지가 쌓이고, limit 이 풀리는 순간
쌓인 걸 **한꺼번에 쏟으면**(burst flush) NEXA 가 갑자기 도배한다 — 게다가 오래된(stale) 메시지는 맥락이 지나
부적절하다. **fake JDA rate limit 과 `retry-after` 를 주입**(deliverable T019)해 이 두 사고를 막는지 본다.

**합성**이다 — 실제 JDA·실제 Discord API 를 호출하지 않는다.

## 안전 규칙 (acceptance: queue 가 오래된 메시지를 폭발적으로 전송하지 않는다)

1. **retry-after 준수**: rate limit 창 동안에는 전송하지 않는다.
2. **token-bucket 평탄화**: limit 해제 후에도 초당 전송을 한도(합성 5/s)로 평탄화한다. bucket 용량을 1 token 으로
   둬, 해제 순간 묵힌 토큰을 한꺼번에 방출하는 burst flush 를 막는다.
3. **stale drop**: due 후 staleness 한도(합성 30s)를 넘긴 메시지는 보내지 않고 폐기한다(맥락 지난 발화 금지).

## 결과

`python3 scripts/load-test-discord-executor.py` 로 재현(위반 검출 시 비-0 종료).

| 시나리오 | enq | sent | drop(stale) | max/s | burst! | retry-after |
| --- | --- | --- | --- | --- | --- | --- |
| no-rate-limit-spread | 20 | 20 | 0 | 1 | - | ok |
| burst-arrival-50-at-once | 50 | 50 | 0 | 5 | - | ok |
| rate-limit-then-release | 40 | 40 | 0 | 5 | - | ok |
| long-stall-stale-drop | 30 | 0 | 30 | 0 | - | ok |

해석:
- **burst-arrival**: 동시 도착 50건이 들어와도 순간 전송률이 한도(5/s) 이하로 평탄화된다(burst 없음).
- **rate-limit-then-release**: 10s rate limit 후 해제돼도 한꺼번에 쏟지 않고 5/s 로 흘린다 — retry-after 도 준수.
- **long-stall-stale-drop**: 60s 정체로 모든 메시지가 stale 한도를 넘기면 **폭발 전송 대신 전부 폐기**한다(맥락
  지난 발화 0). 이것이 acceptance 의 핵심 — 큐가 묵은 메시지를 쏟지 않는다.

## 운영 경계

실제 전송은 shadow 단계에서 hard block 되며, 이 실험은 전송 게이팅(평탄화·stale drop·retry-after)만 본다.
운영 rate limit 파라미터는 [slo.md](../operations/slo.md)·[canary-plan.md](../operations/canary-plan.md) 와 맞춘다.
