package com.discordassistant.central.actionruntime.application.execution

import com.discordassistant.central.actionruntime.application.port.out.ActionAuditPort
import com.discordassistant.central.actionruntime.application.port.out.ActionConsentPort
import com.discordassistant.central.actionruntime.application.port.out.ActionExecutionModePort
import com.discordassistant.central.actionruntime.application.port.out.ActionOutcomeObservationPort
import com.discordassistant.central.actionruntime.application.port.out.ActionReevaluationPort
import com.discordassistant.central.actionruntime.application.port.out.ActionSchedulerPort
import com.discordassistant.central.actionruntime.application.port.out.DiscordExecutorPort
import com.discordassistant.central.actionruntime.application.port.out.ExecutionLimits
import com.discordassistant.central.actionruntime.application.port.out.ExecutionPermitPort
import com.discordassistant.central.actionruntime.application.port.out.ExecutionResult
import com.discordassistant.central.actionruntime.application.port.out.ReevaluationTarget
import com.discordassistant.central.actionruntime.domain.OutboundDecision
import com.discordassistant.central.actionruntime.domain.OutboundGuard
import com.discordassistant.central.actionruntime.domain.model.ActionAuditEvent
import com.discordassistant.central.actionruntime.domain.model.ActionAuditPhase
import com.discordassistant.central.actionruntime.domain.model.ActionFailureReason
import com.discordassistant.central.actionruntime.domain.model.ActionStatus
import com.discordassistant.central.actionruntime.domain.model.ScheduledSocialAction
import com.discordassistant.central.participation.domain.model.shadow.ShadowMode
import java.time.Clock
import java.time.Instant

/**
 * REACT 행동 실행 use case(NEXA-P13-T016, application 레이어).
 *
 * REACT 는 권한·target 존재·emoji availability 를 확인해 reaction 을 단다. 실패하면 **조용히 실패 종결**한다 —
 * **acceptance(T016): reaction 실패가 SPEAK fallback 을 자동 유발하지 않는다.** 따라서 이 서비스는 어떤 실패에서도
 * 발화 경로([ActionExecutionService])를 호출하지 않고, 실패를 audit 후 FAILED 로 종결한다.
 *
 * **P09 shadow hard block**: 차단 단계(OBSERVE_ONLY 등)에서는 [DiscordExecutorPort.react] 를 한 번도 호출하지 않고
 * SUPPRESSED_SHADOW 만 audit 한다(전송 0회).
 *
 * 순수성 경계: application 레이어 — 포트·도메인 타입만. Spring/JPA/JDA 미참조.
 */
