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
    /** 이 실행에서 발화 모델이 실제로 받은 최근 대화. 관리자 확인용 최근 메모리 추적이며 재시작 시 사라진다. */
    val currentConversation: List<ParticipationTraceMessage> = emptyList(),
    /** 대화 에피소드 RAG가 고른 원문. 런타임 연결 전에는 비어 있다. */
    val retrievedConversations: List<ParticipationTraceConversation> = emptyList(),
    /** 실제 모델 호출 직전에 조립된 입력. 메모리 trace 에만 남고 서버 재시작 시 사라진다. */
    val inputSnapshot: NiaInputSnapshot? = null,
    /** 발화 파이프라인이 최종 선택한 Discord 버블. 발화하지 않았으면 비어 있다. */
    val niaReply: List<String> = emptyList(),
    val features: ParticipationGateTraceFeatures,
) {
    init {
        require(correlationId.isNotBlank()) { "correlationId must not be blank" }
    }
}

data class ParticipationTraceMessage(
    val speaker: String,
    val text: String,
)

data class ParticipationTraceConversation(
    val id: String,
    val title: String,
    val score: Double,
    val scoringMethod: String,
    val expectedAction: String,
    val messages: List<ParticipationTraceMessage>,
    val expectedReplies: List<String> = emptyList(),
)

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
