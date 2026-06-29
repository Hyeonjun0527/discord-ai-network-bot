package com.discordassistant.central.participation.application.runtime

import com.discordassistant.central.participation.domain.service.AttentionGateConstants
import com.discordassistant.central.participation.domain.service.ChannelAttentionGate
import java.util.concurrent.ConcurrentHashMap

/**
 * participation runtime event loop seam.
 *
 * Discord message ingress still calls the bridge per message, but humanlike participation also needs idle polling and
 * pending-action wake-up. This application component owns that timing surface while every wake leads to one downstream
 * single-judge evaluation.
 */
class ParticipationRuntimeLoop {
    private val attentionStates = ConcurrentHashMap<String, ChannelAttentionGate.ChannelAttentionState>()
    private val threadStates = ConcurrentHashMap<String, RuntimeThreadState>()

    fun onMessage(event: ParticipationRuntimeMessageEvent): ParticipationRuntimeDecision {
        val attentionState = attentionStates.computeIfAbsent(event.scope.key) { ChannelAttentionGate.ChannelAttentionState() }
        val threadState = threadStates.computeIfAbsent(event.scope.key) { RuntimeThreadState() }
        val turnTaking = synchronized(threadState) { threadState.record(event) }
        val wake =
            synchronized(attentionState) {
                ChannelAttentionGate.decide(
                    tsMs = event.tsMs,
                    isNia = event.isNia,
                    hardPolicy = event.hardPolicy,
                    state = attentionState,
                )
            }
        val signals = event.toSignals(turnTaking)
        return when (wake.action) {
            AttentionGateConstants.WAKE_NOW ->
                ParticipationRuntimeDecision.EvaluateNow(wake.reasonCode, signals)
            AttentionGateConstants.WAKE_AFTER_IDLE ->
                ParticipationRuntimeDecision.ScheduleIdleReevaluation(
                    deadlineMs = requireNotNull(wake.idleDeadlineMs) { "idle deadline is required for WAKE_AFTER_IDLE" },
                    reasonCode = wake.reasonCode,
                    signals = signals,
                )
            AttentionGateConstants.WAIT ->
                ParticipationRuntimeDecision.Wait(wake.reasonCode, wake.idleDeadlineMs, signals)
            else ->
                ParticipationRuntimeDecision.NoWake(wake.reasonCode, signals)
        }
    }

    fun onTyping(
        scope: ParticipationRuntimeScope,
        tsMs: Long,
    ): ParticipationRuntimeDecision.Wait {
        require(tsMs >= 0) { "tsMs 는 음수일 수 없다: $tsMs" }
        val state = attentionStates.computeIfAbsent(scope.key) { ChannelAttentionGate.ChannelAttentionState() }
        val wake = synchronized(state) { ChannelAttentionGate.onTyping(tsMs = tsMs, state = state) }
        return ParticipationRuntimeDecision.Wait(wake.reasonCode, wake.idleDeadlineMs, ParticipationRuntimeSceneSignals.EMPTY)
    }

    fun onIdleTick(
        scope: ParticipationRuntimeScope,
        nowMs: Long,
    ): ParticipationRuntimeDecision {
        require(nowMs >= 0) { "nowMs 는 음수일 수 없다: $nowMs" }
        val state = attentionStates[scope.key] ?: return ParticipationRuntimeDecision.NoWake("NO_PENDING_IDLE")
        return synchronized(state) {
            if (!ChannelAttentionGate.idleDue(nowMs = nowMs, state = state)) {
                ParticipationRuntimeDecision.NoWake("IDLE_NOT_DUE")
            } else {
                ChannelAttentionGate.clearPending(state)
                ParticipationRuntimeDecision.EvaluateNow("IDLE_DUE", ParticipationRuntimeSceneSignals.EMPTY)
            }
        }
    }

