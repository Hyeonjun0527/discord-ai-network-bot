package com.discordassistant.central.speech.application.humanstyle

import com.discordassistant.central.speech.application.port.out.HumanSpeechStyleExampleStorePort
import com.discordassistant.central.speech.application.port.out.SpeechStyleEmbeddingPort
import com.discordassistant.central.speech.domain.model.HumanSpeechResponseMode
import com.discordassistant.central.speech.domain.model.HumanSpeechSceneTrait
import com.discordassistant.central.speech.domain.model.HumanSpeechStyleBubble
import com.discordassistant.central.speech.domain.model.HumanSpeechStyleExample
import com.discordassistant.central.speech.domain.model.HumanSpeechStylePromptSurface
import com.discordassistant.central.speech.domain.model.HumanSpeechStyleProviderStyleCue
import com.discordassistant.central.speech.domain.model.HumanSpeechStyleQuality
import com.discordassistant.central.speech.domain.model.HumanSpeechStyleResponseForm
import com.discordassistant.central.speech.domain.model.HumanSpeechStyleResponseMove
import com.discordassistant.central.speech.domain.model.HumanSpeechStyleResponseMoveProvenance
import com.discordassistant.central.speech.domain.model.HumanSpeechStyleRhythmCue
import com.discordassistant.central.speech.domain.model.humanSpeechStyleRetrievalText
import com.discordassistant.central.speech.domain.model.humanSpeechStyleRhythmText
import com.fasterxml.jackson.annotation.JsonProperty
import com.fasterxml.jackson.databind.DeserializationFeature
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Service
import java.nio.file.Files
import java.nio.file.Path

/**
 * 사용자 승인된 private human-review JSONL을 Speech 말투 RAG 저장소로 적재한다.
 *
 * 외부 파일의 source path/메시지 ID/원문 추적 메타는 수용하지 않고, runtime 계약의 최소 필드만 받는다. 원문 대화와
 * 응답 말풍선은 DB에만 암호화해 보관하고 embedding 요청과 Speech provider prompt에는 넣지 않는다. 대신 이미 일반화된
 * 장면·말투 신호와 답변에서 로컬로 계산한 닫힌 리듬 표지만 각각의 검색 벡터와 provider style pattern에 쓴다.
 */