class ReactionExecutionService(
    private val executor: DiscordExecutorPort,
    private val scheduler: ActionSchedulerPort,
    private val reevaluation: ActionReevaluationPort,
    private val audit: ActionAuditPort,
    private val clock: Clock,
    private val modePort: ActionExecutionModePort = ActionExecutionModePort.REQUESTED_MODE,
    private val codeResolver: ReactionCodeResolver = ReactionCodeResolver(),
    private val outcomeObserver: ActionOutcomeObservationPort = ActionOutcomeObservationPort.Noop,
    private val consent: ActionConsentPort = ActionConsentPort.AllowForIsolatedTests,
    private val executionPermit: ExecutionPermitPort = ExecutionPermitPort.AllowAll,
) {
    /** 예약에 영속된 target과 안정 reaction code를 검증한 뒤 실행한다. */
    fun react(
        mode: ShadowMode,
        action: ScheduledSocialAction,
    ): ReactionOutcome {
        val targetMessageId = action.target.targetMessageId
        val emoji = codeResolver.resolve(action.reactionCode)
        if (targetMessageId == null || emoji == null) {
            val reason = if (targetMessageId == null) ActionFailureReason.TARGET_MISSING else ActionFailureReason.INVALID_PAYLOAD
            record(action, ActionAuditPhase.FAILED, reason = reason.wireName)
            scheduler.fail(action.identity, reason)
            return ReactionOutcome.Failed(reason)
        }
        return react(mode, action, targetMessageId, emoji)
    }

    /**
     * [mode] 에서 [action] 의 [emoji] reaction 을 [targetMessageId] 에 단다. 차단이면 전송 없이 Suppressed,
     * 성공이면 Completed, 실패면 **SPEAK fallback 없이** Failed 로 종결한다(T016 acceptance).
     */
    fun react(
        mode: ShadowMode,
        action: ScheduledSocialAction,
        targetMessageId: String,
        emoji: String,
    ): ReactionOutcome {
        require(action.status == ActionStatus.TYPING) {
            "ReactionExecutionService 는 TYPING 상태만 실행할 수 있다: ${action.status} (action=${action.identity.value})"
        }

        val requestedMode = action.originRolloutMode.restrictiveIntersection(mode)
        val currentMode = modePort.currentMode(action.target, requestedMode)
        if (OutboundGuard.decide(currentMode) == OutboundDecision.BLOCK) {
            record(action, ActionAuditPhase.SUPPRESSED_SHADOW, reason = currentMode.name)
            scheduler.cancel(action.identity)
            return ReactionOutcome.Suppressed(currentMode)
        }
        if (!consent.isAllowed(action.target)) {
            record(action, ActionAuditPhase.FAILED, reason = ActionFailureReason.CONSENT_REVOKED.wireName)
            scheduler.fail(action.identity, ActionFailureReason.CONSENT_REVOKED)
            return ReactionOutcome.Failed(ActionFailureReason.CONSENT_REVOKED)
        }

        val routingChannelId = action.target.discordChannelId()
        if (routingChannelId == null) {
            scheduler.fail(action.identity, ActionFailureReason.INVALID_PAYLOAD)
            return ReactionOutcome.Failed(ActionFailureReason.INVALID_PAYLOAD)
        }
        val permitId = "${action.identity.value}:react"
        if (!executionPermit.reserve(permitId, action.target.channelId, action.executionLimits())) {
            record(action, ActionAuditPhase.FAILED, reason = ActionFailureReason.EXECUTION_QUOTA_EXCEEDED.wireName)
            scheduler.fail(action.identity, ActionFailureReason.EXECUTION_QUOTA_EXCEEDED)
            return ReactionOutcome.Failed(ActionFailureReason.EXECUTION_QUOTA_EXCEEDED)
        }
        // Poller 재평가 뒤 실제 Discord 호출 사이에도 새 메시지가 도착할 수 있다. permit 확보 뒤 호출 직전에
        // 한 번 더 장면을 읽어 stale reaction이 나가는 경합 창을 닫는다.
        if (!isStillValid(action)) {
            executionPermit.release(permitId)
            record(action, ActionAuditPhase.CANCELLED, reason = REASON_CONTEXT_CHANGED_BEFORE_REACT)
            scheduler.cancel(action.identity)
            return ReactionOutcome.Cancelled(REASON_CONTEXT_CHANGED_BEFORE_REACT)
        }
        return when (val result = executor.react(routingChannelId, targetMessageId, emoji)) {
            ExecutionResult.Ok -> {
                runCatching { outcomeObserver.recordExecuted(action, targetMessageId, Instant.now(clock)) }
                record(action, ActionAuditPhase.COMPLETED)
                scheduler.complete(action.identity)
                ReactionOutcome.Reacted
            }
            // 실패 — SPEAK fallback 자동 유발 금지(T016). 사유 그대로 audit 후 종결.
            is ExecutionResult.Failed -> {
                executionPermit.release(permitId)
                record(action, ActionAuditPhase.FAILED, reason = result.reason.wireName)
                scheduler.fail(action.identity, result.reason)
                ReactionOutcome.Failed(result.reason)
            }
            // react 는 Ok/Failed 만 — 방어적.
            is ExecutionResult.Sent -> {
                runCatching { outcomeObserver.recordExecuted(action, result.messageId, Instant.now(clock)) }
                record(action, ActionAuditPhase.COMPLETED)
                scheduler.complete(action.identity)
                ReactionOutcome.Reacted
            }
        }
    }

    private fun ScheduledSocialAction.executionLimits(): ExecutionLimits =
        ExecutionLimits(executionPerChannelLimit, executionGlobalLimit, executionWindowSeconds)

    private fun isStillValid(action: ScheduledSocialAction): Boolean {
        val target = action.toReevaluationTarget()
        val current = reevaluation.currentContextVersion(target) ?: return false
        if (!action.isStale(current)) return true
        return reevaluation.stillValid(
            decisionId = action.decisionId,
            target = target,
            scheduledContextVersion = action.contextVersion,
            currentContextVersion = current,
        )
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

    private fun record(
        action: ScheduledSocialAction,
        phase: ActionAuditPhase,
        reason: String? = null,
    ) {
        audit.append(
            ActionAuditEvent(
                actionId = action.identity.value,
                decisionId = action.decisionId,
                phase = phase,
                reason = reason,
                occurredAt = Instant.now(clock),
            ),
        )
    }

    companion object {
        const val REASON_CONTEXT_CHANGED_BEFORE_REACT: String = "context_changed_before_react"
    }
}

/**
 * REACT 실행 결과(application sealed 값 객체). SPEAK fallback 분기가 **없음**을 타입으로 보장한다(T016).
 */
sealed interface ReactionOutcome {
    /** shadow 차단 — reaction 0회. */
    data class Suppressed(
        val mode: ShadowMode,
    ) : ReactionOutcome

    /** reaction 성공(COMPLETED). */
    data object Reacted : ReactionOutcome

    /** 실제 Discord 호출 직전 장면이 달라져 reaction을 보내지 않고 취소함. */
    data class Cancelled(
        val reason: String,
    ) : ReactionOutcome

    /** reaction 실패 — SPEAK fallback 없이 FAILED 종결(권한/대상 부재/emoji 불가 등). */
    data class Failed(
        val reason: ActionFailureReason,
    ) : ReactionOutcome
}
