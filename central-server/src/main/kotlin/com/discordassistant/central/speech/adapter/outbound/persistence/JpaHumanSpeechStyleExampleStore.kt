package com.discordassistant.central.speech.adapter.outbound.persistence

import com.discordassistant.central.global.crypto.EncryptedStringConverter
import com.discordassistant.central.global.crypto.FieldCrypto
import com.discordassistant.central.speech.application.port.out.HumanSpeechStyleExampleStorePort
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
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import jakarta.persistence.Column
import jakarta.persistence.Convert
import jakarta.persistence.Entity
import jakarta.persistence.Id
import jakarta.persistence.Table
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.stereotype.Repository
import org.springframework.transaction.annotation.Transactional
import java.time.Clock
import java.time.Instant

/** 암호화된 Speech 말투 카드 저장소. 원문/응답/벡터 모두 encrypted column에만 보관한다. */
@Repository
class JpaHumanSpeechStyleExampleStore(
    private val rows: HumanSpeechStyleExampleRepository,
    private val clock: Clock = Clock.systemUTC(),
) : HumanSpeechStyleExampleStorePort {
    private val mapper = jacksonObjectMapper().findAndRegisterModules()

    @Transactional(readOnly = true)
    override fun listEnabled(): List<HumanSpeechStyleExample> {
        if (!FieldCrypto.isConfigured()) return emptyList()
        return rows
            .findAllByEnabledTrueOrderByExampleIdAsc()
            .map(::toDomain)
            .filter(HumanSpeechStyleExample::promptEligible)
            .filter { it.promptSurface.isProviderSafe() }
    }

    @Transactional(readOnly = true)
    override fun listEnabled(responseMode: HumanSpeechResponseMode): List<HumanSpeechStyleExample> {
        if (!FieldCrypto.isConfigured()) return emptyList()
        return rows
            .findAllByEnabledTrueAndResponseModeOrderByExampleIdAsc(responseMode.name)
            .map(::toDomain)
            .filter(HumanSpeechStyleExample::promptEligible)
            .filter { it.promptSurface.isProviderSafe() }
    }

    @Transactional
    override fun replaceAll(examples: List<HumanSpeechStyleExample>): Int {
        require(FieldCrypto.isConfigured()) { "human speech style encryption key is not configured" }
        require(examples.isNotEmpty()) { "human speech style import requires examples" }
        require(examples.size <= MAX_EXAMPLES) { "human speech style import has too many examples" }
        require(examples.map(HumanSpeechStyleExample::exampleId).toSet().size == examples.size) {
            "human speech style import contains duplicate example ids"
        }
        rows.deleteAllInBatch()
        rows.saveAll(examples.sortedBy(HumanSpeechStyleExample::exampleId).map(::toEntity))
        return examples.size
    }

    private fun toEntity(example: HumanSpeechStyleExample): HumanSpeechStyleExampleEntity =
        Instant.now(clock).let { now ->
            HumanSpeechStyleExampleEntity(
                exampleId = example.exampleId,
                responseMode = example.responseMode.name,
                quality = example.quality.name,
                sourceFingerprint = example.sourceFingerprint,
                consentRevision = example.consentRevision,
                combinedChars = example.combinedChars,
                enabled = example.promptEligible,
                payloadJson = mapper.writeValueAsString(example.toPayload()),
                embeddingJson = mapper.writeValueAsString(example.embedding.toList()),
                embeddingModel = example.embeddingModel,
                createdAt = now,
                updatedAt = now,
            )
        }

    private fun toDomain(entity: HumanSpeechStyleExampleEntity): HumanSpeechStyleExample {
        val payload = mapper.readValue<HumanSpeechStyleStoredPayload>(entity.payloadJson)
        val embedding = mapper.readValue<List<Float>>(entity.embeddingJson).toFloatArray()
        val responseMode = HumanSpeechResponseMode.valueOf(entity.responseMode)
        val providerStyleCues = providerStyleCuesForRuntime(payload, responseMode)
        val promptSurface = promptSurfaceForRuntime(entity.enabled, payload, providerStyleCues)
        val responseMoveProvenance =
            payload.responseMoveProvenance
                ?: if (payload.responseMove == null) {
                    HumanSpeechStyleResponseMoveProvenance.NONE
                } else {
                    HumanSpeechStyleResponseMoveProvenance.FRESH_REJECTED
                }
        val responseMove = payload.responseMove.takeIf(responseMoveProvenance::matches)
        return HumanSpeechStyleExample(
            exampleId = entity.exampleId,
            responseMode = responseMode,
            situation = payload.situation,
            styleSignals = payload.styleSignals,
            contextBubbles = payload.contextBubbles,
            responseBubbles = payload.responseBubbles,
            quality = HumanSpeechStyleQuality.valueOf(entity.quality),
            sourceFingerprint = entity.sourceFingerprint,
            consentRevision = entity.consentRevision,
            combinedChars = entity.combinedChars,
            promptEligible = promptSurface.isProviderSafe(),
            promptSurface = promptSurface,
            responseMove = responseMove,
            sceneTraits = payload.sceneTraits,
            providerStyleCues = providerStyleCues,
            responseMoveProvenance = responseMoveProvenance,
            responseForm = payload.responseForm,
            responseRhythm = payload.responseRhythm,
            embedding = embedding,
            embeddingModel = entity.embeddingModel,
            rhythmEmbedding = payload.rhythmEmbedding.toFloatArray(),
        )
    }

    private fun providerStyleCuesForRuntime(
        payload: HumanSpeechStyleStoredPayload,
        responseMode: HumanSpeechResponseMode,
    ): List<HumanSpeechStyleProviderStyleCue> {
        val cues = payload.providerStyleCues
        val areCompatible =
            cues.size <= HumanSpeechStyleExample.MAX_PROVIDER_STYLE_CUES &&
                cues.all { it.responseMode == responseMode }
        if (!areCompatible) return emptyList()
        return when (payload.promptSurface) {
            HumanSpeechStylePromptSurface.STYLE_PATTERN,
            HumanSpeechStylePromptSurface.AUDIT_ONLY,
            -> cues
            HumanSpeechStylePromptSurface.CONTEXT_RESPONSE_PAIR,
            HumanSpeechStylePromptSurface.RESPONSE_ONLY,
            null,
            -> emptyList()
        }
    }

    private fun promptSurfaceForRuntime(
        enabled: Boolean,
        payload: HumanSpeechStyleStoredPayload,
        providerStyleCues: List<HumanSpeechStyleProviderStyleCue>,
    ): HumanSpeechStylePromptSurface =
        if (
            enabled &&
            payload.promptSurface == HumanSpeechStylePromptSurface.STYLE_PATTERN &&
            providerStyleCues.size == HumanSpeechStyleExample.STYLE_PATTERN_PROVIDER_STYLE_CUE_COUNT
        ) {
            HumanSpeechStylePromptSurface.STYLE_PATTERN
        } else {
            HumanSpeechStylePromptSurface.AUDIT_ONLY
        }

    private fun HumanSpeechStyleExample.toPayload(): HumanSpeechStyleStoredPayload =
        HumanSpeechStyleStoredPayload(
            situation = situation,
            styleSignals = styleSignals,
            contextBubbles = contextBubbles,
            responseBubbles = responseBubbles,
            promptSurface = promptSurface,
            responseMove = responseMove,
            sceneTraits = sceneTraits,
            providerStyleCues = providerStyleCues,
            responseMoveProvenance = responseMoveProvenance,
            responseForm = responseForm,
            responseRhythm = responseRhythm,
            rhythmEmbedding = rhythmEmbedding.toList(),
        )

    private companion object {
        const val MAX_EXAMPLES: Int = 2_000
    }
}