@Service
class HumanSpeechStyleRagImportService(
    private val store: HumanSpeechStyleExampleStorePort,
    private val embeddingPort: SpeechStyleEmbeddingPort,
    @param:Value("\${central.nexa.speech-style-rag.embedding-model:text-embedding-3-small}")
    private val embeddingModel: String = DEFAULT_EMBEDDING_MODEL,
) {
    private val mapper =
        jacksonObjectMapper()
            .findAndRegisterModules()
            .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, true)
            .configure(DeserializationFeature.FAIL_ON_MISSING_CREATOR_PROPERTIES, true)

    /**
     * [HumanSpeechStyleRagImportArtifactVerifier]가 봉인 artifact를 검증한 뒤에만 호출하는 module-internal 적재 단계다.
     * startup 환경에서 JSONL 경로만으로 이 메서드에 도달하는 경로는 두지 않는다.
     */
    internal fun importJsonLines(
        file: Path,
        allowedQualities: Set<HumanSpeechStyleQuality> = setOf(HumanSpeechStyleQuality.CURATION_APPROVED),
    ): HumanSpeechStyleImportReport {
        require(Files.isRegularFile(file)) { "human speech style import file does not exist" }
        require(allowedQualities.isNotEmpty()) { "human speech style import allowed qualities are empty" }
        val cards = readCards(file)
        require(cards.all { it.runtimeQuality() in allowedQualities }) {
            "human speech style import contains a disallowed quality"
        }
        val eligibleCards = cards.filter(HumanSpeechStyleImportCard::isPromptEligible)
        val embeddingsByExampleId =
            eligibleCards
                .zip(embedEligibleCards(eligibleCards))
                .associate { (card, vectors) -> card.exampleId to vectors }
        val auditOnlyEmbeddingDimension =
            embeddingsByExampleId.values
                .firstOrNull()
                ?.scene
                ?.size ?: AUDIT_ONLY_EMBEDDING_DIMENSION

        val examples =
            cards.map { card ->
                card.toExample(
                    embedding = embeddingsByExampleId[card.exampleId]?.scene ?: auditOnlyEmbedding(auditOnlyEmbeddingDimension),
                    rhythmEmbedding = embeddingsByExampleId[card.exampleId]?.rhythm ?: auditOnlyEmbedding(auditOnlyEmbeddingDimension),
                    actualEmbeddingModel = embeddingModel,
                )
            }
        val imported = store.replaceAll(examples)
        return HumanSpeechStyleImportReport(
            importedCount = imported,
            promptEligibleCount = examples.count(HumanSpeechStyleExample::promptEligible),
            responseModeCounts = examples.groupingBy(HumanSpeechStyleExample::responseMode).eachCount(),
            embeddingModel = embeddingModel,
        )
    }

    private fun embedEligibleCards(cards: List<HumanSpeechStyleImportCard>): List<HumanSpeechStyleImportEmbeddings> {
        if (cards.isEmpty()) return emptyList()
        val texts = cards.flatMap { card -> listOf(card.retrievalText(), card.responseRhythmText()) }
        val vectors = embeddingPort.embedAll(texts)
        require(vectors != null && vectors.size == texts.size) { "human speech style embedding is unavailable" }
        validateVectors(vectors)
        return cards.indices.map { index ->
            HumanSpeechStyleImportEmbeddings(
                scene = vectors[index * EMBEDDINGS_PER_CARD],
                rhythm = vectors[index * EMBEDDINGS_PER_CARD + 1],
            )
        }
    }

    private fun auditOnlyEmbedding(dimension: Int): FloatArray = FloatArray(dimension)

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
            val node = mapper.readTree(line)
            require(node.isObject && node.fieldNames().asSequence().toSet() == HumanSpeechStyleImportCard.IMPORT_CARD_FIELDS) {
                "human speech style import card fields are invalid"
            }
            mapper.readValue<HumanSpeechStyleImportCard>(node.traverse(mapper)).validated()
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
        const val AUDIT_ONLY_EMBEDDING_DIMENSION: Int = 1
        const val DEFAULT_EMBEDDING_MODEL: String = "text-embedding-3-small"
        const val EMBEDDINGS_PER_CARD: Int = 2
    }
}

private data class HumanSpeechStyleImportEmbeddings(
    val scene: FloatArray,
    val rhythm: FloatArray,
)

