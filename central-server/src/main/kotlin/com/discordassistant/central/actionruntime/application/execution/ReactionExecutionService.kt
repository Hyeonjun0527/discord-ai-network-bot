package com.discordassistant.central.actionruntime.application.execution

import com.discordassistant.central.actionruntime.application.port.out.ActionAuditPort
import com.discordassistant.central.actionruntime.application.port.out.ActionExecutionModePort
import com.discordassistant.central.actionruntime.application.port.out.ActionSchedulerPort
import com.discordassistant.central.actionruntime.application.port.out.DiscordExecutorPort
import com.discordassistant.central.actionruntime.application.port.out.ExecutionResult
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
    private val audit: ActionAuditPort,
    private val clock: Clock,
    private val modePort: ActionExecutionModePort = ActionExecutionModePort.REQUESTED_MODE,
) {
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

        return when (val result = executor.react(action.target.channelId, targetMessageId, emoji)) {
            ExecutionResult.Ok -> {
                record(action, ActionAuditPhase.COMPLETED)
                scheduler.complete(action.identity)
                ReactionOutcome.Reacted
            }
            // 실패 — SPEAK fallback 자동 유발 금지(T016). 사유 그대로 audit 후 종결.
            is ExecutionResult.Failed -> {
                record(action, ActionAuditPhase.FAILED, reason = result.reason.wireName)
                scheduler.fail(action.identity, result.reason)
                ReactionOutcome.Failed(result.reason)
            }
            // react 는 Ok/Failed 만 — 방어적.
            is ExecutionResult.Sent -> {
                record(action, ActionAuditPhase.COMPLETED)
                scheduler.complete(action.identity)
                ReactionOutcome.Reacted
            }
        }
    }

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

    /** reaction 실패 — SPEAK fallback 없이 FAILED 종결(권한/대상 부재/emoji 불가 등). */
    data class Failed(
        val reason: ActionFailureReason,
    ) : ReactionOutcome
}
