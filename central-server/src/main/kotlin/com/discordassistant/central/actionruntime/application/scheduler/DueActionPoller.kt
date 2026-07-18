package com.discordassistant.central.actionruntime.application.scheduler

import com.discordassistant.central.actionruntime.application.port.out.ActionSchedulerPort
import com.discordassistant.central.actionruntime.application.port.out.ClaimedAction
import com.discordassistant.central.actionruntime.application.port.out.ReevaluationTarget
import com.discordassistant.central.actionruntime.application.port.out.WaitReevaluationOutboxPort
import com.discordassistant.central.actionruntime.application.reevaluate.ReevaluationOutcome
import com.discordassistant.central.actionruntime.application.reevaluate.StaleActionReevaluator
import com.discordassistant.central.actionruntime.domain.model.ScheduledActionType
import com.discordassistant.central.actionruntime.domain.model.ScheduledSocialAction
import com.discordassistant.central.actionruntime.domain.service.CancellationPolicy
import com.discordassistant.central.actionruntime.domain.service.CancellationVerdict
import com.discordassistant.central.actionruntime.domain.service.SceneEvidence
import java.time.Clock
import java.time.Duration
import java.time.Instant

/**
 * due 예약 처리 orchestration(NEXA-P13-T006, application 레이어). DB polling scheduler 의 **한 tick 본문**이다 —
 * adapter(스케줄 트리거)가 주기적으로 [pollOnce] 를 호출한다.
 *
 * 한 tick 흐름(각 claim 행동마다):
 * 1. [ActionSchedulerPort.claimDue] 로 due 행을 lease 와 함께 claim(SELECT FOR UPDATE SKIP LOCKED — 다중 인스턴스
 *    중복 claim 방지, T006/T007). claim 된 행은 REEVALUATING 상태.
 * 2. **취소 정책**([CancellationPolicy]) — scene evidence 로 다른 인간 응답/주제 전환/대상 만료면 취소(T012/T013).
 * 3. **contextVersion 재평가**([StaleActionReevaluator]) — stale 이면 participation 재판단, 무효면 취소(T011).
 * 4. 둘 다 통과하면 TYPING 으로 진행만 표시한다(실제 전송 executor 는 T015~T017 — 이 묶음은 전송 안 함).
 *
 * 시간은 [Clock] 주입(테스트 시간 제어 — Date.now 직접 금지, T008). 실제 sleep 없이 [pollOnce] 호출과 시계 전진으로
 * due 동작을 테스트한다.
 *
 * 순수성 경계: application 레이어 — 포트·도메인 타입만. Spring/JPA/JDA 미참조.
 */