private data class HumanSpeechStyleStoredPayload(
    val situation: String,
    val styleSignals: List<String>,
    val contextBubbles: List<HumanSpeechStyleBubble>,
    val responseBubbles: List<HumanSpeechStyleBubble>,
    /** null 또는 raw pair/response-only 값은 legacy audit row이며 runtime 검색에서 제외된다. */
    val promptSurface: HumanSpeechStylePromptSurface? = null,
    val responseMove: HumanSpeechStyleResponseMove? = null,
    val sceneTraits: List<HumanSpeechSceneTrait> = emptyList(),
    val providerStyleCues: List<HumanSpeechStyleProviderStyleCue> = emptyList(),
    /** Missing on a legacy encrypted payload means its old response move must never reach runtime. */
    val responseMoveProvenance: HumanSpeechStyleResponseMoveProvenance? = null,
    val responseForm: HumanSpeechStyleResponseForm? = null,
    val responseRhythm: List<HumanSpeechStyleRhythmCue> = emptyList(),
    val rhythmEmbedding: List<Float> = emptyList(),
)

@Entity
@Table(name = "nia_human_speech_style_example")
class HumanSpeechStyleExampleEntity(
    @Id
    @Column(name = "example_id", length = 64)
    var exampleId: String = "",
    @Column(name = "response_mode", nullable = false, length = 32)
    var responseMode: String = "",
    @Column(name = "quality", nullable = false, length = 48)
    var quality: String = "",
    @Column(name = "source_fingerprint", nullable = false, length = 96)
    var sourceFingerprint: String = "",
    @Column(name = "consent_revision", nullable = false, length = 128)
    var consentRevision: String = "",
    @Column(name = "combined_chars", nullable = false)
    var combinedChars: Int = 0,
    @Column(name = "enabled", nullable = false)
    var enabled: Boolean = true,
    @Convert(converter = EncryptedStringConverter::class)
    @Column(name = "payload_json", nullable = false, columnDefinition = "TEXT")
    var payloadJson: String = "",
    @Convert(converter = EncryptedStringConverter::class)
    @Column(name = "embedding_json", nullable = false, columnDefinition = "TEXT")
    var embeddingJson: String = "",
    @Column(name = "embedding_model", nullable = false, length = 96)
    var embeddingModel: String = "",
    @Column(name = "created_at", nullable = false)
    var createdAt: Instant = Instant.EPOCH,
    @Column(name = "updated_at", nullable = false)
    var updatedAt: Instant = Instant.EPOCH,
) {
    override fun toString(): String =
        "HumanSpeechStyleExampleEntity(exampleId=$exampleId, responseMode=$responseMode, quality=$quality, " +
            "combinedChars=$combinedChars, enabled=$enabled, embeddingModel=$embeddingModel)"
}

interface HumanSpeechStyleExampleRepository : JpaRepository<HumanSpeechStyleExampleEntity, String> {
    fun findAllByEnabledTrueOrderByExampleIdAsc(): List<HumanSpeechStyleExampleEntity>

    fun findAllByEnabledTrueAndResponseModeOrderByExampleIdAsc(responseMode: String): List<HumanSpeechStyleExampleEntity>
}