    fun onPendingActionWake(
        pending: ParticipationRuntimePendingAction,
        latest: ParticipationRuntimeLatestScene,
    ): ParticipationRuntimeDecision =
        when {
            latest.targetExpired || latest.resolvedLikely || latest.humanRepliesSinceSchedule >= SUFFICIENT_HUMAN_REPLIES ->
                ParticipationRuntimeDecision.CancelPending(pending.pendingActionId, latest.cancelReason())
            latest.contextVersion != pending.scheduledContextVersion ->
                ParticipationRuntimeDecision.ReevaluatePending(pending.pendingActionId, "CONTEXT_VERSION_CHANGED")
            else ->
                ParticipationRuntimeDecision.ReevaluatePending(pending.pendingActionId, "PENDING_DUE")
        }

    private fun ParticipationRuntimeMessageEvent.toSignals(turnTaking: RuntimeTurnTakingUpdate): ParticipationRuntimeSceneSignals =
        ParticipationRuntimeSceneSignals(
            directAddressPressure = turnTaking.directAddressPressure,
            replyChainDepth = replyChainDepth,
            nicknameCall = nicknameCall,
            previousIgnoredRequestCount = previousIgnoredRequestCount,
            humansTalkingToEachOtherLikely = humansTalkingToEachOtherLikely || (replyToHuman && !addressedToNia),
            niaAddressedOrIdleOpportunity = addressedToNia,
            rateLimitPressure = rateLimitPressure,
            antiSpamPressure = antiSpamPressure,
        )

    private fun ParticipationRuntimeLatestScene.cancelReason(): String =
        when {
            targetExpired -> "TARGET_EXPIRED"
            resolvedLikely -> "ALREADY_RESOLVED"
            else -> "OTHER_HUMAN_ANSWERED"
        }

    private class RuntimeThreadState {
        private var pressure: Double = 0.0

        fun record(event: ParticipationRuntimeMessageEvent): RuntimeTurnTakingUpdate {
            pressure =
                if (event.addressedToNia) {
                    (pressure + 0.35 + event.previousIgnoredRequestCount * 0.1).coerceIn(0.0, 1.0)
                } else {
                    (pressure * 0.65).coerceIn(0.0, 1.0)
                }
            return RuntimeTurnTakingUpdate(directAddressPressure = pressure)
        }
    }

    private data class RuntimeTurnTakingUpdate(
        val directAddressPressure: Double,
    )

    companion object {
        private const val SUFFICIENT_HUMAN_REPLIES: Int = 2
    }
}

data class ParticipationRuntimeScope(
    val guildPseudonym: String,
    val channelId: String,
    val threadId: String,
) {
    init {
        require(guildPseudonym.isNotBlank()) { "guildPseudonym 은 비어 있을 수 없다" }
        require(channelId.isNotBlank()) { "channelId 는 비어 있을 수 없다" }
        require(threadId.isNotBlank()) { "threadId 는 비어 있을 수 없다" }
    }

    val key: String = "$guildPseudonym:$channelId:$threadId"
}

data class ParticipationRuntimeMessageEvent(
    val scope: ParticipationRuntimeScope,
    val tsMs: Long,
    val isNia: Boolean,
    val hardPolicy: String?,
    val directAddressed: Boolean,
    val replyToNia: Boolean,
    val replyToHuman: Boolean,
    val conversationMentionsNia: Boolean,
    val nicknameCall: Boolean,
    val replyChainDepth: Int,
    val previousIgnoredRequestCount: Int,
    val humansTalkingToEachOtherLikely: Boolean,
    val rateLimitPressure: Double = 0.0,
    val antiSpamPressure: Double = 0.0,
) {
    init {
        require(tsMs >= 0) { "tsMs 는 음수일 수 없다: $tsMs" }
        require(replyChainDepth >= 0) { "replyChainDepth 는 음수일 수 없다: $replyChainDepth" }
        require(previousIgnoredRequestCount >= 0) {
            "previousIgnoredRequestCount 는 음수일 수 없다: $previousIgnoredRequestCount"
        }
        require(rateLimitPressure in 0.0..1.0) { "rateLimitPressure 는 [0,1] 범위여야 한다: $rateLimitPressure" }
        require(antiSpamPressure in 0.0..1.0) { "antiSpamPressure 는 [0,1] 범위여야 한다: $antiSpamPressure" }
    }

    val addressedToNia: Boolean
        get() = directAddressed || replyToNia || nicknameCall || conversationMentionsNia
}

