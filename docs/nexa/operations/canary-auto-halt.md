# NEXA canary 자동 중단 조건 (P18-T023)

canary(또는 LIVE) 운영 중 위반이 관측되면 **사람 개입 없이 자동으로 단계를 강등**하는 메커니즘의 SSOT. 결정
코어는 순수 [`CanaryHalt`](../../../central-server/src/main/kotlin/com/discordassistant/central/participation/application/rollout/CanaryHaltDecision.kt),
부수효과(강등·pending 취소·운영자 알림)는 [`CanaryAutoHaltService`](../../../central-server/src/main/kotlin/com/discordassistant/central/participation/application/rollout/CanaryAutoHaltService.kt)
가 수행한다. 검증: [`CanaryHaltTest`](../../../central-server/src/test/kotlin/com/discordassistant/central/participation/application/rollout/CanaryHaltTest.kt),
[`CanaryAutoHaltServiceTest`](../../../central-server/src/test/kotlin/com/discordassistant/central/participation/application/rollout/CanaryAutoHaltServiceTest.kt).

## 자동 중단 조건 (deliverable T023)

실제 전송이 켜진 단계(CANARY/LIVE)에서만 평가한다 — shadow/off 는 이미 안전하다.

| 위반 | 임계 | 강등 대상 |
| --- | --- | --- |
| 과다 발화(over_talk) | 시간당 발화 > 한도([canary-plan](canary-plan.md) max share) | SHADOW_PREDICT |
| complaint | 누적 불만/신고 > 한도 | SHADOW_PREDICT |
| stale send | due 초과 지연 전송 > 한도 | SHADOW_PREDICT |
| privacy error | 1건이라도(0-tolerance) | **OFF**(즉시 정지) |
| model mismatch | 1건이라도(rollback 중 혼합 추론) | **OFF**(즉시 정지) |

여러 위반이 동시면 **가장 강한 강등(OFF)** 을 우선한다. privacy/model 위반은 정책 평가 자체를 끄는 OFF 로,
나머지는 관측은 계속하되 전송만 막는 SHADOW_PREDICT 로 강등한다.

## acceptance: 중단 후 pending action 도 취소되고 운영자 알림이 간다

자동 중단 시퀀스(안전 순서 — 먼저 막고 청소):

1. **단계 강등**: CANARY/LIVE → target(OFF 또는 SHADOW_PREDICT). 전송을 새로 켜지 않는 강등이라 운영 권한이면
   충분([ShadowModeTransition](../../../central-server/src/main/kotlin/com/discordassistant/central/participation/domain/model/shadow/ShadowModeTransition.kt)).
2. **pending 취소**: 강등된 길드의 미종결 예약 행동을 모두 취소한다(생성 content 포함) — 강등 후에도 묵은 행동이
   나가지 않게.
3. **운영자 알림**: 무엇이/왜/어디로 강등됐는지 on-call 에게 통지([OperatorAlertPort](../../../central-server/src/main/kotlin/com/discordassistant/central/participation/application/rollout/CanaryAutoHaltPorts.kt)).

위반이 없거나 이미 전송이 꺼진 단계면 no-op(멱등).

## 운영 연계

- 임계값은 [slo.md](slo.md)·[alerts.md](alerts.md) 의 error budget·alert 와 일치시킨다.
- 수동 정지는 [canary-plan](canary-plan.md) 의 kill switch·channel mute·rollback 경로.
- 운영 배포·실제 자동 발동은 P18-T025 게이트 통과 후 staging 시연을 거친다.
