package com.discordassistant.central.participation.adapter.inbound.web

import com.discordassistant.central.participation.application.debug.ParticipationGateTrace
import com.discordassistant.central.participation.application.debug.ParticipationGateTraceFeatures
import com.discordassistant.central.participation.application.debug.ParticipationGateTraceStore
import com.discordassistant.central.participation.application.port.out.DecisionLogRecord
import com.discordassistant.central.participation.application.port.out.ParticipationDecisionLogPort
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
    private val decisions: ParticipationDecisionLogPort,
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

    @GetMapping("/decisions/{correlationId}")
    fun decisionExplanation(
        @PathVariable correlationId: String,
    ): ParticipationDecisionExplanationDto? = decisions.findByCorrelationId(correlationId)?.toExplanationDto()
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
    val currentConversation: List<ParticipationTraceMessageDto>,
    val retrievedConversations: List<ParticipationTraceConversationDto>,
    val niaReply: List<String>,
    val features: ParticipationGateTraceFeaturesDto,
)

data class ParticipationTraceMessageDto(
    val speaker: String,
    val text: String,
)

data class ParticipationTraceConversationDto(
    val id: String,
    val messages: List<ParticipationTraceMessageDto>,
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

data class ParticipationDecisionExplanationDto(
    val correlationId: String,
    val action: String,
    val gate: String,
    val reasonCode: String?,
    val judgeConfidence: Double?,
    val decisionDelayMillis: Long?,
    val lastWakeUpReason: String?,
    val missingInputCodes: Set<String>,
    val evidenceRefs: Set<String>,
    val contextVersion: Long,
    val consumedGenerationQuota: Boolean,
    val modelVersion: String,
    val decidedAt: Instant,
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
        currentConversation = currentConversation.map { ParticipationTraceMessageDto(it.speaker, it.text) },
        retrievedConversations =
            retrievedConversations.take(2).map { conversation ->
                ParticipationTraceConversationDto(
                    id = conversation.id,
                    messages = conversation.messages.map { ParticipationTraceMessageDto(it.speaker, it.text) },
                )
            },
        niaReply = niaReply,
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

private fun DecisionLogRecord.toExplanationDto(): ParticipationDecisionExplanationDto =
    ParticipationDecisionExplanationDto(
        correlationId = correlationId,
        action = actionKind.wireName,
        gate = "DECISION_LOG",
        reasonCode = reasonCode,
        judgeConfidence = judgeConfidence,
        decisionDelayMillis = decisionDelayMillis,
        lastWakeUpReason = lastWakeUpReason,
        missingInputCodes = missingInputCodes,
        evidenceRefs = evidenceRefs,
        contextVersion = contextVersion,
        consumedGenerationQuota = consumedGenerationQuota,
        modelVersion = modelVersion,
        decidedAt = decidedAt,
    )
