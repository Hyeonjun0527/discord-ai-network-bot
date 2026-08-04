package com.discordassistant.central.speech.application.humanstyle

import com.discordassistant.central.speech.application.port.out.HumanSpeechStyleExampleStorePort
import com.discordassistant.central.speech.application.port.out.HumanSpeechStyleRagPort
import com.discordassistant.central.speech.application.port.out.SpeechStyleEmbeddingPort
import com.discordassistant.central.speech.domain.model.HumanSpeechResponseMode
import com.discordassistant.central.speech.domain.model.HumanSpeechStyleExample
import com.discordassistant.central.speech.domain.model.HumanSpeechStyleMatch
import com.discordassistant.central.speech.domain.model.HumanSpeechStyleSelection
import com.discordassistant.central.speech.domain.model.SpeechScenePacket
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Service
import kotlin.math.sqrt

/**
 * 현재 Speech 장면과 사람 말투 카드의 vector 유사도를 계산하는 실제 Speech-only RAG.
 *
 * 현재 private corpus 규모에서는 DB vector extension 없이 암호화된 벡터를 메모리에서 cosine으로 비교하는 편이 단순하고
 * 운영상 충분하다. 외부 embedding이 실패하거나 Judge 말투 enum이 없으면 사람 카드 없이 기존 Speech 생성으로 계속한다.
 */
@Service
class HumanSpeechStyleRagService(
    private val store: HumanSpeechStyleExampleStorePort,
    private val embeddingPort: SpeechStyleEmbeddingPort,
    @param:Value("\${central.nexa.speech-style-rag.enabled:true}") private val enabled: Boolean = true,
) : HumanSpeechStyleRagPort {
    override fun retrieve(packet: SpeechScenePacket): HumanSpeechStyleSelection {
        if (!enabled) return HumanSpeechStyleSelection.EMPTY
        val requestedMode = packet.styleResponseMode ?: return HumanSpeechStyleSelection.EMPTY
        val examples = store.listEnabled()
        if (examples.isEmpty()) return HumanSpeechStyleSelection.EMPTY

        val queryEmbedding =
            embeddingPort.embedAll(listOf(queryText(packet, requestedMode)))?.singleOrNull()
                ?: return HumanSpeechStyleSelection.EMPTY
        if (queryEmbedding.isEmpty()) return HumanSpeechStyleSelection.EMPTY

        return HumanSpeechStyleSelection(
            examples
                .asSequence()
                .mapNotNull { example -> score(example, queryEmbedding, requestedMode) }
                .filter { it.score >= MIN_SCORE }
                .sortedWith(compareByDescending<HumanSpeechStyleMatch> { it.score }.thenBy { it.example.exampleId })
                .take(HumanSpeechStyleSelection.MAX_MATCHES)
                .toList(),
        )
    }

    private fun score(
        example: HumanSpeechStyleExample,
        queryEmbedding: FloatArray,
        requestedMode: HumanSpeechResponseMode,
    ): HumanSpeechStyleMatch? {
        val semantic = cosineSimilarity(queryEmbedding, example.embedding) ?: return null
        val responseModeBonus = if (requestedMode == example.responseMode) 1.0 else 0.0
        return HumanSpeechStyleMatch(
            example = example,
            score = (semantic * (1.0 - DIRECT_MODE_WEIGHT) + responseModeBonus * DIRECT_MODE_WEIGHT).coerceIn(0.0, 1.0),
        )
    }

    private fun queryText(
        packet: SpeechScenePacket,
        requestedMode: HumanSpeechResponseMode,
    ): String =
        buildString {
            appendLine("현재 필요한 반응 방식: ${requestedMode.name}")
            packet.speechIntent?.let { appendLine("현재 발화 방향: ${it.take(MAX_INTENT_CHARS)}") }
            appendLine("현재 대화:")
            packet.recentTurns.takeLast(MAX_QUERY_TURNS).forEach { turn ->
                appendLine("- ${turn.speakerLabel}: ${turn.text.take(MAX_TURN_CHARS)}")
            }
        }.trim()

    private fun cosineSimilarity(
        left: FloatArray,
        right: FloatArray,
    ): Double? {
        if (left.size != right.size || left.isEmpty()) return null
        var dot = 0.0
        var leftMagnitude = 0.0
        var rightMagnitude = 0.0
        left.indices.forEach { index ->
            val leftValue = left[index].toDouble()
            val rightValue = right[index].toDouble()
            dot += leftValue * rightValue
            leftMagnitude += leftValue * leftValue
            rightMagnitude += rightValue * rightValue
        }
        val denominator = sqrt(leftMagnitude) * sqrt(rightMagnitude)
        return if (denominator > 0.0) (dot / denominator).coerceIn(-1.0, 1.0).coerceAtLeast(0.0) else null
    }

    private companion object {
        const val MAX_QUERY_TURNS: Int = 6
        const val MAX_TURN_CHARS: Int = 420
        const val MAX_INTENT_CHARS: Int = 800
        const val DIRECT_MODE_WEIGHT: Double = 0.20
        const val MIN_SCORE: Double = 0.28
    }
}
