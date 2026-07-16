package com.discordassistant.central.actionruntime.application.execution

import com.discordassistant.central.actionruntime.application.port.out.ActionAuditPort
import com.discordassistant.central.actionruntime.application.port.out.ActionExecutionModePort
import com.discordassistant.central.actionruntime.application.port.out.ActionReevaluationPort
import com.discordassistant.central.actionruntime.application.port.out.ActionSchedulerPort
import com.discordassistant.central.actionruntime.application.port.out.DiscordExecutorPort
import com.discordassistant.central.actionruntime.application.port.out.ExecutionResult
import com.discordassistant.central.actionruntime.application.port.out.ReevaluationTarget
import com.discordassistant.central.actionruntime.domain.OutboundDecision
import com.discordassistant.central.actionruntime.domain.OutboundGuard
import com.discordassistant.central.actionruntime.domain.model.ActionAuditEvent
import com.discordassistant.central.actionruntime.domain.model.ActionAuditPhase
import com.discordassistant.central.actionruntime.domain.model.ActionFailureReason
import com.discordassistant.central.actionruntime.domain.model.ActionStatus
import com.discordassistant.central.actionruntime.domain.model.BurstPlan
import com.discordassistant.central.actionruntime.domain.model.ScheduledActionType
import com.discordassistant.central.actionruntime.domain.model.ScheduledSocialAction
import com.discordassistant.central.participation.domain.model.shadow.ShadowMode
import java.time.Clock
import java.time.Duration
import java.time.Instant

/**
 * 예약 행동의 **실제 실행** orchestration(NEXA-P13-T015/T017/T018/T020/T021, application 레이어).
 *
 * 재평가를 통과한(TYPING) 행동을 받아 typing 시작 → 버스트 버블 순차 전송 → 완료/취소/실패까지 진행한다. 모든
 * 단계에서 다음 안전 규칙을 강제한다:
 *
 * - **P09 shadow hard block**: [OutboundGuard] 가 BLOCK 이면(OBSERVE_ONLY/SHADOW_PREDICT/OFF) [DiscordExecutorPort]
 *   를 **한 번도 호출하지 않고** SUPPRESSED_SHADOW 만 audit 한다(전송 0회 — acceptance, P09-T008 일관).
 * - **우아한 실패(T018)**: 대상 삭제/권한 상실 시 executor 가 [ExecutionResult.Failed] 를 돌려준다(던지지 않음).
 *   호출자는 크래시 없이 취소/영구 실패로 종결하고 audit 한다 — **다른 채널로 보내는 fallback 은 없다**.
 * - **잔여 버블 취소(T020)**: 첫 버블 뒤 contextVersion 이 바뀌면 남은 버블을 전송하지 않고 PARTIALLY_SENT 로
 *   기록 후 취소 사유를 남긴다(이미 보낸 버블은 보존).
 * - **rate-limit·backpressure(T021)**: 429 retryAfter 가 staleness 예산 안이면 [BackpressureGate] 가 1회만
 *   존중·재시도하고, 예산을 넘으면 무한 재시도·spam 대신 취소한다.
 * - **idempotency·audit(T017/T022)**: 전송된 버블마다 Discord 메시지 ID 를 SENT audit 에 연결한다.
 *
 * 순수성 경계: application 레이어 — 포트·도메인 타입만. Spring/JPA/JDA 미참조.
 */
