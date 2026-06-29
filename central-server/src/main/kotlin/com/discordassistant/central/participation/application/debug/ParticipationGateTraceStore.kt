package com.discordassistant.central.participation.application.debug

import com.discordassistant.central.participation.domain.model.shadow.ShadowMode
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Component
import java.time.Instant
import java.util.ArrayDeque
import java.util.concurrent.ConcurrentHashMap

@Component
class ParticipationGateTraceStore(
    @Value("\${central.nexa.participation.debug.max-traces-per-channel:100}")
    private val maxTracesPerChannel: Int = 100,
) {
    init {
        require(maxTracesPerChannel > 0) { "maxTracesPerChannel must be positive: $maxTracesPerChannel" }
    }

    private val traces = ConcurrentHashMap<TraceKey, ArrayDeque<ParticipationGateTrace>>()

    fun append(trace: ParticipationGateTrace) {
        val key = TraceKey(trace.guildId, trace.channelId)
        val channelTraces = traces.computeIfAbsent(key) { ArrayDeque() }
        synchronized(channelTraces) {
            channelTraces.addLast(trace)
            while (channelTraces.size > maxTracesPerChannel) {
                channelTraces.removeFirst()
            }
        }
    }

    fun recent(
        guildId: Long,
        channelId: Long,
        limit: Int = maxTracesPerChannel,
    ): List<ParticipationGateTrace> {
        require(guildId > 0) { "guildId must be positive: $guildId" }
        require(channelId > 0) { "channelId must be positive: $channelId" }
        val boundedLimit = limit.coerceIn(1, maxTracesPerChannel)
        val channelTraces = traces[TraceKey(guildId, channelId)] ?: return emptyList()
        return synchronized(channelTraces) {
            channelTraces
                .toList()
                .takeLast(boundedLimit)
                .asReversed()
        }
    }
}

data class ParticipationGateTrace(
    val correlationId: String,
    val guildId: Long,
    val channelId: Long,
    val sceneSeq: Long,
    val contextVersion: Long,
    val recordedAt: Instant,
    val mode: ShadowMode,
    val outcome: String,
    val reasonCode: String? = null,
    val policyAction: String? = null,
    val safeAction: String? = null,
    val speechOutcome: String? = null,
    val consentStage: String? = null,
    val willSpeak: Boolean? = null,
    val features: ParticipationGateTraceFeatures,
) {
    init {
        require(correlationId.isNotBlank()) { "correlationId must not be blank" }
    }
}

data class ParticipationGateTraceFeatures(
    val mentioned: Boolean,
    val replyToNia: Boolean,
    val duplicateOfPrevHuman: Boolean,
    val burstIncomplete: Boolean,
    val conversationMentionsNia: Boolean,
    val recentAgentBurstCount: Int,
    val hasTimestamp: Boolean,
)

private data class TraceKey(
    val guildId: Long,
    val channelId: Long,
)
