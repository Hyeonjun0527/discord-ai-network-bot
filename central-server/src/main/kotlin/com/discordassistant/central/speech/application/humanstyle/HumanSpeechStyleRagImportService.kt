package com.discordassistant.central.speech.application.humanstyle

import com.discordassistant.central.speech.application.port.out.HumanSpeechStyleExampleStorePort
import com.discordassistant.central.speech.application.port.out.SpeechStyleEmbeddingPort
import com.discordassistant.central.speech.domain.model.HumanSpeechResponseMode
import com.discordassistant.central.speech.domain.model.HumanSpeechStyleBubble
import com.discordassistant.central.speech.domain.model.HumanSpeechStyleExample
import com.discordassistant.central.speech.domain.model.HumanSpeechStyleQuality
import com.fasterxml.jackson.annotation.JsonProperty
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Service
import java.nio.file.Files
import java.nio.file.Path

/**
 * 사용자 승인된 private human-review JSONL을 Speech 말투 RAG 저장소로 적재한다.
 *
 * 외부 파일의 source path/메시지 ID/원문 추적 메타는 수용하지 않고, runtime 계약의 최소 필드만 받는다. 응답 말풍선은
 * DB에는 암호화해 보관하지만 embedding 요청에는 넣지 않는다.
 */
@Service
class HumanSpeechStyleRagImportService(
    private val store: HumanSpeechStyleExampleStorePort,
    private val embeddingPort: SpeechStyleEmbeddingPort,
    @param:Value("\${central.nexa.speech-style-rag.embedding-model:text-embedding-3-small}")
    private val embeddingModel: String = DEFAULT_EMBEDDING_MODEL,
) {
    private val mapper = jacksonObjectMapper().findAndRegisterModules()

    fun importJsonLines(file: Path): HumanSpeechStyleImportReport {
        require(Files.isRegularFile(file)) { "human speech style import file does not exist" }
        val cards = readCards(file)
        val vectors = embeddingPort.embedAll(cards.map(HumanSpeechStyleImportCard::retrievalText))
        require(vectors != null && vectors.size == cards.size) { "human speech style embedding is unavailable" }
        validateVectors(vectors)

        val examples =
            cards.mapIndexed { index, card ->
                card.toExample(vectors[index], embeddingModel)
            }
        val imported = store.replaceAll(examples)
        return HumanSpeechStyleImportReport(
            importedCount = imported,
            responseModeCounts = examples.groupingBy(HumanSpeechStyleExample::responseMode).eachCount(),
            embeddingModel = embeddingModel,
        )
    }

    private fun readCards(file: Path): List<HumanSpeechStyleImportCard> {
        val cards =
            Files
                .newBufferedReader(file)
                .useLines { lines ->
                    lines
                        .filter(String::isNotBlank)
                        .mapIndexed { index, line ->
                            parseCard(line, index + 1)
                        }.toList()
                }
        require(cards.isNotEmpty()) { "human speech style import file is empty" }
        require(cards.size <= MAX_IMPORT_CARDS) { "human speech style import has too many cards" }
        require(cards.map(HumanSpeechStyleImportCard::exampleId).toSet().size == cards.size) {
            "human speech style import contains duplicate example ids"
        }
        return cards
    }

    private fun parseCard(
        line: String,
        lineNumber: Int,
    ): HumanSpeechStyleImportCard =
        try {
            mapper.readValue<HumanSpeechStyleImportCard>(line).validated()
        } catch (error: com.fasterxml.jackson.core.JsonProcessingException) {
            throw IllegalArgumentException("human speech style import JSON is invalid at line $lineNumber", error)
        } catch (error: IllegalArgumentException) {
            throw IllegalArgumentException("human speech style import card is invalid at line $lineNumber", error)
        }

    private fun validateVectors(vectors: List<FloatArray>) {
        val dimensions = vectors.firstOrNull()?.size ?: 0
        require(dimensions in 1..MAX_EMBEDDING_DIMENSIONS) { "human speech style embedding dimensions are invalid" }
        require(vectors.all { vector -> vector.size == dimensions && vector.all(Float::isFinite) }) {
            "human speech style embedding vectors are invalid"
        }
    }

    private companion object {
        const val MAX_IMPORT_CARDS: Int = 2_000
        const val MAX_EMBEDDING_DIMENSIONS: Int = 8_192
        const val DEFAULT_EMBEDDING_MODEL: String = "text-embedding-3-small"
    }
}

