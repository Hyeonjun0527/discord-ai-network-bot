package com.discordassistant.central.participation.application.debug

import com.discordassistant.central.speech.application.port.out.SpeechInputTracePort
import org.springframework.stereotype.Component
import java.util.concurrent.ConcurrentHashMap

@Component
class NiaInputTraceStore : SpeechInputTracePort {
    private val snapshots = ConcurrentHashMap<String, NiaInputSnapshot>()

    fun recordJudge(
        traceId: String,
        judgePrompt: String,
        globalFewShotSetId: Long?,
        globalFewShotVersion: Int?,
        globalFewShotExampleCount: Int,
        ragQuery: String,
        ragMatches: List<ParticipationTraceConversation>,
    ) {
        snapshots.compute(traceId) { _, current ->
            (current ?: NiaInputSnapshot()).copy(
                judgePrompt = judgePrompt,
                globalFewShotSetId = globalFewShotSetId,
                globalFewShotVersion = globalFewShotVersion,
                globalFewShotExampleCount = globalFewShotExampleCount,
                ragQuery = ragQuery,
                ragMatches = ragMatches,
            )
        }
    }

    override fun record(
        traceId: String,
        systemPrompt: String,
        userPrompt: String,
    ) {
        snapshots.compute(traceId) { _, current ->
            (current ?: NiaInputSnapshot()).copy(
                speechSystemPrompt = systemPrompt,
                speechUserPrompt = userPrompt,
            )
        }
    }

    fun take(traceId: String): NiaInputSnapshot? = snapshots.remove(traceId)
}

data class NiaInputSnapshot(
    val judgePrompt: String? = null,
    val speechSystemPrompt: String? = null,
    val speechUserPrompt: String? = null,
    val globalFewShotSetId: Long? = null,
    val globalFewShotVersion: Int? = null,
    val globalFewShotExampleCount: Int = 0,
    val ragQuery: String? = null,
    val ragMatches: List<ParticipationTraceConversation> = emptyList(),
)
