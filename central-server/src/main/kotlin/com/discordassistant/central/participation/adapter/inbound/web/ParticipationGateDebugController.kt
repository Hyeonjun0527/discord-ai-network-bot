package com.discordassistant.central.participation.adapter.inbound.web

import com.discordassistant.central.participation.application.debug.ParticipationGateTrace
import com.discordassistant.central.participation.application.debug.ParticipationGateTraceFeatures
import com.discordassistant.central.participation.application.debug.ParticipationGateTraceStore
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import java.time.Instant

@RestController
@RequestMapping("/api/ai-network/nexa/debug/participation")
class ParticipationGateDebugController(
    private val traces: ParticipationGateTraceStore,
) {
    @GetMapping("/guilds/{guildId}/channels/{channelId}/traces")
    fun recentTraces(
        @PathVariable guildId: Long,
        @PathVariable channelId: Long,
        @RequestParam(defaultValue = "20") limit: Int,
    ): List<ParticipationGateTraceDto> =
        traces
            .recent(guildId = guildId, channelId = channelId, limit = limit)
            .map { it.toDto() }
}

data class ParticipationGateTraceDto(
    val correlationId: String,
    val guildId: Long,
    val channelId: Long,
    val sceneSeq: Long,
    val contextVersion: Long,
    val recordedAt: Instant,
    val mode: String,
    val evaluatesPolicy: Boolean,
    val allowsRealSend: Boolean,
    val outcome: String,
    val reasonCode: String?,
    val policyAction: String?,
    val safeAction: String?,
    val speechOutcome: String?,
    val consentStage: String?,
    val willSpeak: Boolean?,
    val features: ParticipationGateTraceFeaturesDto,
)

data class ParticipationGateTraceFeaturesDto(
    val mentioned: Boolean,
    val replyToNia: Boolean,
    val duplicateOfPrevHuman: Boolean,
    val burstIncomplete: Boolean,
    val conversationMentionsNia: Boolean,
    val recentAgentBurstCount: Int,
    val hasTimestamp: Boolean,
)

private fun ParticipationGateTrace.toDto(): ParticipationGateTraceDto =
    ParticipationGateTraceDto(
        correlationId = correlationId,
        guildId = guildId,
        channelId = channelId,
        sceneSeq = sceneSeq,
        contextVersion = contextVersion,
        recordedAt = recordedAt,
        mode = mode.name,
        evaluatesPolicy = mode.evaluatesPolicy,
        allowsRealSend = mode.allowsRealSend,
        outcome = outcome,
        reasonCode = reasonCode,
        policyAction = policyAction,
        safeAction = safeAction,
        speechOutcome = speechOutcome,
        consentStage = consentStage,
        willSpeak = willSpeak,
        features = features.toDto(),
    )

private fun ParticipationGateTraceFeatures.toDto(): ParticipationGateTraceFeaturesDto =
    ParticipationGateTraceFeaturesDto(
        mentioned = mentioned,
        replyToNia = replyToNia,
        duplicateOfPrevHuman = duplicateOfPrevHuman,
        burstIncomplete = burstIncomplete,
        conversationMentionsNia = conversationMentionsNia,
        recentAgentBurstCount = recentAgentBurstCount,
        hasTimestamp = hasTimestamp,
    )
