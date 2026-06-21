# EXP — scheduler crash·restart chaos (NEXA-P13-T024)

- 작업: NEXA-P13-T024 (`kind: experiment`, `risk: medium`) · 상위: [actionruntime-context](../architecture/actionruntime-context.md)
- 테스트: [`ActionRuntimeChaosTest.kt`](../../../central-server/src/test/kotlin/com/discordassistant/central/actionruntime/application/ActionRuntimeChaosTest.kt)
- 협력: [`RestartRecoveryService.kt`](../../../central-server/src/main/kotlin/com/discordassistant/central/actionruntime/application/recovery/RestartRecoveryService.kt)(T010),
  [`ActionExecutionService.kt`](../../../central-server/src/main/kotlin/com/discordassistant/central/actionruntime/application/execution/ActionExecutionService.kt),
  [`ActionAuditPort.kt`](../../../central-server/src/main/kotlin/com/discordassistant/central/actionruntime/application/port/out/ActionAuditPort.kt)(T022)

## 위협 모델

예약 사회적 행동은 **여러 단계**(claim → 본문 생성(GLM) → typing → 버블 전송)를 거친다. 어느 단계에서든 프로세스가
죽을 수 있다. 두 가지 사고가 사용자 신뢰를 깬다:

1. **중복 전송**: 재시작이 이미 보낸(또는 보내려던) 버블을 다시 보내 같은 말을 두 번 한다.
2. **유실된 terminal audit**: 행동이 어떤 terminal(COMPLETED/CANCELLED/FAILED)에도 도달하지 못한 채 사라져,
   사후에 "무슨 일이 있었는지" 를 재구성할 수 없다.

## 모의하는 중단 지점 (deliverable: claim 직후·GLM 직후·첫 버블 직후)

| 중단 지점 | in-flight 상태 | 재시작 복구(T010) | 기대 |
| --- | --- | --- | --- |
| claim 직후(아무 것도 안 보냄) | `REEVALUATING` | 재예약(`RESCHEDULED`) | 다시 처리 → 정확히 1회 전송 |
| GLM 본문 생성 직후 | `TYPING`(본문 미전송) | 재예약(`RESCHEDULED`) | 다시 처리 → 정확히 1회 전송 |
| 첫 버블 직후 | `PARTIALLY_SENT` | 재전송 없이 종결(`COMPLETED_NO_RESEND`) | **재전송 안 함** → 누적 전송 1회 유지 |

핵심 안전 규칙(T010): 본문을 아직 안 보낸 상태(`REEVALUATING`/`TYPING`)는 재예약해 다시 처리해도 이중 전송이
없다(미전송이므로). 이미 일부 보낸 상태(`PARTIALLY_SENT`)는 **자동 재전송하지 않고** 종결한다 — "두 번 보냄" 보다
"한 번 덜 보냄" 이 안전하다.

## acceptance: 중복 전송 0, 유실된 terminal audit 0

테스트는 전송 executor 의 **누적** 호출(재시작을 거쳐도 리셋되지 않음)을 세어 중복 전송을 검출하고, 감사 로그에서
terminal phase(COMPLETED 등)가 남았는지 확인한다.

| 테스트 | 검증 |
| --- | --- |
| `claim 직후 크래시` | REEVALUATING → 재예약 → 재실행. 전송 정확히 1회, COMPLETED, terminal audit 존재. |
| `GLM 직후 크래시(TYPING)` | TYPING → 재예약 → 재실행. 전송 정확히 1회, COMPLETED, terminal audit 존재. |
| `첫 버블 직후 크래시(PARTIALLY_SENT)` | 재전송 안 함(누적 전송 1회 유지), COMPLETED 종결. |
| `복구 두 번 호출(멱등)` | 두 번째 복구는 회수할 lease 가 없어 no-op — 중복 재예약·중복 전송 0. |

## shadow 경계(P09)와의 일관

이 실험은 **상태 정리·중복 방지**를 검증한다. 실제 Discord 전송 자체는 OBSERVE_ONLY 등 shadow 단계에서 hard
block 되어 0회다([ActionExecutionServiceTest](../../../central-server/src/test/kotlin/com/discordassistant/central/actionruntime/application/ActionExecutionServiceTest.kt)
의 P09 케이스). chaos 테스트는 LIVE 경로(mock executor)에서 재시작 정합성을 본다 — 운영 배포·실제 전송은 하지 않는다.
