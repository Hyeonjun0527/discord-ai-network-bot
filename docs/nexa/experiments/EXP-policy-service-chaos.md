# EXP — 정책 serving 장애 chaos (NEXA-P18-T020)

- 작업: NEXA-P18-T020 (`kind: experiment`, `risk: medium`) · 상위: [participation-context](../architecture/participation-context.md)
- 테스트: [`PolicyServingChaosTest.kt`](../../../central-server/src/test/kotlin/com/discordassistant/central/participation/application/policy/PolicyServingChaosTest.kt)
- 협력: [`PolicyFallbackChain.kt`](../../../central-server/src/main/kotlin/com/discordassistant/central/participation/application/policy/PolicyFallbackChain.kt)(P12-T021),
  [`SocialPolicyPort.kt`](../../../central-server/src/main/kotlin/com/discordassistant/central/participation/application/port/out/SocialPolicyPort.kt)(P08-T018)

## 위협 모델

정책 결정 엔진(learned ML policy serving)은 원격 추론이다. 서빙 계층이 다양한 방식으로 망가질 수 있고, 어느
경우에도 NEXA 가 **잘못 말하면 안 된다** — 불확실하면 침묵이 안전이다. "조용히 못 말하는 것" 보다 "잘못 끼어드는
것" 이 사용자 신뢰를 더 깬다.

## 주입하는 장애 (deliverable: timeout, partial response, schema mismatch, network partition)

| 장애 | 모의 방식(합성) | 기대 |
| --- | --- | --- |
| timeout / 무한 hang | 영원히 미완료인 `CompletableFuture`(`neverCompletingPort`) | SLO budget 이 끊고 안전 IGNORE |
| network partition | `IOException("connection reset")` 로 future 예외 완료 | 더 조용한 단계로 하강 → IGNORE |
| schema mismatch | `IllegalArgumentException("feature schema v3 != v1")` | 하강 → IGNORE, 사유 로그 |
| partial / garbled response | 깨진 분포(빈 actionWeights)를 `IllegalStateException` 으로 흡수 | 하강 → IGNORE |

모든 장애는 **합성**이다 — 실제 ML 서빙·실제 네트워크에 접근하지 않는다(in-process fake port).

## acceptance: fallback-to-silent 와 recovery 가 SLO 내 작동한다

- **fallback-to-silent**: 어떤 장애에서도 결정은 [`PolicyFallbackChain`](../../../central-server/src/main/kotlin/com/discordassistant/central/participation/application/policy/PolicyFallbackChain.kt)
  의 안전 단조성(점점 조용해짐)으로 IGNORE 에 귀결한다 — 절대 mention-always/SPEAK 로 떨어지지 않는다. 각 장애
  사유가 fallback 콜백(decision log)에 남는다.
- **SLO**: 결정은 SLO budget(테스트 합성 200ms) 안에 완료된다 — 느린 엔진이 호출자를 무한 블록하지 않게
  `orTimeout` 으로 SLO 를 강제하고, 그 timeout 도 안전 침묵으로 흡수한다. SLO 정의는 [slo.md](../operations/slo.md)
  의 policy latency 항목과 일치한다.
- **recovery**: 장애가 사라지면(엔진 healthy) 다음 결정부터 learned 가 다시 1차로 쓰이고 강등이 해제된다
  (`FlappingPort` 로 검증).

| 테스트 | 검증 |
| --- | --- |
| `timeout fault falls back to silent within SLO` | 무한 hang → SLO 가 끊음, IGNORE, degraded |
| `network partition fault falls back to silent` | IOException → IGNORE(mention-always 아님) |
| `schema mismatch fault falls back to silent` | schema 예외 → IGNORE, 사유 로그 |
| `partial response fault falls back to silent` | garbled → IGNORE |
| `recovery — learned is used again once fault clears` | healthy 복구 후 learned 1차·강등 해제 |

## 운영 경계

이 실험은 **결정 안전성**(불확실하면 침묵)과 **빠른 실패·복구**를 검증한다. 실제 Discord 전송은 shadow 단계
(OBSERVE_ONLY/SHADOW_PREDICT)에서 hard block 되어 0회다. chaos 는 in-process fake 로 돌며 운영 배포·실제 서빙을
건드리지 않는다. critical 지속 시 자동 강등은 [alerts.md](../operations/alerts.md)(P18-T012)·
[CanaryAutoHaltService](../../../central-server/src/main/kotlin/com/discordassistant/central/participation/application/rollout/CanaryAutoHaltService.kt)(P18-T023)
가 담당한다.