data class HumanSpeechStyleImportReport(
    val importedCount: Int,
    val responseModeCounts: Map<HumanSpeechResponseMode, Int>,
    val embeddingModel: String,
)

private data class HumanSpeechStyleImportCard(
    val schema: String,
    @param:JsonProperty("example_id") val exampleId: String,
    @param:JsonProperty("response_mode") val responseMode: String,
    val situation: String,
    @param:JsonProperty("style_signals") val styleSignals: List<String>,
    @param:JsonProperty("context_bubbles") val contextBubbles: List<HumanSpeechStyleImportBubble>,
    @param:JsonProperty("response_bubbles") val responseBubbles: List<HumanSpeechStyleImportBubble>,
    val quality: String,
    @param:JsonProperty("source_fingerprint") val sourceFingerprint: String,
    @param:JsonProperty("consent_revision") val consentRevision: String,
    @param:JsonProperty("combined_chars") val combinedChars: Int,
    @param:JsonProperty("embedding_model") val embeddingModel: String = DEFAULT_EMBEDDING_MODEL,
) {
    fun validated(): HumanSpeechStyleImportCard {
        require(schema == IMPORT_SCHEMA) { "human speech style import schema is unsupported" }
        require(quality == HumanSpeechStyleQuality.USER_AUTHORIZED_CANDIDATE.name) {
            "human speech style import quality is unsupported"
        }
        require(sourceFingerprint.matches(SOURCE_FINGERPRINT)) { "human speech style source fingerprint is invalid" }
        require(consentRevision.matches(CONSENT_REVISION)) { "human speech style consent revision is invalid" }
        HumanSpeechStyleExample(
            exampleId = exampleId,
            responseMode = HumanSpeechResponseMode.valueOf(responseMode),
            situation = situation,
            styleSignals = styleSignals,
            contextBubbles = contextBubbles.map(HumanSpeechStyleImportBubble::toDomain),
            responseBubbles = responseBubbles.map(HumanSpeechStyleImportBubble::toDomain),
            quality = HumanSpeechStyleQuality.USER_AUTHORIZED_CANDIDATE,
            sourceFingerprint = sourceFingerprint,
            consentRevision = consentRevision,
            combinedChars = combinedChars,
            embedding = floatArrayOf(1f),
            embeddingModel = embeddingModel,
        )
        return this
    }

    fun retrievalText(): String =
        buildString {
            appendLine("반응 방식: $responseMode")
            appendLine("상황: $situation")
            if (styleSignals.isNotEmpty()) appendLine("말투 신호: ${styleSignals.joinToString(", ")}")
            appendLine("앞 대화:")
            contextBubbles.forEach { bubble -> appendLine("- ${bubble.speaker}: ${bubble.text}") }
        }.trim()

    fun toExample(
        embedding: FloatArray,
        actualEmbeddingModel: String,
    ): HumanSpeechStyleExample =
        HumanSpeechStyleExample(
            exampleId = exampleId,
            responseMode = HumanSpeechResponseMode.valueOf(responseMode),
            situation = situation,
            styleSignals = styleSignals,
            contextBubbles = contextBubbles.map(HumanSpeechStyleImportBubble::toDomain),
            responseBubbles = responseBubbles.map(HumanSpeechStyleImportBubble::toDomain),
            quality = HumanSpeechStyleQuality.USER_AUTHORIZED_CANDIDATE,
            sourceFingerprint = sourceFingerprint,
            consentRevision = consentRevision,
            combinedChars = combinedChars,
            embedding = embedding,
            embeddingModel = actualEmbeddingModel,
        )

    private companion object {
        const val IMPORT_SCHEMA: String = "nia-human-speech-style-import-card.v1"
        const val DEFAULT_EMBEDDING_MODEL: String = "text-embedding-3-small"
        val SOURCE_FINGERPRINT = Regex("sha256:[0-9a-f]{64}")
        val CONSENT_REVISION = Regex("[A-Za-z0-9._-]{1,96}")
    }
}

private data class HumanSpeechStyleImportBubble(
    val speaker: String,
    val text: String,
) {
    fun toDomain(): HumanSpeechStyleBubble = HumanSpeechStyleBubble(speaker = speaker, text = text)
}