class ActionExecutionService(
    private val executor: DiscordExecutorPort,
    private val scheduler: ActionSchedulerPort,
    private val reevaluation: ActionReevaluationPort,
    private val audit: ActionAuditPort,
    private val backpressure: BackpressureGate,
    private val clock: Clock,
    private val modePort: ActionExecutionModePort = ActionExecutionModePort.REQUESTED_MODE,
    private val retryDecider: ActionRetryDecider = ActionRetryDecider(clock),
) {
    /**
     * [mode] 요청과 [action]이 예약될 때의 rollout 권한 중 더 좁은 모드에서 [plan]을 실행한다. shadow 차단이면 전송 없이
     * [ExecutionOutcome.Suppressed]. 그 외에는
     * typing 시작 후 버블을 순차 전송하며 위 안전 규칙을 적용한 결과를 돌려준다(상태·audit 는 부수효과로 기록).
     */
    fun execute(
        mode: ShadowMode,
        action: ScheduledSocialAction,
        plan: BurstPlan,
    ): ExecutionOutcome {
        require(action.status == ActionStatus.TYPING) {
            "ActionExecutionService 는 TYPING 상태만 실행할 수 있다: ${action.status} (action=${action.identity.value})"
        }

        val requestedMode = action.originRolloutMode.restrictiveIntersection(mode)
        val currentMode = modePort.currentMode(action.target, requestedMode)
        // P09 hard block — 실행 직전 현재 모드가 차단 단계면 executor 를 한 번도 호출하지 않는다(전송 0회).
        if (OutboundGuard.decide(currentMode) == OutboundDecision.BLOCK) {
            record(action, ActionAuditPhase.SUPPRESSED_SHADOW, reason = currentMode.name)
            scheduler.cancel(action.identity)
            return ExecutionOutcome.Suppressed(currentMode)
        }

        // typing 시작(P12) — 실패해도 전송 자체를 막지 않되, 대상 부재면 우아하게 종결(T018).
        when (val typing = executor.startTyping(action.target.channelId)) {
            is ExecutionResult.Failed -> {
                if (typing.reason == ActionFailureReason.TARGET_MISSING ||
                    typing.reason == ActionFailureReason.PERMISSION_DENIED
                ) {
                    return failGracefully(action, typing.reason)
                }
            }
            else -> record(action, ActionAuditPhase.TYPING_STARTED)
        }

        return sendBurst(action, plan)
    }

    private fun sendBurst(
        action: ScheduledSocialAction,
        plan: BurstPlan,
    ): ExecutionOutcome {
        // staleness 는 "이 사회적 응답이 얼마나 늦었나" 다 → burst 시작이 아니라 **예약 발사 시점(executeAfter)** 기준.
        // burst 시작 기준이면 poller 백로그·lease 만료 후 재실행으로 8분 늦게 실행돼도 staleness≈0 이라, 뒤늦게
        // 쏟아내지 않는다는 T021 취지가 무력화된다.
        val stalenessBaseline = action.executeAfter
        val sentMessageIds = mutableListOf<String>()
        val target = action.toReevaluationTarget()

        for (bubble in plan.bubbles) {
            // 첫 버블 뒤부터, 동의철회/kill-switch/채널mute purge 가 이 행동을 종결시켰는지 재확인한다(T014 즉시 무효화).
            // purge 는 별도 트랜잭션에서 상태를 CANCELLED 로 바꾸고 lease 를 지우므로, 남은 버블을 보내기 전에 현재
            // 상태를 읽어 종결됐으면 즉시 멈춘다(이미 보낸 버블은 보존, 추가 전송 0회 — 동의 철회 후 발화 누출 방지).
            if (bubble.index > 0) {
                val liveStatus = scheduler.find(action.identity)?.status
                if (liveStatus == null || liveStatus.isTerminal) {
                    record(action, ActionAuditPhase.TYPING_STOPPED)
                    record(action, ActionAuditPhase.PARTIALLY_CANCELLED, reason = REASON_REVOKED_MID_BURST)
                    return ExecutionOutcome.PartiallyCancelled(sentMessageIds, REASON_REVOKED_MID_BURST)
                }
            }
            // 첫 버블 뒤부터 contextVersion 재확인 — 바뀌었으면 잔여 버블 취소(T020).
            if (bubble.index > 0 && isContextChanged(action, target)) {
                record(action, ActionAuditPhase.TYPING_STOPPED)
                scheduler.markPartiallySent(action.identity)
                record(action, ActionAuditPhase.PARTIALLY_CANCELLED, reason = REASON_CONTEXT_CHANGED)
                scheduler.cancel(action.identity)
                return ExecutionOutcome.PartiallyCancelled(sentMessageIds, REASON_CONTEXT_CHANGED)
            }

            // backpressure: 너무 지연된 사회적 응답은 뒤늦게 쏟아내지 않는다(T021).
            val staleness = Duration.between(stalenessBaseline, Instant.now(clock))
            if (backpressure.shouldDropForStaleness(staleness)) {
                return abortForBackpressure(action, sentMessageIds)
            }

            when (val result = sendWithBackoff(action, plan, bubble.index, stalenessBaseline)) {
                is ExecutionResult.Sent -> {
                    sentMessageIds += result.messageId
                    record(action, ActionAuditPhase.SENT, messageId = result.messageId)
                    // 첫 버블이 실제로 나간 즉시 TYPING→PARTIALLY_SENT 를 영속한다. 성공 버스트 도중 크래시하면
                    // 상태가 TYPING 으로 남아 recovery 가 "본문 미전송" 으로 오판·재예약해 같은 버블을 이중 전송하는
                    // T010 위반이 생긴다. 첫 전송 시점에 부분전송으로 표시하면 recovery 가 재전송 없이 종결한다.
                    if (sentMessageIds.size == 1) scheduler.markPartiallySent(action.identity)
                }
                is ExecutionResult.Failed -> return failPartialOrWhole(action, result.reason, sentMessageIds)
                ExecutionResult.Ok -> Unit // sendBubble 은 Sent/Failed 만 — 방어적.
            }
        }

        record(action, ActionAuditPhase.TYPING_STOPPED)
        record(action, ActionAuditPhase.COMPLETED)
        scheduler.complete(action.identity)
        return ExecutionOutcome.Completed(sentMessageIds)
    }

    /** 한 버블 전송 + 429 backoff 1회 존중(예산 안일 때만 — 무한 재시도·spam 금지, T021). */
    private fun sendWithBackoff(
        action: ScheduledSocialAction,
        plan: BurstPlan,
        bubbleIndex: Int,
        stalenessBaseline: Instant,
    ): ExecutionResult {
        val replyTo = if (bubbleIndex == 0) replyTargetFor(action) else null
        val first =
            executor.sendBubble(
                channelId = action.target.channelId,
                speechPlanRef = plan.bubbles[bubbleIndex].speechPlanRef,
                bubbleIndex = bubbleIndex,
                replyToMessageId = replyTo,
            )
        if (first !is ExecutionResult.Failed || first.retryAfter == null) return first

        // 429 — 권고 backoff 가 staleness 예산 안이면 1회만 재시도, 아니면 취소(spam 금지).
        val staleness = Duration.between(stalenessBaseline, Instant.now(clock))
        if (!backpressure.acceptBackoff(staleness, first.retryAfter)) return first
        return executor.sendBubble(
            channelId = action.target.channelId,
            speechPlanRef = plan.bubbles[bubbleIndex].speechPlanRef,
            bubbleIndex = bubbleIndex,
            replyToMessageId = replyTo,
        )
    }

    private fun failPartialOrWhole(
        action: ScheduledSocialAction,
        reason: ActionFailureReason,
        sent: List<String>,
    ): ExecutionOutcome =
        if (sent.isEmpty()) {
            failGracefully(action, reason)
        } else {
            // 일부 이미 전송됨 — 남은 버블은 보내지 않고 부분 전송으로 종결(이중 전송·다른 채널 fallback 없음).
            record(action, ActionAuditPhase.TYPING_STOPPED)
            scheduler.markPartiallySent(action.identity)
            record(action, ActionAuditPhase.PARTIALLY_CANCELLED, reason = reason.wireName)
            scheduler.cancel(action.identity)
            ExecutionOutcome.PartiallyCancelled(sent, reason.wireName)
        }

    private fun abortForBackpressure(
        action: ScheduledSocialAction,
        sent: List<String>,
    ): ExecutionOutcome {
        record(action, ActionAuditPhase.TYPING_STOPPED)
        return if (sent.isEmpty()) {
            record(action, ActionAuditPhase.CANCELLED, reason = REASON_TOO_STALE)
            scheduler.cancel(action.identity)
            ExecutionOutcome.Cancelled(REASON_TOO_STALE)
        } else {
            scheduler.markPartiallySent(action.identity)
            record(action, ActionAuditPhase.PARTIALLY_CANCELLED, reason = REASON_TOO_STALE)
            scheduler.cancel(action.identity)
            ExecutionOutcome.PartiallyCancelled(sent, REASON_TOO_STALE)
        }
    }

    /** 우아한 실패(T018): 크래시 없이 종결하고 audit. transient(DISCORD_TRANSIENT 5xx/네트워크)이고 재시도 여유가
     *  있으면 backoff 후 재예약(SCHEDULED 복귀 — poller 가 다시 실행), 아니면 영구 [ExecutionOutcome.Failed] 로 종결한다.
     *  이 결정은 [ActionRetryDecider](T009 bounded retry)가 소유한다 — 무한 재시도 없음, reason 보존. */
    private fun failGracefully(
        action: ScheduledSocialAction,
        reason: ActionFailureReason,
    ): ExecutionOutcome =
        when (val decision = retryDecider.decide(action, reason)) {
            is RetryDecision.Retry -> {
                // typing 은 끝내고 SCHEDULED 로 되돌린다(다음 due 에 재실행). attempt+1 로 maxAttempts 경계가 강제된다.
                record(action, ActionAuditPhase.TYPING_STOPPED)
                record(action, ActionAuditPhase.SCHEDULED, reason = reason.wireName)
                scheduler.reschedule(action.identity, decision.nextExecuteAfter, decision.attempt)
                ExecutionOutcome.RetryScheduled(reason, decision.nextExecuteAfter, decision.attempt)
            }
            is RetryDecision.Fail -> {
                record(action, ActionAuditPhase.FAILED, reason = decision.reason.wireName)
                scheduler.fail(action.identity, decision.reason)
                ExecutionOutcome.Failed(decision.reason)
            }
        }

    private fun isContextChanged(
        action: ScheduledSocialAction,
        target: ReevaluationTarget,
    ): Boolean {
        val current = reevaluation.currentContextVersion(target) ?: return true // 장면 소멸 = 바뀐 것.
        return action.isStale(current)
    }

    /** reply 대상(원 메시지 ID) — 대화 초점 키인 threadId를 Discord snowflake로 오인하지 않는다. */
    private fun replyTargetFor(action: ScheduledSocialAction): String? =
        if (action.type == ScheduledActionType.SPEAK) action.target.replyToMessageId else null

    private fun record(
        action: ScheduledSocialAction,
        phase: ActionAuditPhase,
        messageId: String? = null,
        reason: String? = null,
    ) {
        audit.append(
            ActionAuditEvent(
                actionId = action.identity.value,
                decisionId = action.decisionId,
                phase = phase,
                messageId = messageId,
                reason = reason,
                occurredAt = Instant.now(clock),
            ),
        )
    }

    private fun ScheduledSocialAction.toReevaluationTarget(): ReevaluationTarget =
        ReevaluationTarget(
            guildPseudonym = target.guildPseudonym,
            channelId = target.channelId,
            threadId = target.threadId,
        )

    companion object {
        const val REASON_CONTEXT_CHANGED: String = "context_changed_mid_burst"
        const val REASON_TOO_STALE: String = "too_stale_backpressure"
        const val REASON_REVOKED_MID_BURST: String = "consent_revoked_mid_burst"
    }
}