data class ParticipationRuntimePendingAction(
    val pendingActionId: String,
    val scheduledContextVersion: Long,
) {
    init {
        require(pendingActionId.isNotBlank()) { "pendingActionId 는 비어 있을 수 없다" }
        require(scheduledContextVersion >= 0) { "scheduledContextVersion 은 음수일 수 없다: $scheduledContextVersion" }
    }
}

data class ParticipationRuntimeLatestScene(
    val contextVersion: Long,
    val humanRepliesSinceSchedule: Int,
    val resolvedLikely: Boolean,
    val targetExpired: Boolean,
) {
    init {
        require(contextVersion >= 0) { "contextVersion 은 음수일 수 없다: $contextVersion" }
        require(humanRepliesSinceSchedule >= 0) {
            "humanRepliesSinceSchedule 는 음수일 수 없다: $humanRepliesSinceSchedule"
        }
    }
}

sealed interface ParticipationRuntimeDecision {
    val reasonCode: String
    val signals: ParticipationRuntimeSceneSignals

    data class EvaluateNow(
        override val reasonCode: String,
        override val signals: ParticipationRuntimeSceneSignals = ParticipationRuntimeSceneSignals.EMPTY,
    ) : ParticipationRuntimeDecision

    data class ScheduleIdleReevaluation(
        val deadlineMs: Long,
        override val reasonCode: String,
        override val signals: ParticipationRuntimeSceneSignals,
    ) : ParticipationRuntimeDecision {
        init {
            require(deadlineMs >= 0) { "deadlineMs 는 음수일 수 없다: $deadlineMs" }
        }
    }

    data class Wait(
        override val reasonCode: String,
        val idleDeadlineMs: Long?,
        override val signals: ParticipationRuntimeSceneSignals,
    ) : ParticipationRuntimeDecision

    data class NoWake(
        override val reasonCode: String,
        override val signals: ParticipationRuntimeSceneSignals = ParticipationRuntimeSceneSignals.EMPTY,
    ) : ParticipationRuntimeDecision

    data class ReevaluatePending(
        val pendingActionId: String,
        override val reasonCode: String,
    ) : ParticipationRuntimeDecision {
        override val signals: ParticipationRuntimeSceneSignals = ParticipationRuntimeSceneSignals.EMPTY
    }

    data class CancelPending(
        val pendingActionId: String,
        override val reasonCode: String,
    ) : ParticipationRuntimeDecision {
        override val signals: ParticipationRuntimeSceneSignals = ParticipationRuntimeSceneSignals.EMPTY
    }
}

data class ParticipationRuntimeSceneSignals(
    val directAddressPressure: Double,
    val replyChainDepth: Int,
    val nicknameCall: Boolean,
    val previousIgnoredRequestCount: Int,
    val humansTalkingToEachOtherLikely: Boolean,
    val niaAddressedOrIdleOpportunity: Boolean,
    val rateLimitPressure: Double,
    val antiSpamPressure: Double,
) {
    init {
        require(directAddressPressure in 0.0..1.0) {
            "directAddressPressure 는 [0,1] 범위여야 한다: $directAddressPressure"
        }
        require(replyChainDepth >= 0) { "replyChainDepth 는 음수일 수 없다: $replyChainDepth" }
        require(previousIgnoredRequestCount >= 0) {
            "previousIgnoredRequestCount 는 음수일 수 없다: $previousIgnoredRequestCount"
        }
        require(rateLimitPressure in 0.0..1.0) { "rateLimitPressure 는 [0,1] 범위여야 한다: $rateLimitPressure" }
        require(antiSpamPressure in 0.0..1.0) { "antiSpamPressure 는 [0,1] 범위여야 한다: $antiSpamPressure" }
    }

    companion object {
        val EMPTY =
            ParticipationRuntimeSceneSignals(
                directAddressPressure = 0.0,
                replyChainDepth = 0,
                nicknameCall = false,
                previousIgnoredRequestCount = 0,
                humansTalkingToEachOtherLikely = false,
                niaAddressedOrIdleOpportunity = false,
                rateLimitPressure = 0.0,
                antiSpamPressure = 0.0,
            )
    }
}