class DueActionPoller(
    private val scheduler: ActionSchedulerPort,
    private val reevaluator: StaleActionReevaluator,
    private val cancellationPolicy: CancellationPolicy,
    private val sceneEvidenceProvider: SceneEvidenceProvider,
    private val clock: Clock,
    /** lease 보유 기간(이 tick 에서 claim 한 행을 이만큼 소유 — 만료 후 다른 인스턴스 회수 가능). */
    private val leaseDuration: Duration = DEFAULT_LEASE_DURATION,
    /** 한 tick 에 claim 할 최대 건수(과점유 방지). */
    private val batchLimit: Int = DEFAULT_BATCH_LIMIT,
    private val waitReevaluationOutbox: WaitReevaluationOutboxPort? = null,
) {
    /**
     * due 예약을 한 batch claim 해 처리하고 결과 목록을 돌려준다. 빈 목록이면 처리할 due 가 없었던 것이다.
     * 같은 시점에 다른 인스턴스가 도는 [pollOnce] 는 SKIP LOCKED 로 **다른 행** 을 가져간다(중복 처리 없음).
     */
    fun pollOnce(): List<DueActionOutcome> {
        val now = Instant.now(clock)
        val claimed = scheduler.claimDue(now = now, leaseExpiresAt = now.plus(leaseDuration), limit = batchLimit)
        return claimed.map { process(it) }
    }

    private fun process(claimed: ClaimedAction): DueActionOutcome {
        val action = claimed.action
        val target = action.toReevaluationTarget()

        // 2) 취소 정책 — scene evidence 로 취소 후보면 취소.
        val verdict = cancellationPolicy.decide(action, sceneEvidenceProvider.evidenceFor(action))
        if (verdict.cancels) {
            scheduler.cancel(action.identity)
            return DueActionOutcome(action, DueActionDisposition.CANCELLED_BY_POLICY, cancellation = verdict)
        }

        if (action.type == ScheduledActionType.WAIT) {
            if (action.isExpired(Instant.now(clock)) || action.waitAttempt >= action.maxAttempts) {
                scheduler.cancel(action.identity)
                return DueActionOutcome(action, DueActionDisposition.CANCELLED_STALE)
            }
            val currentVersion = reevaluator.currentSceneContextVersion(target)
            val enqueued = currentVersion?.let { waitReevaluationOutbox?.completeAndEnqueue(action, it) }
            return if (enqueued != null) {
                DueActionOutcome(action.complete(), DueActionDisposition.READY_TO_REEVALUATE)
            } else {
                scheduler.cancel(action.identity)
                DueActionOutcome(action, DueActionDisposition.CANCELLED_STALE)
            }
        }

        // 3) contextVersion 재평가 — stale·무효면 취소.
        return when (reevaluator.decide(action, target)) {
            ReevaluationOutcome.CANCEL -> {
                scheduler.cancel(action.identity)
                DueActionOutcome(action, DueActionDisposition.CANCELLED_STALE)
            }
            // 4) 통과 — TYPING 진행만(실제 전송은 T015~T017). 상태만 전이.
            ReevaluationOutcome.PROCEED -> {
                scheduler.markTyping(action.identity)
                DueActionOutcome(action.passReevaluation(), DueActionDisposition.READY_TO_TYPE)
            }
        }
    }

    private fun ScheduledSocialAction.toReevaluationTarget(): ReevaluationTarget =
        ReevaluationTarget(
            guildPseudonym = target.guildPseudonym,
            channelId = target.channelId,
            threadId = target.threadId,
            routingChannelId = target.routingChannelId,
            scheduledTurnGeneration = target.targetMessageId?.toLongOrNull(),
            scheduledSceneContextVersion = target.sceneContextVersion,
        )

    companion object {
        /** 기본 lease 보유 기간. */
        val DEFAULT_LEASE_DURATION: Duration = Duration.ofSeconds(30)

        /** 기본 batch 상한. */
        const val DEFAULT_BATCH_LIMIT: Int = 50
    }
}

/**
 * 한 due 행동의 처리 결과(application 값 객체). 테스트·관찰이 어떤 분기로 갔는지 확인한다.
 */
data class DueActionOutcome(
    /** 처리 후 행동(취소면 원본, 진행이면 TYPING 으로 전이된 인스턴스). */
    val action: ScheduledSocialAction,
    val disposition: DueActionDisposition,
    /** 취소 정책으로 취소된 경우 그 사유(그 외 null). */
    val cancellation: CancellationVerdict? = null,
)

/**
 * due 행동 처리 분기(application enum).
 */
enum class DueActionDisposition {
    /** 취소 정책으로 취소(다른 인간 응답/주제 전환/대상 만료 — T012/T013). */
    CANCELLED_BY_POLICY,

    /** contextVersion 재평가에서 stale·무효라 취소(T011). */
    CANCELLED_STALE,

    /** 통과 — TYPING 진행(실제 전송은 다음 묶음 T015~T017). */
    READY_TO_TYPE,

    /** WAIT 완료와 child 판단 outbox가 원자적으로 생성됨. 전송 상태로 진입하지 않는다. */
    READY_TO_REEVALUATE,
}

/**
 * 취소 정책 evidence 공급 포트(application). adapter 가 conversation/socialmemory 의 장면 evidence(대상 thread 기준)
 * 를 채운다 — 다른 동시 thread 활동은 싣지 않는다(T013 잘못된 취소 방지).
 */
fun interface SceneEvidenceProvider {
    fun evidenceFor(action: ScheduledSocialAction): SceneEvidence
}