/**
 * 실행 결과(application sealed 값 객체). 테스트·관찰이 어떤 종결로 갔는지(전송 횟수·terminal state)를 확인한다.
 */
sealed interface ExecutionOutcome {
    /** shadow 차단 — 전송 0회(SUPPRESSED_SHADOW). */
    data class Suppressed(
        val mode: ShadowMode,
    ) : ExecutionOutcome

    /** 모든 버블 전송 완료(COMPLETED). [messageIds] 는 전송된 버블의 Discord 메시지 ID. */
    data class Completed(
        val messageIds: List<String>,
    ) : ExecutionOutcome

    /** 부분 전송 후 잔여 버블 취소(PARTIALLY_SENT→CANCELLED — T020). 이미 보낸 [messageIds] 보존. */
    data class PartiallyCancelled(
        val messageIds: List<String>,
        val reason: String,
    ) : ExecutionOutcome

    /** 전송 전 취소(CANCELLED — 예: backpressure staleness). */
    data class Cancelled(
        val reason: String,
    ) : ExecutionOutcome

    /** 영구 실패(FAILED — 권한/대상 부재 등, 다른 채널 fallback 없음, T018). */
    data class Failed(
        val reason: ActionFailureReason,
    ) : ExecutionOutcome

    /** transient 실패로 bounded 재시도 예약(SCHEDULED 복귀 — T009). [nextExecuteAfter] 에 [attempt] 회차로 재실행. */
    data class RetryScheduled(
        val reason: ActionFailureReason,
        val nextExecuteAfter: Instant,
        val attempt: Int,
    ) : ExecutionOutcome
}