data class HumanSpeechStyleImportReport(
    val importedCount: Int,
    val promptEligibleCount: Int,
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
    @param:JsonProperty("prompt_eligible") val promptEligible: Boolean,
    @param:JsonProperty("prompt_surface") val promptSurface: String,
    /** Exporter가 card-local 가명 metadata와 응답 표면을 대조해 남긴 비가역 안전 표지다. */
    @param:JsonProperty("response_surface_has_card_local_alias")
    val responseSurfaceHasCardLocalAlias: Boolean,
    @param:JsonProperty("response_move") val responseMove: String?,
    @param:JsonProperty("scene_traits") val sceneTraits: List<String>,
    @param:JsonProperty("provider_style_cues") val providerStyleCues: List<String>,
    @param:JsonProperty("response_move_provenance") val responseMoveProvenance: String,
    @param:JsonProperty("response_form") val responseForm: String?,
    @param:JsonProperty("response_rhythm") val responseRhythm: List<String>,
    @param:JsonProperty("embedding_model") val embeddingModel: String,
) {
    fun validated(): HumanSpeechStyleImportCard {
        require(schema == IMPORT_SCHEMA) { "human speech style import schema is unsupported" }
        runtimeQuality()
        require(sourceFingerprint.matches(SOURCE_FINGERPRINT)) { "human speech style source fingerprint is invalid" }
        require(consentRevision.matches(CONSENT_REVISION)) { "human speech style consent revision is invalid" }
        HumanSpeechStyleExample(
            exampleId = exampleId,
            responseMode = HumanSpeechResponseMode.valueOf(responseMode),
            situation = situation,
            styleSignals = styleSignals,
            contextBubbles = contextBubbles.map(HumanSpeechStyleImportBubble::toDomain),
            responseBubbles = responseBubbles.map(HumanSpeechStyleImportBubble::toDomain),
            quality = runtimeQuality(),
            sourceFingerprint = sourceFingerprint,
            consentRevision = consentRevision,
            combinedChars = combinedChars,
            promptEligible = isPromptEligible(),
            promptSurface = runtimePromptSurface(),
            responseMove = runtimeResponseMove(),
            sceneTraits = runtimeSceneTraits(),
            providerStyleCues = runtimeProviderStyleCues(),
            responseMoveProvenance = runtimeResponseMoveProvenance(),
            responseForm = runtimeResponseForm(),
            responseRhythm = runtimeResponseRhythm(),
            embedding = floatArrayOf(1f),
            embeddingModel = embeddingModel,
        )
        return this
    }

    fun retrievalText(): String =
        humanSpeechStyleRetrievalText(
            responseMode = HumanSpeechResponseMode.valueOf(responseMode),
            responseMove = runtimeResponseMove(),
            sceneTraits = runtimeSceneTraits(),
            providerStyleCues = runtimeProviderStyleCues(),
            responseForm = runtimeResponseForm(),
            responseRhythm = runtimeResponseRhythm(),
        )

    fun responseRhythmText(): String =
        humanSpeechStyleRhythmText(
            responseMode = HumanSpeechResponseMode.valueOf(responseMode),
            responseRhythm = runtimeResponseRhythm(),
        )

    fun toExample(
        embedding: FloatArray,
        rhythmEmbedding: FloatArray,
        actualEmbeddingModel: String,
    ): HumanSpeechStyleExample =
        HumanSpeechStyleExample(
            exampleId = exampleId,
            responseMode = HumanSpeechResponseMode.valueOf(responseMode),
            situation = situation,
            styleSignals = styleSignals,
            contextBubbles = contextBubbles.map(HumanSpeechStyleImportBubble::toDomain),
            responseBubbles = responseBubbles.map(HumanSpeechStyleImportBubble::toDomain),
            quality = runtimeQuality(),
            sourceFingerprint = sourceFingerprint,
            consentRevision = consentRevision,
            combinedChars = combinedChars,
            promptEligible = isPromptEligible(),
            promptSurface = runtimePromptSurface(),
            responseMove = runtimeResponseMove(),
            sceneTraits = runtimeSceneTraits(),
            providerStyleCues = runtimeProviderStyleCues(),
            responseMoveProvenance = runtimeResponseMoveProvenance(),
            responseForm = runtimeResponseForm(),
            responseRhythm = runtimeResponseRhythm(),
            embedding = embedding,
            embeddingModel = actualEmbeddingModel,
            rhythmEmbedding = rhythmEmbedding,
        )

    fun runtimeQuality(): HumanSpeechStyleQuality =
        HumanSpeechStyleQuality.entries.singleOrNull { it.name == quality }
            ?: throw IllegalArgumentException("human speech style import quality is unsupported")

    /** response move/form은 관찰용 선택 메타데이터다. 7개 response mode가 맞으면 metadata 없이도 검색할 수 있다. */
    fun isPromptEligible(): Boolean {
        val surface = runtimePromptSurface()
        if (!promptEligible) {
            require(surface == HumanSpeechStylePromptSurface.AUDIT_ONLY) {
                "human speech style prompt-ineligible card has a provider surface"
            }
            return false
        }
        require(surface.isProviderSafe()) {
            "human speech style prompt-eligible card must use the closed style-pattern surface"
        }
        runtimeResponseMove()
        requireNotNull(runtimeResponseForm()) {
            "human speech style style-pattern card needs an observed response form"
        }
        require(runtimeResponseRhythm().any(HumanSpeechStyleRhythmCue::isObservedResponseBehavior)) {
            "human speech style style-pattern card needs an observed response rhythm"
        }
        require(runtimeProviderStyleCues().size == HumanSpeechStyleExample.STYLE_PATTERN_PROVIDER_STYLE_CUE_COUNT) {
            "human speech style style-pattern card must have exactly one provider style cue"
        }
        return true
    }

    fun runtimePromptSurface(): HumanSpeechStylePromptSurface {
        require(promptSurface.isNotBlank()) { "human speech style prompt surface is blank" }
        return HumanSpeechStylePromptSurface.entries.singleOrNull { it.name == promptSurface }
            ?: throw IllegalArgumentException("human speech style prompt surface is unsupported")
    }

    private fun runtimeResponseMove(): HumanSpeechStyleResponseMove? =
        responseMove?.let { serializedMove ->
            require(serializedMove.isNotBlank()) { "human speech style response move is blank" }
            HumanSpeechStyleResponseMove.entries.singleOrNull { it.name == serializedMove }
                ?: throw IllegalArgumentException("human speech style response move is unsupported")
        }

    private fun runtimeSceneTraits(): List<HumanSpeechSceneTrait> {
        require(sceneTraits.size <= MAX_SCENE_TRAITS) { "human speech style scene traits has too many values" }
        return sceneTraits
            .map { serializedTrait ->
                require(serializedTrait.isNotBlank()) { "human speech style scene trait is blank" }
                HumanSpeechSceneTrait.entries.singleOrNull { it.name == serializedTrait }
                    ?: throw IllegalArgumentException("human speech style scene trait is unsupported")
            }.also { traits ->
                require(traits.distinct().size == traits.size) { "human speech style scene trait is duplicated" }
            }
    }

    private fun runtimeProviderStyleCues(): List<HumanSpeechStyleProviderStyleCue> {
        require(providerStyleCues.size <= HumanSpeechStyleExample.MAX_PROVIDER_STYLE_CUES) {
            "human speech style provider style cues has too many values"
        }
        return providerStyleCues
            .map { serializedCue ->
                require(serializedCue.isNotBlank()) { "human speech style provider style cue is blank" }
                HumanSpeechStyleProviderStyleCue.entries.singleOrNull { it.name == serializedCue }
                    ?: throw IllegalArgumentException("human speech style provider style cue is unsupported")
            }.also { cues ->
                require(cues.distinct().size == cues.size) { "human speech style provider style cue is duplicated" }
            }
    }

    private fun runtimeResponseMoveProvenance(): HumanSpeechStyleResponseMoveProvenance =
        HumanSpeechStyleResponseMoveProvenance.entries.singleOrNull { it.name == responseMoveProvenance }
            ?: throw IllegalArgumentException("human speech style response move provenance is unsupported")

    private fun runtimeResponseForm(): HumanSpeechStyleResponseForm? =
        responseForm?.let { serializedForm ->
            require(serializedForm.isNotBlank()) { "human speech style response form is blank" }
            HumanSpeechStyleResponseForm.entries.singleOrNull { it.name == serializedForm }
                ?: throw IllegalArgumentException("human speech style response form is unsupported")
        }

    private fun runtimeResponseRhythm(): List<HumanSpeechStyleRhythmCue> {
        require(responseRhythm.size <= MAX_RESPONSE_RHYTHM_CUES) {
            "human speech style response rhythm has too many cues"
        }
        return responseRhythm
            .map { serializedCue ->
                require(serializedCue.isNotBlank()) { "human speech style response rhythm cue is blank" }
                HumanSpeechStyleRhythmCue.entries.singleOrNull { it.name == serializedCue }
                    ?: throw IllegalArgumentException("human speech style response rhythm cue is unsupported")
            }.also { cues ->
                require(cues.distinct().size == cues.size) { "human speech style response rhythm cue is duplicated" }
            }
    }

    companion object {
        const val IMPORT_SCHEMA: String = "nia-human-speech-style-import-card.v4"
        const val MAX_RESPONSE_RHYTHM_CUES: Int = 8
        const val MAX_SCENE_TRAITS: Int = 2
        val SOURCE_FINGERPRINT = Regex("sha256:[0-9a-f]{64}")
        val CONSENT_REVISION = Regex("[A-Za-z0-9._-]{1,96}")
        val IMPORT_CARD_FIELDS =
            setOf(
                "schema",
                "example_id",
                "response_mode",
                "situation",
                "style_signals",
                "context_bubbles",
                "response_bubbles",
                "quality",
                "source_fingerprint",
                "consent_revision",
                "combined_chars",
                "prompt_eligible",
                "prompt_surface",
                "response_surface_has_card_local_alias",
                "response_move",
                "scene_traits",
                "provider_style_cues",
                "response_move_provenance",
                "response_form",
                "response_rhythm",
                "embedding_model",
            )
    }
}

private data class HumanSpeechStyleImportBubble(
    val speaker: String,
    val text: String,
) {
    fun toDomain(): HumanSpeechStyleBubble = HumanSpeechStyleBubble(speaker = speaker, text = text)
}
