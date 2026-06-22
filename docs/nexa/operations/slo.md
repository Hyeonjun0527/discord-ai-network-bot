# NEXA SLO·error budget (P18-T021)

NEXA 운영의 **시스템 신뢰성 SLO** 와 error budget 의 SSOT. 부하·chaos 실험
([EXP-action-load](../experiments/EXP-action-load.md), [EXP-discord-rate-limit](../experiments/EXP-discord-rate-limit.md),
[EXP-policy-service-chaos](../experiments/EXP-policy-service-chaos.md))과 alert 임계([alerts.md](alerts.md))가
이 값을 기준으로 한다.

## 경계: 시스템 신뢰성 ≠ 사람다움 (acceptance T021)

**이 문서는 사람다움(human-likeness) metric 과 시스템 신뢰성 SLO 를 절대 혼합하지 않는다.** 둘은 다른 종류의
목표다:

- **시스템 신뢰성 SLO**(이 문서): 측정 가능한 인프라 계약 — durability, latency, availability, 중복/지연 전송,
  삭제 SLA. 위반은 운영 사고이며 error budget 을 소모한다.
- **사람다움 metric**(별도 — 사람다움 게이트·`nexa-human-likeness-eval.py`): 응답 품질·공감·타이밍의 *주관적*
  평가다. 이건 SLO 가 아니라 **제품 품질 게이트**이고, error budget 과 무관하며, 위반해도 자동 강등하지 않는다
  (사람 판단). 여기 섞으면 "공감 점수 낮음" 이 가용성 사고로 오인되거나, 반대로 "발화 지연" 이 품질 문제로
  희석된다. 분리가 핵심.

## 시스템 신뢰성 SLO

| SLO | 정의 | 목표 | 측정 | error budget 소모 |
| --- | --- | --- | --- | --- |
| **ingestion durability** | 정규화 이벤트가 event store 에 손실 없이 append | 99.99% (월 손실 ≤ 0.01%) | append 성공/시도 | 손실 이벤트 |
| **duplicate send** | 같은 행동이 두 번 전송되는 비율 | 0 (hard) — 월 ≤ 1건 | 누적 전송 counter | 중복 전송 1건당 |
| **stale send** | due 후 staleness 한도(30s) 초과 전송 비율 | ≤ 0.1% | 전송 지연 분포 | 초과 전송 |
| **policy latency** | 정책 결정 p95(요청→결정) | ≤ 2000ms (SLO budget) | 결정 latency 분포 | budget 초과 결정 |
| **GLM availability** | speech 텍스트 생성 GLM 호출 성공률 | 99.5% | 성공/시도(2xx) | 실패 호출 |
| **deletion SLA** | 동의 철회/삭제 요청 처리 완료 시한 | ≤ 24h (privacy 계약) | 요청→완료 시간 | SLA 초과 요청 |

## error budget

- 각 SLO 는 (1 − 목표)를 월간 error budget 으로 둔다. 예: GLM availability 99.5% → budget 0.5%/월.
- **소진 시 정책**: budget 의 50% 소모 시 warn(사람 확인, [alerts.md](alerts.md)), 100% 소모 시 해당 길드는 자동
  강등(LIVE→SHADOW/OFF, [CanaryAutoHaltService](../../../central-server/src/main/kotlin/com/discordassistant/central/participation/application/rollout/CanaryAutoHaltService.kt))
  대상이 된다 — budget 이 다 떨어지면 안전(침묵) 쪽으로 물러난다.
- **duplicate send·deletion SLA 는 0-tolerance 성격**: 1건이라도 즉시 incident 로 다루고(error budget 이 매우 작다),
  privacy error 는 즉시 OFF 강등(P18-T023 hard halt).

## scale-out·운영 연계

- policy latency·throughput SLO 위반 임계는 [EXP-action-load](../experiments/EXP-action-load.md) 의 scale-out 기준과
  일치한다(16 worker 로 50 guild 까지 SLO 유지, 200 guild 부터 증설).
- stale send·burst 방지는 [EXP-discord-rate-limit](../experiments/EXP-discord-rate-limit.md) 의 token-bucket·stale
  drop 으로 강제된다.

이 문서는 정의·목표이며, 실제 측정·발송은 운영 배포에서만 한다(이 작업에서는 정의까지).
