package com.discordassistant.central.actionruntime.application.recovery

import com.discordassistant.central.actionruntime.application.port.out.ActionAuditPort
import com.discordassistant.central.actionruntime.application.port.out.ActionSchedulerPort
import com.discordassistant.central.actionruntime.domain.model.ActionAuditEvent
import com.discordassistant.central.actionruntime.domain.model.ActionAuditPhase
import com.discordassistant.central.actionruntime.domain.model.ActionIdentity
import com.discordassistant.central.actionruntime.domain.model.ActionStatus
import com.discordassistant.central.actionruntime.domain.model.ScheduledSocialAction
import java.time.Clock
import java.time.Instant

/**
 * 프로세스 재시작 예약 복구 유스케이스(NEXA-P13-T010, application 레이어).
 *
 * 워커가 죽으면 그가 잡고 있던 예약은 **만료된 lease** 를 가진 채 in-flight(REEVALUATING/TYPING/PARTIALLY_SENT)
 * 상태로 남는다. 시작 시 이 서비스가 만료 lease 를 회수해 **유실 없이** 다시 처리 가능하게 만든다. 단 이미 일부가
 * 전송된 상태(PARTIALLY_SENT)나 전송 진행 중(TYPING)은 **같은 버블을 두 번 보내지 않도록** 안전하게 다룬다.
 *
 * **acceptance(T010) — 재시작으로 동일 버블이 두 번 전송되지 않는다**:
 * - REEVALUATING(아직 아무것도 안 보냄): 안전하게 재예약(SCHEDULED 복귀)해 다시 due 처리 — 이중 전송 위험 없음.
 * - TYPING(typing 만 표시, 본문 미전송): 마찬가지로 재예약해 재평가부터 다시 — 본문 미전송이라 이중 전송 없음.
 * - PARTIALLY_SENT(일부 버블 이미 전송됨): **자동 재전송하지 않는다**. 이미 보낸 버블을 다시 보내는 위험이 있으므로
 *   복구는 이를 [com.discordassistant.central.actionruntime.domain.model.ActionStatus.COMPLETED] 로 종결한다
 *   (남은 버블 미전송으로 둔다 — "두 번 보냄" 보다 "한 번 덜 보냄" 이 안전. 실제 전송 executor 는 T015~T017).
 *
 * 실제 전송이 없는 이 묶음에서는 복구가 **상태 정리(reclaim + 재예약/종결)** 까지만 한다(shadow 경계 유지).
 *
 * 순수성 경계: application 레이어 — 포트·도메인 타입만. Spring/JPA/JDA 미참조.
 */
class RestartRecoveryService(
    private val scheduler: ActionSchedulerPort,
    private val audit: ActionAuditPort,
    private val clock: Clock,
) {
    /**
     * 시작 시 만료 lease 를 회수하고 각 in-flight 행동을 안전 상태로 정리한다. 처리한 행동별 [RecoveryAction] 목록을
     * 돌려준다(복구 로그·검증용). 만료 lease 가 없으면 빈 목록.
     */
    fun recoverOnStartup(): List<RecoveryAction> {
        val now = Instant.now(clock)
        val reclaimed: List<ActionIdentity> = scheduler.reclaimExpiredLeases(now)
        return reclaimed.mapNotNull { identity ->
            // 회수 시점과 조회 사이에 행이 사라졌으면(보존/정리 잡의 하드 삭제 등) 그 하나만 건너뛴다 — 예외로
            // recoverOnStartup 전체를 중단시켜 나머지 in-flight 행동을 영구 stranded 로 남기지 않는다.
            val action = scheduler.find(identity) ?: return@mapNotNull null
            classify(action)
        }
    }

    private fun classify(action: ScheduledSocialAction): RecoveryAction =
        when (action.status) {
            // 아직 본문 미전송 — 안전하게 재예약(다시 due 처리). 이중 전송 위험 없음.
            ActionStatus.REEVALUATING, ActionStatus.TYPING -> {
                scheduler.reschedule(action.identity, action.executeAfter, action.attempt)
                RecoveryAction(action.identity, RecoveryDisposition.RESCHEDULED)
            }
            // 일부 이미 전송됨 — 자동 재전송 금지. 종결해 같은 버블 재전송 차단(T010 핵심).
            ActionStatus.PARTIALLY_SENT -> {
                scheduler.complete(action.identity)
                audit.append(
                    ActionAuditEvent(
                        actionId = action.identity.value,
                        decisionId = action.decisionId,
                        phase = ActionAuditPhase.RECOVERED_NO_RESEND,
                        reason = REASON_PARTIAL_RECOVERY_NO_RESEND,
                        occurredAt = Instant.now(clock),
                    ),
                )
                RecoveryAction(action.identity, RecoveryDisposition.COMPLETED_NO_RESEND)
            }
            // SCHEDULED/CONSIDERING/terminal: lease 와 무관(또는 회수 대상 아님) — 정리 불필요.
            else -> RecoveryAction(action.identity, RecoveryDisposition.NO_OP)
        }

    private companion object {
        const val REASON_PARTIAL_RECOVERY_NO_RESEND = "partial_recovery_no_resend"
    }
}

/**
 * 한 행동의 복구 처리 결과(application 값 객체). 복구가 무엇을 했는지 감사·검증한다.
 */
data class RecoveryAction(
    val identity: ActionIdentity,
    val disposition: RecoveryDisposition,
)

/**
 * 복구 처리 종류(application enum).
 */
enum class RecoveryDisposition {
    /** 안전하게 재예약(REEVALUATING/TYPING — 본문 미전송). */
    RESCHEDULED,

    /** 부분 전송 — 재전송 없이 종결(이중 전송 차단, T010). */
    COMPLETED_NO_RESEND,

    /** 정리 불필요(이미 SCHEDULED/terminal). */
    NO_OP,
}
